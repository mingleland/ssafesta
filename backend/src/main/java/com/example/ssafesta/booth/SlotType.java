package com.example.ssafesta.booth;

/**
 * Slot categories. Only {@link #USER_RENTAL} can be leased by a member (spec 004 FR-002).
 *
 * <p>{@link BoothSlot#isRentable()} is the single gate, so adding a category here is enough to keep
 * it out of leasing — no new check anywhere else.
 */
public enum SlotType {
    USER_RENTAL,
    ADMIN,
    /**
     * The festival's own event slot (S15P21A604-615, GitLab #170).
     *
     * <p>Slot 1 is it. Unity had been hard-coding "슬롯 1 = 이벤트 부스" in the scene, which goes
     * silently wrong the day the slot number changes; the slot list already carries {@code type},
     * so saying it here is what lets the client stop guessing.
     *
     * <p><b>The event content does not live in a booth.</b> The prize shop and its survey are found
     * outside the booth graph (by {@code surveyKey}, GitLab #173) precisely because a booth would
     * require a fake user, lease and published layout — any one of which expiring turns the event
     * into a silent 404. This category marks the ground the machine stands on, nothing more.
     */
    EVENT
}
