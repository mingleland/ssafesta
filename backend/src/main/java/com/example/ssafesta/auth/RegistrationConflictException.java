package com.example.ssafesta.auth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

public class RegistrationConflictException extends ApiException {
    public RegistrationConflictException() {
        super(ErrorCode.REGISTRATION_CONFLICT, "가입 처리 중 충돌이 발생했습니다. 다시 시도해 주세요.");
    }
}
