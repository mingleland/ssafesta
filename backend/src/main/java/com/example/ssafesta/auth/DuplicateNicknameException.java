package com.example.ssafesta.auth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/**
 * The nickname chosen during signup already belongs to someone.
 *
 * <p>The same event on the account screen has answered {@code NICKNAME_DUPLICATED} since spec 005
 * ({@code MyAccountController}); only the signup path was left carrying no code at all, so it fell
 * through to {@code GlobalExceptionHandler.handleUnexpected} and told the person picking a nickname
 * that the server had broken (#113 sweep).
 */
public class DuplicateNicknameException extends ApiException {
    public DuplicateNicknameException() {
        super(ErrorCode.NICKNAME_DUPLICATED, "이미 사용 중인 닉네임입니다.");
    }
}
