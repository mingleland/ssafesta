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
class S3DocumentStorage implements AiDocumentStorage {

    private static final Logger log = LoggerFactory.getLogger(S3DocumentStorage.class);

    /** R2 has no regions; the SDK still requires one for SigV4, and {@code auto} is R2's answer. */
    private static final Region SIGNING_REGION = Region.of("auto");

    /** What S3 calls "the bucket is full". Not retryable, unlike everything else here (C-10). */
    private static final String QUOTA_EXCEEDED = "QuotaExceeded";

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
        } catch (NoSuchKeyException absent) {
            return Optional.empty();
        } catch (S3Exception exception) {
            // 404 without a typed exception happens on HEAD, where there is no body to parse.
            if (exception.statusCode() == 404) {
                return Optional.empty();
            }
            if (isQuotaExceeded(exception)) {
                throw new StorageQuotaExceededException();
            }
            throw unavailable(provider, exception);
        } catch (SdkException exception) {
            throw unavailable(provider, exception);
        }
    }

    private static boolean isQuotaExceeded(S3Exception exception) {
        return exception.awsErrorDetails() != null
                && QUOTA_EXCEEDED.equals(exception.awsErrorDetails().errorCode());
    }

    private StorageUnavailableException unavailable(String provider, SdkException exception) {
        // The SDK message is logged, never returned: it names the endpoint and can echo headers.
        log.warn("저장소 요청 실패 provider={}", provider, exception);
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
    }
}
