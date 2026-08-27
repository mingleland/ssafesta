package com.example.ssafesta.auth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/**
 * No usable session: the refresh cookie is absent, expired, or already spent.
 *
 * <p>All three are the same event to the person in front of the browser, so they carry one code —
 * the rule {@link ErrorCode} states about itself, that one event must not get two names.
 *
 * <p>As a plain {@code RuntimeException} this carried no code at all and fell through to
 * {@code GlobalExceptionHandler.handleUnexpected}, which answered every expired session with a 500
 * (#113).
 */
public class InvalidRefreshTokenException extends ApiException {
    public InvalidRefreshTokenException() {
        super(ErrorCode.INVALID_MEMBER_TOKEN, "유효하지 않거나 만료된 로그인 세션입니다.");
    }
}
