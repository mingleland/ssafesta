package com.example.ssafesta.user;

public class InvalidNicknameException extends RuntimeException {
    public InvalidNicknameException() {
        super("사용할 수 없는 닉네임입니다.");
    }
}
