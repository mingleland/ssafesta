package com.example.ssafesta.staff;

/**
 * 초대의 일생 (spec 011 FR-015·FR-017, C-07·C-10).
 *
 * <p>거절이 없다 — C-10 이 <b>Owner 취소만</b> 제공하기로 정했다. 초대받은 사람이 아무것도 하지
 * 않으면 48시간 뒤 {@link #EXPIRED} 다.
 */
public enum StaffInvitationStatus {
    PENDING,
    ACCEPTED,
    CANCELLED,
    EXPIRED
}
