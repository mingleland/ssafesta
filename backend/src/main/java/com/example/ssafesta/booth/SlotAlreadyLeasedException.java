package com.example.ssafesta.booth;

/**
 * Someone else holds a valid lease on the slot
 * (spec 004 contracts: {@code BOOTH_SLOT_ALREADY_LEASED}).
 *
 * <p>Raised both by the up-front check and by the partial unique index losing race. In the race
 * case the whole transaction rolls back, which is exactly right here: the losing request must
 * leave nothing behind, coins included (SC-002).
 */
public class SlotAlreadyLeasedException extends RuntimeException {

    public SlotAlreadyLeasedException(Long slotId) {
        super("이미 임대 중인 슬롯입니다 — slotId=" + slotId);
    }
}
