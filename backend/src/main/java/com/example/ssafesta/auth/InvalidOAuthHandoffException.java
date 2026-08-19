package com.example.ssafesta.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.GONE)
public class InvalidOAuthHandoffException extends RuntimeException {
    public InvalidOAuthHandoffException() {
        super("OAuth 로그인 완료 요청이 만료되었거나 이미 사용되었습니다.");
    }
}
