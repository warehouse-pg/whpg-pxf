package org.greenplum.pxf.automation.components.gpdb;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

/**
 * Pins {@link Gpdb#parseServerMajorVersion(String)} to the three banners the harness runs against.
 * Pure-logic test, no cluster needed: {@code mvn -Dtest=GpdbVersionParseTest test} from automation/.
 */
public class GpdbVersionParseTest {

	@Test
	public void greenplum6Banner() {
		assertEquals(Gpdb.parseServerMajorVersion(
				"PostgreSQL 9.4.26 (Greenplum Database 6.27.5+WHPG build commit:0123abcd) on x86_64-pc-linux-gnu, compiled by gcc (GCC) 8.5.0, 64-bit compiled on Oct  1 2026 12:00:00"), 6);
	}

	@Test
	public void greenplum7Banner() {
		assertEquals(Gpdb.parseServerMajorVersion(
				"PostgreSQL 12.22 (Greenplum Database 7.5.0+WHPG build commit:0123abcd) on x86_64-pc-linux-gnu, compiled by gcc (GCC) 11.4.1, 64-bit compiled on Oct  1 2026 12:00:00"), 7);
	}

	@Test
	public void warehousePg19Banner() {
		assertEquals(Gpdb.parseServerMajorVersion(
				"PostgreSQL 17.0 (WarehousePG 19.0.0 build dev) on x86_64-pc-linux-gnu, compiled by gcc (GCC) 11.4.1, 64-bit compiled on Oct  1 2026 12:00:00"), 19);
	}

	@Test(expectedExceptions = IllegalStateException.class, expectedExceptionsMessageRegExp = ".*PostgreSQL 17\\.0 on x86_64.*")
	public void unknownBannerNamesTheString() {
		Gpdb.parseServerMajorVersion("PostgreSQL 17.0 on x86_64-pc-linux-gnu, compiled by gcc (GCC) 11.4.1, 64-bit");
	}
}
