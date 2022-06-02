// Copyright (c) 2020 Gitpod GmbH. All rights reserved.
// Licensed under the GNU Affero General Public License (AGPL).
// See License-AGPL.txt in the project root for license information.

package cmd

import (
	"context"
	"fmt"
	"time"

	gitpod "github.com/gitpod-io/gitpod/gitpod-cli/pkg/gitpod"
	protocol "github.com/gitpod-io/gitpod/gitpod-protocol"
	"github.com/spf13/cobra"
)

// extendTimeoutCmd extends the workspace timeout
var extendTimeoutCmd = &cobra.Command{
	Use:   "extend",
	Short: "Extend the workspace timeout",
	Args:  cobra.NoArgs,
	Run: func(cmd *cobra.Command, args []string) {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		wsInfo, err := gitpod.GetWSInfo(ctx)
		if err != nil {
			fail(err.Error())
		}
		client, err := gitpod.ConnectToServer(ctx, wsInfo, []string{
			"function:setWorkspaceTimeout",
			"resource:workspace::" + wsInfo.WorkspaceId + "::get/update",
		})
		if err != nil {
			fail(err.Error())
		}

		timeout := protocol.WorkspaceTimeoutDuration180m
		_, err = client.SetWorkspaceTimeout(ctx, wsInfo.WorkspaceId, &timeout)
		if err != nil {
			fail(err.Error())
		}

		fmt.Println("workspace timeout extended")
	},
}

func init() {
	timeoutCommand.AddCommand(extendTimeoutCmd)
}
