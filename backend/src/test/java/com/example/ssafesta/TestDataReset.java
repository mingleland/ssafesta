package com.example.ssafesta;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * 테스트 클래스 사이에 DB·Redis 를 테스트 시작 상태로 되돌린다 (GitLab #261 §7, S15P21A604-945).
 *
 * <p>예전에는 격리를 컨테이너가 했다. 컨텍스트마다 postgres 가 새로 떠서 그룹 사이에 칸막이가
 * 있었다. 컨테이너를 JVM 당 하나로 줄이면({@link TestcontainersConfiguration}) 그 칸막이가
 * 사라지므로, 같은 일을 데이터 리셋으로 한다 — #261 §7 이 요구하는 방향이다.
 *
 * <p><b>시드 표는 건드리지 않는다.</b> 마이그레이션이 값을 넣는 표가 다섯 개 있고
 * ({@code booth_slots} · {@code surveys} · {@code survey_questions} · {@code survey_options} ·
 * {@code catalog_items}), 지웠다 다시 넣는 방식은 택하지 않았다. 이유가 셋이다. ① {@code
 * booth_slots} 는 id 1~12 를 앵커로 단언하는 테스트가 있는데(BoothLayoutApiIntegrationTest)
 * 재삽입하면 IDENTITY 가 그 번호를 보장하지 않는다. ② 시드 정본이 마이그레이션과 테스트 두
 * 곳으로 갈려, 마이그레이션이 시드를 바꿔도 테스트 사본은 따라오지 않는다 — CI 는 초록인데
 * 실제와 다른 데이터로 검증하게 된다. ③ {@code catalog_items} 는 아바타 파츠 카탈로그라
 * 비우면 아바타 계열이 전부 죽는다.
 *
 * <p>{@code admin_actions} 는 시드 INSERT 가 있지만(V36) {@code users WHERE is_master} 를
 * 골라 넣는 문장이고 마이그레이션 시점의 테스트 DB 에는 회원이 없어 0행이다. 그래서 비운다.
 *
 * <p>첫 클래스 앞에서는 아직 Flyway 가 돌기 전이라 표가 없다. 그때는 아무것도 하지 않는다 —
 * 지울 것도 없다.
 */
public class TestDataReset implements BeforeAllCallback {

    /** 자동 등록이 실제로 걸렸는지 테스트가 확인할 수 있게 둔다 (TestDataResetIntegrationTest). */
    static volatile int resets;

    /**
     * 테스트가 만드는 행만 들어가는 표. 시드 표 다섯 개는 여기 없다.
     *
     * <p>순서는 상관없다 — FK 는 아래에서 잠시 끈다.
     */
    private static final String[] TABLES = {
            "account_status_histories", "admin_actions", "ai_agents", "ai_document_chunk_staging",
            "ai_document_chunks", "ai_document_jobs", "ai_documents", "arcade_machine_bindings",
            "avatar_presets", "booth_daily_metrics", "booth_layout_drafts",
            "booth_layout_published_versions", "booth_leases", "booth_staffs", "booth_visit_events",
            "booths", "coin_ledger_entries", "coin_reconciliation_runs", "consultation_messages",
            "consultations", "event_prizes", "event_purchases", "game_asset_delete_queue",
            "game_assets", "game_drafts", "game_published_versions", "games", "minigame_sessions",
            "oauth_identities", "project_likes", "project_logo_uploads", "projects",
            "staff_invitations", "storage_reconciliation_log", "survey_answer_options",
            "survey_answers", "survey_responses", "user_inventory_items", "users", "wallets"};

    /** V5 가 1~7, V12 가 8~12 를 명시 id 로 넣는다. 그 위는 테스트가 만든 것이다. */
    private static final int SEEDED_SLOT_MAX_ID = 12;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                TestcontainersConfiguration.POSTGRES.getJdbcUrl(),
                TestcontainersConfiguration.POSTGRES.getUsername(),
                TestcontainersConfiguration.POSTGRES.getPassword())) {
            if (!migrated(connection)) {
                return;
            }
            try (Statement statement = connection.createStatement()) {
                // FK 를 잠시 끈다. CASCADE 를 쓰면 지우면 안 되는 시드 표까지 딸려 간다 —
                // surveys 가 booths·users 를 참조하므로 users 를 CASCADE 로 비우는 순간
                // 이벤트 설문 시드(V29)가 사라진다.
                statement.execute("SET session_replication_role = replica");
                // TRUNCATE 가 아니라 DELETE 다. TRUNCATE 는 session_replication_role 과 무관하게
                // "참조하는 표를 같이 비우거나 CASCADE 하라" 고 거절하는데, 여기서 참조하는 쪽이
                // 지우면 안 되는 시드 표다 (surveys → booths). replica 모드에서 FK 트리거가 꺼지므로
                // DELETE 는 순서를 맞출 필요도 없다.
                for (String table : TABLES) {
                    statement.addBatch("DELETE FROM " + table);
                }
                statement.executeBatch();
                // 시드 슬롯은 남기고 테스트가 더한 자리만 지운다. 상태는 되돌린다 —
                // 임대가 사라졌는데 OCCUPIED 로 남으면 다음 클래스가 빈 자리를 못 찾는다.
                statement.execute("DELETE FROM booth_slots WHERE id > " + SEEDED_SLOT_MAX_ID);
                statement.execute("SELECT setval(pg_get_serial_sequence('booth_slots', 'id'), "
                        + SEEDED_SLOT_MAX_ID + ")");
                statement.execute("UPDATE booth_slots SET status = 'AVAILABLE' WHERE status <> 'AVAILABLE'");
                statement.execute("SET session_replication_role = origin");
            }
        }
        flushRedis();
        resets++;
    }

    private boolean migrated(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT to_regclass('public.users') IS NOT NULL")) {
            return rows.next() && rows.getBoolean(1);
        }
    }

    /**
     * Redis 는 키만 비우면 된다. {@code docker exec} 로 {@code redis-cli} 를 부르면 클래스마다
     * 100 ms 를 넘겨 157개 클래스에서 수십 초가 되므로, 열린 포트로 FLUSHALL 한 줄을 보낸다.
     */
    private void flushRedis() throws Exception {
        try (Socket socket = new Socket(TestcontainersConfiguration.REDIS.getHost(),
                TestcontainersConfiguration.REDIS.getMappedPort(6379))) {
            OutputStream out = socket.getOutputStream();
            out.write("*1\r\n$8\r\nFLUSHALL\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            byte[] reply = new byte[5];
            int read = in.read(reply);
            if (read < 3 || reply[0] != '+') {
                throw new IllegalStateException("Redis FLUSHALL 응답이 +OK 가 아니다: "
                        + new String(reply, 0, Math.max(read, 0), StandardCharsets.US_ASCII));
            }
        }
    }

}
