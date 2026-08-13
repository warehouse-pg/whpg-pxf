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
