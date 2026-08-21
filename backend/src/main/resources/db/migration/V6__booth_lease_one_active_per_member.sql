-- One valid lease per member (spec 004 D01, FR-005).
--
-- The rule was enforced only by a read in BoothLeaseService, which is a check-then-act: seven
-- parallel requests on seven different slots each read "no active lease" and each created one
-- (T-110). ux_booth_leases_active_slot could not catch it because the slot ids differ.
--
-- Existing rows may already violate the rule, so clean up before constraining.

-- 1. Leases whose time has passed but that were never transitioned.
UPDATE booth_leases SET status = 'EXPIRED'
WHERE status = 'ACTIVE' AND ends_at <= now();

-- 2. Duplicates created by the race: keep the newest, expire the rest.
UPDATE booth_leases SET status = 'EXPIRED'
WHERE status = 'ACTIVE'
  AND id NOT IN (
    SELECT DISTINCT ON (lessee_user_id) id
    FROM booth_leases
    WHERE status = 'ACTIVE'
    ORDER BY lessee_user_id, starts_at DESC, id DESC
  );

-- 3. Booths left pointing at a slot they no longer hold. booths.current_slot_id is UNIQUE, so a
--    stale pointer would block the next lease of that slot.
UPDATE booths SET current_slot_id = NULL
WHERE current_slot_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM booth_leases l WHERE l.booth_id = booths.id AND l.status = 'ACTIVE'
  );

-- 4. The constraint itself. Partial, like ux_booth_leases_active_slot: only ACTIVE rows are
--    unique, so a member's lease history is unbounded.
--
--    NOTE: this index does not look at ends_at. A member whose expired lease is still ACTIVE
--    would be blocked forever, which is why BoothLeaseService transitions the member's own stale
--    leases inside the lease transaction (FR-017, the same treatment the slot index needed).
CREATE UNIQUE INDEX ux_booth_leases_active_lessee
  ON booth_leases(lessee_user_id) WHERE status = 'ACTIVE';
