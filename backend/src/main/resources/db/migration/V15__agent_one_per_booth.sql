-- One AI agent per booth (spec 007 C-13, data-model invariant A-1).
--
-- Same shape and same reason as V7__booth_one_per_owner and V14__project_one_per_booth: an
-- application-side "does one already exist?" check cannot hold this on its own, because two
-- concurrent POSTs each read "none" and each insert.
--
-- The limit also lives in configuration (app.agent.per-booth-limit) because C-13 says the number
-- must not be hardcoded. That leaves two places to disagree, so AiAgentProperties refuses to start
-- unless the configured limit is 1 — raising it means dropping this index in the same change.
--
-- No de-duplication step: ai_agents has had zero write paths since V1 created it. If a row somehow
-- exists in a duplicate pair this fails loudly, which is the correct outcome.

CREATE UNIQUE INDEX ux_ai_agents_booth ON ai_agents(booth_id);
