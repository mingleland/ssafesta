package com.example.ssafesta.ai;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/**
 * The booth is already at its AI staff limit (spec 007 C-13).
 *
 * <p>Named for the limit rather than for "already exists" because the number is a setting: the
 * message carries it, so a client that hard-coded 1 does not go silently wrong when it changes.
 * Same reasoning as {@code GAME_LIMIT_EXCEEDED}.
 */
public class AiAgentLimitException extends ApiException {

    public AiAgentLimitException(int limit) {
        super(ErrorCode.AGENT_LIMIT_EXCEEDED,
                "부스당 AI 직원은 " + limit + "명까지입니다. 수정으로 변경해 주세요.");
    }
}
