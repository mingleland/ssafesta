package com.example.ssafesta.auth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/**
 * Two signups raced and the database refused the loser.
 *
 * <p>Distinct from {@link DuplicateNicknameException}: that one is a verdict on the value the person
 * typed, this one is a timing accident between the duplicate check and the insert, and retrying the
 * identical request is the right response to it.
 *
 * <p>As a plain {@code RuntimeException} it carried no code and was reported as a 500 (#113 sweep).
 */
public class RegistrationConflictException extends ApiException {
    public RegistrationConflictException() {
        super(ErrorCode.REGISTRATION_CONFLICT, "가입 처리 중 충돌이 발생했습니다. 다시 시도해 주세요.");
    }
}
