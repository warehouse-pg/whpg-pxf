package org.greenplum.pxf.automation.testplugin;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileSplit;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.greenplum.pxf.api.model.BaseFragmenter;
import org.greenplum.pxf.api.model.Fragment;
import org.greenplum.pxf.api.model.Metadata;
import org.greenplum.pxf.plugins.hive.HiveClientWrapper;
import org.greenplum.pxf.plugins.hive.HiveFragmentMetadata;
import org.greenplum.pxf.plugins.hive.utilities.HiveUtilities;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Fragmenter which splits one file into multiple fragments. Helps to simulate a
 * case of big files.
 * <p>
 * inputData has to have following parameters:
 * TEST-FRAGMENTS-NUM - defines how many fragments will be returned for current file
 */
public class MultipleHiveFragmentsPerFileFragmenter extends BaseFragmenter {
    private static final Log LOG = LogFactory.getLog(MultipleHiveFragmentsPerFileFragmenter.class);

    private static final long SPLIT_SIZE = 1024;
    private JobConf jobConf;
    private HiveClientWrapper hiveClientWrapper;
    private HiveUtilities hiveUtilities;

    /**
     * Sets the {@link HiveClientWrapper} object
     *
     * @param hiveClientWrapper the hive client wrapper object
     */
    @Autowired
    public void setHiveClientWrapper(HiveClientWrapper hiveClientWrapper) {
        this.hiveClientWrapper = hiveClientWrapper;
    }

    /**
     * Sets the {@link HiveUtilities} object
     *
     * @param hiveUtilities the hive utilities object
     */
    @Autowired
    public void setHiveUtilities(HiveUtilities hiveUtilities) {
        this.hiveUtilities = hiveUtilities;
    }

    @Override
    public void afterPropertiesSet() {
        super.afterPropertiesSet();
        jobConf = new JobConf(configuration, MultipleHiveFragmentsPerFileFragmenter.class);
    }

    @Override
    public List<Fragment> getFragments() throws Exception {
        // TODO allowlist property
        int fragmentsNum = Integer.parseInt(context.getOption("TEST-FRAGMENTS-NUM"));
        Metadata.Item tblDesc = hiveClientWrapper.extractTableFromName(context.getDataSource());
        Table tbl;
        try (HiveClientWrapper.MetaStoreClientHolder holder = hiveClientWrapper.initHiveClient(context, configuration)) {
            tbl = hiveClientWrapper.getHiveTable(holder.getClient(), tblDesc);
        }
        Properties properties = getSchema(tbl);

        for (int i = 0; i < fragmentsNum; i++) {
            String filePath = getFilePath(tbl);
            fragments.add(new Fragment(filePath, new HiveFragmentMetadata(i * SPLIT_SIZE, SPLIT_SIZE, properties)));
        }

        return fragments;
    }


    // MetaStoreUtils.getSchema moved from org.apache.hadoop.hive.metastore to
    // org.apache.hadoop.hive.metastore.utils in Hive's standalone-metastore
    // split (Hive 3.0+) and no longer exists at the old location on Hive
    // 4.x. This class is compiled against automation's own Hive dependency
    // (a separate, older pin from the PXF server's), but it is deployed as
    // a plugin class loaded by the live PXF server -- so it needs to
    // resolve MetaStoreUtils against whichever metastore artifact is on
    // *that* classpath, not automation's compile-time one. Resolving the
    // method via reflection at each of the two possible locations (rather
    // than adding a second, conflicting Hive-version dependency to
    // automation's own build) makes the same compiled class work either
    // way.
    private static final String[] META_STORE_UTILS_CLASS_NAMES = {
            "org.apache.hadoop.hive.metastore.utils.MetaStoreUtils", // Hive 3.0+
            "org.apache.hadoop.hive.metastore.MetaStoreUtils",       // pre-3.0
    };

    private static Properties getSchema(Table table) throws ReflectiveOperationException {
        Method getSchema = null;
        ReflectiveOperationException lastFailure = null;
        for (String className : META_STORE_UTILS_CLASS_NAMES) {
            try {
                Class<?> metaStoreUtils = Class.forName(className);
                getSchema = metaStoreUtils.getMethod("getSchema",
                        StorageDescriptor.class, StorageDescriptor.class,
                        Map.class, String.class, String.class, List.class);
                break;
            } catch (ReflectiveOperationException e) {
                lastFailure = e;
            }
        }
        if (getSchema == null) {
            throw lastFailure;
        }
        return (Properties) getSchema.invoke(null, table.getSd(), table.getSd(),
                table.getParameters(), table.getDbName(), table.getTableName(),
                table.getPartitionKeys());
    }

    private String getFilePath(Table tbl) throws Exception {

        StorageDescriptor descTable = tbl.getSd();

        InputFormat<?, ?> fformat = hiveUtilities.makeInputFormat(descTable.getInputFormat(), jobConf);

        FileInputFormat.setInputPaths(jobConf, new Path(descTable.getLocation()));

        InputSplit[] splits;
        try {
            splits = fformat.getSplits(jobConf, 1);
        } catch (org.apache.hadoop.mapred.InvalidInputException e) {
            LOG.debug("getSplits failed on " + e.getMessage());
            throw new RuntimeException("Unable to get file path for table.");
        }

        for (InputSplit split : splits) {
            FileSplit fsp = (FileSplit) split;
            return fsp.getPath().toString();
        }
        throw new RuntimeException("Unable to get file path for table.");
    }
}
