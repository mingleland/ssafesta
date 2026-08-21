package com.example.ssafesta.booth;

/** No such slot (spec 004 contracts: 404). */
public class SlotNotFoundException extends RuntimeException {

    public SlotNotFoundException(Long slotId) {
        super("존재하지 않는 슬롯입니다 — slotId=" + slotId);
    }
}
