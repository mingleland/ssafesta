package com.example.ssafesta.booth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/**
 * The member already holds a valid lease (spec 004 D01, FR-005: {@code ACTIVE_LEASE_LIMIT}).
 *
 * <p>A separate code from {@code BOOTH_SLOT_ALREADY_LEASED} on purpose: that one sends the visitor
 * to another slot, this one does not — the member has to wait for expiry (FR-017).
 */
public class ActiveLeaseLimitException extends ApiException {

    public ActiveLeaseLimitException(Long existingLeaseId) {
        // No id in the message — see BoothNotFoundException. Which lease is holding the member is
        // theirs to look up; the response says the rule, not the row.
        super(ErrorCode.ACTIVE_LEASE_LIMIT);
    }
}
