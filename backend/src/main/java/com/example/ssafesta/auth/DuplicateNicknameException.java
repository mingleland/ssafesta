package com.example.ssafesta.auth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

public class DuplicateNicknameException extends ApiException {
    public DuplicateNicknameException() {
        super(ErrorCode.NICKNAME_DUPLICATED, "이미 사용 중인 닉네임입니다.");
    }
}
