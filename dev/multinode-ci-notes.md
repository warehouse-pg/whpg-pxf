# PXF MultiNode CI Notes

Based on the `Test PXF-GP6-HDP2-SECURE-MULTI-IMPERS on RHEL7` job of the retired
`pxf-build` Concourse pipeline: a secure (Kerberized) HDP 2 multi-node run with
user impersonation, on RHEL 7. That pipeline ran on infrastructure that is no
longer reachable, so these notes stand on their own — they describe what the job
provisioned and configured, for anyone reproducing the setup by hand.

## Infrastructure

1. Dataproc Cluster 1
    - `dataproc_env_files` in CI tasks
    - Dataproc cluster in data-gpdb-ud w/ Kerberos enabled
    - Hadoop Proxy Users
        - `hive`
            - `hosts=*`
            - `groups=*`
        - `gpamin`
            - `hosts=*`
            - `groups=*`
2. Dataproc Cluster 2
    - `dataproc_2_env_files` in CI tasks
    - Dataproc cluster in data-gpdb-ud-kerberos w/ Kerberos enabled
    - Hadoop Proxy Users
        - `hive`
            - `hosts=*`
            - `groups=*`
        - `gpuser`
            - `hosts=*`
            - `groups=*`
3. EDW Hadoop Cluster
    - Singlecluster on CCP provisioned edw0 in data-gpdb-ud w/o Kerberos
    - Hadoop Proxy Users
        - `gpadmin`
            - `hosts=*`
            - `groups=*`
4. IPA Hadoop Cluster
    - `ipa_env_files` in CI tasks
    - Multinode cluster provisioned with [Terraform](ipa-multinode-hadoop/terraform) and [Ansible](ipa-multinode-hadoop/ansible)
    - Hadoop Proxy Users
        - **None**

## Automation Clusters

The property `sutFile` in `jsystem.properties` is updated to point at `MultiHadoopMultiNodesCluster.xml`.
This defines three (3) Hadoop clusters

1. hdfs
    - points at the NameNode of Dataproc Cluster 1
2. hdfs2
    - points at the NameNode of Dataproc Cluster 2
3. hdfsNonSecure
    - points at the NameNode of EDW Hadoop Cluster
4. hdfsIpa
    - points at the NameNode of IPA Hadoop Cluster

## PXF Servers

1. default
    - Dataproc Cluster 1
    - - `pxf.service.user.impersonation = true`
2. default-no-impersonation
    - Dataproc Cluster 1
    - `pxf.service.user.impersonation = false`
    - `pxf.service.user.name = foobar`
3. hdfs-non-secure
    - EDW Hadoop Cluster
4. hdfs-secure
    - Dataproc Cluster 2
    - impersonation w/ KRB5 principal gpuser/_HOST@REALM2
5. secure-hdfs-invalid-keytab
    - Based on default (Dataproc Cluster 1)
    - `pxf.service.kerberos.keytab = ${pxf.base}/keytabs/non.existent.keytab`
6. secure-hdfs-invalid-principal (Dataproc 1)
    - Based on default (Dataproc Cluster 1)
    - `pxf.service.kerberos.principal = foobar/_HOST@INVALID.REALM.INTERNAL`
7. hdfs-ipa
    - IPA Hadoop Cluster
    - impersonation via constrained delegation w/ KRB5 principal stout@REALM3
