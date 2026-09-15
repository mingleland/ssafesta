-- One project per booth (spec 009 C-01, BE/data-model.md invariant I-1).
--
-- C-01 is an invariant, not a UI rule. An application-side "does one already exist?" check cannot
-- hold it on its own: two concurrent POSTs each read "none" and each insert. V7__booth_one_per_owner
-- was written for exactly this shape of bug (see its header, and T-110) — the difference here is
-- that we get to add the constraint before any row exists rather than after.
--
-- No de-duplication step, unlike V7. `projects` has had zero write paths since V1 created it
-- (getName/getBoothId had no callers), so the table is empty. If it somehow is not, this index
-- creation fails and the migration stops — which is the correct outcome. Losing exhibition content
-- silently would be worse than a loud failure a person resolves.
--
-- Not partial: a booth has at most one project for the life of the booth, whether or not it is
-- currently leased to a slot. Content follows the owner (spec 004 C-01 / 009 C-04), so an expired
-- lease must not free the slot for a second project row.

CREATE UNIQUE INDEX ux_projects_booth ON projects(booth_id);
