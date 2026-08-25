package org.greenplum.pxf.plugins.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * jackson 2.15+ enforces StreamReadConstraints by default (max single
 * string ~20MB, max nesting depth 1000). PXF parses arbitrary customer
 * JSON, so the connector restores the unconstrained pre-2.15 behavior —
 * these tests pin that: inputs that exceed the jackson defaults must
 * still parse.
 */
public class JsonStreamConstraintsTest {

    @Test
    public void stringLargerThanJacksonDefaultLimitParses() throws Exception {
        // jackson's default max string is 20_000_000 chars; exceed it
        int size = 20_000_001;
        StringBuilder sb = new StringBuilder(size + 20);
        sb.append("{\"big\":\"");
        for (int i = 0; i < size; i++) {
            sb.append('x');
        }
        sb.append("\"}");

        JsonFactory factory = JsonAccessor.newUnconstrainedFactory();
        try (JsonParser parser = factory.createParser(sb.toString())) {
            assertEquals(JsonToken.START_OBJECT, parser.nextToken());
            assertEquals(JsonToken.FIELD_NAME, parser.nextToken());
            assertEquals(JsonToken.VALUE_STRING, parser.nextToken());
            assertEquals(size, parser.getText().length());
            assertEquals(JsonToken.END_OBJECT, parser.nextToken());
        }
    }

    @Test
    public void nestingDeeperThanJacksonDefaultLimitParses() throws Exception {
        // jackson's default max nesting depth is 1000; exceed it
        int depth = 1200;
        StringBuilder sb = new StringBuilder(depth * 8 + 16);
        for (int i = 0; i < depth; i++) {
            sb.append("{\"a\":");
        }
        sb.append("1");
        for (int i = 0; i < depth; i++) {
            sb.append("}");
        }

        JsonFactory factory = JsonAccessor.newUnconstrainedFactory();
        try (JsonParser parser = factory.createParser(sb.toString())) {
            int opened = 0;
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token == JsonToken.START_OBJECT) {
                    opened++;
                }
            }
            assertEquals(depth, opened);
        }
    }
}
