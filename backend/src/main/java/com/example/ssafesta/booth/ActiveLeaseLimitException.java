package com.example.ssafesta.booth;

/** The member already holds a valid lease (spec 004 D01, FR-005: {@code ACTIVE_LEASE_LIMIT}). */
public class ActiveLeaseLimitException extends RuntimeException {

    private final Long existingLeaseId;

    public ActiveLeaseLimitException(Long existingLeaseId) {
        super("이미 임대 중인 부스가 있습니다 — leaseId=" + existingLeaseId);
        this.existingLeaseId = existingLeaseId;
    }

    public Long getExistingLeaseId() { return existingLeaseId; }
}
