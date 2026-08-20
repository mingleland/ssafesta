package com.example.ssafesta.booth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/** The requester is neither the owner nor staff of this booth (spec 005 FR-012). */
public class BoothEditorForbiddenException extends ApiException {

    public BoothEditorForbiddenException() {
        super(ErrorCode.BOOTH_EDITOR_FORBIDDEN);
    }
}
