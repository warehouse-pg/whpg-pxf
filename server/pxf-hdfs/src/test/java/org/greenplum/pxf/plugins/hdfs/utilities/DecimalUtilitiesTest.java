package org.greenplum.pxf.plugins.hdfs.utilities;

import org.apache.hadoop.hive.common.type.HiveDecimal;
import org.apache.hadoop.hive.serde2.io.HiveDecimalWritable;
import org.greenplum.pxf.api.error.UnsupportedTypeException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

public class DecimalUtilitiesTest {
    private static final int precision = 38;
    private static final int scale = 18;
    private static final String columnName = "dec";
    private static final String decimalStringOverflowsPrecision = "1234567890123456789012345678901234567890.12345";
    private static final String decimalStringOverflowsScale = "123456789012345.12345678901234567890";
    private static final String decimalStringOverflowsPrecisionMinusScale = "123456789012345678901234567890.12345678";

    private DecimalUtilities decimalUtilities;

    @Test
    public void testParseDecimalNoOverflow() {
        String decimalString = "123.12345";
        HiveDecimal expectedHiveDecimal = HiveDecimalWritable.enforcePrecisionScale(new HiveDecimalWritable(decimalString), precision, scale).getHiveDecimal();

        // results should be the same no matter enforcePrecisionAndScaleOnIgnore is true or false
        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.ROUND, true);
        HiveDecimal hiveDecimal = decimalUtilities.parseDecimalStringWithHiveDecimal(decimalString, precision, scale, columnName);
        assertEquals(expectedHiveDecimal, hiveDecimal);

        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.ROUND, false);
        hiveDecimal = decimalUtilities.parseDecimalStringWithHiveDecimal(decimalString, precision, scale, columnName);
        assertEquals(expectedHiveDecimal, hiveDecimal);
    }

    @Test
    public void testParseDecimalIntegerDigitCountOverflowsPrecisionOptionError() {
        // results should be the same no matter enforcePrecisionAndScaleOnIgnore is true or false
        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.ERROR, true);
        Exception e = assertThrows(UnsupportedTypeException.class,
                () -> decimalUtilities.parseDecimalStringWithHiveDecimal(decimalStringOverflowsPrecision, precision, scale, columnName));
        assertEquals(String.format("The value %s for the NUMERIC column %s exceeds the maximum supported precision %d.",
                decimalStringOverflowsPrecision, columnName, precision), e.getMessage());

        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.ERROR, false);
        e = assertThrows(UnsupportedTypeException.class,
                () -> decimalUtilities.parseDecimalStringWithHiveDecimal(decimalStringOverflowsPrecision, precision, scale, columnName));
        assertEquals(String.format("The value %s for the NUMERIC column %s exceeds the maximum supported precision %d.",
                decimalStringOverflowsPrecision, columnName, precision), e.getMessage());
    }

    @Test
    public void testParseDecimalIntegerDigitCountOverflowsPrecisionOptionRound() {
        // results should be the same no matter enforcePrecisionAndScaleOnIgnore is true or false
        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.ROUND, true);
        Exception e = assertThrows(UnsupportedTypeException.class,
                () -> decimalUtilities.parseDecimalStringWithHiveDecimal(decimalStringOverflowsPrecision, precision, scale, columnName));
        assertEquals(String.format("The value %s for the NUMERIC column %s exceeds the maximum supported precision %d.",
                decimalStringOverflowsPrecision, columnName, precision), e.getMessage());

        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.ROUND, false);
        e = assertThrows(UnsupportedTypeException.class,
                () -> decimalUtilities.parseDecimalStringWithHiveDecimal(decimalStringOverflowsPrecision, precision, scale, columnName));
        assertEquals(String.format("The value %s for the NUMERIC column %s exceeds the maximum supported precision %d.",
                decimalStringOverflowsPrecision, columnName, precision), e.getMessage());
    }

    @Test
    public void testParseDecimalIntegerDigitCountOverflowsPrecisionOptionIgnore() {
        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.IGNORE, true);

        HiveDecimal hiveDecimal = decimalUtilities.parseDecimalStringWithHiveDecimal(decimalStringOverflowsPrecision, precision, scale, columnName);
        assertNull(hiveDecimal);
    }

    @Test
    public void testParseDecimalIntegerDigitCountOverflowsPrecisionOptionIgnoreWithoutEnforcing() {
        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.IGNORE, false);

        HiveDecimal hiveDecimal = decimalUtilities.parseDecimalStringWithHiveDecimal(decimalStringOverflowsPrecision, precision, scale, columnName);
        assertNull(hiveDecimal);
    }

    @Test
    public void testParseDecimalIntegerDigitCountOverflowsPrecisionMinusScaleOptionError() {
        // results should be the same no matter enforcePrecisionAndScaleOnIgnore is true or false
        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.ERROR, true);
        Exception e = assertThrows(UnsupportedTypeException.class,
                () -> decimalUtilities.parseDecimalStringWithHiveDecimal(decimalStringOverflowsPrecisionMinusScale, precision, scale, columnName));
        assertEquals(String.format("The value %s for the NUMERIC column %s exceeds the maximum supported precision and scale (%d,%d).",
                decimalStringOverflowsPrecisionMinusScale, columnName, precision, scale), e.getMessage());

        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.ERROR, false);
        e = assertThrows(UnsupportedTypeException.class,
                () -> decimalUtilities.parseDecimalStringWithHiveDecimal(decimalStringOverflowsPrecisionMinusScale, precision, scale, columnName));
        assertEquals(String.format("The value %s for the NUMERIC column %s exceeds the maximum supported precision and scale (%d,%d).",
                decimalStringOverflowsPrecisionMinusScale, columnName, precision, scale), e.getMessage());
    }

    @Test
    public void testParseDecimalIntegerDigitCountOverflowsPrecisionMinusScaleOptionRound() {
        // results should be the same no matter enforcePrecisionAndScaleOnIgnore is true or false
        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.ROUND, true);
        Exception e = assertThrows(UnsupportedTypeException.class,
                () -> decimalUtilities.parseDecimalStringWithHiveDecimal(decimalStringOverflowsPrecisionMinusScale, precision, scale, columnName));
        assertEquals(String.format("The value %s for the NUMERIC column %s exceeds the maximum supported precision and scale (%d,%d).",
                decimalStringOverflowsPrecisionMinusScale, columnName, precision, scale), e.getMessage());

        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.ROUND, false);
        e = assertThrows(UnsupportedTypeException.class,
                () -> decimalUtilities.parseDecimalStringWithHiveDecimal(decimalStringOverflowsPrecisionMinusScale, precision, scale, columnName));
        assertEquals(String.format("The value %s for the NUMERIC column %s exceeds the maximum supported precision and scale (%d,%d).",
                decimalStringOverflowsPrecisionMinusScale, columnName, precision, scale), e.getMessage());
    }

    @Test
    public void testParseDecimalIntegerDigitCountOverflowsPrecisionMinusScaleOptionIgnore() {
        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.IGNORE, true);

        HiveDecimal hiveDecimal = decimalUtilities.parseDecimalStringWithHiveDecimal(decimalStringOverflowsPrecisionMinusScale, precision, scale, columnName);
        assertNull(hiveDecimal);
    }

    @Test
    public void testParseDecimalIntegerDigitCountOverflowsPrecisionMinusScaleOptionIgnoreWithoutEnforcing() {
        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.IGNORE, false);

        HiveDecimal hiveDecimal = decimalUtilities.parseDecimalStringWithHiveDecimal(decimalStringOverflowsPrecisionMinusScale, precision, scale, columnName);
        HiveDecimal expectedHiveDecimal = (new HiveDecimalWritable(decimalStringOverflowsPrecisionMinusScale)).getHiveDecimal();
        assertEquals(expectedHiveDecimal, hiveDecimal);
    }

    @Test
    public void testParseDecimalOverflowsScaleOptionError() {
        // results should be the same no matter enforcePrecisionAndScaleOnIgnore is true or false
        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.ERROR, true);
        Exception e = assertThrows(UnsupportedTypeException.class,
                () -> decimalUtilities.parseDecimalStringWithHiveDecimal(decimalStringOverflowsScale, precision, scale, columnName));
        assertEquals(String.format("The value %s for the NUMERIC column %s exceeds the maximum supported scale %s, and cannot be stored without precision loss.",
                decimalStringOverflowsScale, columnName, scale), e.getMessage());

        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.ERROR, false);
        e = assertThrows(UnsupportedTypeException.class,
                () -> decimalUtilities.parseDecimalStringWithHiveDecimal(decimalStringOverflowsScale, precision, scale, columnName));
        assertEquals(String.format("The value %s for the NUMERIC column %s exceeds the maximum supported scale %s, and cannot be stored without precision loss.",
                decimalStringOverflowsScale, columnName, scale), e.getMessage());
    }

    @Test
    public void testParseDecimalOverflowsScaleOptionRound() {
        HiveDecimal expectedHiveDecimal = HiveDecimalWritable.enforcePrecisionScale(new HiveDecimalWritable(decimalStringOverflowsScale), precision, scale).getHiveDecimal();

        // results should be the same no matter enforcePrecisionAndScaleOnIgnore is true or false
        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.ROUND, true);
        HiveDecimal hiveDecimal = decimalUtilities.parseDecimalStringWithHiveDecimal(decimalStringOverflowsScale, precision, scale, columnName);
        assertEquals(expectedHiveDecimal, hiveDecimal);

        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.ROUND, false);
        hiveDecimal = decimalUtilities.parseDecimalStringWithHiveDecimal(decimalStringOverflowsScale, precision, scale, columnName);
        assertEquals(expectedHiveDecimal, hiveDecimal);
    }

    @Test
    public void testParseDecimalOverflowsScaleOptionIgnore() {
        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.IGNORE, true);

        HiveDecimal hiveDecimal = decimalUtilities.parseDecimalStringWithHiveDecimal(decimalStringOverflowsScale, precision, scale, columnName);
        HiveDecimal expectedHiveDecimal = HiveDecimalWritable.enforcePrecisionScale(new HiveDecimalWritable(decimalStringOverflowsScale), precision, scale).getHiveDecimal();
        assertEquals(expectedHiveDecimal, hiveDecimal);
    }

    @Test
    public void testParseDecimalOverflowsScaleOptionIgnoreWithoutEnforcing() {
        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.IGNORE, false);

        HiveDecimal hiveDecimal = decimalUtilities.parseDecimalStringWithHiveDecimal(decimalStringOverflowsScale, precision, scale, columnName);
        HiveDecimal expectedHiveDecimal = (new HiveDecimalWritable(decimalStringOverflowsScale)).getHiveDecimal();
        assertEquals(expectedHiveDecimal, hiveDecimal);
    }

    @Test
    public void testParseDecimalOverflowsScaleOptionIgnoreWithoutEnforcing_decimalPartFailedToBorrowDigits() {
        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.IGNORE, false);
        // integer digit count == precision - scale && decimal count > scale
        String decimalString = "12345678901234567890.12345678901234567890";

        HiveDecimal hiveDecimal = decimalUtilities.parseDecimalStringWithHiveDecimal(decimalString, precision, scale, columnName);
        // Although we don't enforce precision and scale when parsing the decimal, decimal part failed to borrow digits from
        // integer part when decimal part overflows. Then the behavior would be the same as parsing the value with precision and scale enforced.
        HiveDecimal expectedHiveDecimal = HiveDecimalWritable.enforcePrecisionScale(new HiveDecimalWritable(decimalString), precision, scale).getHiveDecimal();
        assertEquals(expectedHiveDecimal, hiveDecimal);
    }

    @Test
    public void testParseDecimalWithBigDecimalNoOverflow() {
        String decimalString = "-123456789012345.1234567890123456789012345678901234567890123456789012345678901";
        // results should be the same for all the overflow options
        for (DecimalOverflowOption option : DecimalOverflowOption.values()) {
            decimalUtilities = new DecimalUtilities(option, true);
            BigDecimal bigDecimal = decimalUtilities.parseDecimalStringWithBigDecimal(decimalString, 76, 61, columnName);
            assertEquals(new BigDecimal(decimalString), bigDecimal);
        }

        // the value is padded to the designated scale
        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.ERROR, true);
        assertEquals(new BigDecimal("1.0000000000"), decimalUtilities.parseDecimalStringWithBigDecimal("1", 50, 10, columnName));
    }

    @Test
    public void testParseDecimalWithBigDecimalIntegerDigitCountOverflowsPrecision() {
        String decimalString = "123456789012345678901234567890123456789012345678901.1";

        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.ERROR, true);
        Exception e = assertThrows(UnsupportedTypeException.class,
                () -> decimalUtilities.parseDecimalStringWithBigDecimal(decimalString, 50, 10, columnName));
        assertEquals(String.format("The value %s for the NUMERIC column %s exceeds the maximum supported precision %d.",
                decimalString, columnName, 50), e.getMessage());

        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.ROUND, true);
        e = assertThrows(UnsupportedTypeException.class,
                () -> decimalUtilities.parseDecimalStringWithBigDecimal(decimalString, 50, 10, columnName));
        assertEquals(String.format("The value %s for the NUMERIC column %s exceeds the maximum supported precision %d.",
                decimalString, columnName, 50), e.getMessage());

        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.IGNORE, true);
        assertNull(decimalUtilities.parseDecimalStringWithBigDecimal(decimalString, 50, 10, columnName));
    }

    @Test
    public void testParseDecimalWithBigDecimalIntegerDigitCountOverflowsPrecisionMinusScale() {
        String decimalString = "1234567890123456789012345678901234567890.1";

        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.ERROR, true);
        Exception e = assertThrows(UnsupportedTypeException.class,
                () -> decimalUtilities.parseDecimalStringWithBigDecimal(decimalString, 50, 20, columnName));
        assertEquals(String.format("The value %s for the NUMERIC column %s exceeds the maximum supported precision and scale (%d,%d).",
                decimalString, columnName, 50, 20), e.getMessage());

        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.ROUND, true);
        e = assertThrows(UnsupportedTypeException.class,
                () -> decimalUtilities.parseDecimalStringWithBigDecimal(decimalString, 50, 20, columnName));
        assertEquals(String.format("The value %s for the NUMERIC column %s exceeds the maximum supported precision and scale (%d,%d).",
                decimalString, columnName, 50, 20), e.getMessage());

        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.IGNORE, true);
        assertNull(decimalUtilities.parseDecimalStringWithBigDecimal(decimalString, 50, 20, columnName));
    }

    @Test
    public void testParseDecimalWithBigDecimalRoundingCarriesIntoPrecisionMinusScale() {
        // 30 integer digits fit in (50,20), but rounding to scale 20 carries into a 31st integer digit
        String decimalString = "999999999999999999999999999999.999999999999999999999";

        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.ROUND, true);
        Exception e = assertThrows(UnsupportedTypeException.class,
                () -> decimalUtilities.parseDecimalStringWithBigDecimal(decimalString, 50, 20, columnName));
        assertEquals(String.format("The value %s for the NUMERIC column %s exceeds the maximum supported precision and scale (%d,%d).",
                decimalString, columnName, 50, 20), e.getMessage());

        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.IGNORE, true);
        assertNull(decimalUtilities.parseDecimalStringWithBigDecimal(decimalString, 50, 20, columnName));
    }

    @Test
    public void testParseDecimalWithBigDecimalOverflowsScale() {
        String decimalString = "0.12345678901234567890123456789012345678901234567890123456789012";

        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.ERROR, true);
        Exception e = assertThrows(UnsupportedTypeException.class,
                () -> decimalUtilities.parseDecimalStringWithBigDecimal(decimalString, 76, 61, columnName));
        assertEquals(String.format("The value %s for the NUMERIC column %s exceeds the maximum supported scale %d, and cannot be stored without precision loss.",
                decimalString, columnName, 61), e.getMessage());

        // round and ignore both round half up to the designated scale
        BigDecimal expected = new BigDecimal("0.1234567890123456789012345678901234567890123456789012345678901");
        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.ROUND, true);
        assertEquals(expected, decimalUtilities.parseDecimalStringWithBigDecimal(decimalString, 76, 61, columnName));
        decimalUtilities = new DecimalUtilities(DecimalOverflowOption.IGNORE, true);
        assertEquals(expected, decimalUtilities.parseDecimalStringWithBigDecimal(decimalString, 76, 61, columnName));
    }
}
