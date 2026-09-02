package com.example.ssafesta.ai;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * The one {@link AiDocumentStorage} implementation, over any S3-compatible endpoint.
 *
 * <p>R2 and MinIO differ only in configuration: path-style addressing and {@code auto} as the region
 * work for both, so there is one client per configured provider and no per-vendor branch.
 *
 * <p><b>Nothing here logs the object key, the presigned URL, or a raw SDK error.</b> The key names a
 * customer's file and the URL is a bearer credential for fifteen minutes; the raw error carries
 * endpoint and header detail. Callers get the document id and the provider name, which is enough to
 * find the row (spec 007 plan.md 로깅 정책).
 */
class S3DocumentStorage implements AiDocumentStorage, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(S3DocumentStorage.class);

    /** R2 has no regions; the SDK still requires one for SigV4, and {@code auto} is R2's answer. */
    private static final Region SIGNING_REGION = Region.of("auto");

    private final Map<String, Endpoint> endpoints;
    private final String activeProvider;
    private final Duration presignTtl;

    S3DocumentStorage(AiStorageProperties properties) {
        this.activeProvider = properties.activeWriteProvider();
        this.presignTtl = properties.presignTtl();
        this.endpoints = properties.providers().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> Endpoint.of(entry.getValue())));
    }

    @Override
    public WriteTarget activeWriteTarget() {
        return new WriteTarget(activeProvider, endpoint(activeProvider).bucket);
    }

    @Override
    public String presignPut(String provider, String bucket, String objectKey, String contentType,
                             long contentLength) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                // Signed in, so a browser cannot upload a different type or a larger file than the
                // one the quota check approved. Without these the grant is a blank cheque.
                .contentType(contentType)
                .contentLength(contentLength)
                .build();
        return endpoint(provider).presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(presignTtl)
                .putObjectRequest(put)
                .build()).url().toString();
    }

    @Override
    public Optional<Long> headSize(String provider, String bucket, String objectKey) {
        try {
            HeadObjectResponse response = endpoint(provider).client.headObject(
                    HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
            return Optional.ofNullable(response.contentLength());
        } catch (S3Exception exception) {
            if (meansObjectAbsent(exception)) {
                return Optional.empty();
            }
            // Deliberately no branch for a full bucket here: HEAD is a read, and a quota is a
            // write-side limit. The upload it would break goes browser → storage without passing
            // through this process, so Spring never sees that failure (docs/26 미결정 항목).
            throw unavailable(provider, exception.statusCode(), errorCodeOf(exception));
        } catch (SdkException exception) {
            throw unavailable(provider, 0, exception.getClass().getSimpleName());
        }
    }

    /**
     * Whether a failed HEAD means "there is no such object" rather than "storage cannot answer".
     *
     * <p>The distinction decides between telling the owner to upload again and telling them to
     * retry later, so a wrong guess sends them down a road that cannot work.
     *
     * <p><b>A missing bucket is also a 404</b>, and it is not a missing upload — it is a deleted or
     * mistyped bucket, which no amount of re-uploading fixes. HEAD carries no response body, so the
     * SDK cannot always name the error; when it can, this uses it, and when it cannot the remaining
     * ambiguous 404 is read as an absent object because that is the case that actually happens in
     * normal use (a grant nobody finished).
     */
    static boolean meansObjectAbsent(S3Exception exception) {
        if (exception instanceof NoSuchBucketException) {
            return false;
        }
        if (exception instanceof NoSuchKeyException) {
            return true;
        }
        return exception.statusCode() == 404 && !"NoSuchBucket".equals(errorCodeOf(exception));
    }

    /** The provider's own short code ({@code AccessDenied}, …) — never its message. */
    private static String errorCodeOf(S3Exception exception) {
        return exception.awsErrorDetails() == null ? "UNKNOWN"
                : String.valueOf(exception.awsErrorDetails().errorCode());
    }

    /**
     * Logs enough to find the failure and nothing the provider wrote.
     *
     * <p>Not {@code log.warn(…, exception)}: that prints the SDK message and stack trace, which
     * carry the endpoint, the signed URL and response headers. spec 007 forbids exactly that, and
     * the earlier version of this method did it anyway one line under a comment saying not to.
     */
    private StorageUnavailableException unavailable(String provider, int status, String errorCode) {
        log.warn("저장소 요청 실패 provider={} status={} code={}", provider, status, errorCode);
        return new StorageUnavailableException("저장소에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.");
    }

    /**
     * A document may point at a provider this deployment has no configuration for — the row outlives
     * a fallback that was later removed. That leaves the object's existence <b>unknown</b>, which is
     * a 503; answering "gone" would tell the user to re-upload a file that is still there.
     */
    private Endpoint endpoint(String provider) {
        Endpoint endpoint = endpoints.get(provider);
        if (endpoint == null) {
            log.warn("설정되지 않은 저장소 provider={}", provider);
            throw new StorageUnavailableException("이 문서의 저장소를 사용할 수 없습니다. 관리자에게 문의해 주세요.");
        }
        return endpoint;
    }

    private record Endpoint(S3Client client, S3Presigner presigner, String bucket) {

        static Endpoint of(AiStorageProperties.Provider provider) {
            StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(provider.accessKeyId(), provider.secretAccessKey()));
            URI endpoint = URI.create(provider.endpoint());
            S3Client client = S3Client.builder()
                    .endpointOverride(endpoint)
                    .region(SIGNING_REGION)
                    .credentialsProvider(credentials)
                    // Virtual-host style would need per-bucket DNS; path style works on both R2 and
                    // a single-node MinIO, so one setting covers every provider we support.
                    .forcePathStyle(true)
                    .build();
            S3Presigner presigner = S3Presigner.builder()
                    .endpointOverride(endpoint)
                    .region(SIGNING_REGION)
                    .credentialsProvider(credentials)
                    .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder()
                            .pathStyleAccessEnabled(true)
                            .build())
                    .build();
            return new Endpoint(client, presigner, provider.bucket());
        }

        void close() {
            client.close();
            presigner.close();
        }
    }

    /**
     * Releases every provider's HTTP pool when the context shuts down.
     *
     * <p>Spring calls this on its own — an {@code AutoCloseable} bean gets its {@code close} wired
     * as the destroy method. Without it each context that builds this bean leaks a connection pool
     * and its threads, which a test suite notices long before production does.
     */
    @Override
    public void close() {
        endpoints.values().forEach(Endpoint::close);
    }
}
