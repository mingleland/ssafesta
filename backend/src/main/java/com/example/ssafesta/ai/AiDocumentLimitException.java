package com.example.ssafesta.ai;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import org.springframework.util.unit.DataSize;

/**
 * The agent is at its document count or total-size ceiling (spec 007 FR-018).
 *
 * <p>Both limits are settings, so the message carries the number rather than the client hard-coding
 * 10 or 100MB — same reasoning as {@link AiAgentLimitException}.
 */
class AiDocumentLimitException extends ApiException {

    private AiDocumentLimitException(String message) {
        super(ErrorCode.DOCUMENT_LIMIT_EXCEEDED, message);
    }

    static AiDocumentLimitException byCount(int limit) {
        return new AiDocumentLimitException(
                "AI 직원당 문서는 " + limit + "개까지입니다. 기존 문서를 지운 뒤 올려 주세요.");
    }

    static AiDocumentLimitException byTotalSize(DataSize limit) {
        return new AiDocumentLimitException("AI 직원당 문서 총 용량은 " + limit.toMegabytes()
                + "MB까지입니다. 기존 문서를 지운 뒤 올려 주세요.");
    }
}
