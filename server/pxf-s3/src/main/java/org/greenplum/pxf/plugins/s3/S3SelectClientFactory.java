package org.greenplum.pxf.plugins.s3;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.Protocol;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.util.AwsHostNameUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

import static org.apache.hadoop.fs.s3a.Constants.ACCESS_KEY;
import static org.apache.hadoop.fs.s3a.Constants.AWS_CREDENTIALS_PROVIDER;
import static org.apache.hadoop.fs.s3a.Constants.AWS_REGION;
import static org.apache.hadoop.fs.s3a.Constants.AWS_S3_CENTRAL_REGION;
import static org.apache.hadoop.fs.s3a.Constants.ENDPOINT;
import static org.apache.hadoop.fs.s3a.Constants.ESTABLISH_TIMEOUT;
import static org.apache.hadoop.fs.s3a.Constants.MAXIMUM_CONNECTIONS;
import static org.apache.hadoop.fs.s3a.Constants.MAX_ERROR_RETRIES;
import static org.apache.hadoop.fs.s3a.Constants.PATH_STYLE_ACCESS;
import static org.apache.hadoop.fs.s3a.Constants.PROXY_DOMAIN;
import static org.apache.hadoop.fs.s3a.Constants.PROXY_HOST;
import static org.apache.hadoop.fs.s3a.Constants.PROXY_PASSWORD;
import static org.apache.hadoop.fs.s3a.Constants.PROXY_PORT;
import static org.apache.hadoop.fs.s3a.Constants.PROXY_USERNAME;
import static org.apache.hadoop.fs.s3a.Constants.PROXY_WORKSTATION;
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

/**
 * Builds the AWS SDK v1 {@link AmazonS3} client used by the S3 Select
 * pipeline from the standard {@code fs.s3a.*} configuration keys
 * (imported from {@link org.apache.hadoop.fs.s3a.Constants}, a public
 * hadoop-aws class).
 *
 * <p>Prior to Hadoop 3.4, {@link S3SelectAccessor} obtained this client
 * from {@code org.apache.hadoop.fs.s3a.DefaultS3ClientFactory}. Hadoop 3.4
 * migrated its S3 client to AWS SDK v2 while the S3 Select pipeline is
 * written against v1 API types, so PXF now builds the v1 client itself.
 * The mapping below reproduces what the 3.3.x factory applied through
 * {@code S3AUtils.createAwsConf} (proxy, timeouts, connection pool, SSL,
 * retries, signing algorithm, user agent), using the hadoop-aws 3.3.6
 * default values, so existing {@code s3-site.xml} tuning keeps working
 * for S3 Select.
 *
 * <p>Two behaviors are deliberate <em>changes</em> from the pre-3.4
 * factory rather than reproductions of it — the old code passed an empty
 * parameter set, which made it ignore parts of the server configuration
 * that the plain {@code s3a://} path honored:
 * <ul>
 * <li>{@code fs.s3a.endpoint} and {@code fs.s3a.path.style.access} are
 * now honored for S3 Select (the old factory always ignored them and
 * forced global bucket access). Deployments using custom endpoints get
 * consistent behavior across both S3 paths.</li>
 * <li>{@code fs.s3a.access.key}/{@code fs.s3a.secret.key} (and session
 * token) are now honored for S3 Select, matching how the plain s3a path
 * resolves credentials. The old factory always used the SDK default
 * provider chain (environment, system properties, instance profile) and
 * silently ignored keys configured in {@code s3-site.xml}.</li>
 * </ul>
 */
final class S3SelectClientFactory {

    private static final Logger LOG = LoggerFactory.getLogger(S3SelectClientFactory.class);

    // Defaults mirror hadoop-aws 3.3.6's Constants, i.e. the values the
    // pre-3.4 factory applied when the corresponding key was unset.
    static final int DEFAULT_MAXIMUM_CONNECTIONS = 96;
    static final boolean DEFAULT_SECURE_CONNECTIONS = true;
    static final int DEFAULT_MAX_ERROR_RETRIES = 10;
    static final long DEFAULT_ESTABLISH_TIMEOUT_MS = 50_000L;
    static final long DEFAULT_SOCKET_TIMEOUT_MS = 200_000L;
    static final long DEFAULT_REQUEST_TIMEOUT_MS = 0L;
    static final int DEFAULT_SOCKET_SEND_BUFFER = 8192;
    static final int DEFAULT_SOCKET_RECV_BUFFER = 8192;

    private S3SelectClientFactory() {
    }

    /**
     * Creates the SDK v1 client for S3 Select from the request's Hadoop
     * configuration.
     */
    static AmazonS3 createClient(Configuration configuration) {
        rejectClientSideEncryption(configuration);

        AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard()
                .withCredentials(createCredentialsProvider(configuration))
                .withClientConfiguration(createClientConfiguration(configuration));

        applyEndpointAndRegion(builder, configuration);

        if (configuration.getBoolean(PATH_STYLE_ACCESS, false)) {
            builder.enablePathStyleAccess();
        }
        return builder.build();
    }

    /**
     * S3 Select has no client-side-encryption support: the SDK v1 client
     * built here is a plain client, so CSE-encrypted objects would be
     * served as ciphertext. Fail fast with an actionable message instead.
     * (The plain {@code s3a://} path supports CSE through hadoop-aws's own
     * encryption client factory.)
     */
    // the pre-3.4 name of fs.s3a.encryption.algorithm; deprecated in
    // hadoop-aws but still honored there, so honor it here too
    private static final String LEGACY_ENCRYPTION_ALGORITHM_KEY =
            "fs.s3a.server-side-encryption-algorithm";

    static void rejectClientSideEncryption(Configuration configuration) {
        String algorithm = configuration.getTrimmed(S3_ENCRYPTION_ALGORITHM,
                configuration.getTrimmed(LEGACY_ENCRYPTION_ALGORITHM_KEY, ""));
        if (Strings.CI.startsWith(algorithm, "CSE")) {
            throw new UnsupportedOperationException(String.format(
                    "S3 Select does not support client-side encryption (%s=%s). "
                            + "Query the data without S3_SELECT, or disable client-side encryption.",
                    S3_ENCRYPTION_ALGORITHM, algorithm));
        }
    }

    /**
     * Resolves credentials the same way the plain s3a path does at its
     * core: a full static key pair (optionally with a session token) from
     * the configuration wins; no configured pair falls through to the SDK
     * default provider chain; a partial pair is a configuration error.
     * Values are read through {@link Configuration#getPassword} so
     * credential-provider (JCEKS) storage works, and are trimmed.
     */
    static AWSCredentialsProvider createCredentialsProvider(Configuration configuration) {
        if (StringUtils.isNotBlank(configuration.getTrimmed(AWS_CREDENTIALS_PROVIDER, ""))) {
            LOG.warn("S3 Select ignores {}; it uses configured static keys or the SDK default provider chain",
                    AWS_CREDENTIALS_PROVIDER);
        }

        String accessKey = lookupPassword(configuration, ACCESS_KEY);
        String secretKey = lookupPassword(configuration, SECRET_KEY);
        String sessionToken = lookupPassword(configuration, SESSION_TOKEN);

        if (StringUtils.isBlank(accessKey) && StringUtils.isBlank(secretKey)) {
            LOG.debug("No static S3 credentials configured, using the SDK default provider chain for S3 Select");
            return new DefaultAWSCredentialsProviderChain();
        }
        if (StringUtils.isBlank(accessKey) || StringUtils.isBlank(secretKey)) {
            throw new IllegalArgumentException(String.format(
                    "Partial static S3 credentials: only one of %s and %s is set", ACCESS_KEY, SECRET_KEY));
        }
        return new AWSStaticCredentialsProvider(
                StringUtils.isNotBlank(sessionToken)
                        ? new BasicSessionCredentials(accessKey, secretKey, sessionToken)
                        : new BasicAWSCredentials(accessKey, secretKey));
    }

    /**
     * Maps the s3a connection/proxy tuning keys onto the SDK v1
     * {@link ClientConfiguration}, reproducing what
     * {@code S3AUtils.createAwsConf} used to apply to this client.
     */
    static ClientConfiguration createClientConfiguration(Configuration configuration) {
        ClientConfiguration awsConf = new ClientConfiguration();
        awsConf.setMaxConnections(configuration.getInt(MAXIMUM_CONNECTIONS, DEFAULT_MAXIMUM_CONNECTIONS));
        boolean secureConnections = configuration.getBoolean(SECURE_CONNECTIONS, DEFAULT_SECURE_CONNECTIONS);
        awsConf.setProtocol(secureConnections ? Protocol.HTTPS : Protocol.HTTP);
        awsConf.setMaxErrorRetry(configuration.getInt(MAX_ERROR_RETRIES, DEFAULT_MAX_ERROR_RETRIES));
        // getTimeDuration accepts both the plain-millisecond values that
        // hadoop-aws 3.3.x documented and the suffixed durations ("30s")
        // that 3.4.x prefers
        awsConf.setConnectionTimeout((int) configuration.getTimeDuration(
                ESTABLISH_TIMEOUT, DEFAULT_ESTABLISH_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        awsConf.setSocketTimeout((int) configuration.getTimeDuration(
                SOCKET_TIMEOUT, DEFAULT_SOCKET_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        // request timeout: 0 (the 3.3.6 default) means no timeout; the SDK
        // takes an int, so larger configured values are capped like
        // S3AUtils did
        long requestTimeout = configuration.getTimeDuration(
                REQUEST_TIMEOUT, DEFAULT_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (requestTimeout > Integer.MAX_VALUE) {
            LOG.debug("Request timeout is too high({} ms). Setting to {} ms instead",
                    requestTimeout, Integer.MAX_VALUE);
            requestTimeout = Integer.MAX_VALUE;
        }
        awsConf.setRequestTimeout((int) requestTimeout);
        awsConf.setSocketBufferSizeHints(
                configuration.getInt(SOCKET_SEND_BUFFER, DEFAULT_SOCKET_SEND_BUFFER),
                configuration.getInt(SOCKET_RECV_BUFFER, DEFAULT_SOCKET_RECV_BUFFER));

        String signerOverride = configuration.getTrimmed(SIGNING_ALGORITHM, "");
        if (StringUtils.isNotBlank(signerOverride)) {
            awsConf.setSignerOverride(signerOverride);
        }
        // User-Agent reproduces S3AUtils.initUserAgent: always "Hadoop
        // <version>", with the configured prefix prepended when present
        String userAgent = "Hadoop " + org.apache.hadoop.util.VersionInfo.getVersion();
        String userAgentPrefix = configuration.getTrimmed(USER_AGENT_PREFIX, "");
        if (StringUtils.isNotBlank(userAgentPrefix)) {
            userAgent = userAgentPrefix + ", " + userAgent;
        }
        awsConf.setUserAgentPrefix(userAgent);

        applyProxySettings(awsConf, configuration, secureConnections);
        return awsConf;
    }

    private static void applyProxySettings(ClientConfiguration awsConf, Configuration configuration,
                                           boolean secureConnections) {
        String proxyHost = configuration.getTrimmed(PROXY_HOST, "");
        int proxyPort = configuration.getInt(PROXY_PORT, -1);
        if (StringUtils.isBlank(proxyHost)) {
            if (proxyPort >= 0) {
                throw new IllegalArgumentException(
                        String.format("Proxy error: %s set without %s", PROXY_PORT, PROXY_HOST));
            }
            return;
        }
        awsConf.setProxyHost(proxyHost);
        if (proxyPort >= 0) {
            awsConf.setProxyPort(proxyPort);
        } else {
            // mirror S3AUtils.initProxySupport: infer the scheme port
            awsConf.setProxyPort(secureConnections ? 443 : 80);
        }
        String proxyUsername = lookupPassword(configuration, PROXY_USERNAME);
        String proxyPassword = lookupPassword(configuration, PROXY_PASSWORD);
        if (StringUtils.isBlank(proxyUsername) != StringUtils.isBlank(proxyPassword)) {
            throw new IllegalArgumentException(String.format(
                    "Proxy error: %s or %s set without the other", PROXY_USERNAME, PROXY_PASSWORD));
        }
        if (StringUtils.isNotBlank(proxyUsername)) {
            awsConf.setProxyUsername(proxyUsername);
            awsConf.setProxyPassword(proxyPassword);
        }
        String proxyDomain = configuration.getTrimmed(PROXY_DOMAIN, "");
        if (StringUtils.isNotBlank(proxyDomain)) {
            awsConf.setProxyDomain(proxyDomain);
        }
        String proxyWorkstation = configuration.getTrimmed(PROXY_WORKSTATION, "");
        if (StringUtils.isNotBlank(proxyWorkstation)) {
            awsConf.setProxyWorkstation(proxyWorkstation);
        }
    }

    /**
     * Endpoint and region selection.
     *
     * <p>With {@code fs.s3a.endpoint} set, the endpoint is honored and the
     * signing region is {@code fs.s3a.endpoint.region} when set, otherwise
     * derived from the endpoint host name, otherwise us-east-1 (the SDK's
     * convention for custom endpoints).
     *
     * <p>Without an endpoint, global bucket access is enabled (so a client
     * created in one region can address buckets in another) and the region
     * follows hadoop-aws 3.3.6's configureEndpoint exactly (verified in
     * bytecode): {@code fs.s3a.endpoint.region} when set, us-east-1 when the
     * key is absent, and the SDK default region resolution chain when the
     * key is <em>explicitly</em> set to the empty string.
     */
    static void applyEndpointAndRegion(AmazonS3ClientBuilder builder, Configuration configuration) {
        String endpoint = configuration.getTrimmed(ENDPOINT, "");
        String rawRegion = configuration.get(AWS_REGION);
        String region = rawRegion == null ? null : rawRegion.trim();

        if (StringUtils.isNotBlank(endpoint)) {
            String signingRegion = StringUtils.isNotBlank(region)
                    ? region
                    : StringUtils.defaultIfBlank(deriveRegionFromEndpoint(endpoint), AWS_S3_CENTRAL_REGION);
            builder.withEndpointConfiguration(
                    new AwsClientBuilder.EndpointConfiguration(endpoint, signingRegion));
            return;
        }

        builder.withForceGlobalBucketAccessEnabled(true);
        if (region == null) {
            builder.withRegion(AWS_S3_CENTRAL_REGION);
        } else if (region.isEmpty()) {
            LOG.debug("{} is explicitly empty; deferring to the SDK region resolution chain", AWS_REGION);
        } else {
            builder.withRegion(region);
        }
    }

    /**
     * Derives the signing region from an endpoint that may or may not
     * carry a scheme or port, mirroring how the SDK normalizes endpoints
     * before parsing. Returns null when the host encodes no region (the
     * usual case for custom/on-prem endpoints).
     */
    static String deriveRegionFromEndpoint(String endpoint) {
        String uriText = endpoint.contains("://") ? endpoint : "https://" + endpoint;
        String host;
        try {
            host = URI.create(uriText).getHost();
        } catch (IllegalArgumentException e) {
            host = null;
        }
        if (host == null) {
            throw new IllegalArgumentException(String.format(
                    "Unable to parse a host name from %s value '%s'", ENDPOINT, endpoint));
        }
        return AwsHostNameUtils.parseRegion(host, "s3");
    }

    /**
     * Reads a sensitive value through the Hadoop credential-provider API
     * (so JCEKS-stored secrets work) and trims it, like
     * {@code S3AUtils.lookupPassword}.
     */
    static String lookupPassword(Configuration configuration, String key) {
        try {
            char[] value = configuration.getPassword(key);
            return value == null ? null : new String(value).trim();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read credential entry " + key, e);
        }
    }
}
