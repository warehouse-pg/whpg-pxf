package org.greenplum.pxf.service.resolved;

import io.netty.util.Version;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Security tripwire: the netty version is overridden above the Spring
 * Boot BOM (which would silently downgrade it to an advisory-carrying
 * release) and above the AWS SDK's own pin (which no longer clears the
 * published advisory set). If this assertion fires, a BOM change or a
 * conflicting pin has moved the RESOLVED netty — re-run the advisory
 * check before accepting the new version and update the override plus
 * this test together.
 */
public class NettyResolvedVersionTest {

    private static final String EXPECTED = "4.1.137.Final";
    private static final Set<String> SHADED_COPIES = Collections.unmodifiableSet(
            new java.util.HashSet<>(Arrays.asList("netty-all")));

    @Test
    public void resolvedNettyMatchesTheSecurityOverride() {
        Map<String, Version> versions = Version.identify();
        assertFalse(versions.isEmpty(), "no netty artifacts on the classpath?");
        versions.forEach((artifact, version) -> {
            // hbase-shaded-netty (hbase-thirdparty) bundles a RELOCATED
            // netty copy whose version metadata stays at the original
            // path and reports the shaded version ("netty-all"), not the
            // resolved io.netty dependency. It is a separate bundled
            // copy, pinned by the hbase-thirdparty line — outside this
            // override's control and tracked separately.
            //
            // io.netty.util.Version only exposes an artifactId, not a
            // Maven group id, so there is no way to distinguish "a real
            // io.netty artifact" from "a differently-shaded bundled copy"
            // other than by name. If a future dependency bundles another
            // shaded netty copy under a new artifactId, add it here too.
            if (SHADED_COPIES.contains(artifact)) {
                return;
            }
            assertEquals(EXPECTED, version.artifactVersion(),
                    "netty artifact '" + artifact + "' resolved off the security override");
        });
    }
}
