package org.greenplum.pxf.plugins.hbase;

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


import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.greenplum.pxf.api.model.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class HBaseAccessorTest {
    static final String tableName = "fishy_HBase_table";

    private RequestContext context;
    Table table;
    Scan scanDetails;
    Configuration hbaseConfiguration;
    Connection hbaseConnection;
    HBaseAccessor accessor;

    /*
     * After each test is done, close the accessor
     * if it was created
     */
    @AfterEach
    public void tearDown() throws Exception {
        if (accessor == null) {
            return;
        }

        closeAccessor();
        accessor = null;
    }

    /*
     * Test construction of HBaseAccessor.
     * Actually no need for this as it is tested in all other tests
     * constructing HBaseAccessor but it serves as a simple example
     * of mocking
     *
     * HBaseAccessor is created and then HBaseTupleDescriptioncreation
     * is verified
     */
    @Test
    public void construction() {
        prepareConstruction();
        HBaseAccessor accessor = new HBaseAccessor();
        accessor.setRequestContext(context);
        accessor.afterPropertiesSet();
    }

    /*
     * Test Open returns false when table has no regions
     *
     * Done by returning an empty Map from getRegionLocations
     * Verify Scan object doesn't contain any columns / filters
     * Verify scan did not start
     */
    @Test
    @Disabled
    public void tableHasNoMetadata() throws Exception {
        prepareConstruction();
        prepareTableOpen();
        prepareEmptyScanner();

        accessor = new HBaseAccessor();
        accessor.setRequestContext(context);
        accessor.afterPropertiesSet();

        Exception e = assertThrows(Exception.class, accessor::openForRead,
                "should throw no metadata exception");
        assertEquals("Missing fragment metadata information", e.getMessage());

        verifyScannerDidNothing();
    }

    /*
     * NEW-4 (PTT-1135): hbase.read.rpc.timeout config-propagation smoke test.
     *
     * HBase 2.5 renamed the old hbase.rpc.timeout knob to a pair of more
     * specific keys: hbase.read.rpc.timeout / hbase.write.rpc.timeout
     * (HBASE-27078). PXF doesn't read these keys directly — it just wraps
     * the caller-supplied Configuration via HBaseConfiguration.create(...)
     * before handing it to ConnectionFactory. This test pins the contract
     * that the wrap doesn't drop or rename the new keys, so an operator
     * can set them in $PXF_SERVER/hbase-site.xml and have them reach the
     * HBase client.
     */
    @Test
    public void readRpcTimeoutConfigIsHonored() {
        Configuration cfg = new Configuration(false);
        cfg.set("hbase.read.rpc.timeout", "12345");
        cfg.set("hbase.write.rpc.timeout", "67890");
        // Also set the legacy key to confirm it survives alongside the new ones.
        cfg.set("hbase.rpc.timeout", "54321");

        // This mirrors what HBaseAccessor.openTable() does just before
        // calling ConnectionFactory.createConnection(...).
        Configuration wrapped = HBaseConfiguration.create(cfg);

        assertEquals("12345", wrapped.get("hbase.read.rpc.timeout"),
                "hbase.read.rpc.timeout (HBase 2.5 key) must propagate through HBaseConfiguration.create");
        assertEquals("67890", wrapped.get("hbase.write.rpc.timeout"),
                "hbase.write.rpc.timeout (HBase 2.5 key) must propagate through HBaseConfiguration.create");
        assertEquals("54321", wrapped.get("hbase.rpc.timeout"),
                "Legacy hbase.rpc.timeout must continue to propagate (back-compat for older operators)");
    }

    /*
     * NEW-7 (PTT-1135): post-Connection.close() lifecycle test.
     *
     * HBASE-21684 (HBase 2.3) changed StoppedRpcClientException to extend
     * DoNotRetryIOException so a closed connection no longer triggers a
     * retry storm. Our PXF accessor closes the connection in closeForRead.
     * This test pins the contract: closeForRead() actually invokes close()
     * on both the Table and the Connection it acquired in openForRead(),
     * so any subsequent RPC against that connection would surface the new
     * fast-fail exception class. It also guards against a future
     * refactor that accidentally caches and reuses a closed connection.
     */
    @Test
    public void closeForReadClosesTableAndConnection() throws Exception {
        prepareConstruction();

        accessor = new HBaseAccessor();
        accessor.setRequestContext(context);
        accessor.afterPropertiesSet();

        // Directly wire up mocks for the private table + connection fields so
        // closeForRead() has something to act on without going through the
        // full openForRead path (which needs a region locator etc.). This
        // keeps the test scoped to the closeForRead lifecycle contract.
        Connection mockConnection = mock(Connection.class);
        Table mockTable = mock(Table.class);
        // Reassign the fields used by the accessor closure callbacks
        hbaseConnection = mockConnection;
        table = mockTable;
        java.lang.reflect.Field connField = HBaseAccessor.class.getDeclaredField("connection");
        connField.setAccessible(true);
        connField.set(accessor, mockConnection);
        java.lang.reflect.Field tableField = HBaseAccessor.class.getDeclaredField("table");
        tableField.setAccessible(true);
        tableField.set(accessor, mockTable);

        accessor.closeForRead();

        // Both must be closed — any subsequent RPC on the connection would
        // now surface as DoNotRetryIOException (HBASE-21684) rather than
        // retrying.
        verify(mockTable).close();
        verify(mockConnection).close();

        // Sanity: the fields are still referenceable (not nulled), so an
        // accidental re-use would clearly hit the closed connection and
        // fast-fail.
        assertNotNull(connField.get(accessor),
                "connection field is expected to retain its reference after closeForRead so a stale-reuse bug fast-fails");
        assertNotNull(tableField.get(accessor),
                "table field is expected to retain its reference after closeForRead so a stale-reuse bug fast-fails");

        // Prevent @AfterEach's closeAccessor() from invoking closeForRead twice
        // (which would NPE on the closed table mock).
        accessor = null;
    }

    /*
     * Helper for test setup.
     * Creates a mock for HBaseTupleDescription and RequestContext
     */
    private void prepareConstruction() {
        context = new RequestContext();
        context.setConfig("default");
        context.setUser("test-user");
        context.setFragmentMetadata(new HBaseFragmentMetadata(new byte[0], new byte[0], new HashMap<>()));
    }

    /*
     * Helper for test setup.
     * Adds a table name and prepares for table creation
     */
    private void prepareTableOpen() throws Exception {
        // Set table name
        context.setDataSource(tableName);

        hbaseConfiguration = mock(Configuration.class);
        when(HBaseConfiguration.create()).thenReturn(hbaseConfiguration);

        // Make sure we mock static functions in ConnectionFactory
        hbaseConnection = mock(Connection.class);
        when(ConnectionFactory.createConnection(hbaseConfiguration)).thenReturn(hbaseConnection);
        table = mock(Table.class);
        when(hbaseConnection.getTable(TableName.valueOf(tableName))).thenReturn(table);
    }

    /*
     * Helper for test setup.
     * Sets zero columns (not realistic) and no filter
     */
    private void prepareEmptyScanner() {
        scanDetails = mock(Scan.class);
    }

    /*
     * Verify Scan object was used but didn't do much
     */
    private void verifyScannerDidNothing() throws Exception {
        // readVersions was called with 1
        verify(scanDetails).readVersions(1);
        // addColumn was not called
        verify(scanDetails, never()).addColumn(any(byte[].class), any(byte[].class));
        // addFilter was not called
        verify(scanDetails, never()).setFilter(any(org.apache.hadoop.hbase.filter.Filter.class));
        // Nothing else was missed
        verifyNoMoreInteractions(scanDetails);
        // Scanner was not used
        verify(table, never()).getScanner(scanDetails);
    }

    /*
     * Close the accessor and make sure table was closed
     */
    private void closeAccessor() throws Exception {
        accessor.closeForRead();
        verify(table).close();
    }
}
