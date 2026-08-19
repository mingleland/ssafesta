package com.example.ssafesta.auth;

public class InvalidRegistrationTicketException extends RuntimeException {
    public InvalidRegistrationTicketException() { super("가입 완료 요청이 만료되었거나 유효하지 않습니다."); }
}
