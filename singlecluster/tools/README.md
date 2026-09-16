> **Note:** The `public-repo-1.hortonworks.com` URLs in the examples
> below no longer resolve (the domain is gone). These scripts and their
> instructions are retained as historical reference for how the
> `singlecluster` tarballs were originally produced.

# Tools

## Compress HDP

The HDP tarball we get from Hortonworks is around 5GB of Hadoop components. We only use a small subset of these for singlecluster so the `compressHDP.sh` script downloads the Hortonworks tarball, strips out the unnecessary components and creates a much smaller tarball

To invoke the command:

```
#HDP 2.4
./compressHDP.sh http://public-repo-1.hortonworks.com  HDP-2.4.2.0-centos6-tars-tarball.tar.gz 2.4.2.0 centos6 HDP

#HDP 2.5
./compressHDP.sh http://public-repo-1.hortonworks.com HDP-2.5.0.0-centos6-tars-tarball.tar.gz 2.5.0.0 centos6 HDP
```

Once the artifact has been created locally scp it to our dist server

## Download CDH

Cloudera has different Hadoop components packaged separately. The "downloadCDH.sh" script downloads tarballs of required components of specific versions respectively, and archives them together into one single tarball.

To invoke the command:

```
#CDH 5.12.2
./downloadCDH.sh
```

For other CDH versions, update required component tarballs as needed:
```
tarballs=(
  'hadoop-<hadoop_version>-cdh<cdh_version>.tar.gz'
  'hbase-<hbase_version>-cdh<cdh_version>.tar.gz'
  'hive-<hive_version>-cdh<cdh_version>.tar.gz'
  'zookeeper-<zookeeper_version>-cdh<cdh_version>.tar.gz'
  '<some_component>-<some_component_version>-cdh<cdh_version>.tar.gz'
)
```
Find CDH tarballs information [here](https://www.cloudera.com/documentation/enterprise/release-notes/topics/cdh_vd_cdh_package_tarball.html). Going forward, please keep this script updated for the preferred CDH version.
