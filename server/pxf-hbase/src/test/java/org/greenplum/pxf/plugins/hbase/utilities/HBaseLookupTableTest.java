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
import org.apache.hadoop.hbase.client.Table;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/*
 * Regression tests for the resource-cleanup lifecycle of HBaseLookupTable.
 *
 * HBaseLookupTable's real constructor opens a live HBase Connection/Admin,
 * so these tests never call it directly. Instead they build the object
 * as a partial mock (byte-buddy/Objenesis creates the instance without
 * running the declared constructor, same trick used in
 * HBaseAccessorTest#closeForReadClosesTableAndConnection) and wire mock
 * Admin/Connection/Table instances into the private fields via reflection.
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

        verify(mockTable).close();
        verify(mockAdmin).close();
        verify(mockConnection).close();
    }

    /*
     * If lookupTable was never opened (early-return path), close() must
     * not attempt to close a null Table reference.
     */
    @Test
    public void closeDoesNotTouchLookupTableWhenNeverOpened() throws Exception {
        Admin mockAdmin = mock(Admin.class);
        Connection mockConnection = mock(Connection.class);

        HBaseLookupTable lookupTable = newLookupTableWithMocks(mockAdmin, mockConnection, null);

        lookupTable.close();

        verify(mockAdmin).close();
        verify(mockConnection).close();
        // No Table mock was ever wired in, so there is nothing to verify
        // interactions "never" happened on -- absence of an NPE here is
        // itself the assertion that the null lookupTable was skipped.
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

        setField(instance, "admin", admin);
        setField(instance, "connection", connection);
        if (lookupTable != null) {
            setField(instance, "lookupTable", lookupTable);
        }

        return instance;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = HBaseLookupTable.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
