package com.example.ssafesta.booth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/**
 * No such slot (spec 004 contracts: 404).
 *
 * <p>Carries its own {@link ErrorCode}, like {@link BoothNotFoundException}. It was thrown before
 * the error envelope existed and every caller translated it by hand — which is right until a caller
 * forgets, and then a missing slot reads as a broken server (T-113).
 */
public class SlotNotFoundException extends ApiException {

    public SlotNotFoundException(Long slotId) {
        // No id in the message — see BoothNotFoundException.
        super(ErrorCode.BOOTH_SLOT_NOT_FOUND);
    }
}
