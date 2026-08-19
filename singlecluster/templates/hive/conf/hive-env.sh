# load singlecluster environment
if [ -f $bin/../bin/gphd-env.sh ]; then
	. $bin/../bin/gphd-env.sh
elif [ -f $bin/../../bin/gphd-env.sh ]; then
	. $bin/../../bin/gphd-env.sh
fi

# Log locations are JVM system properties, not HiveConf keys: log4j2 reads
# hive.log.dir from -D and Derby reads derby.stream.error.file from -D.
# They used to ride HIVE_OPTS as -hiveconf, which only works where a
# launcher translates the values onward (hive.log.dir is not a registered
# HiveConf key). Passing them as -D on HADOOP_CLIENT_OPTS delivers them
# uniformly to every Hive-launched JVM on every Hive line.
export HADOOP_CLIENT_OPTS="${HADOOP_CLIENT_OPTS} -Dhive.log.dir=$LOGS_ROOT -Dderby.stream.error.file=$LOGS_ROOT/derby.log"

# The embedded-Derby metastore URL has to reach the daemons on their
# command line (schematool and the metastore server must agree on the
# database path -- see init-gphd.sh). javax.jdo.option.ConnectionURL is a
# registered HiveConf key on every Hive line, so -hiveconf is safe for it.
export HIVE_OPTS="-hiveconf javax.jdo.option.ConnectionURL=jdbc:derby:;databaseName=$HIVE_STORAGE_ROOT/metastore_db;create=true"
# hive-service.sh launches the legacy hiveserver (Hive 1.x) with
# HIVE_SERVER_OPTS instead of HIVE_OPTS. The two carry the same settings;
# this used to be a mangled copy of HIVE_OPTS missing the ConnectionURL
# key before the ;databaseName= fragment.
export HIVE_SERVER_OPTS="${HIVE_OPTS}"
export HADOOP_HOME=$HADOOP_ROOT
export HADOOP_CLASSPATH="$HADOOP_CLASSPATH"
