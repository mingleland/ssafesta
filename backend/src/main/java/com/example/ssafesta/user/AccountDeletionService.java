package com.example.ssafesta.user;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deletes the relational graph first; object storage/vector/OAuth adapters are invoked before this service in production. */
@Service
public class AccountDeletionService {
    private final JdbcTemplate jdbc;
    public AccountDeletionService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public void deleteUserGraph(Long userId) {
        jdbc.update("DELETE FROM account_status_histories WHERE user_id = ? OR actor_user_id = ?", userId, userId);
        jdbc.update("DELETE FROM survey_answer_options WHERE answer_id IN (SELECT sa.id FROM survey_answers sa JOIN survey_responses sr ON sr.id = sa.response_id WHERE sr.respondent_user_id = ? OR sr.survey_id IN (SELECT id FROM surveys WHERE created_by_user_id = ?))", userId, userId);
        jdbc.update("DELETE FROM survey_answers WHERE response_id IN (SELECT id FROM survey_responses WHERE respondent_user_id = ? OR survey_id IN (SELECT id FROM surveys WHERE created_by_user_id = ?))", userId, userId);
        jdbc.update("DELETE FROM survey_responses WHERE respondent_user_id = ? OR survey_id IN (SELECT id FROM surveys WHERE created_by_user_id = ?)", userId, userId);
        jdbc.update("DELETE FROM survey_options WHERE question_id IN (SELECT id FROM survey_questions WHERE survey_id IN (SELECT id FROM surveys WHERE created_by_user_id = ?))", userId);
        jdbc.update("DELETE FROM survey_questions WHERE survey_id IN (SELECT id FROM surveys WHERE created_by_user_id = ?)", userId);
        jdbc.update("DELETE FROM surveys WHERE created_by_user_id = ?", userId);
        jdbc.update("DELETE FROM consultation_messages WHERE sender_user_id = ? OR consultation_id IN (SELECT id FROM consultations WHERE booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?) OR visitor_user_id = ? OR staff_user_id = ?)", userId, userId, userId, userId);
        jdbc.update("DELETE FROM consultations WHERE booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?) OR visitor_user_id = ? OR staff_user_id = ?", userId, userId, userId);
        jdbc.update("DELETE FROM ai_document_chunks WHERE document_id IN (SELECT d.id FROM ai_documents d JOIN ai_agents a ON a.id = d.agent_id WHERE a.booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?) OR d.uploaded_by_user_id = ?)", userId, userId);
        jdbc.update("DELETE FROM ai_documents WHERE agent_id IN (SELECT id FROM ai_agents WHERE booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?)) OR uploaded_by_user_id = ?", userId, userId);
        jdbc.update("DELETE FROM ai_agents WHERE booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?)", userId);
        jdbc.update("DELETE FROM project_likes WHERE project_id IN (SELECT id FROM projects WHERE booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?))", userId);
        jdbc.update("DELETE FROM project_likes WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM projects WHERE booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?)", userId);
        jdbc.update("DELETE FROM booth_layout_published_versions WHERE booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?) OR published_by_user_id = ?", userId, userId);
        jdbc.update("DELETE FROM booth_layout_drafts WHERE booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?) OR updated_by_user_id = ?", userId, userId);
        jdbc.update("DELETE FROM booth_daily_metrics WHERE booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?)", userId);
        jdbc.update("DELETE FROM booth_visit_events WHERE booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?) OR visitor_user_id = ?", userId, userId);
        jdbc.update("DELETE FROM user_inventory_items WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM booth_staffs WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM booth_staffs WHERE booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?)", userId);
        jdbc.update("DELETE FROM staff_invitations WHERE invited_user_id = ? OR invited_by_user_id = ?", userId, userId);
        jdbc.update("DELETE FROM staff_invitations WHERE booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?)", userId);
        jdbc.update("DELETE FROM minigame_sessions WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM coin_ledger_entries WHERE wallet_id IN (SELECT id FROM wallets WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM booth_leases WHERE booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?) OR lessee_user_id = ?", userId, userId);
        jdbc.update("DELETE FROM booths WHERE owner_user_id = ?", userId);
        jdbc.update("DELETE FROM oauth_identities WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM wallets WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }
}
