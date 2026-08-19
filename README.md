PXF is built and certified through the GitHub Actions workflows. The legacy Concourse
pipelines under `concourse/` are deprecated and retained for historical
reference only.

----------------------------------------------------------------------

Introduction
============

PXF is an extensible framework that allows a distributed database like WarehousePG to query external data files, whose metadata is not managed by the database.
PXF includes built-in connectors for accessing data that exists inside HDFS files, Hive tables, HBase tables, JDBC-accessible databases and more.
Users can also create their own connectors to other data storage or processing engines.

Repository Contents
================
## external-table/
Contains the WarehousePG extension implementing an External Table protocol handler

## fdw/
Contains the WarehousePG extension implementing a Foreign Data Wrapper (FDW) for PXF

## server/
Contains the server side code of PXF along with the PXF Service and all the Plugins

## cli/
Contains command line interface code for PXF

## automation/
Contains the automation and integration tests for PXF against the various datasources

## singlecluster/
Hadoop testing environment to exercise the pxf automation tests

## concourse/
Legacy resources for PXF's Concourse Continuous Integration pipelines. Deprecated and retained for historical reference only; the live CI surface is the GitHub Actions workflows under `whpg-extensions-packaging/.github/workflows/`.

## regression/
Contains the end-to-end (integration) tests for PXF against the various datasources, utilizing the PostgreSQL testing framework `pg_regress`

## downloads/
An empty directory that serves as a staging location for WarehousePG RPMs for the development Docker image

PXF Development
=================
Below are the steps to build and install PXF along with its dependencies including WarehousePG and Hadoop.

To start, ensure you have a `~/workspace` directory and have cloned the `pxf` and its prerequisites (shown below) under it.
(The name `workspace` is not strictly required but will be used throughout this guide.)
```bash
mkdir -p ~/workspace
cd ~/workspace

git clone https://github.com/warehouse-pg/whpg-pxf.git pxf
```
Alternatively, you may create a symlink to your existing repo folder.
```bash
ln -s ~/<git_repos_root> ~/workspace
```

## Install Dependencies

To build PXF, you must have:

1. GCC compiler, `make` system, `unzip` package, `maven` for running integration tests
2. Installed WarehousePG DB

    Either download and install the WarehousePG RPM or build WarehousePG from source by following instructions in the [WarehousePG README](https://github.com/warehouse-pg/warehouse-pg).

    Assuming you have installed WarehousePG into `/usr/edb/whpg7` directory, run its environment script:
    ```
    source /usr/edb/whpg7/greenplum_path.sh
    ```

3. JDK 8 to build (the server build uses Lombok, which requires JDK 8); JDK 8 or JDK 11 to run

    Export your `JAVA_HOME`:
    ```
    export JAVA_HOME=<PATH_TO_YOUR_JAVA_HOME>
    ```

4. Go (1.21 or later — see [`cli/README.md`](cli/README.md))

    To install Go on CentOS, `sudo yum install go`. For other platforms, see the [Go downloads page](https://golang.org/dl/).

    Make sure to export your `GOPATH` and add go to your `PATH`. For example:
    ```shell
    export GOPATH=$HOME/go
    export PATH=$PATH:/usr/local/go/bin:$GOPATH/bin
    ```

   For the new M1 Apple Macs, add the following to your path instead
   ```shell
   export PATH=$PATH:/opt/homebrew/bin/go/bin:$GOPATH/bin
   ```

5. cURL (7.29 or later):

    To install cURL devel package on CentOS 7, `sudo yum install libcurl-devel`.

    Note that CentOS 6 provides an older, unsupported version of cURL (7.19). You should install a newer version from source if you are on CentOS 6.

## How to Build PXF
PXF uses Makefiles to build its components. PXF server component uses Gradle that is wrapped into the Makefile for convenience.
```bash
cd ~/workspace/pxf

# Compile & Test PXF
make

# Only run unit tests
make test
```

## How to Install PXF

To install PXF, first make sure that the user has sufficient permissions in the `$GPHOME` and `$PXF_HOME` directories to perform the installation. It's recommended to change ownership to match the installing user. For example, when installing PXF as user `gpadmin` under `/usr/edb/whpg7`:

```bash
export GPHOME=/usr/edb/whpg7
export PXF_HOME=/usr/local/pxf
export PXF_BASE=${HOME}/pxf-base
chown -R gpadmin:gpadmin "${GPHOME}" "${PXF_HOME}"
make -C ~/workspace/pxf install
```

NOTE: if `PXF_BASE` is not set, it will default to `PXF_HOME`, and server configurations, libraries or other configurations, might get deleted after a PXF re-install.

## How to Run PXF

Ensure that PXF is in your path. This command can be added to your .bashrc
```bash
export PATH=/usr/local/pxf/bin:$PATH
```

Then you can prepare and start up PXF by doing the following.
```bash
pxf prepare
pxf start
```
If `${HOME}/pxf-base` does not exist, `pxf prepare` will create the directory for you. This command should only need to be run once.

## Re-installing PXF after making changes
Note: Local development with PXF requires a running WarehousePG cluster.

Once the desired changes have been made, there are 2 options to re-install PXF:

1. Run `make -sj4 install` to re-install and run tests
2. Run `make -sj4 install-server` to only re-install the PXF server without running unit tests.

After PXF has been re-installed, you can restart the PXF instance using:
```bash
pxf restart
```

## How to demonstrate Hadoop Integration
In order to demonstrate end to end functionality you will need Hadoop installed. All the related Hadoop components (HDFS, Hive, HBase, ZooKeeper) are bundled into a single self-contained artifact named `singlecluster`.

Build the bundle from a vanilla-Apache stack (Hadoop 3.4.3, HBase 2.6.5, ZooKeeper 3.8.6, Hive 4.0.1) and extract it. See [`singlecluster/README.md`](singlecluster/README.md) for the full build, layout, and startup instructions.

```bash
cd ~/workspace/pxf/singlecluster
tools/downloadApache.sh
make HADOOP_VERSION=3.4.3 HADOOP_DISTRO=Apache

mv singlecluster-Apache.tar.gz ~/workspace/
cd ~/workspace
tar xzf singlecluster-Apache.tar.gz
ln -sfn ~/workspace/singlecluster-Apache ~/workspace/singlecluster
```

`HADOOP_VERSION` above is informational only -- the Makefile doesn't
read it; the actual version is controlled by `hadoopVersion` in
`server/gradle.properties` (see [`singlecluster/README.md`](singlecluster/README.md) for details).

Then follow the steps in [Setup Hadoop](#Setup-Hadoop).

JDK 8 or JDK 11 are the validated runtimes for the singlecluster stack (HBase 2.6.5 supports both; Hive 4.0.1 targets Java 8 class files and runs on both). The PXF server JVM itself may run on Java 8 or Java 11. Set `JAVA_HOME` accordingly before starting the Hadoop components.

On a Mac, you can set your Java version using `JAVA_HOME` like so:
```
export JAVA_HOME=`/usr/libexec/java_home -v 1.8`
```

Initialize the default server configurations:
```
cp ${PXF_HOME}/templates/*-site.xml ${PXF_BASE}/servers/default
```

# Local Development Setup

> **Note:** A Docker-based dev flow previously documented here relied on
> pre-built images (`gcr.io/$PROJECT_ID/gpdb-pxf-dev/...`) and the
> `singlecluster-HDP` tarball that are no longer accessible, plus the
> now-deprecated `dev/start.bash` helper. That flow is deprecated. The
> steps below run WarehousePG, the `singlecluster` Hadoop stack, and PXF
> directly on the host. Build the vanilla-Apache `singlecluster` bundle
> first (see [`singlecluster/README.md`](singlecluster/README.md)).

### Setup Hadoop
Hdfs will be needed to demonstrate functionality. You can choose to start additional hadoop components (hive/hbase) if you need them.

Setup [User Impersonation](https://hadoop.apache.org/docs/current/hadoop-project-dist/hadoop-common/Superusers.html) prior to starting the hadoop components (this allows the `gpadmin` user to access hadoop data).
```bash
~/workspace/pxf/dev/configure_singlecluster.bash
```

Setup and start HDFS
```bash
pushd ~/workspace/singlecluster/bin
echo y | ./init-gphd.sh
./start-hdfs.sh
popd
```

Start other optional components based on your need
```bash
pushd ~/workspace/singlecluster/bin
# Start Hive
./start-yarn.sh
./start-hive.sh

# Start HBase
./start-zookeeper.sh
./start-hbase.sh
popd
```

### Setup Minio (optional)
Minio is an S3-API compatible local storage solution. With the Minio server binary installed and on your `PATH`, start it by running the following script:
```bash
source ~/workspace/pxf/dev/start_minio.bash
```
After the server starts, you can access Minio UI at `http://localhost:9000` from the host OS. Use `admin` for the access key and `password` for the secret key when connecting to your local Minio instance.

The script also sets `PROTOCOL=minio` so that the automation framework will use the local Minio server when running S3 automation tests. If later you would like to run Hadoop HDFS tests, unset this variable with `unset PROTOCOL` command.

### Setup PXF

Install PXF Server
```bash
# Install PXF
make -C ~/workspace/pxf install

# Start PXF
export PXF_JVM_OPTS="-Xmx512m -Xms256m"
$PXF_HOME/bin/pxf start
```

Install PXF client (ignore if this is already done)
```bash
psql -d template1 -c "create extension pxf"
```

### Run PXF Tests
All tests use a database named `pxfautomation`.
```bash
pushd ~/workspace/pxf/automation

# Initialize default server configs using template
cp ${PXF_HOME}/templates/{hdfs,mapred,yarn,core,hbase,hive}-site.xml ${PXF_BASE}/servers/default

# Run specific tests. Example: Hdfs Smoke Test
make TEST=HdfsSmokeTest

# Run all tests. This will be very time consuming.
make GROUP=gpdb

# If you wish to run test(s) against a different storage protocol set the following variable (for eg: s3)
export PROTOCOL=s3
popd
```

Before running the HBase tests, copy `pxf-hbase-*.jar` onto the HBase classpath and restart HBase. This is a required step: the HBase filter-pushdown tests fail without the PXF JAR on the RegionServer classpath.

```
cp ${PXF_HOME}/share/pxf-hbase-*.jar ~/workspace/singlecluster/hbase/lib/pxf-hbase.jar
~/workspace/singlecluster/bin/stop-hbase.sh
~/workspace/singlecluster/bin/start-hbase.sh
```

### Make Changes to PXF

To deploy your changes to PXF in the development environment.

```bash
# $PXF_HOME folder is replaced each time you make install.
# So, if you have any config changes, you may want to back those up.
$PXF_HOME/bin/pxf stop
make -C ~/workspace/pxf install
# Make any config changes you had backed up previously
rm -rf $PXF_HOME/pxf-service
yes | $PXF_HOME/bin/pxf init
$PXF_HOME/bin/pxf start
```

# IDE Setup (IntelliJ)

- Start IntelliJ. Click "Open" and select the directory to which you cloned the `pxf` repo.
- Select `File > Project Structure`.
- Make sure you have a JDK (version 1.8) selected. JDK 8 is required here because the server build uses Lombok, which only supports JDK 8.
- In the `Project Settings > Modules` section, select `Import Module`, pick the `pxf/server` directory and import as a Gradle module. You may see an error saying that there's
no JDK set for Gradle. Just cancel and retry. It goes away the second time.
- Import a second module, giving the `pxf/automation` directory, select "Import module from external model", pick `Maven` then click Finish.
- Restart IntelliJ
- Check that it worked by running a unit test (cannot currently run automation tests from IntelliJ) and making sure that imports, variables, and auto-completion function in the two modules.
- Optionally you can replace `${PXF_TMP_DIR}` with `${GPHOME}/pxf/tmp` in `automation/pom.xml`
- Select `Tools > Create Command-line Launcher...` to enable starting Intellij with the `idea` command, e.g. `cd ~/workspace/pxf && idea .`.

## Debugging the locally running instance of PXF server using IntelliJ

- In IntelliJ, click `Edit Configuration` and add a new one of type `Remote`
- Change the name to `PXF Service Boot`
- Change the port number to `2020`
- Save the configuration
- Restart PXF in DEBUG Mode `PXF_DEBUG=true pxf restart`
- Debug the new configuration in IntelliJ
- Run a query in GPDB that uses PXF to debug with IntelliJ

# To run a Kerberized Hadoop Cluster

- See [`dev/IPA.md`](dev/IPA.md) for spinning up a kerberized multi-node Hadoop cluster backed by FreeIPA, or [`dev/Dataproc-with-Kerberos.md`](dev/Dataproc-with-Kerberos.md) for a kerberized Dataproc cluster in GCP.