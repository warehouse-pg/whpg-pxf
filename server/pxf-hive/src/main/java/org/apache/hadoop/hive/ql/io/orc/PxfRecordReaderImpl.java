package org.apache.hadoop.hive.ql.io.orc;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.ql.exec.vector.ColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.DateColumnVector;
import org.apache.orc.Reader;
import org.apache.orc.TypeDescription;

import java.io.IOException;

/**
 * This class fixes an issue introduced in ORC core library version 1.5.9
 * where the {@link org.apache.orc.TypeDescription#createColumn(TypeDescription.RowBatchVersion, int)}
 * method returns a {@link DateColumnVector} instead of a
 * {@link org.apache.hadoop.hive.ql.exec.vector.LongColumnVector}. The problem
 * with that change is that
 * {@link RecordReaderImpl#copyColumn(ColumnVector, ColumnVector, int, int)}
 * does not handle {@link DateColumnVector} objects. Because
 * {@link DateColumnVector} is a subclass of {@link org.apache.hadoop.hive.ql.exec.vector.LongColumnVector}
 * we can use the {@link #copyLongColumn(ColumnVector, ColumnVector, int, int)}
 * method to maintain the previous behavior of copyColumn.
 */
public class PxfRecordReaderImpl extends RecordReaderImpl
        implements RecordReader {

    /**
     * Constructs a PxfRecordReaderImpl given a file reader, options and a
     * configuration (required by the Hive 4.x RecordReaderImpl constructor)
     */
    public PxfRecordReaderImpl(ReaderImpl fileReader, Reader.Options options, Configuration conf)
            throws IOException {
        super(fileReader, options, forceOriginalRowBatchLayout(conf));
    }

    /**
     * The superclass constructor reads hive.vectorized.input.format.supports.enabled
     * from the given Configuration to decide the batch layout: "decimal_64"
     * (the Hive 4.x default) selects TypeDescription#createRowBatchV2(),
     * which represents DECIMAL columns as Decimal64ColumnVector; anything
     * else selects createRowBatch(), which uses DecimalColumnVector (the
     * ORIGINAL layout, the only one the pre-4.x 2-arg constructor ever
     * produced). copyColumn's dispatch below -- like the superclass's own,
     * which it delegates to -- has no branch for Decimal64ColumnVector, so
     * with the decimal_64 default it silently copies nothing, corrupting
     * every DECIMAL(precision &lt;= 18) column read through this class.
     * The Configuration is only consulted here for this one decision (the
     * superclass constructor does not retain it), so overriding the key on
     * a copy -- rather than the caller's original Configuration -- is
     * side-effect-free and forces the ORIGINAL layout unconditionally.
     */
    private static Configuration forceOriginalRowBatchLayout(Configuration conf) {
        if (conf == null) {
            return null;
        }
        Configuration copy = new Configuration(conf);
        copy.set("hive.vectorized.input.format.supports.enabled", "none");
        return copy;
    }

    /**
     * This method handles {@link DateColumnVector} object copying. For
     * other {@link ColumnVector} types it maintains the original functionality
     *
     * @param destination  the destination column vector
     * @param source       the source column vector
     * @param sourceOffset the offset in the source
     * @param length       the length of the copy
     */
    @Override
    void copyColumn(ColumnVector destination, ColumnVector source, int sourceOffset, int length) {
        if (source.getClass() == DateColumnVector.class) {
            copyLongColumn(destination, source, sourceOffset, length);
        } else {
            super.copyColumn(destination, source, sourceOffset, length);
        }
    }
}
