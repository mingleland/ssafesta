package com.example.ssafesta.booth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Checks that a placed object points at content <b>this booth owns</b> (spec 005 US2).
 *
 * <p>Without this the server would take the client's word for {@code configId}, and a crafted
 * request could hang another booth's AI agent on your wall — 헌법 16조 (do not trust the client) and
 * 헌법 17조 (vector isolation) would then rest on the editor behaving itself.
 *
 * <p>Read through {@link JdbcTemplate} rather than a JPA entity: spec 005 owns no part of the AI
 * model, and importing one to ask a single ownership question would tie the layout code to a schema
 * that spec 007 is still shaping.
 */
@Component
public class LayoutConfigResolver {

    private final JdbcTemplate jdbc;

    public LayoutConfigResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return {@code true} when the reference is known to belong to the booth,
     *         {@code false} when it demonstrably does not
     */
    boolean belongsToBooth(LayoutObjectType type, Integer configId, Long boothId) {
        if (configId == null) {
            return true;
        }
        return switch (type) {
            case AI_AGENT -> countAgents(configId, boothId) > 0;
            // AI_AGENT is the only kind that binds its content by configId, so it is the only kind
            // an ownership question fits. LAPTOP and SURVEY_KIOSK are answered a booth at a time,
            // before this call (see LayoutValidator): a laptop by booths.homepage_url (spec 016
            // C-01), a kiosk by whether the booth has a survey at all (spec 010 C-06 — the binding
            // is per booth, so configId is not an identifier there).
            //
            // The rest — VIDEO_SCREEN, PROJECT_PANEL, RECRUITMENT_BOARD, CONSULTATION_DESK,
            // LIKE_VOTE — are still not judged, and say so as CONFIG_UNVERIFIED rather than being
            // waved through: "not looked at" must not read as "verified".
            default -> true;
        };
    }

    /** Whether this type can be checked at all today. Drives the {@code CONFIG_UNVERIFIED} warning. */
    boolean isVerifiable(LayoutObjectType type) {
        return type == LayoutObjectType.AI_AGENT;
    }

    /**
     * Whether the booth has a homepage registered — what a {@code LAPTOP} is linked to (spec 016
     * C-01, contracts/homepage-api.md §3-1).
     *
     * <p>Counted rather than fetched so a boothId with no row answers {@code false} instead of
     * throwing. Validation has to be able to report warnings for a document whose booth is not
     * there (a unit test passes a bare id, and a booth can be deleted between edit and publish);
     * an exception would replace the warning list with a 500.
     */
    boolean boothHomepageRegistered(Long boothId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM booths WHERE id = ? AND homepage_url IS NOT NULL",
                Integer.class, boothId);
        return count != null && count > 0;
    }

    /**
     * Whether the booth has a survey at all — what a {@code SURVEY_KIOSK} shows (spec 010 C-06).
     *
     * <p>The kiosk's {@code configId} is not the key. A booth holds at most one survey
     * ({@code ux_surveys_booth}) and {@code GET /booths/{boothId}/survey/run} looks it up by booth,
     * so the only question that decides whether the kiosk has something to show is whether that row
     * is there.
     *
     * <p>Presence is the whole predicate. The run endpoint answers 200 for a survey with no
     * questions and for a closed one, and refuses an expired lease or an unpublished layout earlier
     * and under different codes — folding any of that in here would warn about kiosks that work.
     *
     * <p>Event surveys cannot be counted by accident: {@code booth_id} and {@code survey_key} are
     * mutually exclusive by check constraint, so filtering on {@code booth_id} excludes them.
     *
     * <p>Counted rather than fetched, for the same reason as {@link #boothHomepageRegistered}.
     */
    boolean boothSurveyRegistered(Long boothId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM surveys WHERE booth_id = ?", Integer.class, boothId);
        return count != null && count > 0;
    }

    private int countAgents(Integer agentId, Long boothId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ai_agents WHERE id = ? AND booth_id = ? AND status = 'ACTIVE'",
                Integer.class, agentId.longValue(), boothId);
        return count == null ? 0 : count;
    }
}
