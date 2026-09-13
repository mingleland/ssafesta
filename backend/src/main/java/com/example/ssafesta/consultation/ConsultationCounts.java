package com.example.ssafesta.consultation;

/**
 * 한 부스, 한 기간의 상담 건수 (spec 015 FR-004).
 *
 * @param requested 그 기간에 요청된 상담 전부 — 수락됐든 만료됐든
 * @param ended 그 중 실제로 상담이 이뤄지고 끝난 것. {@code requested} 와의 차이가 놓친 요청이다
 */
public record ConsultationCounts(long requested, long ended) { }
