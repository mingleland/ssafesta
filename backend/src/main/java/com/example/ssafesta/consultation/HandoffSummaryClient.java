package com.example.ssafesta.consultation;

import org.springframework.stereotype.Component;

/**
 * 요청 생성 시점의 AI 대화 요약을 조달한다 (spec 011 FR-008·FR-012).
 *
 * <p><b>지금은 항상 {@code null} 이다.</b> 요약 생성은 FastAPI 몫이고 그 API 는
 * {@code S15P21A604-139} 에서 온다. 기다리지 않는 이유는 확정 계약이 이미
 * {@code handoffSummary: string | null} 이고 "요약 생성 실패도 {@code null} 일 뿐 요청은
 * 진행된다" 를 담고 있기 때문이다 — FastAPI 가 없는 동안의 동작이 이미 정의돼 있으므로, 없는
 * 동안이 곧 그 경로다 (헌법 3조, research R-02).
 *
 * <p>{@code -139} 가 도착하면 이 구현만 FastAPI 호출로 갈아 끼운다. 호출 쪽은 반환값을 그대로
 * 저장하므로 바뀌지 않는다. <b>실패·timeout 도 {@code null} 로 흘려야 한다</b> — 요약이 없어서
 * 상담을 못 여는 상태를 만들면 AI 장애가 사람 상담을 멈추게 된다.
 */
@Component
public class HandoffSummaryClient {

    /**
     * @param conversationId 방문자가 보고 있던 AI 대화. 대화 없이 바로 요청하면 {@code null}
     * @return 요약, 또는 조달할 수 없으면 {@code null}
     */
    public String summarize(String conversationId) {
        return null;
    }
}
