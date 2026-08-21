package com.example.ssafesta.auth;

public class RegistrationConflictException extends RuntimeException {
    public RegistrationConflictException() {
        super("가입 처리 중 충돌이 발생했습니다. 다시 시도해 주세요.");
    }
}
