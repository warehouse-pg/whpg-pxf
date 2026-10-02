package org.greenplum.pxf.api.examples;

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

import org.greenplum.pxf.api.OneField;
import org.greenplum.pxf.api.OneRow;
import org.greenplum.pxf.api.io.DataType;
import org.greenplum.pxf.api.model.GreenplumCSV;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Class that defines the serialization / deserialization of one record brought from the external input data.
 * <p>
 * Demo implementation of resolver that returns text format
 */
public class DemoTextResolver extends DemoResolver {

    /**
     * Read the next record
     * The record contains as many fields as defined by the DDL schema.
     *
     * @param row one record
     * @return the first column contains the entire text data
     */
    @Override
    public List<OneField> getFields(OneRow row) {
        List<OneField> output = new LinkedList<>();
        Object data = row.getData();
        output.add(new OneField(DataType.VARCHAR.getOID(), data));
        return output;
    }

    /**
     * Creates a OneRow object holding one text line from the fields of a record.
     * <p>
     * For TEXT and CSV writable tables the service parses every incoming line
     * into one field per table column before calling the resolver (resolvers
     * annotated with {@code @InputStreamHandler} receive the raw stream
     * instead). This demo re-joins the fields with the table's delimiter and
     * newline, so the bytes handed to the accessor are the line as the
     * database sent it.
     *
     * @param record list of {@link OneField}, one per table column
     * @return the constructed {@link OneRow} whose data is the line as {@code byte[]}
     * @throws Exception if the record is null or empty
     */
    @Override
    public OneRow setFields(List<OneField> record) throws Exception {
        if (record == null || record.isEmpty()) {
            throw new Exception("Unexpected record format, expected at least 1 field, found " +
                    (record == null ? 0 : record.size()));
        }
        GreenplumCSV csv = context.getGreenplumCSV();
        Charset encoding = context.getDatabaseEncoding() != null
                ? context.getDatabaseEncoding() : StandardCharsets.UTF_8;
        // DELIMITER 'OFF' (single-column tables) leaves the delimiter null: join with nothing
        String delimiter = csv.getDelimiter() == null ? "" : String.valueOf(csv.getDelimiter());
        StringJoiner line = new StringJoiner(delimiter, "", csv.getNewline());
        for (OneField field : record) {
            line.add(formatField(field.val, csv.getValueOfNull(), encoding));
        }
        return new OneRow(line.toString().getBytes(encoding));
    }

    /**
     * Renders one parsed field back to text. The service hands most column
     * types over as a String or a boxed number; BYTEA arrives as a
     * {@link ByteBuffer} (or {@code byte[]}) and NULL as {@code null}.
     */
    private static String formatField(Object value, String nullString, Charset encoding) {
        if (value == null) {
            return nullString;
        }
        if (value instanceof ByteBuffer) {
            ByteBuffer buffer = ((ByteBuffer) value).duplicate();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return new String(bytes, encoding);
        }
        if (value instanceof byte[]) {
            return new String((byte[]) value, encoding);
        }
        return String.valueOf(value);
    }
}
