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
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.TableDescriptor;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/*
 * Regression tests for the resource-cleanup lifecycle of HBaseLookupTable.
 *
 * HBaseLookupTable's real constructor opens a live HBase Connection/Admin,
 * so these tests never call it directly. Instead they build the object
 * as a partial mock (byte-buddy/Objenesis creates the instance without
 * running the declared constructor) and wire mock
 * Admin/Connection/Table instances into the private fields via reflection.
 * This is similar to the field-injection strategy used in
 * HBaseAccessorTest#closeForReadClosesTableAndConnection, though that test
 * constructs HBaseAccessor normally rather than bypassing its constructor.
 * close() and getMappings() are then invoked for real via CALLS_REAL_METHODS.
 */
public class HBaseLookupTableTest {

    private static final String LOOKUP_TABLE_NAME = "pxflookup";

    /*
     * Bug regression test: previously close() only closed admin, leaking
     * the Connection whenever getMappings() returned early from
     * lookupTableValid() (e.g. missing/invalid pxflookup table). This
     * pins that close() now closes admin AND connection on that path,
     * and never opens (or attempts to close) a lookupTable Table handle
     * since one was never opened.
     */
    @Test
    public void closeReleasesConnectionWhenLookupTableInvalid() throws Exception {
        Admin mockAdmin = mock(Admin.class);
        Connection mockConnection = mock(Connection.class);

        // Lookup table does not exist / is not available -> lookupTableValid()
        // returns false on the first check, so getMappings() returns early
        // and no Table is ever opened.
        when(mockAdmin.isTableAvailable(TableName.valueOf(LOOKUP_TABLE_NAME))).thenReturn(false);

        HBaseLookupTable lookupTable = newLookupTableWithMocks(mockAdmin, mockConnection, null);

        Map<String, byte[]> mappings = lookupTable.getMappings("some_table");
        assertNull(mappings, "expected null mappings when lookup table is invalid");

        lookupTable.close();

        // The fix: close() must close both admin and connection even
        // though the early-return path never opened a Table.
        verify(mockAdmin).close();
        verify(mockConnection).close();
    }

    /*
     * Success-path test: when the lookup table is opened, close() must
     * close the lookupTable Table handle, admin, and connection.
     */
    @Test
    public void closeReleasesLookupTableAdminAndConnectionOnSuccessPath() throws Exception {
        Admin mockAdmin = mock(Admin.class);
        Connection mockConnection = mock(Connection.class);
        Table mockTable = mock(Table.class);

        HBaseLookupTable lookupTable = newLookupTableWithMocks(mockAdmin, mockConnection, mockTable);

        lookupTable.close();

        InOrder inOrder = inOrder(mockTable, mockAdmin, mockConnection);
        inOrder.verify(mockTable).close();
        inOrder.verify(mockAdmin).close();
        inOrder.verify(mockConnection).close();
    }

    /*
     * Regression test: if lookupTable.close() throws, close() must still
     * attempt to close admin and connection and propagate the original exception.
     */
    @Test
    public void closeStillClosesAdminAndConnectionWhenLookupTableCloseThrows() throws Exception {
        Admin mockAdmin = mock(Admin.class);
        Connection mockConnection = mock(Connection.class);
        Table mockTable = mock(Table.class);

        doThrow(new IOException("table close failed")).when(mockTable).close();

        HBaseLookupTable lookupTable = newLookupTableWithMocks(mockAdmin, mockConnection, mockTable);

        try {
            lookupTable.close();
        } catch (IOException e) {
            InOrder inOrder = inOrder(mockTable, mockAdmin, mockConnection);
            inOrder.verify(mockTable).close();
            inOrder.verify(mockAdmin).close();
            inOrder.verify(mockConnection).close();
            return;
        }
        throw new AssertionError("Expected IOException from Table.close() to be thrown");
    }

    /*
     * Regression test: when the lookup table exists but does not contain the
     * expected mapping family, this is treated as invalid structure and
     * getMappings() should return null.
     */
    @Test
    public void getMappingsReturnsNullAndClosesAdminAndConnectionWhenLookupTableStructureInvalid() throws Exception {
        Admin mockAdmin = mock(Admin.class);
        Connection mockConnection = mock(Connection.class);
        TableDescriptor mockDescriptor = mock(TableDescriptor.class);

        when(mockAdmin.isTableAvailable(TableName.valueOf(LOOKUP_TABLE_NAME))).thenReturn(true);
        when(mockAdmin.isTableEnabled(TableName.valueOf(LOOKUP_TABLE_NAME))).thenReturn(true);
        when(mockAdmin.getDescriptor(TableName.valueOf(LOOKUP_TABLE_NAME))).thenReturn(mockDescriptor);
        when(mockDescriptor.hasColumnFamily(Bytes.toBytes("mapping"))).thenReturn(false);

        HBaseLookupTable lookupTable = newLookupTableWithMocks(mockAdmin, mockConnection, null);

        Map<String, byte[]> mappings = lookupTable.getMappings("some_table");
        assertNull(mappings, "expected null mappings when lookup table structure is invalid");

        lookupTable.close();

        InOrder inOrder = inOrder(mockAdmin, mockConnection);
        inOrder.verify(mockAdmin).close();
        inOrder.verify(mockConnection).close();
        verify(mockConnection, never()).getTable(any(TableName.class));
    }

    /*
     * Success-path test: when getMappings() is executed, resources should
     * remain open until close() is explicitly called.
     */
    @Test
    public void getMappingsKeepsResourcesOpenUntilClose() throws Exception {
        Admin mockAdmin = mock(Admin.class);
        Connection mockConnection = mock(Connection.class);
        Table mockTable = mock(Table.class);
        TableDescriptor mockDescriptor = mock(TableDescriptor.class);
        Result mockResult = mock(Result.class);

        when(mockAdmin.isTableAvailable(TableName.valueOf(LOOKUP_TABLE_NAME))).thenReturn(true);
        when(mockAdmin.isTableEnabled(TableName.valueOf(LOOKUP_TABLE_NAME))).thenReturn(true);
        when(mockAdmin.getDescriptor(TableName.valueOf(LOOKUP_TABLE_NAME))).thenReturn(mockDescriptor);
        when(mockDescriptor.hasColumnFamily(Bytes.toBytes("mapping"))).thenReturn(true);
        when(mockConnection.getTable(TableName.valueOf(LOOKUP_TABLE_NAME))).thenReturn(mockTable);
        when(mockTable.get(any(Get.class))).thenReturn(mockResult);
        when(mockResult.getFamilyMap(Bytes.toBytes("mapping"))).thenReturn(new HashMap<>());

        HBaseLookupTable lookupTable = newLookupTableWithMocks(mockAdmin, mockConnection, null);

        Map<String, byte[]> mappings = lookupTable.getMappings("some_table");

        assertNotNull(mappings, "expected non-null mappings on success path");
        verify(mockAdmin, never()).close();
        verify(mockConnection, never()).close();
        verify(mockTable, never()).close();

        ReflectionTestUtils.setField(lookupTable, "lookupTable", mockTable);
        lookupTable.close();

        InOrder inOrder = inOrder(mockTable, mockAdmin, mockConnection);
        inOrder.verify(mockTable).close();
        inOrder.verify(mockAdmin).close();
        inOrder.verify(mockConnection).close();
    }

    /*
     * Constructs an HBaseLookupTable instance without invoking its real
     * constructor (which would try to reach a live HBase cluster), and
     * injects the given mocks into its private fields.
     */
    private HBaseLookupTable newLookupTableWithMocks(Admin admin, Connection connection, Table lookupTable)
            throws Exception {
        HBaseLookupTable instance = mock(HBaseLookupTable.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS));

        ReflectionTestUtils.setField(instance, "admin", admin);
        ReflectionTestUtils.setField(instance, "connection", connection);
        if (lookupTable != null) {
            ReflectionTestUtils.setField(instance, "lookupTable", lookupTable);
        }

        return instance;
    }

}
