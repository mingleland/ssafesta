package com.example.ssafesta.booth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/**
 * No such booth (spec 004 contracts: 404).
 *
 * <p>Carries its own {@link ErrorCode} rather than relying on each controller to translate it. In
 * spec 005 the layout endpoints let this escape and it came back as a 500 — the translation had
 * only ever existed in the two places spec 004 happened to write it.
 *
 * <p>The message names the id for the log; the client sees the code's default text.
 */
public class BoothNotFoundException extends ApiException {

    public BoothNotFoundException(Long boothId) {
        super(ErrorCode.BOOTH_NOT_FOUND, "존재하지 않는 부스입니다 — boothId=" + boothId);
    }
}
