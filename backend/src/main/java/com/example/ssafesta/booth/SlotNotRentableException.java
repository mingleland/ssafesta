package com.example.ssafesta.booth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/** The slot is not offered for member rental (spec 004 contracts: {@code BOOTH_SLOT_NOT_RENTABLE}). */
public class SlotNotRentableException extends ApiException {

    public SlotNotRentableException(Long slotId) {
        // No id in the message — see BoothNotFoundException.
        super(ErrorCode.BOOTH_SLOT_NOT_RENTABLE);
    }
}
