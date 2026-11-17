package org.greenplum.pxf.plugins.hdfs;

import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.schema.MessageType;
import org.greenplum.pxf.api.model.RequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ParquetFileAccessorTest {
    ParquetFileAccessor accessor;
    RequestContext context;
    MessageType schema;

    @TempDir
    Path tempDir;

    @BeforeEach
    public void setup() {
        accessor = new ParquetFileAccessor();
        context = new RequestContext();
        context.setConfig("default");
        context.setUser("test-user");
        schema = new MessageType("hive_schema");
    }

    @Test
    public void testInitialize() {
        accessor.setRequestContext(context);
        assertNull(context.getMetadata());
    }

    /**
     * A footer that declares a field of almost 2 GB must be rejected, not
     * allocated. The Thrift runtime shaded into parquet-format 2.7.0 had no
     * size limit, so this 34-byte file made the read allocate the full
     * declared length and fail with OutOfMemoryError, which kills the PXF
     * JVM when PXF_OOM_KILL is on (the default).
     */
    @Test
    public void testFooterDeclaringHugeFieldIsRejectedWithoutAllocating() throws Exception {
        Path file = tempDir.resolve("huge_field_footer.parquet");
        Files.write(file, parquetFileWithFooter(footerWithBinaryFieldOfLength(0x7FFFFFF0)));

        context.setProfileScheme("localfile");
        context.setRequestType(RequestContext.RequestType.READ_BRIDGE);
        context.setDataSource(file.toString());
        context.setFragmentMetadata(new HcfsFragmentMetadata(0, Files.size(file)));
        context.setTupleDescription(Collections.emptyList());
        context.setConfiguration(new Configuration());
        accessor.setRequestContext(context);
        accessor.afterPropertiesSet();

        IOException e = assertThrows(IOException.class, accessor::openForRead);
        assertTrue(rootCauseMessage(e).contains("MaxMessageSize reached"),
                "unexpected root cause: " + rootCauseMessage(e));
    }

    /**
     * FileMetaData holding one unknown field (id 15, compact type BINARY)
     * whose length prefix is {@code length}, followed by only 16 real bytes.
     */
    private static byte[] footerWithBinaryFieldOfLength(int length) {
        ByteArrayOutputStream footer = new ByteArrayOutputStream();
        footer.write(0xF8);
        while ((length & ~0x7F) != 0) {
            footer.write((length & 0x7F) | 0x80);
            length >>>= 7;
        }
        footer.write(length);
        footer.write(new byte[16], 0, 16);
        return footer.toByteArray();
    }

    /** "PAR1", the footer, its little-endian length, "PAR1". */
    private static byte[] parquetFileWithFooter(byte[] footer) {
        byte[] magic = "PAR1".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(magic, 0, magic.length);
        out.write(footer, 0, footer.length);
        int n = footer.length;
        out.write(n & 0xFF);
        out.write((n >>> 8) & 0xFF);
        out.write((n >>> 16) & 0xFF);
        out.write((n >>> 24) & 0xFF);
        out.write(magic, 0, magic.length);
        return out.toByteArray();
    }

    private static String rootCauseMessage(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getClass().getName() + ": " + t.getMessage();
    }
}
