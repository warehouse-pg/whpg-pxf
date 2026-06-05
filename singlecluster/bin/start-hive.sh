#!/usr/bin/env bash

# Load settings
root=`cd \`dirname $0\`/..;pwd`
bin=${root}/bin
. ${bin}/gphd-env.sh

# Check to see HDFS is up
hdfs_running
if [ $? != 0 ]; then
	echo HDFS is not ready, Hive cannot start
	echo Please see HDFS is up and out of safemode
	exit 1
fi

${bin}/hive-service.sh metastore start
${bin}/hive-service.sh hiveserver2 start
