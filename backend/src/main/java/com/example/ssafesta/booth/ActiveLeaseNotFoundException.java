package com.example.ssafesta.booth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/**
 * There is no valid lease of the caller's to hand back at the slot they named (spec 004 FR-020:
 * {@code ACTIVE_LEASE_NOT_FOUND}).
 *
 * <p>One exception for three situations — the member holds no lease, their lease is on a different
 * slot, or the one they had has just expired or already been returned. They are the same event to
 * the caller: the screen they acted from is stale, and re-reading the slot list fixes all three.
 * Which one it was is recorded in {@link BoothLeaseService}'s log rather than in the response.
 */
public class ActiveLeaseNotFoundException extends ApiException {

    public ActiveLeaseNotFoundException() {
        super(ErrorCode.ACTIVE_LEASE_NOT_FOUND);
    }
}
