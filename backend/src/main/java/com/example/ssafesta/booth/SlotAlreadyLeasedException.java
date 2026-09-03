package com.example.ssafesta.booth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/**
 * Someone else holds a valid lease on the slot
 * (spec 004 contracts: {@code BOOTH_SLOT_ALREADY_LEASED}).
 *
 * <p>Raised both by the up-front check and by the partial unique index losing race. In the race
 * case the whole transaction rolls back, which is exactly right here: the losing request must
 * leave nothing behind, coins included (SC-002).
 */
public class SlotAlreadyLeasedException extends ApiException {

    public SlotAlreadyLeasedException(Long slotId) {
        // No id in the message — see BoothNotFoundException.
        super(ErrorCode.BOOTH_SLOT_ALREADY_LEASED);
    }
}
