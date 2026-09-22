> **NOTE.** This module used to live under `concourse/terraform/` and was invoked by the retired Concourse CI pipeline. It is still in use: it provisions fresh GCE VMs in your own GCP project (`TF_VAR_gcp_project`) and is driven by the local kerberized-dev flow [`dev/ipa-cluster.bash`](../../ipa-cluster.bash) (see [`dev/IPA.md`](../../IPA.md)). It does **not** depend on the retired Pivotal infrastructure.

# IPA multinode Hadoop — Terraform module

**Terraform module for provisioning the kerberized multi-node Hadoop dev cluster in GCE**

## Multinode Hadoop with IPA provided Kerberos

The `ipa-multinode-hadoop` module provisions Google Compute Engine (GCE) VMs for running multinode Hadoop (currently only HDFS is running) cluster with Kerberos authentication backed by [FreeIPA][0].
After creating the VMs with terraform, the [`ipa-multinode-hadoop` Ansible play](../ansible) can be used to configure and start the Hadoop cluster.
In addition to creating the VMs, the module will also generate TLS private keys and self-signed certificates for the Hadoop nodes.
There are several variables for customizing the module, see [`./variables.tf`](./variables.tf) for a list of all variables and their description.

## Helpful Tips

The following are some tips that can be useful when debugging or iterating on Terraform modules

### Running locally

This module was originally written for Concourse CI; it runs fine from a local workstation.
The first step is to run

```bash
terraform init
```

All variables without default values will need to be provided.
This can be done in one of several different ways:

1. Terraform will prompted for all undefined variables when running a plan or an apply step

2. Variables can be given on the command line

    ```bash
    terraform plan -var 'foo=bar'
    ```

3. Variables can be given in a file; if the file `terraform.tfvars` or `.auto.tfvars` are present, they will be automatically loaded

    ```bash
    terraform plan -var-file=foo
    ```

    An example variables file can be found in the module directory.

4. Variables can be specified with environment variables

    ```bash
    export TF_VAR_gcp_project="<gcp-project-id>"
    ```
There is an [`ipa-cluster.bash` script](../../ipa-cluster.bash) that automates provisioning of the IPA Hadoop
cluster via `terraform` and setting it up using `ansible` when running from a local development workstation.
It is used by developers to quickly setup a testing environment.
Please refer to the [`IPA.md` document](../../IPA.md) for the instructions on how to run it.
