package com.example.ssafesta.world.chat;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/**
 * 도배 방지를 판정할 수 없다 — 방송하지 않고 보낸 사람에게만 알린다 (S15P21A604-687).
 *
 * <p>{@link ApiException} 에 cause 를 받는 생성자가 없어 {@code initCause} 로 붙인다. 원인을 버리면
 * Redis 가 왜 답하지 않았는지가 로그에서 사라진다.
 */
public class WorldChatUnavailableException extends ApiException {

    WorldChatUnavailableException(Throwable cause) {
        super(ErrorCode.CHAT_UNAVAILABLE);
        if (cause != null) {
            initCause(cause);
        }
    }
}
