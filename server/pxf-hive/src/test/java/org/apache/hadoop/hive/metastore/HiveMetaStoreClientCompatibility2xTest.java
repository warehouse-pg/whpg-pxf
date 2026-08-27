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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        // Not assertSame: the fallback now deep-copies its result (via
        // deepCopyPartitions), matching what the modern-path
        // super.listPartitions/listPartitionsByFilter already do -- see
        // listPartitionsUsesModernRpcWhenServerImplementsIt below, which
        // only ever asserts on field values for the same reason.
        assertEquals(legacyPartition, result.get(0));
        assertEquals("legacy", result.get(0).getTableName());
    }

    @Test
    public void listPartitionsFallsBackOnInvalidMethodNameMessage() throws Exception {
        when(mockThriftClient.get_partitions_req(any(PartitionsRequest.class))).thenThrow(invalidMethodName());
        when(mockThriftClient.get_partitions("db", "tbl", (short) 10))
                .thenReturn(Collections.singletonList(legacyPartition));

        List<Partition> result = client.listPartitions("db", "tbl", (short) 10);

        assertEquals(1, result.size());
        // Not assertSame: the fallback now deep-copies its result (via
        // deepCopyPartitions), matching what the modern-path
        // super.listPartitions/listPartitionsByFilter already do -- see
        // listPartitionsUsesModernRpcWhenServerImplementsIt below, which
        // only ever asserts on field values for the same reason.
        assertEquals(legacyPartition, result.get(0));
        assertEquals("legacy", result.get(0).getTableName());
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
        // Not assertSame: the fallback now deep-copies its result (via
        // deepCopyPartitions), matching what the modern-path
        // super.listPartitions/listPartitionsByFilter already do -- see
        // listPartitionsUsesModernRpcWhenServerImplementsIt below, which
        // only ever asserts on field values for the same reason.
        assertEquals(legacyPartition, result.get(0));
        assertEquals("legacy", result.get(0).getTableName());
    }

    @Test
    public void listPartitionsByFilterDoesNotSwallowTransportFailures() throws Exception {
        when(mockThriftClient.get_partitions_by_filter_req(any(GetPartitionsByFilterRequest.class)))
                .thenThrow(new TTransportException("connection reset"));

        assertThrows(TTransportException.class,
                () -> client.listPartitionsByFilter("db", "tbl", "part = 'a'", (short) 10));
    }

    /**
     * Filters out any partition named "legacy" -- used to prove the
     * fallback path actually runs a configured MetaStoreFilterHook
     * (e.g. Ranger-style partition authorization) rather than returning
     * the legacy RPC's raw, unfiltered result.
     */
    public static class DenyLegacyPartitionFilterHook extends DefaultMetaStoreFilterHookImpl {
        public DenyLegacyPartitionFilterHook(Configuration conf) {
            super(conf);
        }

        @Override
        public List<Partition> filterPartitions(List<Partition> partitions) {
            return partitions.stream()
                    .filter(p -> !"legacy".equals(p.getTableName()))
                    .collect(java.util.stream.Collectors.toList());
        }
    }

    @Test
    public void listPartitionsFallbackAppliesTheConfiguredFilterHook() throws Exception {
        Configuration conf = new Configuration(false);
        conf.set("metastore.client.filter.enabled", "true");
        conf.set("metastore.filter.hook", DenyLegacyPartitionFilterHook.class.getName());
        HiveMetaStoreClientCompatibility2x filteredClient = new HiveMetaStoreClientCompatibility2x(conf);

        when(mockThriftClient.get_partitions_req(any(PartitionsRequest.class))).thenThrow(unknownMethod());
        when(mockThriftClient.get_partitions("db", "tbl", (short) 10))
                .thenReturn(Collections.singletonList(legacyPartition));

        List<Partition> result = filteredClient.listPartitions("db", "tbl", (short) 10);

        assertTrue(result.isEmpty(),
                "the configured MetaStoreFilterHook must run on the legacy-RPC fallback result, same as the modern path");
    }

    @Test
    public void getDatabasesUsesTheUnprefixedLegacyRpc() throws Exception {
        // The modern path (super.getDatabases) would send a
        // catalog-prefixed pattern via get_databases_req/get_databases
        // with a "@hive#..." style pattern, which a pre-3.0 metastore
        // pattern-matches literally and always returns empty for. Mock
        // the raw RPC to only answer the exact, un-prefixed pattern.
        when(mockThriftClient.get_databases("db*")).thenReturn(Collections.singletonList("db1"));

        List<String> result = client.getDatabases("db*");

        assertEquals(Collections.singletonList("db1"), result);
    }

    @Test
    public void getTablesUsesTheUnprefixedLegacyRpc() throws Exception {
        // The modern path (super.getTables) sends tblNames=null and the
        // pattern in a Hive-4-only tablesPattern field via
        // get_table_objects_by_name_req, which a pre-3.0 metastore
        // rejects outright (InvalidOperationException on the null
        // tblNames). Mock the raw get_tables RPC instead.
        when(mockThriftClient.get_tables("db", "tbl*")).thenReturn(Collections.singletonList("tbl1"));

        List<String> result = client.getTables("db", "tbl*");

        assertEquals(Collections.singletonList("tbl1"), result);
    }

    /** Filters out any database/table name equal to "deny". */
    public static class DenyNamedFilterHook extends DefaultMetaStoreFilterHookImpl {
        public DenyNamedFilterHook(Configuration conf) {
            super(conf);
        }

        @Override
        public List<String> filterDatabases(List<String> dbList) {
            return dbList.stream().filter(n -> !"deny".equals(n)).collect(java.util.stream.Collectors.toList());
        }

        @Override
        public List<String> filterTableNames(String catName, String dbName, List<String> tableList) {
            return tableList.stream().filter(n -> !"deny".equals(n)).collect(java.util.stream.Collectors.toList());
        }
    }

    @Test
    public void getDatabasesAndGetTablesApplyTheConfiguredFilterHook() throws Exception {
        Configuration conf = new Configuration(false);
        conf.set("metastore.client.filter.enabled", "true");
        conf.set("metastore.filter.hook", DenyNamedFilterHook.class.getName());
        HiveMetaStoreClientCompatibility2x filteredClient = new HiveMetaStoreClientCompatibility2x(conf);

        when(mockThriftClient.get_databases("*")).thenReturn(java.util.Arrays.asList("keep", "deny"));
        when(mockThriftClient.get_tables("db", "*")).thenReturn(java.util.Arrays.asList("keep", "deny"));

        assertEquals(Collections.singletonList("keep"), filteredClient.getDatabases("*"));
        assertEquals(Collections.singletonList("keep"), filteredClient.getTables("db", "*"));
    }
}
