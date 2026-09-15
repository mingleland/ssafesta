package com.example.ssafesta.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * 저장소 설정이 <b>기동 시</b> 검증되는지 (spec 007 C-07·C-10).
 *
 * <p>여기서 잡지 못한 오설정은 첫 업로드에서 503 으로 나타난다 — 장애처럼 보이지만 실은 오타다.
 *
 * <p>{@code "15m"} 같은 문자열 바인딩은 생성자를 직접 부르는 것으로 검증되지 않으므로
 * {@link Binder} 로 실제로 묶는다. 컨테이너 없이 도는 단위 테스트다.
 */
class ObjectStoragePropertiesTest {

    @Test
    void theShippedSettingsBind() {
        ObjectStorageProperties properties = bind(baseSettings());

        assertEquals(ObjectStorageProperties.UploadGate.OPEN, properties.uploadGate());
        assertEquals("R2", properties.activeWriteProvider());
        assertEquals(Duration.ofMinutes(15), properties.presignTtl());
        assertEquals("test-ai-documents", properties.providers().get("R2").bucket());
    }

    /** 오타 하나로 모든 업로드가 503 이 되는 것을 기동에서 막는다. */
    @Test
    void anActiveProviderThatIsNotConfiguredRefusesToStart() {
        assertTrue(failureOf(settingsWith("app.ai.storage.active-write-provider", "MINIO_LOCAL"))
                .contains("active-write-provider"));
    }

    /**
     * 게이트 세 값이 <b>전부</b> 바인딩돼야 한다.
     *
     * <p>운영자는 Usage Guard 와 저장소 전환 상태를 읽고 이 한 칸에 판정을 옮겨 적는다(#100).
     * 값이 빠지면 옮겨 적을 자리가 없어 번역이 생기고, 번역은 틀린다.
     */
    @ParameterizedTest
    @EnumSource(ObjectStorageProperties.UploadGate.class)
    void everyGateValueBinds(ObjectStorageProperties.UploadGate gate) {
        assertEquals(gate, bind(settingsWith("app.ai.storage.upload-gate", gate.name())).uploadGate());
    }

    /**
     * 게이트에 기본값이 없다 — 이것이 P1 이었다.
     *
     * <p>Spring 은 모르는 키를 조용히 무시하므로, 키 이름이 바뀌거나 오타가 나면 값이 {@code null}
     * 이 되고 기본값이 "허용" 이면 <b>차단이 열린 채로 기동한다.</b> 안전 장치가 오타로 열리는
     * 모양이라, 말하지 않으면 뜨지 않게 한다.
     */
    @Test
    void aMissingGateRefusesToStart() {
        Map<String, String> settings = new LinkedHashMap<>(baseSettings());
        settings.remove("app.ai.storage.upload-gate");

        assertTrue(failureOf(settings).contains("upload-gate"));
    }

    /**
     * 옛 키만 남은 설정도 같은 이유로 기동을 막는다.
     *
     * <p>{@code upload-enabled}(#100 이전 초안)와 {@code usage-state}·{@code storage-state}(구현
     * 중간 형태) 셋 다 Spring 이 조용히 무시한다 — 운영자는 차단을 걸었다고 믿고 서버는 열린 채 뜬다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"upload-enabled", "usage-state", "storage-state"})
    void aRemovedKeyAloneRefusesToStart(String legacyKey) {
        Map<String, String> settings = new LinkedHashMap<>(baseSettings());
        settings.remove("app.ai.storage.upload-gate");
        settings.put("app.ai.storage." + legacyKey, "UPLOAD_BLOCKED");

        assertTrue(failureOf(settings).contains("upload-gate"));
    }

    /** 계약에 없는 값은 바인딩 자체가 실패해야 한다 — 오타가 "허용" 으로 떨어지면 안 된다. */
    @Test
    void aGateValueOutsideTheContractRefusesToStart() {
        assertThrows(BindException.class,
                () -> bind(settingsWith("app.ai.storage.upload-gate", "OPENN")));
    }

    @Test
    void aNonPositiveTtlRefusesToStart() {
        assertTrue(failureOf(settingsWith("app.ai.storage.presign-ttl", "0s"))
                .contains("presign-ttl"));
    }

    /**
     * SigV4 는 7일을 넘는 URL 에 서명하지 않는다 — 통과시키면 기동은 멀쩡하고 <b>첫 업로드 요청이
     * 500</b> 이 된다. 설정 오류가 런타임까지 숨는 모양이라 기동에서 잡는다.
     */
    @Test
    void aTtlBeyondTheSigV4CeilingRefusesToStart() {
        assertTrue(failureOf(settingsWith("app.ai.storage.presign-ttl", "8d"))
                .contains("presign-ttl"));
    }

    /** 상한 자체는 허용된다 — 경계에서 잘못 막으면 정상 설정이 기동을 못 한다. */
    @Test
    void aTtlExactlyAtTheCeilingBinds() {
        assertEquals(Duration.ofDays(7),
                bind(settingsWith("app.ai.storage.presign-ttl", "7d")).presignTtl());
    }

    /**
     * R2 만 쓰는 배포는 MinIO 자격증명을 지어내지 않아도 된다.
     *
     * <p>{@code application.yml} 이 MinIO 를 목록에 적어 두는 것은 fallback 을 "설정 변경 + 재배포"
     * 로 하기 위해서다(계약 §Manual fallback). 네 값이 전부 비면 미구성으로 보고 목록에서 뺀다 —
     * 그러지 않으면 R2 전용 배포가 기동조차 못 한다.
     */
    @Test
    void anEntirelyBlankProviderIsTreatedAsNotConfigured() {
        Map<String, String> settings = new LinkedHashMap<>(baseSettings());
        settings.put("app.ai.storage.providers.MINIO_LOCAL.endpoint", "");
        settings.put("app.ai.storage.providers.MINIO_LOCAL.bucket", "");
        settings.put("app.ai.storage.providers.MINIO_LOCAL.access-key-id", "");
        settings.put("app.ai.storage.providers.MINIO_LOCAL.secret-access-key", "");

        assertEquals(Set.of("R2"), bind(settings).providers().keySet());
    }

    /** 절반만 채운 provider 는 선택이 아니라 오타다 — 미구성으로 봐주면 그 오타가 숨는다. */
    @Test
    void aPartiallyFilledProviderRefusesToStart() {
        Map<String, String> settings = new LinkedHashMap<>(baseSettings());
        settings.put("app.ai.storage.providers.MINIO_LOCAL.endpoint", "http://localhost:9");
        settings.put("app.ai.storage.providers.MINIO_LOCAL.bucket", "");
        settings.put("app.ai.storage.providers.MINIO_LOCAL.access-key-id", "");
        settings.put("app.ai.storage.providers.MINIO_LOCAL.secret-access-key", "");

        String failure = failureOf(settings);
        assertTrue(failure.contains("MINIO_LOCAL"), failure);
    }

    /**
     * fallback 운영 조합이 뜬다 — 여기서 잘못 막으면 MinIO 전환 자체가 불가능해진다.
     *
     * <p>{@code storage-state} 가 사라지면서 "상태와 provider 가 어긋난다" 는 사건도 함께 사라졌다
     * (#100): {@code (OPEN, MINIO_LOCAL)} 이 곧 {@code LOCAL_ACTIVE} 다. 한 곳에 적힌 한 사실은
     * 스스로와 어긋날 수 없다.
     */
    @Test
    void theFallbackProviderBindsWhenItIsConfigured() {
        Map<String, String> settings = new LinkedHashMap<>(baseSettings());
        settings.put("app.ai.storage.active-write-provider", "MINIO_LOCAL");
        settings.put("app.ai.storage.providers.MINIO_LOCAL.endpoint", "http://localhost:9");
        settings.put("app.ai.storage.providers.MINIO_LOCAL.bucket", "fallback");
        settings.put("app.ai.storage.providers.MINIO_LOCAL.access-key-id", "k");
        settings.put("app.ai.storage.providers.MINIO_LOCAL.secret-access-key", "s");

        ObjectStorageProperties properties = bind(settings);

        assertEquals("MINIO_LOCAL", properties.activeWriteProvider());
        assertEquals(Set.of("R2", "MINIO_LOCAL"), properties.providers().keySet());
    }

    /**
     * provider 이름은 문서 행과 FastAPI 계약에 그대로 실린다 — 자유 문자열이 아니다.
     *
     * <p>세 번째 이름은 여기서는 멀쩡히 저장되고 FastAPI 에서 파싱에 실패한다. 원인에서 한 홉
     * 떨어진 자리라 붙잡기 어렵다.
     */
    @Test
    void aProviderNameOutsideTheContractRefusesToStart() {
        Map<String, String> settings = new LinkedHashMap<>(baseSettings());
        settings.put("app.ai.storage.active-write-provider", "S3");
        settings.put("app.ai.storage.providers.S3.endpoint", "http://localhost:9");
        settings.put("app.ai.storage.providers.S3.bucket", "b");
        settings.put("app.ai.storage.providers.S3.access-key-id", "k");
        settings.put("app.ai.storage.providers.S3.secret-access-key", "s");

        assertTrue(failureOf(settings).contains("S3"));
    }

    /** 배포에서 env 를 빠뜨리면 서명할 수 없는 URL 을 나눠 주기 전에 죽어야 한다. */
    @Test
    void aBlankCredentialRefusesToStart() {
        String failure = failureOf(settingsWith("app.ai.storage.providers.R2.secret-access-key", ""));
        assertTrue(failure.contains("secret-access-key"));
        assertTrue(failure.contains("R2"));
    }

    @Test
    void aMissingBucketRefusesToStart() {
        Map<String, String> settings = new LinkedHashMap<>(baseSettings());
        settings.remove("app.ai.storage.providers.R2.bucket");

        assertTrue(failureOf(settings).contains("bucket"));
    }

    private static String failureOf(Map<String, String> settings) {
        BindException failure = assertThrows(BindException.class, () -> bind(settings));
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }

    private static Map<String, String> settingsWith(String key, String value) {
        Map<String, String> settings = new LinkedHashMap<>(baseSettings());
        settings.put(key, value);
        return settings;
    }

    private static Map<String, String> baseSettings() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("app.ai.storage.upload-gate", "OPEN");
        settings.put("app.ai.storage.active-write-provider", "R2");
        settings.put("app.ai.storage.presign-ttl", "15m");
        settings.put("app.ai.storage.providers.R2.endpoint", "http://localhost:9");
        settings.put("app.ai.storage.providers.R2.bucket", "test-ai-documents");
        settings.put("app.ai.storage.providers.R2.access-key-id", "test-access-key");
        settings.put("app.ai.storage.providers.R2.secret-access-key", "test-secret-key");
        return settings;
    }

    private static ObjectStorageProperties bind(Map<String, String> settings) {
        return new Binder(new MapConfigurationPropertySource(settings))
                .bind("app.ai.storage", ObjectStorageProperties.class).get();
    }
}
