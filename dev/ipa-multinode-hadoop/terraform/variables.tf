variable "gcp_project" {
  type        = string
  description = "name of Google Cloud Platform project to create resources in"
}

variable "gcp_region" {
  type        = string
  description = "name of Google Compute Engine region to create resources in"
  default     = "us-central1"
}

variable "gcp_zone" {
  type        = string
  description = "name of Google Compute Enginer zone to create resources in"
  default     = "us-central1-a"
}

variable "hdfs_datanode_count" {
  type        = number
  description = "number of HDFS DataNode VMs to create"
  default     = 3
}

variable "gce_vm_instance_type" {
  type        = string
  description = "machine type to create"
  default     = "n1-standard-4"
}

variable "gce_vm_boot_disk_size" {
  type        = number
  description = "size of the image in gigabytes"
  default     = 40
}

variable "gce_vm_os_family" {
  type        = string
  description = "family name of the image from which to initialize the boot disk"
  default     = "centos-7"
}

variable "network" {
  type        = string
  description = "name of the VPC network to provision VMs in (a fresh GCP project has one called 'default')"
  default     = "default"
}

variable "subnet" {
  type        = string
  description = "name of the subnet to provision VMs in (the auto-mode subnets of the 'default' network are also called 'default')"
  default     = "default"
}

variable "network_tags" {
  type        = list(string)
  description = "network tags to put on every VM, for firewall or Cloud NAT rules in your project that match on tags; dev/ipa-cluster.bash opens the VMs to your IP by source range instead, so none are needed by default"
  default     = []
}

variable "env_name" {
  type        = string
  description = "environment name used in resource names and the ansible inventory; dev/ipa-cluster.bash sets it from TF_VAR_env_name (default $USER)"
}
