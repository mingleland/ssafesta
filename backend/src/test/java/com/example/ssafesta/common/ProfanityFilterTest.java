package com.example.ssafesta.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 금칙어 대조 (S15P21A604-792).
 *
 * <p>컨테이너를 띄우지 않는다 — 이 판정은 Spring 도 DB 도 Redis 도 보지 않는다. 채팅 경로에서
 * 실제로 방송이 막히는지는 {@code WorldChatIntegrationTest} 가 본다.
 */
class ProfanityFilterTest {

    /** 정규화가 우회 표기를 되돌린다 — 이게 없으면 목록은 그대로 쓴 사람만 잡는다. */
    @ParameterizedTest
    @ValueSource(strings = {"씨발", "씨---1---발", "F_U_C_K", "이 개새끼야", "존나 덥다"})
    void catchesProfanityIncludingEvasions(String text) {
        assertTrue(ProfanityFilter.contains(text), text + " 가 걸리지 않았습니다.");
    }

    /**
     * 금칙어를 품고 있지만 욕이 아닌 말.
     *
     * <p>정규화가 공백을 지우기 때문에 생기는 오탐이다. 목록을 손대는 대신 예외 목록으로 푼다 —
     * 목록을 깎으면 진짜 욕까지 함께 통과한다.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "고추장 파는 부스 어디예요", "한번 해보지 뭐", "새끼손가락 걸고 약속", "대마도 다녀왔어요",
            "analysis 자료 올렸어요", "document 확인 부탁드려요", "cocktail 부스 추천", "내년 학년 정보"})
    void letsOrdinaryTalkThrough(String text) {
        assertFalse(ProfanityFilter.contains(text), text + " 가 오탐으로 막혔습니다.");
    }

    /**
     * 예외를 지운 자리가 <b>없던 금칙어를 만들지 않는다</b>.
     *
     * <p>빈 문자열로 지우면 {@code 씨class발} 이 {@code 씨발} 이 된다. 공백 하나를 남겨 벽을 세운다.
     *
     * <p>대가는 알고 있다 — 예외 단어를 욕 사이에 끼우는 우회가 열린다. 우연히 막는 쪽보다 낫다.
     */
    @Test
    void removingAnAllowedTermNeverCreatesProfanity() {
        assertFalse(ProfanityFilter.contains("씨class발"));
    }

    /** 운영자 사칭 예약어는 여기 없다 — 채팅에서 "관리자" 를 막으면 안 된다. */
    @Test
    void reservedNamesAreNotProfanity() {
        assertFalse(ProfanityFilter.contains("관리자님 계신가요"));
    }
}
