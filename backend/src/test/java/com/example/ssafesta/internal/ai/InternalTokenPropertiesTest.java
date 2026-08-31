package com.example.ssafesta.internal.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static String failureOf(String tokens) {
        BindException failure = assertThrows(BindException.class, () -> bind(tokens));
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }

    private static InternalTokenProperties bind(String tokens) {
        return new Binder(new MapConfigurationPropertySource(
                Map.of("app.internal.ai-to-spring-tokens", tokens)))
                .bind("app.internal", InternalTokenProperties.class).get();
    }
}
