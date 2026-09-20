package com.example.ssafesta.user;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/**
 * A nickname rejected by {@link NicknamePolicy}.
 *
 * <p>Nobody caught this before, so a forbidden nickname came back as a <b>500</b> — the user was
 * told the server broke when in fact their input was refused (spec 005 Phase 1).
 */
public class InvalidNicknameException extends ApiException {

    public InvalidNicknameException() {
        super(ErrorCode.NICKNAME_INVALID);
    }
}
