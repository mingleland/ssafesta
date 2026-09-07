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
        // 문서 한 줄이면 청크·Job·staging 이 함께 지워진다 (V21). 이 자리에 청크 삭제가
        // 따로 있던 것은 V1 의 FK 가 NO ACTION 이라 순서가 강제됐기 때문이고, V21 가
        // ai_document_chunks.document_id 를 ON DELETE CASCADE 로 바꾸면서 그 이유가 없어졌다.
        // Job 은 document_id CASCADE 로, staging 은 job_id CASCADE 로 따라 지워진다.
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
        // spec 019 — 탈퇴는 Game, Draft, Published Version 을 모두 제거한다 (FR-040).
        // games.owner_user_id 가 users(id) 를 참조하므로 이 세 줄이 없으면 게임을 가진 회원의
        // 탈퇴가 FK 위반으로 실패한다. published version 이 games 보다 먼저 지워지는데,
        // 복합 FK 의 ON DELETE SET NULL (published_version) 이 포인터를 대신 비워 준다 (V13).
        // spec 019 — asset 의 바이트는 DB 밖(객체 저장소)에 있다. 행을 지우면 좌표가 사라지므로
        // 같은 트랜잭션에서 삭제 큐로 먼저 옮긴다 (game-asset-upload.md §7.1). 여기서 객체를 직접
        // 지우면 저장소 실패가 탈퇴 전체를 되돌리거나 커밋 뒤에 조용히 유실된다.
        jdbc.update("INSERT INTO game_asset_delete_queue (provider, storage_bucket, object_key) SELECT provider, storage_bucket, object_key FROM game_assets WHERE game_id IN (SELECT id FROM games WHERE owner_user_id = ?) OR created_by_user_id = ? ON CONFLICT (provider, storage_bucket, object_key) DO NOTHING", userId, userId);
        jdbc.update("DELETE FROM game_assets WHERE game_id IN (SELECT id FROM games WHERE owner_user_id = ?) OR created_by_user_id = ?", userId, userId);
        jdbc.update("DELETE FROM game_published_versions WHERE game_id IN (SELECT id FROM games WHERE owner_user_id = ?) OR published_by_user_id = ?", userId, userId);
        jdbc.update("DELETE FROM game_drafts WHERE game_id IN (SELECT id FROM games WHERE owner_user_id = ?) OR updated_by_user_id = ?", userId, userId);
        jdbc.update("DELETE FROM games WHERE owner_user_id = ?", userId);
        jdbc.update("DELETE FROM booth_leases WHERE booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?) OR lessee_user_id = ?", userId, userId);
        jdbc.update("DELETE FROM booths WHERE owner_user_id = ?", userId);
        jdbc.update("DELETE FROM oauth_identities WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM wallets WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }
}
