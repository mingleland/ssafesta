package com.example.ssafesta.auth;

public class AccountUnavailableException extends RuntimeException {
    public AccountUnavailableException() { super("현재 로그인할 수 없는 계정입니다."); }
}
