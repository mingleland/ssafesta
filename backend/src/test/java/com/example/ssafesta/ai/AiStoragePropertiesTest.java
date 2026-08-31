package com.example.ssafesta.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
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

        assertTrue(properties.uploadEnabled());
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

    @Test
    void aNonPositiveTtlRefusesToStart() {
        assertTrue(failureOf(settingsWith("app.ai.storage.presign-ttl", "0s"))
                .contains("presign-ttl"));
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
        settings.put("app.ai.storage.upload-enabled", "true");
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
