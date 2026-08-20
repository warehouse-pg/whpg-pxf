package org.greenplum.pxf.plugins.s3;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.Protocol;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSSessionCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import org.apache.hadoop.conf.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.apache.hadoop.fs.s3a.Constants.ACCESS_KEY;
import static org.apache.hadoop.fs.s3a.Constants.AWS_REGION;
import static org.apache.hadoop.fs.s3a.Constants.ENDPOINT;
import static org.apache.hadoop.fs.s3a.Constants.ESTABLISH_TIMEOUT;
import static org.apache.hadoop.fs.s3a.Constants.MAXIMUM_CONNECTIONS;
import static org.apache.hadoop.fs.s3a.Constants.MAX_ERROR_RETRIES;
import static org.apache.hadoop.fs.s3a.Constants.PATH_STYLE_ACCESS;
import static org.apache.hadoop.fs.s3a.Constants.PROXY_HOST;
import static org.apache.hadoop.fs.s3a.Constants.PROXY_PORT;
import static org.apache.hadoop.fs.s3a.Constants.PROXY_USERNAME;
import static org.apache.hadoop.fs.s3a.Constants.S3_ENCRYPTION_ALGORITHM;
import static org.apache.hadoop.fs.s3a.Constants.SECRET_KEY;
import static org.apache.hadoop.fs.s3a.Constants.REQUEST_TIMEOUT;
import static org.apache.hadoop.fs.s3a.Constants.SECURE_CONNECTIONS;
import static org.apache.hadoop.fs.s3a.Constants.SOCKET_RECV_BUFFER;
import static org.apache.hadoop.fs.s3a.Constants.SOCKET_SEND_BUFFER;
import static org.apache.hadoop.fs.s3a.Constants.SESSION_TOKEN;
import static org.apache.hadoop.fs.s3a.Constants.SIGNING_ALGORITHM;
import static org.apache.hadoop.fs.s3a.Constants.SOCKET_TIMEOUT;
import static org.apache.hadoop.fs.s3a.Constants.USER_AGENT_PREFIX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class S3SelectClientFactoryTest {

    private Configuration configuration;

    @BeforeEach
    public void setup() {
        configuration = new Configuration(false);
    }

    // ----- credentials -----

    @Test
    public void testNoConfiguredKeysUsesDefaultProviderChain() {
        AWSCredentialsProvider provider = S3SelectClientFactory.createCredentialsProvider(configuration);
        assertInstanceOf(DefaultAWSCredentialsProviderChain.class, provider);
    }

    @Test
    public void testConfiguredKeyPairIsUsed() {
        configuration.set(ACCESS_KEY, "AKIAFOO");
        configuration.set(SECRET_KEY, "sekrit");
        AWSCredentialsProvider provider = S3SelectClientFactory.createCredentialsProvider(configuration);
        assertInstanceOf(AWSStaticCredentialsProvider.class, provider);
        AWSCredentials credentials = provider.getCredentials();
        assertEquals("AKIAFOO", credentials.getAWSAccessKeyId());
        assertEquals("sekrit", credentials.getAWSSecretKey());
        assertFalse(credentials instanceof AWSSessionCredentials);
    }

    @Test
    public void testConfiguredKeysAreTrimmed() {
        // pretty-printed XML values arrive with whitespace
        configuration.set(ACCESS_KEY, "  AKIAFOO\n");
        configuration.set(SECRET_KEY, "\tsekrit ");
        AWSCredentials credentials =
                S3SelectClientFactory.createCredentialsProvider(configuration).getCredentials();
        assertEquals("AKIAFOO", credentials.getAWSAccessKeyId());
        assertEquals("sekrit", credentials.getAWSSecretKey());
    }

    @Test
    public void testSessionTokenProducesSessionCredentials() {
        configuration.set(ACCESS_KEY, "AKIAFOO");
        configuration.set(SECRET_KEY, "sekrit");
        configuration.set(SESSION_TOKEN, "tok123");
        AWSCredentials credentials =
                S3SelectClientFactory.createCredentialsProvider(configuration).getCredentials();
        assertInstanceOf(AWSSessionCredentials.class, credentials);
        assertEquals("tok123", ((AWSSessionCredentials) credentials).getSessionToken());
    }

    @Test
    public void testPartialKeyPairFails() {
        configuration.set(ACCESS_KEY, "AKIAFOO");
        Exception e = assertThrows(IllegalArgumentException.class,
                () -> S3SelectClientFactory.createCredentialsProvider(configuration));
        assertTrue(e.getMessage().contains(ACCESS_KEY));
        assertTrue(e.getMessage().contains(SECRET_KEY));
    }

    // ----- client configuration (proxy / timeouts / pool) -----

    @Test
    public void testClientConfigurationDefaultsMirrorHadoop336() {
        ClientConfiguration awsConf = S3SelectClientFactory.createClientConfiguration(configuration);
        assertEquals(S3SelectClientFactory.DEFAULT_MAXIMUM_CONNECTIONS, awsConf.getMaxConnections());
        assertEquals(Protocol.HTTPS, awsConf.getProtocol());
        assertEquals(S3SelectClientFactory.DEFAULT_MAX_ERROR_RETRIES, awsConf.getMaxErrorRetry());
        assertEquals(S3SelectClientFactory.DEFAULT_ESTABLISH_TIMEOUT_MS, awsConf.getConnectionTimeout());
        assertEquals(S3SelectClientFactory.DEFAULT_SOCKET_TIMEOUT_MS, awsConf.getSocketTimeout());
        assertEquals(0, awsConf.getRequestTimeout());
        // 3.3.6 always applies the "Hadoop <version>" user agent
        assertTrue(awsConf.getUserAgentPrefix().startsWith("Hadoop "));
        assertNull(awsConf.getProxyHost());
    }

    @Test
    public void testConnectionTuningIsApplied() {
        configuration.setInt(MAXIMUM_CONNECTIONS, 7);
        configuration.setBoolean(SECURE_CONNECTIONS, false);
        configuration.setInt(MAX_ERROR_RETRIES, 3);
        configuration.set(ESTABLISH_TIMEOUT, "5000");
        configuration.set(SOCKET_TIMEOUT, "30s");
        configuration.set(REQUEST_TIMEOUT, "10s");
        configuration.setInt(SOCKET_SEND_BUFFER, 4096);
        configuration.setInt(SOCKET_RECV_BUFFER, 16384);
        configuration.set(SIGNING_ALGORITHM, "S3SignerType");
        configuration.set(USER_AGENT_PREFIX, "pxf-test");

        ClientConfiguration awsConf = S3SelectClientFactory.createClientConfiguration(configuration);
        assertEquals(7, awsConf.getMaxConnections());
        assertEquals(Protocol.HTTP, awsConf.getProtocol());
        assertEquals(3, awsConf.getMaxErrorRetry());
        assertEquals(5000, awsConf.getConnectionTimeout());
        assertEquals(30_000, awsConf.getSocketTimeout());
        assertEquals(10_000, awsConf.getRequestTimeout());
        assertEquals(4096, awsConf.getSocketBufferSizeHints()[0]);
        assertEquals(16384, awsConf.getSocketBufferSizeHints()[1]);
        assertEquals("S3SignerType", awsConf.getSignerOverride());
        // reproduces S3AUtils.initUserAgent: "<prefix>, Hadoop <version>"
        assertTrue(awsConf.getUserAgentPrefix().startsWith("pxf-test, Hadoop "));
    }

    @Test
    public void testProxySettingsAreApplied() {
        configuration.set(PROXY_HOST, "proxy.example.com");
        configuration.setInt(PROXY_PORT, 3128);
        configuration.set(PROXY_USERNAME, "puser");
        configuration.set("fs.s3a.proxy.password", "ppass");

        ClientConfiguration awsConf = S3SelectClientFactory.createClientConfiguration(configuration);
        assertEquals("proxy.example.com", awsConf.getProxyHost());
        assertEquals(3128, awsConf.getProxyPort());
        assertEquals("puser", awsConf.getProxyUsername());
        assertEquals("ppass", awsConf.getProxyPassword());
    }

    @Test
    public void testProxyPortInferredFromScheme() {
        configuration.set(PROXY_HOST, "proxy.example.com");
        assertEquals(443, S3SelectClientFactory.createClientConfiguration(configuration).getProxyPort());
        configuration.setBoolean(SECURE_CONNECTIONS, false);
        assertEquals(80, S3SelectClientFactory.createClientConfiguration(configuration).getProxyPort());
    }

    @Test
    public void testProxyPortWithoutHostFails() {
        configuration.setInt(PROXY_PORT, 3128);
        assertThrows(IllegalArgumentException.class,
                () -> S3SelectClientFactory.createClientConfiguration(configuration));
    }

    @Test
    public void testProxyUsernameWithoutPasswordFails() {
        configuration.set(PROXY_HOST, "proxy.example.com");
        configuration.set(PROXY_USERNAME, "puser");
        assertThrows(IllegalArgumentException.class,
                () -> S3SelectClientFactory.createClientConfiguration(configuration));
    }

    // ----- endpoint / region -----

    @Test
    public void testEndpointWithExplicitRegion() {
        configuration.set(ENDPOINT, "https://s3.example.internal");
        configuration.set(AWS_REGION, "eu-central-1");
        AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard();
        S3SelectClientFactory.applyEndpointAndRegion(builder, configuration);
        assertEquals("https://s3.example.internal", builder.getEndpoint().getServiceEndpoint());
        assertEquals("eu-central-1", builder.getEndpoint().getSigningRegion());
    }

    @Test
    public void testAwsEndpointDerivesRegionFromHostName() {
        configuration.set(ENDPOINT, "s3.eu-west-1.amazonaws.com");
        AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard();
        S3SelectClientFactory.applyEndpointAndRegion(builder, configuration);
        assertEquals("eu-west-1", builder.getEndpoint().getSigningRegion());
    }

    @Test
    public void testSchemelessEndpointWithPortFallsBackToCentralRegion() {
        // regression: the :port suffix must not leak into region parsing
        configuration.set(ENDPOINT, "localhost:9000");
        AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard();
        S3SelectClientFactory.applyEndpointAndRegion(builder, configuration);
        assertEquals("localhost:9000", builder.getEndpoint().getServiceEndpoint());
        assertEquals("us-east-1", builder.getEndpoint().getSigningRegion());
    }

    @Test
    public void testUnparseableEndpointHostFailsWithConfigKeyInMessage() {
        // RFC-invalid host names (docker-compose style) must point at the key
        Exception e = assertThrows(IllegalArgumentException.class,
                () -> S3SelectClientFactory.deriveRegionFromEndpoint("pxf_minio_1:9000"));
        assertTrue(e.getMessage().contains(ENDPOINT));
        assertTrue(e.getMessage().contains("pxf_minio_1:9000"));
    }

    @Test
    public void testNoEndpointEnablesGlobalBucketAccessAndCentralRegion() {
        AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard();
        S3SelectClientFactory.applyEndpointAndRegion(builder, configuration);
        assertTrue(builder.isForceGlobalBucketAccessEnabled());
        // region key absent: us-east-1, hadoop-aws 3.3.6's default
        assertEquals("us-east-1", builder.getRegion());
    }

    @Test
    public void testNoEndpointWithRegionSetsRegion() {
        configuration.set(AWS_REGION, "ap-south-1");
        AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard();
        S3SelectClientFactory.applyEndpointAndRegion(builder, configuration);
        assertTrue(builder.isForceGlobalBucketAccessEnabled());
        assertEquals("ap-south-1", builder.getRegion());
    }

    @Test
    public void testExplicitlyEmptyRegionDefersToSdkChain() {
        configuration.set(AWS_REGION, "");
        AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard();
        S3SelectClientFactory.applyEndpointAndRegion(builder, configuration);
        assertNull(builder.getRegion());
    }

    // ----- bucket addressing style -----

    @Test
    public void testPathStyleAccessIsAppliedWhenConfigured() {
        configuration.set(ENDPOINT, "http://localhost:9100");
        configuration.setBoolean(PATH_STYLE_ACCESS, true);
        AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard();
        S3SelectClientFactory.applyEndpointAndRegion(builder, configuration);
        assertTrue(builder.isPathStyleAccessEnabled());
    }

    @Test
    public void testVirtualHostAddressingIsTheDefault() {
        AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard();
        S3SelectClientFactory.applyEndpointAndRegion(builder, configuration);
        assertFalse(builder.isPathStyleAccessEnabled() != null && builder.isPathStyleAccessEnabled());
    }

    // ----- client-side encryption guard -----

    @Test
    public void testClientSideEncryptionIsRejected() {
        configuration.set(S3_ENCRYPTION_ALGORITHM, "CSE-KMS");
        Exception e = assertThrows(UnsupportedOperationException.class,
                () -> S3SelectClientFactory.rejectClientSideEncryption(configuration));
        assertTrue(e.getMessage().contains("S3 Select"));
        assertTrue(e.getMessage().contains("CSE-KMS"));
    }

    @Test
    public void testClientSideEncryptionViaLegacyKeyIsRejected() {
        configuration.set("fs.s3a.server-side-encryption-algorithm", "CSE-CUSTOM");
        assertThrows(UnsupportedOperationException.class,
                () -> S3SelectClientFactory.rejectClientSideEncryption(configuration));
    }

    @Test
    public void testServerSideEncryptionIsAllowed() {
        configuration.set(S3_ENCRYPTION_ALGORITHM, "SSE-KMS");
        S3SelectClientFactory.rejectClientSideEncryption(configuration);
    }
}
