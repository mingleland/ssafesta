package com.example.ssafesta.booth;

/** The slot is not offered for member rental (spec 004 contracts: {@code BOOTH_SLOT_NOT_RENTABLE}). */
public class SlotNotRentableException extends RuntimeException {

    public SlotNotRentableException(Long slotId) {
        super("임대할 수 없는 슬롯입니다 — slotId=" + slotId);
    }
}
