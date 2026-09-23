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
        // 설문은 부스 소유자를 따라 지운다 — created_by_user_id 가 아니다 (spec 010, S15P21A604-130).
        //
        // requireEditor 가 소유자와 스태프를 함께 허용하므로 스태프가 만든 설문은
        // created_by_user_id = 스태프다. 그 키로 지우면 두 방향이 다 틀린다: 스태프가 탈퇴하면
        // 남의 부스 설문과 응답이 사라지고, 소유자가 탈퇴하면 surveys 행이 남아
        // DELETE FROM booths 가 surveys_booth_id_fkey 로 실패한다 — 탈퇴 전체가 500 이 된다.
        // 이 줄들이 쓰이기 시작하는 것은 설문 쓰기 경로가 열리는 지금부터다.
        //
        // 서비스가 created_by_user_id 에 항상 부스 소유자를 쓰지만(불변식 I-5) 그것에 기대지
        // 않는다 — 삭제는 스키마가 허용하는 모든 값에 대해 옳아야 한다. 응답은 내가 남의 부스
        // 설문에 답한 것도 지워야 하므로 respondent_user_id 조건을 함께 둔다.
        jdbc.update("DELETE FROM survey_answer_options WHERE answer_id IN (SELECT sa.id FROM survey_answers sa JOIN survey_responses sr ON sr.id = sa.response_id WHERE sr.respondent_user_id = ? OR sr.survey_id IN (SELECT id FROM surveys WHERE booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?)))", userId, userId);
        jdbc.update("DELETE FROM survey_answers WHERE response_id IN (SELECT id FROM survey_responses WHERE respondent_user_id = ? OR survey_id IN (SELECT id FROM surveys WHERE booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?)))", userId, userId);
        jdbc.update("DELETE FROM survey_responses WHERE respondent_user_id = ? OR survey_id IN (SELECT id FROM surveys WHERE booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?))", userId, userId);
        jdbc.update("DELETE FROM survey_options WHERE question_id IN (SELECT id FROM survey_questions WHERE survey_id IN (SELECT id FROM surveys WHERE booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?)))", userId);
        jdbc.update("DELETE FROM survey_questions WHERE survey_id IN (SELECT id FROM surveys WHERE booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?))", userId);
        // 남의 부스에 내가 만든 설문은 지우지 않고 소유자에게 넘긴다. created_by_user_id 는
        // NOT NULL 이고 users(id) 를 NO ACTION 으로 참조하므로 그 행을 어떻게든 처리해야 하는데,
        // 지우는 쪽을 고르면 부스 소유자의 설문과 남들이 답한 응답이 제3자의 탈퇴로 사라진다.
        // 게다가 자식(문항·선택지·응답)은 위에서 부스 소유자 기준으로만 지워지므로, surveys 만
        // 작성자 기준으로 지우면 survey_questions_survey_id_fkey 에 걸려 탈퇴 전체가 실패한다.
        jdbc.update("UPDATE surveys s SET created_by_user_id = b.owner_user_id FROM booths b WHERE b.id = s.booth_id AND s.created_by_user_id = ? AND b.owner_user_id <> ?", userId, userId);
        jdbc.update("DELETE FROM surveys WHERE booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?)", userId);
        jdbc.update("DELETE FROM consultation_messages WHERE sender_user_id = ? OR consultation_id IN (SELECT id FROM consultations WHERE booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?) OR visitor_user_id = ? OR staff_user_id = ?)", userId, userId, userId, userId);
        jdbc.update("DELETE FROM consultations WHERE booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?) OR visitor_user_id = ? OR staff_user_id = ?", userId, userId, userId);
        // 문서 한 줄이면 청크·Job·staging 이 함께 지워진다 (V21). 이 자리에 청크 삭제가
        // 따로 있던 것은 V1 의 FK 가 NO ACTION 이라 순서가 강제됐기 때문이고, V21 가
        // ai_document_chunks.document_id 를 ON DELETE CASCADE 로 바꾸면서 그 이유가 없어졌다.
        // Job 은 document_id CASCADE 로, staging 은 job_id CASCADE 로 따라 지워진다.
        // 문서의 바이트도 DB 밖(객체 저장소)에 있다. 행이 사라지면 provider·bucket·key 좌표가 함께
        // 사라져 객체를 다시 찾을 방법이 없으므로, 게임 에셋과 같은 순서로 같은 트랜잭션에서 삭제
        // 큐로 먼저 옮긴다. 큐와 sweeper 는 provider·bucket·key 만 보고 어느 도메인의 객체인지
        // 묻지 않고, 문서와 에셋이 같은 ObjectStorage·같은 provider 어휘(app.ai.storage)를 쓴다.
        // 탈퇴 즉시 전체 하드삭제가 팀 결정이다 (docs/26, 2026-08-19 · S15P21A604-485).
        // s3_key 는 발급 트랜잭션 안에서만 NULL 이라 커밋된 행에는 값이 있지만, 조건을 적어 두면
        // 큐에 NOT NULL 위반이 들어갈 경로가 스키마와 무관하게 닫힌다.
        jdbc.update("INSERT INTO game_asset_delete_queue (provider, storage_bucket, object_key) SELECT storage_provider, storage_bucket, s3_key FROM ai_documents WHERE s3_key IS NOT NULL AND (agent_id IN (SELECT id FROM ai_agents WHERE booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?)) OR uploaded_by_user_id = ?) ON CONFLICT (provider, storage_bucket, object_key) DO NOTHING", userId, userId);
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
        // 이벤트 상점 구매 이력. admin_actions 와 달리 회원 자신의 거래 이력이라 coin_ledger_entries
        // 와 같은 관례로 탈퇴와 함께 지운다 — event_purchases.buyer_user_id 가 users(id) 를
        // 참조하므로 이 줄이 없으면 상점에서 구매한 적 있는 회원의 탈퇴가 FK 위반으로 실패한다.
        jdbc.update("DELETE FROM event_purchases WHERE buyer_user_id = ?", userId);
        // 피드백 제출 이력. 같은 이유로 탈퇴와 함께 지운다 — feedback_submissions.user_id 가
        // users(id) 를 참조하므로 이 줄이 없으면 피드백을 낸 회원의 탈퇴가 FK 위반으로 실패한다.
        jdbc.update("DELETE FROM feedback_submissions WHERE user_id = ?", userId);
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
        // 오락기 바인딩도 games 를 참조한다 (V27, S15P21A604-602). 이 줄이 없으면 자기 게임이
        // 오락기에 걸린 회원의 탈퇴가 FK 위반으로 실패한다. 기계 자체는 월드 고정물이라 남고,
        // 바인딩이 사라진 기계는 운영자가 다시 걸 때까지 MACHINE_NOT_FOUND 로 답한다.
        jdbc.update("DELETE FROM arcade_machine_bindings WHERE game_id IN (SELECT id FROM games WHERE owner_user_id = ?)", userId);
        // 자리를 잡은 사람도 users 를 참조한다 (V45). 자리 주인과 게임 주인이 다를 수 있으니 따로 푼다.
        jdbc.update("DELETE FROM arcade_machine_bindings WHERE owner_user_id = ?", userId);
        jdbc.update("DELETE FROM games WHERE owner_user_id = ?", userId);
        jdbc.update("DELETE FROM booth_leases WHERE booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?) OR lessee_user_id = ?", userId, userId);
        // 프로젝트 로고 업로드는 부스를 참조한다 (V42). 이 두 줄이 없으면 로고를 한 번이라도 올린
        // 회원의 탈퇴가 project_logo_uploads_booth_id_fkey 로 실패한다 (S15P21A604-979).
        // 바이트는 문서·게임 에셋과 같은 삭제 큐로 먼저 옮긴다.
        jdbc.update("INSERT INTO game_asset_delete_queue (provider, storage_bucket, object_key) SELECT provider, storage_bucket, object_key FROM project_logo_uploads WHERE booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?) ON CONFLICT (provider, storage_bucket, object_key) DO NOTHING", userId);
        jdbc.update("DELETE FROM project_logo_uploads WHERE booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?)", userId);
        jdbc.update("DELETE FROM booths WHERE owner_user_id = ?", userId);
        jdbc.update("DELETE FROM oauth_identities WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM wallets WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }
}
