package com.example.ssafesta.auth;

public class InvalidLoginTicketException extends RuntimeException {
    public InvalidLoginTicketException() {
        super("로그인 완료 요청이 만료되었거나 이미 사용되었습니다.");
    }
}
