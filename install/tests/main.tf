variable "kubeconfig" { }
variable "TEST_ID" { default = "nightly" }

# We store the state always in a GCS bucket
terraform {
  backend "gcs" {
    bucket = "nightly-tests"
    prefix = "tf-state"
  }
}

variable "project" { default = "sh-automated-tests" }
variable "sa_creds" { default = "" }
variable "dns_sa_creds" {default = "" }

module "gke" {
  # source = "github.com/gitpod-io/gitpod//install/infra/terraform/gke?ref=main" # we can later use tags here
  source = "../infra/terraform/gke" # we can later use tags here

  name        = var.TEST_ID
  project     = var.project
  credentials = var.sa_creds
  kubeconfig  = var.kubeconfig
  region      = "europe-west1"
  zone        = "europe-west1-b"
}

module "k3s" {
  # source = "github.com/gitpod-io/gitpod//install/infra/terraform/k3s?ref=main" # we can later use tags here
  source = "../infra/terraform/k3s" # we can later use tags here

  name             = var.TEST_ID
  gcp_project      = var.project
  credentials      = var.sa_creds
  kubeconfig       = var.kubeconfig
  dns_sa_creds     = var.dns_sa_creds
  dns_project      = "dns-for-playgrounds"
  managed_dns_zone = "gitpod-self-hosted-com"
  domain_name      = "${var.TEST_ID}.gitpod-self-hosted.com"
}

module "aks" {
  # source = "github.com/gitpod-io/gitpod//install/infra/terraform/aks?ref=main" # we can later use tags here
  source = "../infra/terraform/aks" # we can later use tags here

  domain_name              = false
  enable_airgapped         = false
  enable_external_database = false
  enable_external_registry = false
  enable_external_storage  = false
  dns_enabled =  false
  name_format = join("-", [
    "gitpod",
    "%s", # region
    "%s", # name
    var.TEST_ID
  ])
  name_format_global = join("-", [
    "gitpod",
    "%s", # name
    var.TEST_ID
  ])
  workspace_name = var.TEST_ID
  labels = {
    "gitpod.io/workload_meta"               = true
    "gitpod.io/workload_ide"                = true
    "gitpod.io/workload_workspace_services" = true
    "gitpod.io/workload_workspace_regular"  = true
    "gitpod.io/workload_workspace_headless" = true
  }
}

module "certmanager" {
  # source = "github.com/gitpod-io/gitpod//install/infra/terraform/tools/cert-manager?ref=main"
  source = "../infra/terraform/tools/cert-manager"

  kubeconfig     = var.kubeconfig
  credentials    = var.dns_sa_creds
}

module "externaldns" {
  # source = "github.com/gitpod-io/gitpod//install/infra/terraform/tools/external-dns?ref=main"
  source = "../infra/terraform/tools/external-dns"
  kubeconfig     = var.kubeconfig
  credentials    = var.dns_sa_creds
}
