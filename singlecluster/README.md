SingleCluster
=============

Singlecluster is a self-contained, single-host distribution of Hadoop +
HBase + Hive + ZooKeeper used by PXF's automation and regression test
suites as a local data-source target.

Bundle contents (pinned via `server/gradle.properties` — `hadoopVersion`,
`hbaseVersion`, `zookeeperVersion`, `singleclusterHiveVersion`):

| Component | Version |
|---|---|
| Hadoop    | 3.4.3 |
| HBase     | 2.6.5 |
| ZooKeeper | 3.8.6 |
| Hive      | 4.0.1 |

Hive is deliberately pinned by its own key (`singleclusterHiveVersion`)
rather than by `hiveVersion`, which selects the Hive *client* jars PXF
compiles against — see the comments on both keys in `gradle.properties`.

The legacy CDH and HDP build paths were retired in the PXF 7.0 cut;
the only supported `HADOOP_DISTRO` is `Apache`.

Prerequisites
-------------

- `$JAVA_HOME` points to a JDK 8 or 11 install (HBase 2.6.5 supports 8+;
  Hive 4.0.1 targets Java 8 class files and runs on 8 and 11).
- `bash`, `make`, `tar`, `curl`, and `sha512sum` / `sha256sum` (or
  macOS-native `shasum`) on `$PATH`. The downloader auto-detects which
  digest tool is available.
- Roughly 4–5 GB free disk: ~1.4 GB of tarballs + ~1.6 GB of extracted
  trees + the produced tarball.

Build
-----

```bash
cd singlecluster
tools/downloadApache.sh                          # one-time per version
make HADOOP_VERSION=3.4.3 HADOOP_DISTRO=Apache   # produces the tarball
```

`HADOOP_VERSION` above is informational only (documents which version
`downloadApache.sh` already fetched) -- the Makefile doesn't read it;
only `HADOOP_DISTRO` (must be `Apache`) affects the build. The actual
version is controlled by `hadoopVersion` in `server/gradle.properties`.

`tools/downloadApache.sh` is idempotent — re-running with all tarballs
already present + their checksum sidecars verified is a no-op. Pass
`--force` to re-download everything.

The build artifact is `singlecluster-Apache.tar.gz` in this directory.
Extracting it yields a single directory `singlecluster-Apache/` with
the canonical layout below; the legacy `apache-<component>-<version>-bin/`
directory shapes that vanilla Apache tarballs ship with are renamed
during the build (see `Makefile` `extract_stack_apache`).

Expected post-build layout (inside `singlecluster-Apache/`):

```
bin/         — service launchers (start-hdfs.sh, start-hbase.sh, ...)
conf/        — gphd-conf.sh + per-component overlay configs
hadoop/      — extracted from hadoop-3.4.3.tar.gz
hbase/       — extracted from hbase-2.6.5-bin.tar.gz
hive/        — extracted from apache-hive-4.0.1-bin.tar.gz
zookeeper/   — extracted from apache-zookeeper-3.8.6-bin.tar.gz
versions.txt — build number + component versions
```

No `apache-*-bin/` directories or version suffixes should remain after
the build — if any appear, `Makefile` `extract_stack_apache` and the
post-extract rename loop in `extract_products` need to be updated.

Initialization (after build)
----------------------------

```bash
mv singlecluster/singlecluster-Apache.tar.gz ~/workspace/
cd ~/workspace
tar xzf singlecluster-Apache.tar.gz
ln -sfn ~/workspace/singlecluster-Apache ~/workspace/singlecluster
export GPHD_ROOT=~/workspace/singlecluster
export HADOOP_ROOT=$GPHD_ROOT/hadoop
export HBASE_ROOT=$GPHD_ROOT/hbase
export HIVE_ROOT=$GPHD_ROOT/hive
export ZOOKEEPER_ROOT=$GPHD_ROOT/zookeeper
export PATH=$GPHD_ROOT/bin:$HADOOP_ROOT/bin:$HBASE_ROOT/bin:$HIVE_ROOT/bin:$ZOOKEEPER_ROOT/bin:$PATH

bin/init-gphd.sh    # first-time HDFS format + dir layout
```

Subsequent service startup / shutdown
-------------------------------------

```bash
# All services
$GPHD_ROOT/bin/start-gphd.sh
$GPHD_ROOT/bin/stop-gphd.sh

# Individual components
$GPHD_ROOT/bin/{start,stop}-hdfs.sh
$GPHD_ROOT/bin/{start,stop}-zookeeper.sh
$GPHD_ROOT/bin/{start,stop}-hbase.sh   # needs HDFS + ZK up first
$GPHD_ROOT/bin/{start,stop}-yarn.sh
$GPHD_ROOT/bin/{start,stop}-hive.sh    # needs HDFS up first

# HiveServer2
$GPHD_ROOT/bin/hive-service.sh hiveserver2 start
$GPHD_ROOT/bin/hive-service.sh hiveserver2 stop
```

Notes
-----

- All persistent data lives under `$GPHD_ROOT/storage`. Cleanup this
  directory before running `init-gphd.sh` again.
- About 24 GB of disk is recommended to comfortably run all services
  alongside PXF automation.
- Template files under `templates/` are overlaid on top of the
  extracted vanilla configs at build time (`Makefile` `copy_templates`).
  These overlays carry the WarehousePG-specific tweaks needed for
  Hadoop 3.x + HBase 2.x + ZooKeeper 3.8.x + Hive 4.x compatibility
  (e.g. HBase `WAL provider = filesystem`, ZK 3.5+ 4lw allowlist,
  Hive 4 non-transactional CREATE TABLE defaults).
- `init-gphd.sh` additionally copies Hive's bundled commons-collections
  jar into the extracted Hadoop's lib dirs (Hadoop 3.4 removed it, but
  Hive MapReduce tasks still load it) and initializes the Hive metastore
  schema in Derby.
