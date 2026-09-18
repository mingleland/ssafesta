package com.example.ssafesta.world.chat;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/**
 * 너무 빠르거나 너무 오래 계속 보냈다 (S15P21A604-687, S15P21A604-891).
 *
 * <p>{@code retryAfterMs} 를 싣는다. <b>대기 시간을 아는 쪽은 서버뿐이다</b> — 벌칙 단계와 창의
 * 상태가 서버에 있어 클라이언트는 자기 계산으로 그 값을 알 수 없다 (GitLab #223).
 */
public class WorldChatTooFastException extends ApiException {

    private final long retryAfterMs;

    WorldChatTooFastException(long retryAfterMs) {
        super(ErrorCode.CHAT_TOO_FAST);
        this.retryAfterMs = retryAfterMs;
    }

    public long retryAfterMs() {
        return retryAfterMs;
    }
}
