package com.example.ssafesta.internal.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * 서비스 토큰 설정이 <b>기동 시</b> 검증되는지 (GitLab #102, 2026-08-25 확정).
 *
 * <p>토큰은 Secret 이라 조용히 정규화하지 않는다. 앞뒤 공백이 붙은 값을 서버가 말없이 다듬으면
 * 설정한 값과 실제로 비교하는 값이 달라지고, 그 차이는 런타임 401 로만 드러난다. 여기서
 * 기동을 실패시켜 배포 시점에 보이게 한다.
 *
 * <p>AI 쪽 {@code _parse_csv}({@code festa-ai/app/core/config.py:17})는
 * {@code [item.strip() for item in value.split(",") if item.strip()]} 로 trim 과 빈 항목 제거를
 * 조용히 한다. 즉 Spring 이 더 엄격하다. 이 방향이라야 <b>Spring 이 받아들인 값에서는 AI 의
 * strip·filter 가 항등 연산</b>이 되어 양쪽이 반드시 같은 목록을 갖는다. 어긋난 설정은 런타임
 * 인증 실패가 아니라 Spring 부팅 실패로 즉시 드러난다.
 *
 * <p>컨테이너 없이 도는 단위 테스트다.
 */
class InternalTokenPropertiesTest {

    @Test
    void oneTokenBinds() {
        InternalTokenProperties properties = bind("only-one");

        assertEquals(List.of("only-one"), properties.aiToSpringTokenList());
    }

    /** 회전 중에는 둘이 함께 유효하다 — 송신은 첫 값, 검증은 목록 전체. */
    @Test
    void twoTokensBindInOrder() {
        InternalTokenProperties properties = bind("new-token,old-token");

        assertEquals(List.of("new-token", "old-token"), properties.aiToSpringTokenList());
    }

    /**
     * 빈 항목은 전부 거절한다.
     *
     * <p>{@code "a,"} 가 이 목록의 핵심이다 — 인자 없는 {@code String.split(",")} 은 <b>후행 빈
     * 항목을 버려서</b> 정상 토큰 하나로 통과시킨다. {@code split(",", -1)} 이라야 잡힌다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"a,,b", ",a", "a,", ",", ",,"})
    void anEmptyElementRefusesToStart(String tokens) {
        assertTrue(failureOf(tokens).contains("ai-to-spring-tokens"));
    }

    /**
     * 앞뒤 공백은 다듬지 않고 거절한다.
     *
     * <p>이 케이스는 <b>원시 {@code String} 바인딩이라야 잡힌다.</b> {@code List<String>} 으로
     * 받으면 Spring 이 콤마로 자르면서 각 항목을 먼저 trim 해 버려 검증이 볼 값이 이미 정규화된
     * 뒤가 된다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"a, b", " a,b", "a ,b", "a,b "})
    void surroundingWhitespaceRefusesToStart(String tokens) {
        assertTrue(failureOf(tokens).contains("ai-to-spring-tokens"));
    }

    /** 같은 값 둘은 회전이 아니다 — 설정 실수이므로 조용히 하나로 접지 않는다. */
    @Test
    void aDuplicateRefusesToStart() {
        assertTrue(failureOf("same,same").contains("ai-to-spring-tokens"));
    }

    /** 회전에 필요한 것은 옛것 하나뿐이다. 셋이면 어느 것이 현재인지가 값으로 표현되지 않는다. */
    @Test
    void moreThanTwoRefusesToStart() {
        assertTrue(failureOf("a,b,c").contains("ai-to-spring-tokens"));
    }

    @Test
    void anEmptyValueRefusesToStart() {
        assertTrue(failureOf("").contains("ai-to-spring-tokens"));
    }

    // ── 송신 방향도 같은 검증을 받는다 (S15P21A604-175) ──────────────────────────────

    /** 검증은 방향별로 갈리지 않는다. 같은 실수가 어느 칸에서든 같은 기동 실패다. */
    @ParameterizedTest
    @ValueSource(strings = {"a,,b", "a,", "a, b", "same,same", "a,b,c", ""})
    void theSendingDirectionRefusesTheSameMistakes(String tokens) {
        assertTrue(outboundFailureOf(tokens).contains("spring-to-ai-tokens"));
    }

    /** 송신은 첫 값 하나다 — 나머지는 양쪽이 회전을 준비하는 동안 존재한다. */
    @Test
    void theSentTokenIsTheFirstOne() {
        InternalTokenProperties properties = bind("in-a", "out-new,out-old", "infra-a");

        assertEquals("out-new", properties.springToAiToken());
        assertEquals(List.of("out-new", "out-old"), properties.springToAiTokenList());
    }

    /**
     * 해석되지 않은 placeholder 는 거절한다.
     *
     * <p>이 케이스가 위 검증 전부를 통과한다 — {@code "${INTERNAL_SPRING_TO_AI_TOKENS}"} 는 빈
     * 항목도, 공백도, 중복도, 셋도 아니다. 그대로 두면 그 문자열이 토큰이 되어 그 방향의 모든
     * 호출이 영구히 401 이 되고, 변수 하나를 빠뜨린 배포가 상대 서비스 장애처럼 보인다. 저장소
     * 설정에서 T-101 이 낸 것과 같은 모양이다.
     */
    @Test
    void anUnresolvedPlaceholderRefusesToStart() {
        assertTrue(failureOf("${INTERNAL_AI_TO_SPRING_TOKENS}").contains("해석되지 않았습니다"));
        assertTrue(outboundFailureOf("${INTERNAL_SPRING_TO_AI_TOKENS}")
                .contains("해석되지 않았습니다"));
        assertTrue(infraFailureOf("${INTERNAL_INFRA_TO_SPRING_TOKENS}")
                .contains("해석되지 않았습니다"));
    }

    // ── Infra 방향과 집합 간 분리 (S15P21A604-500) ──────────────────────────────────

    /** 세 번째 방향도 같은 검증을 받는다. */
    @ParameterizedTest
    @ValueSource(strings = {"a,,b", "a,", "a, b", "same,same", "a,b,c", ""})
    void theInfraDirectionRefusesTheSameMistakes(String tokens) {
        assertTrue(infraFailureOf(tokens).contains("infra-to-spring-tokens"));
    }

    @Test
    void theInfraTokensBindInOrder() {
        InternalTokenProperties properties = bind("in-a", "out-a", "infra-new,infra-old");

        assertEquals(List.of("infra-new", "infra-old"), properties.infraToSpringTokenList());
    }

    /**
     * 세 집합 중 어느 둘이라도 값을 공유하면 기동하지 않는다.
     *
     * <p>집합마다 상대와 scope 가 다르다 — AI 방향은 사용자 PDF 를 파싱하는 Worker 옆에 있고,
     * Infra 방향은 reconcile endpoint 하나만 연다. 한 값이 두 집합에 들어가면 그 순간 두 scope 가
     * 하나로 합쳐지고 넓은 쪽 노출이 좁은 쪽에 닿는다.
     *
     * <p>지금까지 이 검사는 AI 쪽에만 있었다({@code festa-ai/app/core/config.py}). Spring 은 두
     * 칸에 같은 값을 붙여 넣어도 떴다.
     */
    @Test
    void aValueSharedByTwoSetsRefusesToStart() {
        assertOverlapRefused(bindingOf("shared", "shared", "infra-only"),
                "ai-to-spring-tokens", "spring-to-ai-tokens");
        assertOverlapRefused(bindingOf("shared", "outbound-only", "shared"),
                "ai-to-spring-tokens", "infra-to-spring-tokens");
        assertOverlapRefused(bindingOf("inbound-only", "shared", "shared"),
                "spring-to-ai-tokens", "infra-to-spring-tokens");
    }

    /** 회전 중 두 값 중 하나만 겹쳐도 겹친 것이다. */
    @Test
    void anOverlapInOnlyOneRotationSlotRefusesToStart() {
        assertOverlapRefused(bindingOf("new-a,shared", "out-a", "shared,infra-old"),
                "ai-to-spring-tokens", "infra-to-spring-tokens");
    }

    /** 메시지에 토큰 값이 들어가면 기동 로그가 Secret 을 흘린다 — 경로와 개수만 남긴다. */
    private static void assertOverlapRefused(org.junit.jupiter.api.function.Executable binding,
                                             String firstPath, String secondPath) {
        String message = rootMessageOf(binding);
        assertTrue(message.contains(firstPath), message);
        assertTrue(message.contains(secondPath), message);
        assertFalse(message.contains("shared"), message);
    }

    private static org.junit.jupiter.api.function.Executable bindingOf(String aiToSpring,
                                                                      String springToAi,
                                                                      String infraToSpring) {
        return () -> bind(aiToSpring, springToAi, infraToSpring);
    }

    private static String failureOf(String tokens) {
        return rootMessageOf(() -> bind(tokens));
    }

    private static String outboundFailureOf(String tokens) {
        return rootMessageOf(() -> bind("valid-inbound", tokens, "valid-infra"));
    }

    private static String infraFailureOf(String tokens) {
        return rootMessageOf(() -> bind("valid-inbound", "valid-outbound", tokens));
    }

    private static String rootMessageOf(org.junit.jupiter.api.function.Executable binding) {
        BindException failure = assertThrows(BindException.class, binding);
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }

    /** The other two values are valid, so a refusal can only be about the inbound argument. */
    private static InternalTokenProperties bind(String tokens) {
        return bind(tokens, "valid-outbound", "valid-infra");
    }

    private static InternalTokenProperties bind(String aiToSpring, String springToAi,
                                                String infraToSpring) {
        return new Binder(new MapConfigurationPropertySource(Map.of(
                "app.internal.ai-to-spring-tokens", aiToSpring,
                "app.internal.spring-to-ai-tokens", springToAi,
                "app.internal.infra-to-spring-tokens", infraToSpring)))
                .bind("app.internal", InternalTokenProperties.class).get();
    }
}
