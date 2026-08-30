package com.example.ssafesta.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

/**
 * 세 상한이 <b>기동 시</b> 검증되는지 (spec 007 FR-018, C-13).
 *
 * <p>{@code per-booth-limit} 은 설정이면서 동시에 V15 유니크 인덱스이기도 하다 — 둘이 갈리면
 * 서비스 검사는 통과하고 인덱스가 500 을 던진다. 갈린 채로 뜨지 않게 한다.
 *
 * <p>{@code "100MB"} 같은 문자열 바인딩과 기본값은 생성자를 직접 부르는 것으로는 검증되지 않으므로
 * {@link Binder} 로 실제로 묶는다. 컨테이너 없이 도는 단위 테스트다.
 */
class AiAgentPropertiesTest {

    @Test
    void theShippedSettingsBind() {
        AiAgentProperties properties = bind(Map.of(
                "app.agent.per-booth-limit", "1",
                "app.agent.document-count-limit", "10",
                "app.agent.document-total-bytes", "100MB"));

        assertEquals(1, properties.perBoothLimit());
        assertEquals(10, properties.documentCountLimit());
        assertEquals(DataSize.ofMegabytes(100), properties.documentTotalBytes());
    }

    /** 2 로 올리려면 {@code ux_ai_agents_booth} 를 같은 변경에서 내려야 한다. */
    @Test
    void aPerBoothLimitOtherThanOneRefusesToStart() {
        assertTrue(failureOf(settingsWith("app.agent.per-booth-limit", "2"))
                .contains("per-booth-limit"));
    }

    @Test
    void aDocumentCountLimitBelowOneRefusesToStart() {
        assertTrue(failureOf(settingsWith("app.agent.document-count-limit", "0"))
                .contains("document-count-limit"));
    }

    @Test
    void aDocumentTotalBytesBelowOneRefusesToStart() {
        assertTrue(failureOf(settingsWith("app.agent.document-total-bytes", "0B"))
                .contains("document-total-bytes"));
    }

    /**
     * 빠진 설정은 {@code DataSize} 를 {@code null} 로 만든다 — {@code toBytes()} 보다 먼저 봐야
     * 메시지가 NPE 로 바뀌지 않는다.
     */
    @Test
    void aMissingDocumentTotalBytesRefusesToStart() {
        Map<String, String> settings = new LinkedHashMap<>(baseSettings());
        settings.remove("app.agent.document-total-bytes");

        assertTrue(failureOf(settings).contains("document-total-bytes"));
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
        settings.put("app.agent.per-booth-limit", "1");
        settings.put("app.agent.document-count-limit", "10");
        settings.put("app.agent.document-total-bytes", "100MB");
        return settings;
    }

    private static AiAgentProperties bind(Map<String, String> settings) {
        return new Binder(new MapConfigurationPropertySource(settings))
                .bind("app.agent", AiAgentProperties.class).get();
    }
}
