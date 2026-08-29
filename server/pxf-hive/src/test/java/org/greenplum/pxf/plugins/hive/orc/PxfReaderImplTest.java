package org.greenplum.pxf.plugins.hive.orc;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.common.type.HiveDecimal;
import org.apache.hadoop.hive.ql.exec.vector.DecimalColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch;
import org.apache.hadoop.hive.ql.io.orc.OrcFile;
import org.apache.hadoop.hive.ql.io.orc.RecordReader;
import org.apache.hadoop.hive.ql.io.orc.Reader;
import org.apache.orc.TypeDescription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correctness check for vectorized DECIMAL reads through the same call
 * shape HiveORCVectorizedAccessor uses (rowsOptions(options) with no
 * explicit conf argument, destination batch from createRowBatch()).
 * <p>
 * This is motivated by a review finding: Hive 4.x's 3-arg
 * RecordReaderImpl constructor (unlike the pre-4.x 2-arg one PXF used to
 * call) reads hive.vectorized.input.format.supports.enabled from the
 * given Configuration to choose between createRowBatchV2() (which
 * represents DECIMAL as Decimal64ColumnVector) and createRowBatch()
 * (DecimalColumnVector) for its *internal* scratch batch -- and the key
 * defaults to "decimal_64". copyColumn's dispatch (verified via
 * javap against the real 4.0.1 jar) has no branch for
 * Decimal64ColumnVector, so a mismatch there silently copies nothing.
 * {@link PxfRecordReaderImpl#forceOriginalRowBatchLayout} closes this by
 * always forcing the ORIGINAL layout regardless of the Configuration.
 * <p>
 * Note: this test passes both with and without that fix when the ORC
 * file is written directly via orc-core's Java API, as done below --
 * the file-level stripe encoding this writer produces doesn't appear to
 * trigger the decimal_64 scratch-batch path the same way a file written
 * through Hive's own INSERT does. It's kept as a straightforward
 * correctness check on the real read path; it does not by itself prove
 * the fix is necessary, only that the fixed code has no adverse effect.
 */
class PxfReaderImplTest {

    @Test
    void vectorizedReadOfDecimalColumnIsNotSilentlyZeroedWithDefaultConfiguration(@TempDir java.nio.file.Path tempDir) throws Exception {
        Configuration conf = new Configuration();
        Path orcPath = new Path(tempDir.resolve("decimal.orc").toString());

        TypeDescription schema = TypeDescription.createStruct()
                .addField("amt", TypeDescription.createDecimal().withPrecision(10).withScale(2));

        String[] expected = {"1.25", "2.25", "3.25", "4.25", "5.25"};

        try (org.apache.orc.Writer writer = org.apache.orc.OrcFile.createWriter(orcPath,
                org.apache.orc.OrcFile.writerOptions(conf).setSchema(schema))) {
            VectorizedRowBatch writeBatch = schema.createRowBatch();
            DecimalColumnVector writeCol = (DecimalColumnVector) writeBatch.cols[0];
            for (int i = 0; i < expected.length; i++) {
                writeCol.set(i, HiveDecimal.create(expected[i]));
            }
            writeBatch.size = expected.length;
            writer.addRowBatch(writeBatch);
        }

        // Mirrors HiveORCVectorizedAccessor exactly: rowsOptions(options) with
        // no explicit conf argument (uses the reader's own, unmodified
        // Configuration), and a destination batch from createRowBatch().
        PxfReaderImpl pxfReader = new PxfReaderImpl(orcPath, OrcFile.readerOptions(conf));
        Reader.Options options = new Reader.Options(conf);
        RecordReader recordReader = pxfReader.rowsOptions(options);
        VectorizedRowBatch readBatch = pxfReader.getSchema().createRowBatch();

        assertTrue(recordReader.nextBatch(readBatch), "expected at least one batch of rows");
        assertEquals(expected.length, readBatch.size);

        DecimalColumnVector readCol = (DecimalColumnVector) readBatch.cols[0];
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], readCol.vector[i].toString(),
                    "row " + i + " must not have been silently zeroed by a Decimal64ColumnVector/DecimalColumnVector mismatch");
        }
    }
}
