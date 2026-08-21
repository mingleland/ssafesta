-- One booth per member (spec 004 C-01, invariant I-5).
--
-- BoothRepository.findByOwnerUserId returns Optional — the code asserts a member has at most one
-- booth — but nothing enforced it. Two rows would make that query throw
-- IncorrectResultSizeDataAccessException and lock the member out of leasing forever.
--
-- The same shape as T-110: an assumption held only by application code. Until V6 the assumption
-- was also reachable: seven parallel lease requests each found no booth and each created one.
-- The wallet lock now serializes the only creation path, but that is 004's accident, not a rule —
-- spec 005 will edit booths without touching the wallet. Constrain it while the table is small.

-- 1. Repoint leases of duplicate booths onto the surviving one.
--    Survivor: the booth holding an active lease > the one still attached to a slot > the oldest.
WITH survivor AS (
  SELECT DISTINCT ON (b.owner_user_id) b.owner_user_id, b.id
  FROM booths b
  ORDER BY b.owner_user_id,
           (EXISTS (SELECT 1 FROM booth_leases l WHERE l.booth_id = b.id AND l.status = 'ACTIVE')) DESC,
           (b.current_slot_id IS NOT NULL) DESC,
           b.id
)
UPDATE booth_leases l
SET booth_id = s.id
FROM booths b
JOIN survivor s ON s.owner_user_id = b.owner_user_id
WHERE l.booth_id = b.id AND b.id <> s.id;

-- 2. Drop the duplicates.
--    booths is referenced by 13 tables, but only booth_leases can hold rows today — the rest
--    belong to specs that are not built yet. If one of them ever does reference a duplicate, this
--    DELETE fails on the foreign key and the migration stops. That is deliberate: losing content
--    silently would be worse than a loud failure that a person resolves.
WITH survivor AS (
  SELECT DISTINCT ON (b.owner_user_id) b.owner_user_id, b.id
  FROM booths b
  ORDER BY b.owner_user_id,
           (EXISTS (SELECT 1 FROM booth_leases l WHERE l.booth_id = b.id AND l.status = 'ACTIVE')) DESC,
           (b.current_slot_id IS NOT NULL) DESC,
           b.id
)
DELETE FROM booths b
USING survivor s
WHERE b.owner_user_id = s.owner_user_id AND b.id <> s.id;

-- 3. The constraint. Not partial: a member has exactly one booth for the life of the account,
--    whether or not it is currently leased to a slot (C-01 — content follows the owner).
CREATE UNIQUE INDEX ux_booths_owner ON booths(owner_user_id);
