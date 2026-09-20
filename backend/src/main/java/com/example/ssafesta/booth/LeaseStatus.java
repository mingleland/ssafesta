package com.example.ssafesta.booth;

/**
 * Lease lifecycle (spec 004). There is no extension and no refund (D05, D06), so a lease only ever
 * leaves {@code ACTIVE} — by running out of time or by the tenant handing it back — and a re-lease
 * creates a new row.
 *
 * <p>{@code CANCELLED} is the tenant's early return (D12, FR-020). It is a separate word from
 * {@code EXPIRED} only so the history says which one happened: both go through the same release in
 * {@link BoothLeaseService}, freeing the slot, detaching the booth and disabling its AI documents in
 * one transaction. <b>Neither refunds the coin</b> (FR-021).
 *
 * <p>Note that {@code ACTIVE} alone does not mean the lease is still valid: a row whose
 * {@code ends_at} has passed is logically expired even before it is transitioned. Validity is
 * always decided by {@link BoothLeaseRepository}'s queries, never by comparing this field alone.
 */
public enum LeaseStatus {
    ACTIVE,
    EXPIRED,
    CANCELLED
}
