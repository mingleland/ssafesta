package com.example.ssafesta.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
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
class AiStoragePropertiesTest {

    @Test
    void theShippedSettingsBind() {
        AiStorageProperties properties = bind(baseSettings());

        assertEquals(AiStorageProperties.UsageState.NORMAL, properties.usageState());
        assertEquals(AiStorageProperties.StorageState.R2_ACTIVE, properties.storageState());
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
     * 계약이 정의한 usage-guard state 네 개가 <b>전부</b> 바인딩돼야 한다.
     *
     * <p>{@code WARNING} 은 허용이라 코드 분기가 같지만, enum 에서 빼면 계약이 "일어난다" 고 적어 둔
     * 상태를 운영자가 그대로 옮겨 적을 수 없다 — 그 순간 번역이 생기고 번역은 틀린다.
     */
    @ParameterizedTest
    @EnumSource(AiStorageProperties.UsageState.class)
    void everyUsageStateInTheContractBinds(AiStorageProperties.UsageState state) {
        assertEquals(state, bind(settingsWith("app.ai.storage.usage-state", state.name()))
                .usageState());
    }

    /**
     * 장애 상태 기계도 마찬가지다.
     *
     * <p>{@code LOCAL_ACTIVE} 만 빠지는데, 그 상태는 쓰기 provider 도 함께 옮겨야 성립하는 조합이라
     * R2 설정 위에 얹을 수 없다 — 아래 {@link #aFallbackStateWithItsOwnProviderBinds} 가 짝을 맞춰
     * 검증한다. 여기서 억지로 통과시키려면 불변식을 풀어야 하고, 그러면 검사가 사라진다.
     */
    @ParameterizedTest
    @EnumSource(value = AiStorageProperties.StorageState.class,
            mode = EnumSource.Mode.EXCLUDE, names = "LOCAL_ACTIVE")
    void everyStorageStateInTheContractBinds(AiStorageProperties.StorageState state) {
        assertEquals(state, bind(settingsWith("app.ai.storage.storage-state", state.name()))
                .storageState());
    }

    /**
     * 두 축이 같은 이름을 쓰지만 뜻이 다르다 — 한 칸에 뭉쳐 있으면 507 인지 503 인지 알 수 없다.
     *
     * <p>따로 설정되는지를 고정해 둔다. 다시 합치면 이 테스트가 컴파일되지 않는다.
     */
    @Test
    void theTwoAxesCarryTheSameTokenIndependently() {
        AiStorageProperties properties = bind(settingsWith(
                "app.ai.storage.usage-state", "UPLOAD_BLOCKED"));

        assertEquals(AiStorageProperties.UsageState.UPLOAD_BLOCKED, properties.usageState());
        assertEquals(AiStorageProperties.StorageState.R2_ACTIVE, properties.storageState());
    }

    /**
     * 게이트에 기본값이 없다 — 이것이 P1 이었다.
     *
     * <p>Spring 은 모르는 키를 조용히 무시하므로, 키 이름이 바뀌거나 오타가 나면 값이 {@code null}
     * 이 되고 기본값이 "허용" 이면 <b>차단이 열린 채로 기동한다.</b> 안전 장치가 오타로 열리는
     * 모양이라, 말하지 않으면 뜨지 않게 한다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"app.ai.storage.usage-state", "app.ai.storage.storage-state"})
    void aMissingGateRefusesToStart(String key) {
        Map<String, String> settings = new LinkedHashMap<>(baseSettings());
        settings.remove(key);

        assertTrue(failureOf(settings).contains(key.substring(key.lastIndexOf('.') + 1)));
    }

    /** 옛 키만 남은 설정도 같은 이유로 기동을 막는다 — Spring 은 모르는 키를 조용히 무시한다. */
    @Test
    void aRemovedKeyAloneRefusesToStart() {
        Map<String, String> settings = new LinkedHashMap<>(baseSettings());
        settings.remove("app.ai.storage.usage-state");
        settings.put("app.ai.storage.upload-enabled", "false");

        assertTrue(failureOf(settings).contains("usage-state"));
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
     * 상태와 쓰기 provider 는 한 사실을 두 곳에 적은 것이라 어긋난 채로 뜨면 안 된다.
     *
     * <p>{@code LOCAL_ACTIVE} 로 옮긴 운영자는 업로드가 MinIO 로 간다고 믿는데, provider 가 R2 로
     * 남아 있으면 방금 못 쓴다고 선언한 저장소로 계속 들어간다. 아무도 그 사실을 말해 주지 않는다.
     */
    @Test
    void aFallbackStateWithTheOldWriteProviderRefusesToStart() {
        String failure = failureOf(settingsWith("app.ai.storage.storage-state", "LOCAL_ACTIVE"));

        assertTrue(failure.contains("MINIO_LOCAL"), failure);
    }

    @Test
    void anR2StateWritingToTheFallbackRefusesToStart() {
        Map<String, String> settings = new LinkedHashMap<>(baseSettings());
        settings.put("app.ai.storage.active-write-provider", "MINIO_LOCAL");
        settings.put("app.ai.storage.providers.MINIO_LOCAL.endpoint", "http://localhost:9");
        settings.put("app.ai.storage.providers.MINIO_LOCAL.bucket", "fallback");
        settings.put("app.ai.storage.providers.MINIO_LOCAL.access-key-id", "k");
        settings.put("app.ai.storage.providers.MINIO_LOCAL.secret-access-key", "s");

        assertTrue(failureOf(settings).contains("R2"));
    }

    /** 짝이 맞으면 뜬다 — 경계에서 잘못 막으면 fallback 운영 자체가 불가능해진다. */
    @Test
    void aFallbackStateWithItsOwnProviderBinds() {
        Map<String, String> settings = new LinkedHashMap<>(baseSettings());
        settings.put("app.ai.storage.storage-state", "LOCAL_ACTIVE");
        settings.put("app.ai.storage.active-write-provider", "MINIO_LOCAL");
        settings.put("app.ai.storage.providers.MINIO_LOCAL.endpoint", "http://localhost:9");
        settings.put("app.ai.storage.providers.MINIO_LOCAL.bucket", "fallback");
        settings.put("app.ai.storage.providers.MINIO_LOCAL.access-key-id", "k");
        settings.put("app.ai.storage.providers.MINIO_LOCAL.secret-access-key", "s");

        assertEquals("MINIO_LOCAL", bind(settings).activeWriteProvider());
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
        settings.put("app.ai.storage.usage-state", "NORMAL");
        settings.put("app.ai.storage.storage-state", "R2_ACTIVE");
        settings.put("app.ai.storage.active-write-provider", "R2");
        settings.put("app.ai.storage.presign-ttl", "15m");
        settings.put("app.ai.storage.providers.R2.endpoint", "http://localhost:9");
        settings.put("app.ai.storage.providers.R2.bucket", "test-ai-documents");
        settings.put("app.ai.storage.providers.R2.access-key-id", "test-access-key");
        settings.put("app.ai.storage.providers.R2.secret-access-key", "test-secret-key");
        return settings;
    }

    private static AiStorageProperties bind(Map<String, String> settings) {
        return new Binder(new MapConfigurationPropertySource(settings))
                .bind("app.ai.storage", AiStorageProperties.class).get();
    }
}
