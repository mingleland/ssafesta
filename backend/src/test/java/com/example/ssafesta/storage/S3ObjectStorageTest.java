package com.example.ssafesta.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * 실제 어댑터를 대상으로 하는 유일한 테스트다.
 *
 * <p>통합 테스트는 저장소 포트를 가짜로 갈아끼우므로 <b>이 클래스의 서명·예외 번역은 한 번도 타지
 * 않는다.</b> 그래서 여기서 직접 본다 — presign 은 네트워크 없이 서명만 하므로 더미 credential 로
 * 진짜 동작을 확인할 수 있고, 예외 번역은 판정 함수를 직접 부른다.
 */
class S3ObjectStorageTest {

    private static final String KEY = "booths/7/agents/78/documents/153/project.pdf";

    // ── 부재와 "답할 수 없음" 의 구분 ───────────────────────────────────────

    @Test
    void aMissingKeyMeansTheObjectIsAbsent() {
        assertTrue(S3ObjectStorage.meansObjectAbsent(
                NoSuchKeyException.builder().statusCode(404).build()));
    }

    /**
     * bucket 이 없는 것도 404 지만 <b>부재가 아니다.</b>
     *
     * <p>삭제됐거나 오설정이라는 뜻이고 재업로드로 풀리지 않는다 — 410/409 로 답하면 사용자를
     * 될 수 없는 길로 보낸다.
     */
    @Test
    void aMissingBucketIsNotAnAbsentObject() {
        assertFalse(S3ObjectStorage.meansObjectAbsent(
                NoSuchBucketException.builder().statusCode(404).build()));
    }

    /** HEAD 는 본문이 없어 SDK 가 이름을 못 붙일 때가 있다. 이름이 실려 오면 그것을 쓴다. */
    @Test
    void anUntypedNoSuchBucketIsStillNotAbsent() {
        assertFalse(S3ObjectStorage.meansObjectAbsent(s3Error(404, "NoSuchBucket")));
    }

    /** 이름이 없는 404 는 정상적으로 일어나는 쪽, 즉 "아직 안 올린 객체" 로 읽는다. */
    @Test
    void anUnnamed404IsReadAsAbsent() {
        assertTrue(S3ObjectStorage.meansObjectAbsent(s3Error(404, null)));
    }

    @Test
    void aPermissionFailureIsNotAbsent() {
        assertFalse(S3ObjectStorage.meansObjectAbsent(s3Error(403, "AccessDenied")));
    }

    // ── 서명 ────────────────────────────────────────────────────────────────

    /**
     * 서명은 오프라인이다 — 더미 키로도 진짜 URL 이 나온다.
     *
     * <p>path-style 이라 bucket 이 경로에 들어가고, TTL 이 쿼리에 실린다. 이게 깨지면 브라우저가
     * 아무 데도 못 올린다.
     */
    @Test
    void aPresignedPutCarriesTheBucketKeyAndTtl() {
        try (S3ObjectStorage storage = new S3ObjectStorage(properties(Duration.ofMinutes(15)))) {
            String url = storage.presignPut("R2", "test-ai-documents", KEY,
                    "application/pdf", 1024, Duration.ofMinutes(15));

            assertTrue(url.contains("/test-ai-documents/" + KEY), "bucket·key 가 경로에 없다: " + url);
            assertTrue(url.contains("X-Amz-Expires=900"), "TTL 이 실리지 않았다: " + url);
            assertTrue(url.contains("X-Amz-Signature="), "서명이 없다: " + url);
        }
    }

    @Test
    void theActiveWriteTargetComesFromConfiguration() {
        try (S3ObjectStorage storage = new S3ObjectStorage(properties(Duration.ofMinutes(15)))) {
            assertEquals(new ObjectStorage.WriteTarget("R2", "test-ai-documents"),
                    storage.activeWriteTarget());
        }
    }

    /**
     * 설정되지 않은 provider 를 가리키는 문서는 <b>모른다</b>는 뜻이라 503 이다.
     *
     * <p>"없어졌다" 로 답하면 아직 있는 파일을 다시 올리라고 하게 된다.
     */
    @Test
    void anUnconfiguredProviderIsUnavailableRatherThanAbsent() {
        try (S3ObjectStorage storage = new S3ObjectStorage(properties(Duration.ofMinutes(15)))) {
            org.junit.jupiter.api.Assertions.assertThrows(StorageUnavailableException.class,
                    () -> storage.headSize("MINIO_LOCAL", "somewhere", KEY));
        }
    }

    private static S3Exception s3Error(int status, String errorCode) {
        return (S3Exception) S3Exception.builder()
                .statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(errorCode).build())
                .build();
    }

    private static ObjectStorageProperties properties(Duration ttl) {
        return new ObjectStorageProperties(ObjectStorageProperties.UploadGate.OPEN, "R2", ttl, Map.of("R2",
                new ObjectStorageProperties.Provider("http://localhost:9", "test-ai-documents",
                        "test-access-key", "test-secret-key")));
    }
}
