package com.example.ssafesta.auth;

public class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException() { super("유효하지 않거나 만료된 로그인 세션입니다."); }
}
