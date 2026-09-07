package com.example.ssafesta.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * FastAPI 주소가 <b>기동 시</b> 검증되는지 (S15P21A604-175).
 *
 * <p>컨테이너 없이 도는 단위 테스트다. 배포 계층에서 실제로 묶이는지는
 * {@code SharedConfigProfileTest} 가 본다.
 */
class AiProcessingPropertiesTest {

    @Test
    void anOriginBinds() {
        assertEquals("http://ai:8000", new AiProcessingProperties("http://ai:8000").baseUrl());
    }

    /**
     * 후행 슬래시는 다듬는다.
     *
     * <p>{@code RestClient} 가 baseUrl 뒤에 계약 경로를 붙이므로 그대로 두면 {@code //ai/v1/...}
     * 가 된다. 값을 거절할 이유는 없다 — 운영자가 쓴 주소로서 틀리지 않았다.
     */
    @Test
    void aTrailingSlashIsTrimmed() {
        assertEquals("https://ai.example.test",
                new AiProcessingProperties("https://ai.example.test/").baseUrl());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void aBlankOriginRefusesToStart(String baseUrl) {
        assertTrue(failureOf(baseUrl).contains("app.ai.processing.base-url"));
    }

    @Test
    void aNullOriginRefusesToStart() {
        assertTrue(failureOf(null).contains("app.ai.processing.base-url"));
    }

    /**
     * 스킴 없는 값은 거절한다.
     *
     * <p>{@code "ai:8000"} 은 비어 있지 않아 위 검사를 통과하고, {@code RestClient} 에서는 상대
     * 경로로 해석돼 엉뚱한 곳으로 간다 — 연결 오류가 아니라 조용한 오배송이 된다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"ai:8000", "//ai:8000", "ws://ai:8000"})
    void anOriginWithoutAnHttpSchemeRefusesToStart(String baseUrl) {
        assertTrue(failureOf(baseUrl).contains("http://"));
    }

    /**
     * 해석되지 않은 placeholder 는 따로 거절한다.
     *
     * <p>{@code "${AI_INTERNAL_BASE_URL}"} 은 비어 있지도 않다. 스킴 검사에도 걸리지만, 메시지가
     * "http:// 로 시작해야 한다" 이면 빠뜨린 변수를 찾는 데 도움이 되지 않는다 — 그래서 변수
     * 이름을 그대로 돌려준다. T-101 이 저장소 설정에서 낸 것과 같은 모양이다.
     */
    @Test
    void anUnresolvedPlaceholderNamesTheVariable() {
        String message = failureOf("${AI_INTERNAL_BASE_URL}");

        assertTrue(message.contains("AI_INTERNAL_BASE_URL"), message);
        assertTrue(message.contains("해석되지 않았습니다"), message);
    }

    private static String failureOf(String baseUrl) {
        return assertThrows(IllegalStateException.class,
                () -> new AiProcessingProperties(baseUrl)).getMessage();
    }
}
