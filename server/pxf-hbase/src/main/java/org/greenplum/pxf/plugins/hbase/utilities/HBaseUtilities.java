package org.greenplum.pxf.plugins.hbase.utilities;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */


import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;

import java.io.IOException;

public class HBaseUtilities {

    /**
     * Returns if given table exists and is enabled.
     *
     * @param hbaseAdmin HBase admin, must be initialized
     * @param tableName table name
     * @return true if table exists
     * @throws IOException if a remote or network exception occurs when connecting to HBase
     */
    public static boolean isTableAvailable(Admin hbaseAdmin, String tableName) throws IOException {
        TableName name = TableName.valueOf(tableName);
        return hbaseAdmin.isTableAvailable(name) &&
                hbaseAdmin.isTableEnabled(name);
    }

    /**
     * Closes HBase admin and connection if they are open.
     *
     * @param hbaseAdmin HBase admin
     * @param hbaseConnection HBase connection
     * @throws IOException if an I/O error occurs when closing HBase resources
     */
    public static void closeConnection(Admin hbaseAdmin, Connection hbaseConnection) throws IOException {
        closeAll(hbaseAdmin, hbaseConnection);
    }

    /**
     * Closes the given resources in order, attempting every one even if an
     * earlier close fails. Null resources are skipped. The first failure is
     * rethrown after all resources have been attempted — an
     * {@link IOException} or {@link RuntimeException} as-is, any other
     * checked exception wrapped in an {@link IOException} — with later
     * failures attached to it as suppressed exceptions.
     *
     * @param resources resources to close, in the order they should be closed
     * @throws IOException if a resource fails to close with a checked exception
     */
    public static void closeAll(AutoCloseable... resources) throws IOException {
        Exception first = null;
        for (AutoCloseable resource : resources) {
            if (resource == null) {
                continue;
            }
            try {
                resource.close();
            } catch (Exception e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (first == null) {
            return;
        }
        if (first instanceof IOException) {
            throw (IOException) first;
        }
        if (first instanceof RuntimeException) {
            throw (RuntimeException) first;
        }
        throw new IOException(first);
    }
}
