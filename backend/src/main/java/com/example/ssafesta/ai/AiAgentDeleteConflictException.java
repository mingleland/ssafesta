package com.example.ssafesta.ai;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/**
 * Something still points at this agent, so it cannot be deleted (spec 007 C-14).
 *
 * <p>The message names <b>what</b> is holding it, because the user's next action differs: remove it
 * from the layout, delete the documents, or wait for a consultation to end. A single "in use" would
 * leave them guessing.
 */
public class AiAgentDeleteConflictException extends ApiException {

    public AiAgentDeleteConflictException(String blockedBy) {
        super(ErrorCode.AGENT_DELETE_CONFLICT, blockedBy);
    }

    static AiAgentDeleteConflictException byLayout() {
        return new AiAgentDeleteConflictException(
                "배치에서 사용 중인 AI 직원은 삭제할 수 없습니다. 배치에서 먼저 제거해 주세요.");
    }

    static AiAgentDeleteConflictException byDocuments() {
        return new AiAgentDeleteConflictException(
                "등록된 문서가 있는 AI 직원은 삭제할 수 없습니다. 문서를 먼저 삭제해 주세요.");
    }

    static AiAgentDeleteConflictException byConsultations() {
        return new AiAgentDeleteConflictException(
                "상담 기록이 있는 AI 직원은 삭제할 수 없습니다.");
    }
}
