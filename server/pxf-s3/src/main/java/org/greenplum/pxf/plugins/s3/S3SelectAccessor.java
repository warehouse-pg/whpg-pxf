package org.greenplum.pxf.plugins.s3;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.util.AwsHostNameUtils;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.CSVInput;
import com.amazonaws.services.s3.model.CSVOutput;
import com.amazonaws.services.s3.model.CompressionType;
import com.amazonaws.services.s3.model.ExpressionType;
import com.amazonaws.services.s3.model.InputSerialization;
import com.amazonaws.services.s3.model.JSONInput;
import com.amazonaws.services.s3.model.OutputSerialization;
import com.amazonaws.services.s3.model.ParquetInput;
import com.amazonaws.services.s3.model.SelectObjectContentEvent;
import com.amazonaws.services.s3.model.SelectObjectContentEventVisitor;
import com.amazonaws.services.s3.model.SelectObjectContentRequest;
import com.amazonaws.services.s3.model.SelectObjectContentResult;
import org.apache.commons.lang3.StringUtils;
import org.greenplum.pxf.api.OneRow;
import org.greenplum.pxf.api.model.Accessor;
import org.greenplum.pxf.api.model.BasePlugin;
import org.greenplum.pxf.api.model.GreenplumCSV;
import org.greenplum.pxf.api.model.RequestContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Accessor to read data from S3, using the S3 Select Framework.
 * S3 Select works on a single key (or object), pushing down as
 * much computation as possible to S3. This reduces the amount of
 * data we transfer over the wire, with the purpose of speeding up
 * query times from S3.
 */
public class S3SelectAccessor extends BasePlugin implements Accessor {

    // We call this option compression_codec to make it compatible to
    // the COMPRESSION_CODECs from the s3:text, s3:parquet profiles
    public static final String COMPRESSION_TYPE = "COMPRESSION_CODEC";
    public static final String FILE_HEADER_INFO = "FILE_HEADER";
    public static final String FILE_HEADER_INFO_NONE = "NONE";
    public static final String FILE_HEADER_INFO_IGNORE = "IGNORE";
    public static final String FILE_HEADER_INFO_USE = "USE";
    public static final String JSON_TYPE = "JSON-TYPE";
    private static final String UNSUPPORTED_ERR_MESSAGE = "S3 Select accessor does not support write operation.";

    private AtomicBoolean isResultComplete;
    private AmazonS3 s3Client;
    private SelectObjectContentResult result;
    private InputStream resultInputStream;
    private BufferedReader reader;
    private int lineReadCount;
    private URI name;

    /**
     * {@inheritDoc}
     */
    @Override
    public void afterPropertiesSet() {
        name = URI.create(context.getDataSource());
        s3Client = initS3Client();
        lineReadCount = 0;
    }

    @Override
    public boolean openForRead() {
        isResultComplete = new AtomicBoolean(false);
        SelectObjectContentRequest request = generateBaseCSVRequest(context);

        result = s3Client.selectObjectContent(request);
        resultInputStream = result.getPayload().getRecordsInputStream(
                new SelectObjectContentEventVisitor() {
                    @Override
                    public void visit(SelectObjectContentEvent.StatsEvent event) {
                        LOG.debug("Received Stats, Bytes Scanned: {}. Bytes Processed: {}",
                                event.getDetails().getBytesScanned(),
                                event.getDetails().getBytesProcessed());
                    }

                    /*
                     * An End Event informs that the request has finished successfully.
                     */
                    @Override
                    public void visit(SelectObjectContentEvent.EndEvent event) {
                        isResultComplete.set(true);
                        LOG.debug("Received End Event. Result is complete.");
                    }
                }
        );
        reader = new BufferedReader(new InputStreamReader(resultInputStream));
        return resultInputStream != null;
    }

    /**
     * Reads one line at a time
     *
     * @return the next line, or null if the EOF has been reached
     */
    @Override
    public OneRow readNextObject() throws Exception {
        String str = reader.readLine();
        if (str != null) {
            lineReadCount++;
            return new OneRow(null, str);
        }

        /*
         * The End Event indicates all matching records have been transmitted.
         * If the End Event is not received, the results may be incomplete.
         */
        if (!isResultComplete.get()) {
            throw new RuntimeException("S3 Select request was incomplete as End Event was not received.");
        }

        return null;
    }

    @Override
    public void closeForRead() throws IOException {
        LOG.debug("Read {} lines", lineReadCount);

        /*
         * Make sure to close all streams
         */
        if (result != null) {
            try {
                result.close();
                LOG.debug("SelectObjectContentResult closed");
            } catch (IOException e) {
                LOG.error("Unable to close SelectObjectContentResult", e);
            }
        }

        if (resultInputStream != null) {
            try {
                resultInputStream.close();
                LOG.debug("ResultInputStream closed");
            } catch (IOException e) {
                LOG.error("Unable to close ResultInputStream", e);
            }
        }
    }

    /**
     * Generates the {@link SelectObjectContentRequest} object from
     * the request context.
     *
     * @param context the request context
     * @return a {@link SelectObjectContentRequest}
     */
    SelectObjectContentRequest generateBaseCSVRequest(RequestContext context) {

        InputSerialization inputSerialization = getInputSerialization(context);

        String fileHeaderInfo = context.getOption(FILE_HEADER_INFO);
        boolean usePositionToIdentifyColumn = inputSerialization.getCsv() != null &&
                (StringUtils.isBlank(fileHeaderInfo) ||
                        !StringUtils.equalsIgnoreCase(FILE_HEADER_INFO_USE, fileHeaderInfo));
        String query = null;
        try {
            S3SelectQueryBuilder queryBuilder = new S3SelectQueryBuilder(context, usePositionToIdentifyColumn);
            query = queryBuilder.buildSelectQuery();
        } catch (SQLException e) {
            LOG.error("Unable to build select query for filter string {}", context.getFilterString());
        }

        LOG.trace("Select query: {}", query);

        SelectObjectContentRequest request = new SelectObjectContentRequest();
        request.setBucketName(name.getHost());
        request.setKey(StringUtils.removeStart(name.getPath(), "/"));
        request.setExpression(query);
        request.setExpressionType(ExpressionType.SQL);

        LOG.debug("With bucket name '{}'", request.getBucketName());
        LOG.debug("With key '{}'", request.getKey());
        LOG.debug("With expression query '{}'", query);

        request.setInputSerialization(inputSerialization);

        OutputSerialization outputSerialization = getOutputSerialization(context);
        request.setOutputSerialization(outputSerialization);

        return request;
    }

    /**
     * Returns a {@link com.amazonaws.services.s3.model.OutputSerialization}
     * object with parsed values from the request context.
     *
     * @param context the request context
     * @return a {@link OutputSerialization} object
     */
    private OutputSerialization getOutputSerialization(RequestContext context) {

        GreenplumCSV csv = context.getGreenplumCSV();

        OutputSerialization outputSerialization = new OutputSerialization();
        CSVOutput csvOutput = new CSVOutput();
        csvOutput.setFieldDelimiter(csv.getDelimiter());
        csvOutput.setQuoteCharacter(csv.getQuote());
        csvOutput.setQuoteEscapeCharacter(csv.getEscape());
        csvOutput.setRecordDelimiter(csv.getNewline());

        outputSerialization.setCsv(csvOutput);
        return outputSerialization;
    }

    /**
     * Returns a {@link com.amazonaws.services.s3.model.InputSerialization}
     * object with parsed values from the request context.
     *
     * @param context the request context
     * @return a {@link InputSerialization} object
     */
    InputSerialization getInputSerialization(RequestContext context) {
        InputSerialization inputSerialization = new InputSerialization();

        // We need to infer the format name from the profile (i.e. s3:parquet
        // would return parquet for the format)
        String format = context.inferFormatName();
        String compressionType = context.getOption(COMPRESSION_TYPE);

        LOG.debug("With format {}", format);
        if (StringUtils.equalsIgnoreCase(format, "parquet")) {
            inputSerialization.setParquet(new ParquetInput());
        } else if (StringUtils.equalsIgnoreCase(format, "json")) {
            inputSerialization.setJson(getJSONInput(context));
        } else {
            inputSerialization.setCsv(getCSVInput(context));
        }

        LOG.debug("With compression type {}", compressionType);
        if (StringUtils.equalsIgnoreCase(compressionType, "gzip")) {
            inputSerialization.setCompressionType(CompressionType.GZIP);
        } else if (StringUtils.equalsIgnoreCase(compressionType, "bzip2")) {
            inputSerialization.setCompressionType(CompressionType.BZIP2);
        } else {
            inputSerialization.setCompressionType(CompressionType.NONE);
        }

        return inputSerialization;
    }

    /**
     * Returns a {@link com.amazonaws.services.s3.model.JSONInput}
     * object with parsed values from the request context.
     *
     * @param context the request context
     * @return a {@link com.amazonaws.services.s3.model.JSONInput}
     */
    JSONInput getJSONInput(RequestContext context) {
        JSONInput jsonInput = new JSONInput();

        String jsonType = context.getOption(JSON_TYPE);

        if (StringUtils.isNoneBlank(jsonType)) {
            jsonInput.setType(jsonType);
        }
        return jsonInput;
    }

    /**
     * Returns a {@link com.amazonaws.services.s3.model.CSVInput}
     * object with parsed values from the request context.
     *
     * @param context the request context
     * @return a {@link CSVInput}
     */
    CSVInput getCSVInput(RequestContext context) {
        CSVInput csvInput = new CSVInput();
        GreenplumCSV csv = context.getGreenplumCSV();

        String fileHeaderInfo = context.getOption(FILE_HEADER_INFO);
        if (fileHeaderInfo != null) {
            LOG.debug("With CSV FileHeaderInfo '{}'", fileHeaderInfo);
            csvInput.setFileHeaderInfo(fileHeaderInfo);
        }

        if (csv.getDelimiter() != null) {
            LOG.debug("With CSV field delimiter '{}'", csv.getDelimiter());
            csvInput.setFieldDelimiter(csv.getDelimiter());
        }

        if (csv.getNewline() != null) {
            LOG.debug("With CSV NEWLINE '{}'", csv.getNewline());
            csvInput.setRecordDelimiter(csv.getNewline());
        }

        if (csv.getEscape() != null) {
            LOG.debug("With CSV quote escape character '{}'", csv.getEscape());
            csvInput.setQuoteEscapeCharacter(csv.getEscape());
        }

        LOG.debug("With CSV quote character '{}'", csv.getQuote());
        csvInput.setQuoteCharacter(csv.getQuote());

        return csvInput;
    }

    // Standard s3a-connector configuration keys. Hardcoded here rather
    // than imported from org.apache.hadoop.fs.s3a.Constants so that this
    // class does not depend on hadoop-aws internals (the constant names
    // and containing class have churned across Hadoop 3.x releases).
    // The wire-level keys themselves have been stable since Hadoop 2.6.
    private static final String S3A_ACCESS_KEY        = "fs.s3a.access.key";
    private static final String S3A_SECRET_KEY        = "fs.s3a.secret.key";
    private static final String S3A_SESSION_TOKEN     = "fs.s3a.session.token";
    private static final String S3A_ENDPOINT          = "fs.s3a.endpoint";
    private static final String S3A_ENDPOINT_REGION   = "fs.s3a.endpoint.region";
    private static final String S3A_PATH_STYLE_ACCESS = "fs.s3a.path.style.access";
    // hadoop-aws's own fallback when fs.s3a.endpoint.region is unset
    // (org.apache.hadoop.fs.s3a.Constants.AWS_S3_CENTRAL_REGION).
    private static final String DEFAULT_REGION = "us-east-1";

    /**
     * Returns a new AmazonS3 client (AWS SDK v1) built from the Hadoop
     * {@link org.apache.hadoop.conf.Configuration} using the standard
     * {@code fs.s3a.*} property keys.
     *
     * <p>Historical note: prior to Hadoop 3.4 this method delegated to
     * {@code org.apache.hadoop.fs.s3a.DefaultS3ClientFactory}. Hadoop 3.4
     * migrated its internal S3 client from AWS SDK v1 to AWS SDK v2 —
     * the factory now returns {@code software.amazon.awssdk.services.s3.S3Client}
     * rather than {@code AmazonS3}. Because the rest of this class's S3
     * Select pipeline is written against SDK v1 API types
     * ({@code SelectObjectContentRequest}, {@code SelectObjectContentResult},
     * {@code SelectObjectContentEventVisitor}, etc.), we build the v1
     * client directly from configuration and drop the dependency on
     * {@code hadoop-aws} internals. Migrating the pipeline to SDK v2 is
     * tracked separately and is out of scope for the Hadoop 3.4 bump.</p>
     *
     * <p>If access/secret keys are absent from the configuration the
     * builder falls through to the AWS SDK v1 default credentials
     * provider chain (environment, system properties, instance profile,
     * container credentials), matching the previous factory's behaviour.</p>
     */
    private AmazonS3 initS3Client() {
        AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard();

        String accessKey    = configuration.get(S3A_ACCESS_KEY);
        String secretKey    = configuration.get(S3A_SECRET_KEY);
        String sessionToken = configuration.get(S3A_SESSION_TOKEN);
        if (StringUtils.isNotBlank(accessKey) && StringUtils.isNotBlank(secretKey)) {
            builder.withCredentials(new AWSStaticCredentialsProvider(
                    StringUtils.isNotBlank(sessionToken)
                            ? new BasicSessionCredentials(accessKey, secretKey, sessionToken)
                            : new BasicAWSCredentials(accessKey, secretKey)));
        }

        // Endpoint / region handling mirrors hadoop-aws 3.3.x
        // DefaultS3ClientFactory#configureEndpoint, verified by decompiling
        // hadoop-aws-3.3.6.jar so that behaviour is unchanged by this bump:
        //   - endpoint set   -> withEndpointConfiguration(endpoint, signingRegion)
        //   - endpoint unset -> forceGlobalBucketAccess(true) + region,
        //                       defaulting the region to us-east-1.
        // The global-bucket-access flag is what lets a client created for
        // one region still address buckets in another; dropping it would
        // silently break cross-region S3 Select reads.
        String endpoint = configuration.getTrimmed(S3A_ENDPOINT, "");
        String region   = configuration.getTrimmed(S3A_ENDPOINT_REGION, "");
        if (StringUtils.isNotBlank(endpoint)) {
            String signingRegion = region;
            if (StringUtils.isBlank(signingRegion)) {
                // Derive the signing region from the endpoint host the same
                // way the AWS SDK does, then fall back to the default.
                String host = endpoint.contains("://")
                        ? URI.create(endpoint).getHost()
                        : endpoint;
                signingRegion = AwsHostNameUtils.parseRegion(host, "s3");
            }
            builder.withEndpointConfiguration(
                    new AwsClientBuilder.EndpointConfiguration(
                            endpoint,
                            StringUtils.defaultIfBlank(signingRegion, DEFAULT_REGION)));
        } else {
            builder.withForceGlobalBucketAccessEnabled(true);
            builder.withRegion(StringUtils.defaultIfBlank(region, DEFAULT_REGION));
        }

        if (configuration.getBoolean(S3A_PATH_STYLE_ACCESS, false)) {
            builder.enablePathStyleAccess();
        }

        return builder.build();
    }

    @Override
    public boolean openForWrite() {
        throw new UnsupportedOperationException(UNSUPPORTED_ERR_MESSAGE);
    }

    @Override
    public boolean writeNextObject(OneRow onerow) {
        throw new UnsupportedOperationException(UNSUPPORTED_ERR_MESSAGE);
    }

    @Override
    public void closeForWrite() {
        throw new UnsupportedOperationException(UNSUPPORTED_ERR_MESSAGE);
    }
}
