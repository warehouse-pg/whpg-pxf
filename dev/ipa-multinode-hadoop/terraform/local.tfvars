####################################################################################
# --- This file is used with terraform apply when running from a local machine --- #
####################################################################################

# --- The following variables are required and the values must be set by environment variables.

# GCP project where the instances will created, use variable TF_VAR_gcp_project to specify
#gcp_project = "gcp_project"

# The environment name is used when naming created resources and should help identify the user
# who ran the deployment. A convention is to use $USER as the value set in TF_VAR_env_name
#env_name = "env_name"

# --- The following variables are defined in variables.tf with the given defaults, change if needed.

#gcp_region = "us-central1"
#gcp_zone = "us-central1-a"
#hdfs_datanode_count = 3
#gce_vm_instance_type = "n1-standard-4"
#gce_vm_boot_disk_size = 40
#gce_vm_os_family = "centos-7"

# VPC network and subnet in your project; the defaults are the ones a fresh GCP project comes with.
#network = "default"
#subnet = "default"

# Network tags for the VMs, only needed if your project's firewall or Cloud NAT rules match on tags.
# dev/ipa-cluster.bash opens the VMs to your own IP with a source-range firewall rule, so this stays empty.
#network_tags = []

# Developers destroy their GCP clusters with 'dev/ipa-cluster.bash --destroy',
# refer to the "Cleanup" section of the dev/IPA.md file.
