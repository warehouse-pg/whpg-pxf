package org.greenplum.pxf.plugins.hdfs;

import org.apache.avro.Schema;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.greenplum.pxf.api.OneRow;
import org.greenplum.pxf.api.model.RequestContext;
import org.greenplum.pxf.plugins.hdfs.avro.AvroUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class AvroFileAccessorTest {
    AvroFileAccessor accessor;
    RequestContext context;
    String avroDirectory;

    @BeforeEach
    public void setup() {
        accessor = new AvroFileAccessor(new AvroUtilities());
        context = new RequestContext();
        context.setConfig("fakeConfig");
        context.setServerName("fakeServerName");
        context.setUser("fakeUser");
        context.setDataSource(avroDirectory + "test.avro");
        context.setSegmentId(0);
        context.setTransactionId("testID");
        context.setProfileScheme("localfile");
        context.setRequestType(RequestContext.RequestType.READ_BRIDGE);
        context.setDataSource(this.getClass().getClassLoader().getResource("avro/").getPath() + "test.avro");
        context.setConfiguration(new Configuration());
    }

    @Test
    public void testInitialize() {
        accessor.setRequestContext(context);
        accessor.afterPropertiesSet();
        Schema schema = (Schema) context.getMetadata();
        assertNotNull(schema);
        verifySchema(schema, "example_schema");
    }

    /**
     * The file's own schema names a class in a java-class property. Reading it
     * must not load or instantiate that class (CVE-2024-47561): no SCHEMA
     * option is set, so the reader schema comes from the file.
     */
    @Test
    public void testReadDoesNotInstantiateClassNamedInFileSchema(@TempDir File tempDir) throws Exception {
        Schema writeSchema = new Schema.Parser().parse("{\"type\":\"record\",\"name\":\"crafted\",\"fields\":[" +
                "{\"name\":\"payload\",\"type\":{\"type\":\"string\",\"java-class\":\"" +
                Canary.class.getName() + "\"}}]}");
        File file = new File(tempDir, "crafted.avro");
        try (DataFileWriter<GenericRecord> writer = new DataFileWriter<>(new GenericDatumWriter<>(writeSchema))) {
            writer.create(writeSchema, file);
            GenericRecord record = new GenericData.Record(writeSchema);
            record.put("payload", "hello");
            writer.append(record);
        }
        Canary.INSTANCES.set(0);
        context.setDataSource(file.toURI().toString());
        context.setFragmentMetadata(new HcfsFragmentMetadata(0, file.length()));

        accessor.setRequestContext(context);
        accessor.afterPropertiesSet();
        accessor.openForRead();
        OneRow row = accessor.readNextObject();
        assertNotNull(row);
        Object payload = ((GenericRecord) row.getData()).get("payload");
        assertFalse(payload instanceof Canary, "payload was read as " + payload.getClass().getName());
        assertInstanceOf(CharSequence.class, payload);
        assertEquals("hello", payload.toString());
        assertNull(accessor.readNextObject());
        accessor.closeForRead();

        assertEquals(0, Canary.INSTANCES.get());
    }

    /**
     * Stands in for an attacker-chosen class: SpecificData would construct it
     * from the string value.
     */
    public static class Canary {
        static final AtomicInteger INSTANCES = new AtomicInteger();

        public Canary(String value) {
            INSTANCES.incrementAndGet();
        }
    }

    /**
     * Helper method for testing schema
     *
     * @param schema the schema
     * @param name   the name
     */
    private static void verifySchema(Schema schema, String name) {
        assertNotNull(schema);
        assertEquals(Schema.Type.RECORD, schema.getType());
        assertEquals(name, schema.getName());
        Map<String, String> fieldToType = new HashMap<String, String>() {{
            put("id", "long");
            put("username", "string");
            put("followers", "array");
        }};
        for (String key : fieldToType.keySet()) {
            assertEquals(
                    fieldToType.get(key),
                    schema.getField(key).schema().getType().getName()
            );
        }
    }
}
