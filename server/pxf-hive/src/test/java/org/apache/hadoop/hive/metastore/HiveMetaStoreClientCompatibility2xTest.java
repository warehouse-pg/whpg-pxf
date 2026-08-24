package org.apache.hadoop.hive.metastore;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.api.GetPartitionsByFilterRequest;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.PartitionsRequest;
import org.apache.hadoop.hive.metastore.api.PartitionsResponse;
import org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.transport.TTransportException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.anyShort;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the pre-3.x metastore fallback: the modern
 * request-object RPCs are rejected by old servers with
 * {@code TApplicationException}, and the client must retry through the
 * legacy positional RPCs — and must NOT swallow any other failure.
 * <p>
 * Construction mirrors the retired Hive-1.x compatibility test: the
 * embedded-metastore seam ({@code callEmbeddedMetastore}) is mocked so
 * the real constructor wires a mock thrift client without a live
 * metastore.
 */
public class HiveMetaStoreClientCompatibility2xTest {

    private ThriftHiveMetastore.Iface mockThriftClient;
    private MockedStatic<HiveMetaStoreClient> embeddedSeam;
    private HiveMetaStoreClientCompatibility2x client;

    private Partition legacyPartition;
    private Partition modernPartition;

    @BeforeEach
    public void setup() throws Exception {
        mockThriftClient = mock(ThriftHiveMetastore.Iface.class);
        embeddedSeam = mockStatic(HiveMetaStoreClient.class);
        embeddedSeam.when(() -> HiveMetaStoreClient.callEmbeddedMetastore(any(Configuration.class)))
                .thenReturn(mockThriftClient);

        // empty thrift URIs select the embedded path the seam intercepts
        client = new HiveMetaStoreClientCompatibility2x(new Configuration(false));

        legacyPartition = new Partition();
        legacyPartition.setDbName("db");
        legacyPartition.setTableName("legacy");
        modernPartition = new Partition();
        modernPartition.setDbName("db");
        modernPartition.setTableName("modern");
    }

    @AfterEach
    public void teardown() {
        embeddedSeam.close();
    }

    private static TApplicationException unknownMethod() {
        return new TApplicationException(TApplicationException.UNKNOWN_METHOD, "get_partitions_req");
    }

    private static TApplicationException invalidMethodName() {
        // a real Hive 2.3.8 metastore answers with this message shape
        return new TApplicationException("Invalid method name: 'get_partitions_req'");
    }

    @Test
    public void listPartitionsFallsBackWhenServerLacksModernRpc() throws Exception {
        when(mockThriftClient.get_partitions_req(any(PartitionsRequest.class))).thenThrow(unknownMethod());
        when(mockThriftClient.get_partitions("db", "tbl", (short) 10))
                .thenReturn(Collections.singletonList(legacyPartition));

        List<Partition> result = client.listPartitions("db", "tbl", (short) 10);

        assertEquals(1, result.size());
        assertSame(legacyPartition, result.get(0));
    }

    @Test
    public void listPartitionsFallsBackOnInvalidMethodNameMessage() throws Exception {
        when(mockThriftClient.get_partitions_req(any(PartitionsRequest.class))).thenThrow(invalidMethodName());
        when(mockThriftClient.get_partitions("db", "tbl", (short) 10))
                .thenReturn(Collections.singletonList(legacyPartition));

        List<Partition> result = client.listPartitions("db", "tbl", (short) 10);

        assertEquals(1, result.size());
        assertSame(legacyPartition, result.get(0));
    }

    @Test
    public void listPartitionsUsesModernRpcWhenServerImplementsIt() throws Exception {
        PartitionsResponse response = new PartitionsResponse();
        response.setPartitions(Collections.singletonList(modernPartition));
        when(mockThriftClient.get_partitions_req(any(PartitionsRequest.class))).thenReturn(response);

        List<Partition> result = client.listPartitions("db", "tbl", (short) 10);

        assertEquals(1, result.size());
        assertEquals("modern", result.get(0).getTableName());
        verify(mockThriftClient, never()).get_partitions(anyString(), anyString(), anyShort());
    }

    @Test
    public void listPartitionsDoesNotSwallowTransportFailures() throws Exception {
        when(mockThriftClient.get_partitions_req(any(PartitionsRequest.class)))
                .thenThrow(new TTransportException("connection reset"));

        assertThrows(TTransportException.class, () -> client.listPartitions("db", "tbl", (short) 10));
        verify(mockThriftClient, never()).get_partitions(anyString(), anyString(), anyShort());
    }

    @Test
    public void listPartitionsDoesNotSwallowOtherApplicationErrors() throws Exception {
        when(mockThriftClient.get_partitions_req(any(PartitionsRequest.class)))
                .thenThrow(new TApplicationException(TApplicationException.INTERNAL_ERROR, "boom"));

        assertThrows(TApplicationException.class, () -> client.listPartitions("db", "tbl", (short) 10));
        verify(mockThriftClient, never()).get_partitions(anyString(), anyString(), anyShort());
    }

    @Test
    public void listPartitionsByFilterFallsBackWhenServerLacksModernRpc() throws Exception {
        when(mockThriftClient.get_partitions_by_filter_req(any(GetPartitionsByFilterRequest.class)))
                .thenThrow(unknownMethod());
        when(mockThriftClient.get_partitions_by_filter("db", "tbl", "part = 'a'", (short) 10))
                .thenReturn(Collections.singletonList(legacyPartition));

        List<Partition> result = client.listPartitionsByFilter("db", "tbl", "part = 'a'", (short) 10);

        assertEquals(1, result.size());
        assertSame(legacyPartition, result.get(0));
    }

    @Test
    public void listPartitionsByFilterDoesNotSwallowTransportFailures() throws Exception {
        when(mockThriftClient.get_partitions_by_filter_req(any(GetPartitionsByFilterRequest.class)))
                .thenThrow(new TTransportException("connection reset"));

        assertThrows(TTransportException.class,
                () -> client.listPartitionsByFilter("db", "tbl", "part = 'a'", (short) 10));
    }
}
