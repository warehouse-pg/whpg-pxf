package org.greenplum.pxf.plugins.hbase;

import org.apache.hadoop.hbase.client.RegionInfo;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HBaseFragmentMetadataTest {

    @Test
    public void testRegionInfoConstructor() {
        final byte[] startKey = new byte[0];
        final byte[] endKey = new byte[0];
        final byte[] fooValue = new byte[0];
        Map<String, byte[]> columnMapping = new HashMap<>();
        columnMapping.put("foo", fooValue);

        RegionInfo regionInfo = mock(RegionInfo.class);
        when(regionInfo.getStartKey()).thenReturn(startKey);
        when(regionInfo.getEndKey()).thenReturn(endKey);

        HBaseFragmentMetadata metadata = new HBaseFragmentMetadata(regionInfo, columnMapping);
        assertNotNull(metadata);
        assertSame(startKey, metadata.getStartKey());
        assertSame(endKey, metadata.getEndKey());
        assertSame(columnMapping, metadata.getColumnMapping());
        assertSame(fooValue, metadata.getColumnMapping().get("foo"));
    }

    @Test
    public void testConstructor() {
        final byte[] startKey = new byte[0];
        final byte[] endKey = new byte[0];
        final byte[] fooValue = new byte[0];
        Map<String, byte[]> columnMapping = new HashMap<>();
        columnMapping.put("foo", fooValue);

        HBaseFragmentMetadata metadata = new HBaseFragmentMetadata(startKey, endKey, columnMapping);
        assertNotNull(metadata);
        assertSame(startKey, metadata.getStartKey());
        assertSame(endKey, metadata.getEndKey());
        assertSame(columnMapping, metadata.getColumnMapping());
        assertSame(fooValue, metadata.getColumnMapping().get("foo"));
    }
}
