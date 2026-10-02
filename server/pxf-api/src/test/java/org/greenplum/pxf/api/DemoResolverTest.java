package org.greenplum.pxf.api;

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


import org.greenplum.pxf.api.examples.DemoResolver;
import org.greenplum.pxf.api.examples.DemoTextResolver;
import org.greenplum.pxf.api.model.GreenplumCSV;
import org.greenplum.pxf.api.model.RequestContext;
import org.greenplum.pxf.api.utilities.ColumnDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.greenplum.pxf.api.io.DataType.BYTEA;
import static org.greenplum.pxf.api.io.DataType.INTEGER;
import static org.greenplum.pxf.api.io.DataType.TEXT;
import static org.greenplum.pxf.api.io.DataType.VARCHAR;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class DemoResolverTest {

    private static final String DATA = "value1,value2";

    private RequestContext context;
    private DemoResolver customResolver;
    private DemoTextResolver textResolver;
    private OneRow row;
    private OneField field;

    @BeforeEach
    public void setup() {
        context = new RequestContext();
        context.setConfig("default");
        context.setUser("test-user");
        context.setDatabaseEncoding(StandardCharsets.UTF_8);

        customResolver = new DemoResolver();
        textResolver = new DemoTextResolver();
        textResolver.setRequestContext(context);

        row = new OneRow("0.0", DATA);
        field = new OneField(VARCHAR.getOID(), DATA);
    }

    @Test
    public void testGetCustomData() {
        List<OneField> output = customResolver.getFields(row);
        assertEquals("value1", output.get(0).toString());
        assertEquals("value2", output.get(1).toString());
    }

    @Test
    public void testGetCustomDataHonorsColumnProjection() {
        RequestContext context = new RequestContext();
        context.setConfig("default");
        context.setUser("test-user");
        context.getTupleDescription().add(new ColumnDescriptor("a", VARCHAR.getOID(), 0, "varchar", null, false));
        context.getTupleDescription().add(new ColumnDescriptor("b", VARCHAR.getOID(), 1, "varchar", null, true));
        customResolver.setRequestContext(context);

        List<OneField> output = customResolver.getFields(row);
        assertEquals(2, output.size());
        assertNull(output.get(0).val);
        assertEquals("value2", output.get(1).toString());
    }

    @Test
    public void testGetTextData() {
        List<OneField> output = textResolver.getFields(row);
        assertEquals(DATA, output.get(0).toString());
    }

    @Test
    public void testSetTextDataSingleField() throws Exception {
        // a one-column table: the line is the field plus the newline
        OneRow output = textResolver.setFields(Collections.singletonList(field));
        assertArrayEquals((DATA + "\n").getBytes(StandardCharsets.UTF_8), (byte[]) output.getData());
    }

    @Test
    public void testSetTextDataManyFieldsAreJoinedWithDelimiter() throws Exception {
        // the service parses a TEXT line into one field per column (typed, not bytes);
        // the demo must re-join them into the line the database sent
        List<OneField> record = Arrays.asList(
                new OneField(INTEGER.getOID(), 10),
                new OneField(TEXT.getOID(), "data_10"));
        OneRow output = textResolver.setFields(record);
        assertArrayEquals("10,data_10\n".getBytes(StandardCharsets.UTF_8), (byte[]) output.getData());
    }

    @Test
    public void testSetTextDataHonorsDelimiterAndNullString() throws Exception {
        context.setGreenplumCSV(new GreenplumCSV().withDelimiter('|').withValueOfNull("\\N"));
        List<OneField> record = Arrays.asList(
                new OneField(INTEGER.getOID(), 7),
                new OneField(TEXT.getOID(), null),
                new OneField(TEXT.getOID(), "x"));
        OneRow output = textResolver.setFields(record);
        assertArrayEquals("7|\\N|x\n".getBytes(StandardCharsets.UTF_8), (byte[]) output.getData());
    }

    @Test
    public void testSetTextDataRendersByteaAsText() throws Exception {
        // the service parses BYTEA columns into a ByteBuffer; the demo writes the bytes back as text
        List<OneField> record = Arrays.asList(
                new OneField(INTEGER.getOID(), 1),
                new OneField(BYTEA.getOID(), ByteBuffer.wrap("raw".getBytes(StandardCharsets.UTF_8))),
                new OneField(BYTEA.getOID(), "arr".getBytes(StandardCharsets.UTF_8)));
        OneRow output = textResolver.setFields(record);
        assertArrayEquals("1,raw,arr\n".getBytes(StandardCharsets.UTF_8), (byte[]) output.getData());
    }

    @Test
    public void testSetTextDataNullInput() {
        assertThrows(Exception.class,
                () -> textResolver.setFields(null));
    }

    @Test
    public void testSetTextDataEmptyInput() {
        assertThrows(Exception.class,
                () -> textResolver.setFields(Collections.emptyList()));
    }


    @Test
    public void testSetFieldsIsUnsupported() {
      Exception e = assertThrows(UnsupportedOperationException.class,
                () -> customResolver.setFields(null));

      assertEquals("Demo resolver does not support write operation", e.getMessage());
    }
}
