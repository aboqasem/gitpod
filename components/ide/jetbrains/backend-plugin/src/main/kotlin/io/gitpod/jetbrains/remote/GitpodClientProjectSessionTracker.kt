// Copyright (c) 2022 Gitpod GmbH. All rights reserved.
// Licensed under the GNU Affero General Public License (AGPL).
// See License-AGPL.txt in the project root for license information.

package io.gitpod.jetbrains.remote

import com.intellij.codeWithMe.ClientId
import com.intellij.ide.BrowserUtil
import com.intellij.idea.StartupUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.client.ClientProjectSession
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.remoteDev.util.onTerminationOrNow
import com.intellij.util.application
import com.jetbrains.rd.util.lifetime.Lifetime
import io.gitpod.gitpodprotocol.api.entities.RemoteTrackMessage
import io.gitpod.gitpodprotocol.api.entities.WorkspaceInstancePort
import io.gitpod.supervisor.api.Info
import io.gitpod.supervisor.api.Status
import io.gitpod.supervisor.api.Status.PortVisibility
import io.gitpod.supervisor.api.Status.PortsStatus
import io.gitpod.supervisor.api.StatusServiceGrpc
import io.grpc.stub.ClientCallStreamObserver
import io.grpc.stub.ClientResponseObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import org.jetbrains.ide.BuiltInServerManager
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

class GitpodClientProjectSessionTracker(
        private val session: ClientProjectSession
) : Disposable {

    private val manager = service<GitpodManager>()

    private lateinit var info: Info.WorkspaceInfoResponse
    private val versionName = ApplicationInfo.getInstance().versionName
    private val fullVersion = ApplicationInfo.getInstance().fullVersion
    private val lifetime = Lifetime.Eternal.createNested()

    override fun dispose() {
        lifetime.terminate()
    }

    init {
        GlobalScope.launch {
            info = manager.pendingInfo.await()
            trackEvent("jb_session", mapOf())
            registerActiveLanguageAnalytics()
        }
    }

    private fun isExposedServedPort(port: Status.PortsStatus?) : Boolean {
        if (port === null) {
            return false
        }
        return port.served && port.hasExposed()
    }

    private fun showOpenServiceNotification(port: PortsStatus, offerMakePublic: Boolean = false) {
        val message = "A service is available on port ${port.localPort}"
        val notification = manager.notificationGroup.createNotification(message, NotificationType.INFORMATION)

        val openBrowserAction = NotificationAction.createSimple("Open Browser") {
            openBrowser(port.exposed.url)
        }
        notification.addAction(openBrowserAction)

        if (offerMakePublic) {
            val makePublicLambda = {
                runBlocking {
                    makePortPublic(info.workspaceId, port)
                }
            }
            val makePublicAction = NotificationAction.createSimple("Make Public", makePublicLambda)
            notification.addAction(makePublicAction)
        }

        ClientId.withClientId(session.clientId) {
            notification.notify(null)
        }
    }

    private suspend fun makePortPublic(workspaceId: String, port: PortsStatus) {
        val p = WorkspaceInstancePort()
        p.port = port.localPort
        p.visibility = io.gitpod.gitpodprotocol.api.entities.PortVisibility.PUBLIC.toString()
        p.url = port.exposed.url

        try {
            manager.client.server.openPort(workspaceId, p).await()
        } catch (e: Exception) {
            thisLogger().error("gitpod: failed to open port ${port.localPort}: ", e)
        }
    }

    private fun openBrowser(url: String) {
        ClientId.withClientId(session.clientId) {
            BrowserUtil.browse(url)
        }
    }

    private val portsObserveJob = GlobalScope.launch {
        if (application.isHeadlessEnvironment) {
            return@launch
        }

        // Ignore ports that aren't actually used by the user (e.g. ports used internally by JetBrains IDEs)
        val backendPort = BuiltInServerManager.getInstance().waitForStart().port
        val serverPort = StartupUtil.getServerFuture().await().port
        val ignorePorts = listOf(backendPort, serverPort, 5990)
        val portsStatus = hashMapOf<Int, Status.PortsStatus>()

        val status = StatusServiceGrpc.newStub(GitpodManager.supervisorChannel)
        while (isActive) {
            try {
                val f = CompletableFuture<Void>()
                status.portsStatus(
                        Status.PortsStatusRequest.newBuilder().setObserve(true).build(),
                        object : ClientResponseObserver<Status.PortsStatusRequest, Status.PortsStatusResponse> {

                            override fun beforeStart(requestStream: ClientCallStreamObserver<Status.PortsStatusRequest>) {
                                lifetime.onTerminationOrNow {
                                    requestStream.cancel(null, null)
                                }
                            }

                            override fun onNext(ps: Status.PortsStatusResponse) {
                                for (port in ps.portsList) {
                                    // Avoiding undesired notifications
                                    if (ignorePorts.contains(port.localPort)) {
                                        continue
                                    }

                                    val previous = portsStatus[port.localPort]
                                    portsStatus[port.localPort] = port

                                    val shouldSendNotification = !isExposedServedPort(previous) && isExposedServedPort(port)

                                    if (shouldSendNotification) {
                                        if (port.exposed.onExposed.number == Status.OnPortExposedAction.ignore_VALUE) {
                                            continue
                                        }

                                        if (port.exposed.onExposed.number == Status.OnPortExposedAction.open_browser_VALUE || port.exposed.onExposed.number == Status.OnPortExposedAction.open_preview_VALUE) {
                                            openBrowser(port.exposed.url)
                                            continue
                                        }

                                        if (port.exposed.onExposed.number == Status.OnPortExposedAction.notify_VALUE) {
                                            showOpenServiceNotification(port)
                                            continue
                                        }

                                        if (port.exposed.onExposed.number == Status.OnPortExposedAction.notify_private_VALUE) {
                                            showOpenServiceNotification(port, port.exposed.visibilityValue !== PortVisibility.public_visibility_VALUE)
                                            continue
                                        }
                                    }
                                }
                            }

                            override fun onError(t: Throwable) {
                                f.completeExceptionally(t)
                            }

                            override fun onCompleted() {
                                f.complete(null)
                            }
                        })
                f.await()
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    throw t
                }
                thisLogger().error("gitpod: failed to stream ports status: ", t)
            }
            delay(1000L)
        }
    }
    init {
        lifetime.onTerminationOrNow {
            portsObserveJob.cancel()
        }
    }

    private fun registerActiveLanguageAnalytics() {
        val activeLanguages = mutableSetOf<String>()
        session.project.messageBus.connect().subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                super.selectionChanged(event)
                if (event.manager.selectedEditor == null) {
                    return
                }
                val file = event.manager.selectedEditor!!.file
                val ext = file.extension
                val fileType = file.fileType
                var lang = "plaintext"
                if (fileType is LanguageFileType) {
                    lang = fileType.language.id
                }
                if (activeLanguages.contains(lang)) {
                    return
                }
                activeLanguages.add(lang)
                trackEvent("jb_active_language", mapOf("lang" to lang, "ext" to ext))
            }
        })
    }

    fun trackEvent(eventName: String, props: Map<String, Any?>) {
        val event = RemoteTrackMessage().apply {
            event = eventName
            properties = mapOf(
                    "sessionId" to session.clientId.value,
                    "instanceId" to info.instanceId,
                    "workspaceId" to info.workspaceId,
                    "appName" to versionName,
                    "appVersion" to fullVersion,
                    "timestamp" to System.currentTimeMillis()
            ).plus(props)
        }
        if (manager.devMode) {
            thisLogger().warn("gitpod: $event")
        } else {
            manager.client.server.trackEvent(event)
        }
    }
}
