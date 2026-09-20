-- Confirmed avatar part pricing replaces the temporary planning value from V16 (GitLab #120 §8-2,
-- product decision 2026-09-10). Ownership contracts are untouched: owned is still
-- (price = 0) OR an inventory row, judged only in InventoryService (#120 §2-1).
--
-- Order matters. Slot prices overwrite every row first, then the free set is set back to 0. That is
-- why the four rows that were free in V16 but are not in the free set below become paid:
-- Shared_Glasses.001 (2107176923), Shared_Hat.001 family (1001), F_Outfit.01 (512024387),
-- M_Outfit.01 (1164919338). Unity's CreateDefault leaves OUTFIT at 0 and never sets HAT or GLASSES,
-- so no default avatar wears them and no stored avatar_code turns unwearable.
UPDATE catalog_items SET price = 30
 WHERE item_type = 'AVATAR_PART' AND equip_slot IN ('HAIR', 'GLASSES');
UPDATE catalog_items SET price = 40
 WHERE item_type = 'AVATAR_PART' AND equip_slot IN ('TOP', 'BOTTOM', 'SHOES', 'HEAD');
UPDATE catalog_items SET price = 50
 WHERE item_type = 'AVATAR_PART' AND equip_slot IN ('OUTFIT', 'HAT');

-- Free set: 20 of 97. All eight HEAD rows are free because a face shape is the character itself,
-- not a costume; the other twelve are the minimum that dresses an avatar. OUTFIT, HAT and GLASSES
-- are fully paid. HEAD therefore has no row at its 40 coin slot price, which is expected.
UPDATE catalog_items SET price = 0
 WHERE item_type = 'AVATAR_PART'
   AND (equip_slot = 'HEAD' OR asset_key IN (
        '2119104882', '817213556', '463374136', '2055903654',
        '2018826405', '884777048', '1335128787', '411197940',
        '656603128', '1361764366',
        '1012069418', '1416813526'));
