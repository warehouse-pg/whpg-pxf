package org.greenplum.pxf.plugins.hbase;

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

import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.TableNotFoundException;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.client.RegionLocator;
import org.greenplum.pxf.api.model.BaseFragmenter;
import org.greenplum.pxf.api.model.Fragment;
import org.greenplum.pxf.api.model.FragmentStats;
import org.greenplum.pxf.plugins.hbase.utilities.HBaseLookupTable;
import org.greenplum.pxf.plugins.hbase.utilities.HBaseUtilities;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Fragmenter class for HBase data resources.
 * <p>
 * Extends the {@link BaseFragmenter} abstract class, with the purpose of transforming
 * an input data path (an HBase table name in this case) into a list of regions
 * that belong to this table.
 * <p>
 * This class also puts HBase lookup table information for the given
 * table (if exists) in each fragment's user data field.
 */
public class HBaseDataFragmenter extends BaseFragmenter {

    @Override
    public void afterPropertiesSet() {
        configuration = HBaseConfiguration.create(configuration);
        configuration.set("hbase.client.retries.number", "3");
    }

    /**
     * Returns statistics for HBase table. Currently it's not implemented.
     */
    @Override
    public FragmentStats getFragmentStats() {
        throw new UnsupportedOperationException("ANALYZE for HBase plugin is not supported");
    }

    /**
     * Returns list of fragments containing all of the
     * HBase's table data.
     * Lookup table information with mapping between
     * field names in GPDB table and HBase table will be
     * returned as user data.
     *
     * @return a list of fragments
     */
    @Override
    public List<Fragment> getFragments() throws Exception {
        // Failures must propagate: a swallowed exception here becomes an empty
        // or partial fragment list that FragmenterService caches as success,
        // and GSSFailureHandler only retries on a thrown IOException.
        try (Connection connection = ConnectionFactory.createConnection(configuration)) {
            try (Admin hbaseAdmin = connection.getAdmin()) {
                if (!HBaseUtilities.isTableAvailable(hbaseAdmin, context.getDataSource())) {
                    throw new TableNotFoundException(context.getDataSource());
                }
            }
            Map<String, byte[]> userData = prepareUserData();
            addTableFragments(connection, userData);
            return fragments;
        }
    }

    /**
     * Loads lookup table mappings for the current table.
     * <p>
     * Cleanup errors from {@link HBaseLookupTable#close()} are logged and do
     * not override the result of the mapping load.
     *
     * @return lookup table mappings keyed by field name
     * @throws IOException when the lookup table connection or mapping lookup fails
     */
    private Map<String, byte[]> prepareUserData() throws IOException {
        HBaseLookupTable lookupTable = new HBaseLookupTable(configuration);
        try {
            return lookupTable.getMappings(context.getDataSource());
        } finally {
            // Exception, not IOException: an unchecked throw from a finally
            // would replace the mapping result or the primary exception.
            try {
                lookupTable.close();
            } catch (Exception closeEx) {
                LOG.warn("Failed to close HBase lookup table after loading mappings", closeEx);
            }
        }
    }

    private void addTableFragments(Connection connection, Map<String, byte[]> userData) throws IOException {
        try (RegionLocator regionLocator = connection.getRegionLocator(TableName.valueOf(context.getDataSource()))) {
            List<HRegionLocation> locations = regionLocator.getAllRegionLocations();

            for (HRegionLocation location : locations) {
                addFragment(location, userData);
            }
        }
    }

    private void addFragment(HRegionLocation location, Map<String, byte[]> userData) throws IOException {
        RegionInfo region = location.getRegion();
        HBaseFragmentMetadata metadata = new HBaseFragmentMetadata(region, userData);
        Fragment fragment = new Fragment(context.getDataSource(), metadata);
        fragments.add(fragment);
    }
}
