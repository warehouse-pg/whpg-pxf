#!/bin/bash

>~/workspace/singlecluster/hadoop/etc/hadoop/core-site.xml cat <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<?xml-stylesheet type="text/xsl" href="configuration.xsl"?>
<!--
  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License. See accompanying LICENSE file.
-->

<!-- Put site-specific property overrides in this file. -->

<configuration>
    <property>
        <name>fs.defaultFS</name>
        <value>hdfs://0.0.0.0:8020</value>
    </property>
    <property>
        <name>ipc.ping.interval</name>
        <value>900000</value>
    </property>
    <property>
        <name>hadoop.proxyuser.gpadmin.hosts</name>
        <value>*</value>
    </property>
    <property>
        <name>hadoop.proxyuser.gpadmin.groups</name>
        <value>*</value>
    </property>
    <property>
        <name>hadoop.security.authorization</name>
        <value>false</value>
    </property>
    <property>
        <name>hbase.security.authorization</name>
        <value>false</value>
    </property>
    <property>
        <name>hbase.rpc.protection</name>
        <value>authentication</value>
    </property>
</configuration>
EOF

>~/workspace/singlecluster/hbase/conf/hbase-site.xml cat <<EOF
<?xml version="1.0"?>
<?xml-stylesheet type="text/xsl" href="configuration.xsl"?>
<!--
/**
 * Copyright 2010 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
-->
<configuration>
    <property>
        <name>hbase.rootdir</name>
        <value>hdfs://0.0.0.0:8020/hbase</value>
    </property>
    <property>
        <name>dfs.replication</name>
        <value>3</value>
    </property>
    <property>
        <name>dfs.support.append</name>
        <value>true</value>
    </property>
    <property>
        <name>hbase.cluster.distributed</name>
        <value>true</value>
    </property>
	<property>
		<name>hbase.zookeeper.quorum</name>
		<value>127.0.0.1</value>
	</property>
	<property>
		<name>hbase.zookeeper.property.clientPort</name>
		<value>2181</value>
	</property>
    <property>
        <name>hadoop.proxyuser.gpadmin.hosts</name>
        <value>*</value>
    </property>
    <property>
        <name>hadoop.proxyuser.gpadmin.groups</name>
        <value>*</value>
    </property>
    <property>
        <name>hadoop.security.authorization</name>
        <value>false</value>
    </property>
    <property>
        <name>hbase.security.authorization</name>
        <value>false</value>
    </property>
    <property>
        <name>hbase.rpc.protection</name>
        <value>authentication</value>
    </property>
    <!--
      HBASE-21639 mitigation: HBase 2.6.5 default asyncfs WAL provider hits a
      ProtobufDecoder reflection incompatibility on Hadoop 3.3.6 causing HMaster
      to abort. Switching to the older synchronous "filesystem" writer bypasses
      the reflection path. Surfaced during singlecluster sanity checks.
    -->
    <property>
        <name>hbase.wal.provider</name>
        <value>filesystem</value>
    </property>
</configuration>
EOF

>~/workspace/singlecluster/hive/conf/hive-site.xml cat <<EOF
<configuration>
	<property>
		<name>hive.metastore.warehouse.dir</name>
		<value>/hive/warehouse</value>
	</property>
	<property>
		<name>hive.metastore.uris</name>
		<value>thrift://localhost:9083</value>
	</property>
	<property>
		<name>hive.server2.enable.impersonation</name>
		<value>true</value>
		<description>Set this property to enable impersonation in Hive Server 2</description>
	</property>
	<property>
		<name>hive.server2.enable.doAs</name>
		<value>false</value>
		<description>Set this property to enable impersonation in Hive Server 2</description>
	</property>
	<property>
		<name>hive.execution.engine</name>
		<value>mr</value>
		<description>Chooses execution engine. Options are: mr(default), tez, or spark</description>
	</property>
	<property>
		<name>hive.metastore.schema.verification</name>
		<value>false</value>
		<description>Modify schema instead of reporting error</description>
	</property>
	<property>
		<name>datanucleus.autoCreateTables</name>
		<value>True</value>
	</property>
	<property>
		<name>hive.metastore.integral.jdo.pushdown</name>
		<value>True</value>
	</property>

	<!--
	  Hive 4.x behavior pins. Some Hive 4 configurations default CREATE
	  TABLE to transactional (ACID) semantics; the test fixtures create
	  plain text-format tables and manage them directly on HDFS, so pin
	  the pre-4.x behavior explicitly. Transactional tables keep working:
	  the ORC ACID tests declare TBLPROPERTIES ('transactional'='true')
	  and run under the DbTxnManager configured below.
	-->
	<property>
		<name>hive.create.as.acid</name>
		<value>false</value>
	</property>
	<property>
		<name>hive.create.as.insert.only</name>
		<value>false</value>
	</property>
	<property>
		<name>metastore.create.as.acid</name>
		<value>false</value>
	</property>
	<property>
		<name>hive.metastore.event.db.notification.api.auth</name>
		<value>false</value>
		<description>Hive 4.x metastores reject notification-API calls unless the caller is a superuser; the singlecluster runs unsecured, so disable the check</description>
	</property>
	<property>
		<name>hive.vectorized.execution.enabled</name>
		<value>false</value>
		<description>Hive 4 enables vectorization by default (Hive 2.x did not). On this MR-engine cluster the vectorized deserializer nulls BINARY columns read from text tables in MR tasks (fetch tasks, which do not vectorize, read the same rows correctly), so fixture INSERT...SELECT copies silently write NULL binary data. Pin the Hive 2.x behavior; vectorization is a server-side optimization irrelevant to what these tests exercise</description>
	</property>

	<!--
	  Transaction (ACID) support. Required by the JSystem hive group:
	  HiveOrcAcidTest creates transactional ORC tables, which drive
	  Hive's DbTxnManager and a lock() RPC against the metastore.

	  The test does SET these on its own JDBC session, but that is
	  not sufficient. The metastore SERVER reads them from here at
	  startup, and without them it rejects the lock RPC with
	    TApplicationException: Internal error processing lock
	  surfacing to the client as
	    FAILED: Error in acquiring locks: Error communicating with the metastore

	  compactor.worker.threads is 1 rather than 0 so compaction can
	  actually run; the initiator alone only queues work.
	-->
	<property>
		<name>hive.support.concurrency</name>
		<value>true</value>
		<description>Required for ACID tables; enables the lock manager</description>
	</property>
	<property>
		<name>hive.txn.manager</name>
		<value>org.apache.hadoop.hive.ql.lockmgr.DbTxnManager</value>
		<description>Transaction manager backing ACID tables</description>
	</property>
	<property>
		<name>hive.compactor.initiator.on</name>
		<value>true</value>
		<description>Queue compaction of ACID delta files</description>
	</property>
	<property>
		<name>hive.compactor.worker.threads</name>
		<value>1</value>
		<description>Workers to run queued compactions</description>
	</property>
</configuration>
EOF
