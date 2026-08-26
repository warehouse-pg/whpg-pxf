#!/usr/bin/env bash

# Load settings
root=`cd \`dirname $0\`/..;pwd`
bin=${root}/bin
. ${bin}/gphd-env.sh

cluster_initialized

if [ $? -eq 0 ]; then
	echo storage directory exists, would you like to reset?
	echo this will remove ALL data from singlecluster
	echo "press [y|Y] to continue"

	read reply
	case ${reply} in
		[yY])
			;;
		*)
			echo abort
			exit 1
			;;
	esac

	rm -rf ${STORAGE_ROOT}
fi

# Initialize HDFS
${bin}/hdfs namenode -format

if [ $? -ne 0 ]; then
	echo cluster initialization failed
	echo check error log in console output
	exit 1
fi

# Hadoop 3.4 removed commons-collections 3.x from its bundled libraries,
# but hive-exec (through 4.x at least) still references
# org.apache.commons.collections classes from code that runs inside
# MapReduce tasks. Task containers are launched with Hadoop's classpath
# only, so without the jar every Hive query that spawns a MapReduce job
# dies in the map task with
#   ClassNotFoundException: org.apache.commons.collections.CollectionUtils
# Hive ships the jar itself; copy it into the Hadoop lib dirs that task
# containers read. Harmless when Hadoop already bundles the jar (pre-3.4)
# and idempotent across re-runs.
# (mkdir -p because Hadoop 3.4.3's tarball no longer ships a
# share/hadoop/mapreduce/lib/ directory at all -- the path is still part
# of MapReduce's default application classpath
# (MRJobConfig.DEFAULT_MAPREDUCE_APPLICATION_CLASSPATH), so creating it
# is safe.)
if compgen -G "${HIVE_ROOT}/lib/commons-collections-*.jar" > /dev/null; then
	for hadoop_lib_dir in ${HADOOP_ROOT}/share/hadoop/common/lib ${HADOOP_ROOT}/share/hadoop/mapreduce/lib; do
		mkdir -p ${hadoop_lib_dir}
		cp -f ${HIVE_ROOT}/lib/commons-collections-*.jar ${hadoop_lib_dir}/
	done
fi

# Initialize the Hive metastore schema in Derby. datanucleus.autoCreateTables
# in hive-site.xml only creates the JDO-managed metadata tables (DBS, TBLS,
# SDS, ...). The transaction tables that DbTxnManager needs (TXNS, HIVE_LOCKS,
# COMPACTION_QUEUE, NEXT_TXN_ID, ...) are NOT JDO-managed and are only
# created by schematool via hive-txn-schema-2.3.0.derby.sql.
#
# Without this, HiveOrcAcidTest -- and any other test that goes through the
# lock() metastore RPC -- fails with
#   TApplicationException: Internal error processing lock
# because the metastore's insert into HIVE_LOCKS hits a missing-table error
# it then swallows into a generic Thrift exception.
#
# Runs unconditionally here rather than gated behind an existence check
# because init-gphd.sh has just cleared ${STORAGE_ROOT}, so Derby is
# guaranteed fresh.
mkdir -p ${HIVE_STORAGE_ROOT}
# schematool does NOT read HIVE_OPTS (that variable is for the hive CLI),
# and hive-site.xml here does not set javax.jdo.option.ConnectionURL --
# the metastore server picks it up from HIVE_OPTS in hive-env.sh at
# start time. Left in the default cwd, schematool would try to create
# ./metastore_db wherever init-gphd.sh was invoked from (e.g.
# /home/runner on the CI runner), find that dir non-writable, boot
# Derby in READ ONLY mode and bail with
#   Error: DDL is not permitted for a read-only connection
#
# cd into ${HIVE_STORAGE_ROOT} first so Derby's default (metastore_db
# in cwd) resolves to the same absolute path the metastore server later
# uses via the javax.jdo.option.ConnectionURL in HIVE_OPTS
# (databaseName=${HIVE_STORAGE_ROOT}/metastore_db). Both tools then
# operate on the same DB.
#
# schemaTool in Hive 2.3.x has no explicit --url flag; the only knobs
# are -dbType / -dbOpts. cd is the working mechanism.
cd ${HIVE_STORAGE_ROOT}
${HIVE_BIN}/schematool -dbType derby -initSchema

if [ $? -ne 0 ]; then
	echo Hive schema initialization failed
	echo check error log in console output
	exit 1
fi

echo
echo
echo Cluster initialized
echo For ease of use, add the following to your environment
echo export GPHD_ROOT=${GPHD_ROOT}
echo export HADOOP_ROOT=\$GPHD_ROOT/hadoop
echo export HBASE_ROOT=\$GPHD_ROOT/hbase
echo export HIVE_ROOT=\$GPHD_ROOT/hive
echo export ZOOKEEPER_ROOT=\$GPHD_ROOT/zookeeper
echo export PATH=\$PATH:\$GPHD_ROOT/bin:\$HADOOP_ROOT/bin:\$HBASE_ROOT/bin:\$HIVE_ROOT/bin:\$ZOOKEEPER_ROOT/bin
echo -------------------------------------------------------------
