package org.greenplum.pxf.plugins.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * jackson 2.15+ enforces read limits by default. The JSON connector lifts the
 * string, number and field-name length limits so customer files that parsed
 * before still parse, and keeps the nesting-depth limit, which is the fix for
 * CVE-2025-52999 (StackOverflowError on deeply nested input).
 */
public class JsonReadConstraintsTest {

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    private static String nested(int depth) {
        StringBuilder sb = new StringBuilder(depth * 6 + 1);
        for (int i = 0; i < depth; i++) {
            sb.append("{\"a\":");
        }
        sb.append('1');
        for (int i = 0; i < depth; i++) {
            sb.append('}');
        }
        return sb.toString();
    }

    @Test
    public void stringLongerThanJacksonDefaultParses() throws Exception {
        // jackson's default maximum string length is 20,000,000 characters
        int size = 20_000_001;
        String json = "{\"big\":\"" + repeat('x', size) + "\"}";
        try (JsonParser parser = JsonAccessor.newReadFactory().createParser(json)) {
            assertEquals(JsonToken.START_OBJECT, parser.nextToken());
            assertEquals(JsonToken.FIELD_NAME, parser.nextToken());
            assertEquals(JsonToken.VALUE_STRING, parser.nextToken());
            assertEquals(size, parser.getText().length());
        }
    }

    @Test
    public void fieldNameLongerThanJacksonDefaultParses() throws Exception {
        // jackson's default maximum field name length is 50,000 characters
        int size = 50_001;
        String json = "{\"" + repeat('n', size) + "\":1}";
        try (JsonParser parser = JsonAccessor.newReadFactory().createParser(json)) {
            assertEquals(JsonToken.START_OBJECT, parser.nextToken());
            assertEquals(JsonToken.FIELD_NAME, parser.nextToken());
            assertEquals(size, parser.currentName().length());
        }
    }

    @Test
    public void numberLongerThanJacksonDefaultParses() throws Exception {
        // jackson's default maximum number length is 1000 digits
        String digits = "1" + repeat('0', 1500);
        ObjectMapper mapper = new ObjectMapper(JsonAccessor.newReadFactory());
        assertEquals(digits, mapper.readTree("{\"n\":" + digits + "}").get("n").bigIntegerValue().toString());
    }

    @Test
    public void nestingWithinJacksonDefaultParses() throws Exception {
        ObjectMapper mapper = new ObjectMapper(JsonAccessor.newReadFactory());
        mapper.readTree(nested(1000));
    }

    @Test
    public void deeplyNestedRecordFailsWithParseErrorNotStackOverflow() {
        // CVE-2025-52999: before jackson 2.15 this overflowed the stack in readTree
        ObjectMapper mapper = new ObjectMapper(JsonAccessor.newReadFactory());
        assertThrows(StreamConstraintsException.class, () -> mapper.readTree(nested(200_000)));
    }

    @Test
    public void accessorFactoryKeepsNestingLimit() {
        JsonFactory factory = JsonAccessor.newReadFactory();
        assertEquals(1000, factory.streamReadConstraints().getMaxNestingDepth());
    }
}
