package org.greenplum.pxf.plugins.s3;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Linkage smoke for the client-side-encryption (CSE) support on the plain
 * s3a path. hadoop-aws 3.4's {@code EncryptionS3ClientFactory}
 * hard-references the amazon-s3-encryption-client-java artifact, which is
 * provided-scope in hadoop-aws's pom and only reaches deployments because
 * this build bundles it explicitly. No functional CSE test exists (it
 * needs a KMS endpoint), so this test pins the classpath argument itself:
 * the artifact resolves at the declared coordinate, and the classes link
 * at the signature level against the resolved classpath.
 *
 * <p>Scope honesty: class initialization plus reflective member
 * enumeration forces resolution of superclasses, interfaces, and every
 * type appearing in constructor/method signatures — which is where a
 * wrong coordinate, wrong scope, or missing signature-level transitive
 * shows up. References that only occur inside method bodies resolve
 * lazily at first execution and are NOT covered here; that residual is
 * the KMS-lane work tracked separately.
 */
public class S3EncryptionClientLinkageTest {

    private static final String ENCRYPTION_CLIENT = "software.amazon.encryption.s3.S3EncryptionClient";
    private static final String HADOOP_ENCRYPTION_FACTORY = "org.apache.hadoop.fs.s3a.impl.EncryptionS3ClientFactory";

    @Test
    public void testEncryptionClientLinksOnTheResolvedClasspath() throws Exception {
        Class<?> clazz = Class.forName(ENCRYPTION_CLIENT, true, getClass().getClassLoader());
        assertTrue(forceSignatureResolution(clazz) > 0);
    }

    @Test
    public void testHadoopEncryptionFactoryLinksAgainstTheBundledClient() throws Exception {
        Class<?> clazz = Class.forName(HADOOP_ENCRYPTION_FACTORY, true, getClass().getClassLoader());
        assertTrue(forceSignatureResolution(clazz) >= 0);
    }

    /**
     * Forces resolution of all types reachable through the class's member
     * signatures; throws NoClassDefFoundError if any is missing.
     */
    private int forceSignatureResolution(Class<?> clazz) {
        int members = 0;
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            assertNotNull(c.getParameterTypes());
            members++;
        }
        for (Method m : clazz.getDeclaredMethods()) {
            assertNotNull(m.getParameterTypes());
            assertNotNull(m.getReturnType());
            members++;
        }
        assertNotNull(clazz.getSuperclass() == null ? Object.class : clazz.getSuperclass());
        assertNotNull(clazz.getInterfaces());
        return members;
    }
}
