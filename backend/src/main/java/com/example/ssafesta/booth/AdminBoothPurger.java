package com.example.ssafesta.booth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes everything hanging off an administrator's booth when its lease ends
 * (S15P21A604-905).
 *
 * <p><b>Only administrator booths.</b> A member's booth survives every lease — that is spec 004
 * C-01, and it is what stops the next tenant of a slot from inheriting the previous one's content.
 * An administrator's booth is the opposite case: it exists for one occupancy, a new one is created
 * for the next slot, and leaving the old rows behind would pile up dormant booths nobody can reach.
 *
 * <p><b>This deletes visitor-generated rows too</b> — survey responses, visit events, consultation
 * transcripts and project likes that visitors left in that booth. There is no way to make the booth
 * disappear and keep them: {@code booths} is referenced by fifteen tables and none of those foreign
 * keys cascade, so a delete that spared them would simply fail. Returning an administrator booth is
 * therefore a destructive act, and the log line below is the only trace it leaves.
 *
 * <p><b>The order is {@link com.example.ssafesta.user.AccountDeletionService}'s, narrowed to one
 * booth.</b> That sequence already had to satisfy the same fifteen foreign keys plus the
 * second-level ones (survey answers under responses, likes under projects, messages under
 * consultations), so re-deriving it here would only be a second chance to get it wrong. Statements
 * that belong to the account rather than the booth — wallets, inventories, OAuth — are left out.
 */
@Component
class AdminBoothPurger {

    private static final Logger log = LoggerFactory.getLogger(AdminBoothPurger.class);

    /**
     * Children first. Every statement takes the booth id — once, or twice where a table is reachable
     * both directly and through {@code ai_agents} — which is what lets the loop below stay a loop.
     */
    private static final String[] STATEMENTS = {
            // 설문 — 응답·답변은 방문자가 남긴 것이다. 부스가 사라지면 함께 사라진다.
            """
            DELETE FROM survey_answer_options WHERE answer_id IN (
              SELECT sa.id FROM survey_answers sa
              JOIN survey_responses sr ON sr.id = sa.response_id
              WHERE sr.survey_id IN (SELECT id FROM surveys WHERE booth_id = ?))
            """,
            """
            DELETE FROM survey_answers WHERE response_id IN (
              SELECT id FROM survey_responses
              WHERE survey_id IN (SELECT id FROM surveys WHERE booth_id = ?))
            """,
            "DELETE FROM survey_responses WHERE survey_id IN (SELECT id FROM surveys WHERE booth_id = ?)",
            """
            DELETE FROM survey_options WHERE question_id IN (
              SELECT id FROM survey_questions
              WHERE survey_id IN (SELECT id FROM surveys WHERE booth_id = ?))
            """,
            "DELETE FROM survey_questions WHERE survey_id IN (SELECT id FROM surveys WHERE booth_id = ?)",
            "DELETE FROM surveys WHERE booth_id = ?",

            // 상담 — 메시지가 먼저다.
            """
            DELETE FROM consultation_messages WHERE consultation_id IN (
              SELECT id FROM consultations WHERE booth_id = ?)
            """,
            "DELETE FROM consultations WHERE booth_id = ?",

            // AI — 청크·문서·작업이 에이전트와 부스 양쪽을 가리킨다.
            """
            DELETE FROM ai_document_chunks
            WHERE booth_id = ? OR agent_id IN (SELECT id FROM ai_agents WHERE booth_id = ?)
            """,
            """
            DELETE FROM ai_document_jobs
            WHERE booth_id = ? OR agent_id IN (SELECT id FROM ai_agents WHERE booth_id = ?)
            """,
            """
            DELETE FROM ai_documents
            WHERE booth_id = ? OR agent_id IN (SELECT id FROM ai_agents WHERE booth_id = ?)
            """,
            "DELETE FROM ai_agents WHERE booth_id = ?",

            // 프로젝트 — 좋아요가 먼저다.
            "DELETE FROM project_likes WHERE project_id IN (SELECT id FROM projects WHERE booth_id = ?)",
            "DELETE FROM project_logo_uploads WHERE booth_id = ?",
            "DELETE FROM projects WHERE booth_id = ?",

            // 레이아웃·계측·인력.
            "DELETE FROM booth_layout_published_versions WHERE booth_id = ?",
            "DELETE FROM booth_layout_drafts WHERE booth_id = ?",
            "DELETE FROM booth_daily_metrics WHERE booth_id = ?",
            "DELETE FROM booth_visit_events WHERE booth_id = ?",
            "DELETE FROM booth_staffs WHERE booth_id = ?",
            "DELETE FROM staff_invitations WHERE booth_id = ?",
    };

    private final JdbcTemplate jdbc;

    AdminBoothPurger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Deletes the booth's content in the caller's transaction.
     *
     * <p><b>The booth row and its lease are not touched here.</b> Those two are managed entities in
     * the caller's persistence context, so the caller removes them through JPA — deleting them
     * underneath Hibernate with JDBC would leave it holding rows that no longer exist and flushing
     * updates onto them at commit. Everything below is content no caller is holding.
     *
     * @param boothId an {@code admin_owned} booth whose lease has just ended
     */
    @Transactional
    void purge(Long boothId) {
        int rows = 0;
        for (String statement : STATEMENTS) {
            // 몇몇 문장은 ? 를 두 번 쓴다 — 셋을 세어 같은 값을 그만큼 넘긴다.
            int parameters = (int) statement.chars().filter(c -> c == '?').count();
            Object[] arguments = new Object[parameters];
            Arrays.fill(arguments, boothId);
            rows += jdbc.update(statement, arguments);
        }
        log.info("관리자 부스 콘텐츠 삭제 — boothId={}, 지운 행={}", boothId, rows);
    }
}
