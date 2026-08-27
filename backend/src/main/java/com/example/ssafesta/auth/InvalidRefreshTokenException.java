package com.example.ssafesta.auth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

public class InvalidRefreshTokenException extends ApiException {
    public InvalidRefreshTokenException() {
        super(ErrorCode.INVALID_MEMBER_TOKEN, "유효하지 않거나 만료된 로그인 세션입니다.");
    }
}
