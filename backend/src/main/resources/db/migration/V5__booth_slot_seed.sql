-- USER_RENTAL booth slots (spec 004 FR-001).
-- Slots are operational data, not user-generated: seeding them here keeps every environment
-- identical and makes tests reproducible.
-- Floor 11 follows the spec's "1차는 11층" note; the floor is still undecided by 기획 (plan U-03),
-- so a later migration replaces the data without touching code.
INSERT INTO booth_slots (slot_code, floor_no, slot_type, status) VALUES
  ('F11-R01', 11, 'USER_RENTAL', 'AVAILABLE'),
  ('F11-R02', 11, 'USER_RENTAL', 'AVAILABLE'),
  ('F11-R03', 11, 'USER_RENTAL', 'AVAILABLE'),
  ('F11-R04', 11, 'USER_RENTAL', 'AVAILABLE'),
  ('F11-R05', 11, 'USER_RENTAL', 'AVAILABLE'),
  ('F11-R06', 11, 'USER_RENTAL', 'AVAILABLE'),
  ('F11-R07', 11, 'USER_RENTAL', 'AVAILABLE')
ON CONFLICT (slot_code) DO NOTHING;
