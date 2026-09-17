-- S15P21A604-151: the one-hour WebSocket reminder is best-effort, but it must not repeat every scan.
-- A persisted claim survives an application restart and lets concurrently running instances skip the
-- same lease. This is notification delivery state, not lease validity: ACTIVE + ends_at remains
-- authoritative for access everywhere else.
ALTER TABLE booth_leases ADD COLUMN expiry_warning_sent_at TIMESTAMPTZ;
