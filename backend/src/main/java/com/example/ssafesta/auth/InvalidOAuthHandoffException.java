package com.example.ssafesta.auth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/**
 * The one-time OAuth handoff was missing from Redis, already consumed, or past its 5 minutes.
 *
 * <p>Kept distinct from a missing cookie on purpose: one is retryable by logging in again, the
 * other means the browser never received the cookie at all (T-103).
 *
 * <p>The status now comes from {@link ErrorCode#OAUTH_HANDOFF_EXPIRED} rather than
 * {@code @ResponseStatus(GONE)} — the annotation produced a 410 with no error code in the body.
 */
public class InvalidOAuthHandoffException extends ApiException {

    public InvalidOAuthHandoffException() {
        super(ErrorCode.OAUTH_HANDOFF_EXPIRED, "OAuth 로그인 완료 요청이 만료되었거나 이미 사용되었습니다.");
    }
}
