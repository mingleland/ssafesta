package com.example.ssafesta.consultation;

/**
 * 상담 요청 겸 세션의 일생 (spec 011 FR-010, C-01).
 *
 * <p>요청과 세션이 한 행이다 — 수락이 같은 행의 상태를 옮긴다. 둘로 나누면 "정확히 한 명만
 * 수락" 판정이 두 표에 걸치고, `requestId` 와 `sessionId` 가 같은 값이라는 계약도 설명이 길어진다
 * (research R-06).
 */
public enum ConsultationStatus {

    REQUESTED,
    ACCEPTED,
    ENDED,
    /** 10분 안에 아무도 수락하지 않았다 (C-01). */
    EXPIRED,
    /**
     * 방문자가 스스로 거뒀다.
     *
     * <p>{@code ENDED} 로 뭉치지 않는 이유는 대기열에서 사라진 <b>까닭</b>이 다르기 때문이다 —
     * 직원 화면은 취소와 만료에 다른 이벤트를 보내고, 나중에 "요청이 얼마나 취소되는가" 를 물을
     * 때 그 구분이 없으면 답할 수 없다.
     */
    CANCELLED,
    /** 어휘에 두되 P1 에서 만들지 않는다 — 확정 계약에 거절 경로가 없다. */
    REJECTED
}
