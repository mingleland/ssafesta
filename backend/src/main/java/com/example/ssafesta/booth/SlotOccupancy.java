package com.example.ssafesta.booth;

/**
 * One slot with whatever occupies it, read in <b>one</b> statement (S15P21A604-682).
 *
 * <p>Two reads tear. {@code @Transactional(readOnly = true)} does not help — under
 * {@code READ COMMITTED} a snapshot is taken per <i>statement</i>, not per transaction, so a
 * withdrawal committing between "list the valid leases" and "load that booth" left the list holding
 * a lease whose booth was gone. The row that came out said {@code OCCUPIED}, carried the deleted
 * {@code boothId} (it comes from the lease), and had a null name — a booth a visitor could see but
 * not enter.
 *
 * <p>One statement sees one snapshot, so the three are all-or-nothing.
 *
 * @param slot  always present — the twelve slots are the shape of the response
 * @param lease {@code null} when the slot is free at this instant
 * @param booth {@code null} only if the lease points at a row that is not there. The foreign key
 *              {@code booth_leases.booth_id REFERENCES booths(id)} forbids that at rest, so it is a
 *              server fault rather than a state to render — see {@code BoothQueryService#listSlots}
 */
public record SlotOccupancy(BoothSlot slot, BoothLease lease, Booth booth) {
}
