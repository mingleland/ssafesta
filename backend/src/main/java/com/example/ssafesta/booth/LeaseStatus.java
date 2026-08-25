package com.example.ssafesta.booth;

/**
 * Lease lifecycle (spec 004). There is no extension and no cancellation (D05, D06), so the only
 * transition is {@code ACTIVE -> EXPIRED} and a re-lease creates a new row.
 *
 * <p>Note that {@code ACTIVE} alone does not mean the lease is still valid: a row whose
 * {@code ends_at} has passed is logically expired even before it is transitioned. Validity is
 * always decided by {@link BoothLeaseRepository}'s queries, never by comparing this field alone.
 */
public enum LeaseStatus {
    ACTIVE,
    EXPIRED
}
