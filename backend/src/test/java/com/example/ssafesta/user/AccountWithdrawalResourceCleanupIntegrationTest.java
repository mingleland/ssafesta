package com.example.ssafesta.user;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.common.RedisKeyspaceProperties;
import com.example.ssafesta.game.GameAssetDeleteQueue;
import com.example.ssafesta.storage.FakeObjectStorage;
import com.example.ssafesta.storage.FakeObjectStorageConfiguration;
import com.example.ssafesta.wallet.WalletService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 탈퇴가 <b>DB 밖 자원</b>까지 정리하는가 (spec 001 T012a, S15P21A604-150).
 *
 * <p>관계 그래프가 지워지는 것은 여러 테스트가 이미 고정한다. 여기서 보는 것은 그 그래프가
 * 가리키던 <b>바깥</b>이다 — Redis 세션, 객체 저장소의 바이트, 벡터. 행만 지우고 바깥을 두면
 * 탈퇴는 성공한 것처럼 보이고 자원은 영영 남는다. 아무도 가리키지 않게 되어 찾을 방법도 없다.
 *
 * <p><b>OAuth unlink 는 여기서 보지 않는다.</b> 할 수 없기 때문이다 — {@code oauth_identities} 가
 * 담는 것은 {@code provider} 와 {@code provider_subject} 뿐이고, Google·Kakao 의 연결 해제는
 * 그 사용자의 provider 토큰을 요구한다. 우리는 로그인 교환 순간 말고는 그 토큰을 갖지 않는다.
 * 결정 요청은 {@code docs/26} 에 있다 ({@code S15P21A604-199} 가 그 결정을 기다린다).
 */
@Import({TestcontainersConfiguration.class, FakeObjectStorageConfiguration.class})
@SpringBootTest
class AccountWithdrawalResourceCleanupIntegrationTest {

    @Autowired private AccountLifecycleService lifecycle;
    @Autowired private AccountDeletionService deletions;
    @Autowired private MemberSessionService sessions;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private BoothRepository booths;
    @Autowired private StringRedisTemplate redis;
    @Autowired private RedisKeyspaceProperties keyspace;
    @Autowired private GameAssetDeleteQueue deleteQueue;
    @Autowired private FakeObjectStorage storage;
    @Autowired private JdbcTemplate jdbc;

    /**
     * Redis 세션은 탈퇴와 함께 끝난다.
     *
     * <p>DB 행이 사라져도 refresh 토큰이 살아 있으면 그 토큰으로 access 토큰을 받을 수 있다.
     * 그 토큰의 주인은 이제 존재하지 않는 회원이다.
     */
    @Test
    void withdrawingEndsTheRedisSession() {
        Long userId = member("세션");
        String refreshToken = sessions.issue(userId).refreshToken();
        String refreshKey = keyspace.prefix() + "auth:refresh:" + sha256(refreshToken);
        assertEquals(3, redisKeys(userId), "탈퇴 전에는 세 키가 다 있어야 합니다.");
        assertTrue(Boolean.TRUE.equals(redis.hasKey(refreshKey)), "refresh 키가 있어야 합니다.");

        lifecycle.withdraw(userId);

        assertEquals(0, redisKeys(userId), "이 회원의 auth 키가 남아 있습니다.");
        assertFalse(Boolean.TRUE.equals(redis.hasKey(refreshKey)),
                "refresh 토큰이 살아 있으면 없는 회원의 access 토큰을 계속 받을 수 있습니다.");
    }

    /**
     * 벡터는 문서와 함께 사라진다.
     *
     * <p>임베딩은 별도 저장소가 아니라 {@code ai_document_chunks.embedding} 에 있다(#119 에서
     * Spring 이 chunk 를 소유하기로 정한 결과다). 그래서 "벡터 삭제 인터페이스" 가 따로 필요하지
     * 않다 — 문서 한 줄이 지워지면 CASCADE 가 따라간다. 그 사실을 여기서 고정한다.
     */
    @Test
    void withdrawingRemovesTheEmbeddedVectors() {
        Seeded seeded = seedBoothWithDocument("벡터");

        assertEquals(1, count("SELECT count(*) FROM ai_document_chunks WHERE document_id = ?",
                seeded.documentId()), "심은 chunk 가 있어야 합니다.");

        lifecycle.withdraw(seeded.userId());

        assertEquals(0, count("SELECT count(*) FROM ai_document_chunks WHERE document_id = ?",
                seeded.documentId()));
        assertEquals(0, count("SELECT count(*) FROM ai_documents WHERE id = ?", seeded.documentId()));
    }

    /**
     * 객체 저장소의 바이트는 <b>큐를 거쳐</b> 지워진다.
     *
     * <p>삭제는 네트워크 호출이라 행을 지우는 트랜잭션 안에 둘 수 없다. 안에 두면 버킷이 안 될 때
     * 탈퇴 자체가 되돌아가거나, 커밋 뒤 실패가 삼켜져 객체가 영영 남는다. 좌표를 먼저 적어 두는
     * 것이 그래서다 — <b>탈퇴 직후에 파일이 사라져 있는 것이 아니라, 사라질 것이 예약돼 있다.</b>
     */
    @Test
    void withdrawingHandsTheStoredObjectsToTheDeleteQueue() {
        Seeded seeded = seedBoothWithDocument("파일");
        String assetKey = seedGameWithAsset(seeded.userId());
        storage.putObject(seeded.objectKey(), 1024);
        storage.putObject(assetKey, 2048);

        lifecycle.withdraw(seeded.userId());

        assertEquals(1, count("SELECT count(*) FROM game_asset_delete_queue WHERE object_key = ?",
                seeded.objectKey()), "문서의 좌표가 큐에 있어야 합니다.");
        assertEquals(1, count("SELECT count(*) FROM game_asset_delete_queue WHERE object_key = ?",
                assetKey), "게임 에셋의 좌표가 큐에 있어야 합니다.");

        deleteQueue.sweep();

        assertFalse(storage.hasObject(seeded.objectKey()), "문서 바이트가 저장소에 남았습니다.");
        assertFalse(storage.hasObject(assetKey), "에셋 바이트가 저장소에 남았습니다.");
        assertEquals(0, count("SELECT count(*) FROM game_asset_delete_queue WHERE object_key IN (?, ?)",
                seeded.objectKey(), assetKey), "비운 뒤에는 큐가 비어야 합니다.");
    }

    /**
     * 같은 회원을 두 번 지워도 안전하다 (완료 조건 "중복 호출 안전").
     *
     * <p>부분 실패 뒤의 재시도가 이 모양이다. 두 번째 호출은 지울 것을 못 찾고 조용히 끝나야 하며,
     * 예외를 던지면 재시도 경로가 그 자리에서 막힌다.
     */
    @Test
    void deletingTwiceIsSafe() {
        Seeded seeded = seedBoothWithDocument("중복");
        lifecycle.withdraw(seeded.userId());

        assertDoesNotThrow(() -> deletions.deleteUserGraph(seeded.userId()));
        assertEquals(0, count("SELECT count(*) FROM users WHERE id = ?", seeded.userId()));
    }

    /** OAuth 연결 <b>행</b>은 사라진다 — provider 쪽 연결 해제와는 다른 이야기다(클래스 주석). */
    @Test
    void withdrawingRemovesTheOAuthIdentityRow() {
        Long userId = member("연결");
        jdbc.update("INSERT INTO oauth_identities(user_id, provider, provider_subject)"
                + " VALUES(?, 'GOOGLE', ?)", userId, "sub-" + UUID.randomUUID());

        lifecycle.withdraw(userId);

        assertEquals(0, count("SELECT count(*) FROM oauth_identities WHERE user_id = ?", userId));
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private Long member(String prefix) {
        return createMemberWithWallet(users, wallets, prefix);
    }

    /** 부스·에이전트·문서·chunk 를 한 회원 아래 심는다 — 벡터와 파일이 둘 다 그 끝에 달린다. */
    private Seeded seedBoothWithDocument(String prefix) {
        Long userId = member(prefix);
        Long boothId = booths.save(new Booth(userId, prefix + " 부스")).getId();
        Long agentId = jdbc.queryForObject("""
                INSERT INTO ai_agents (booth_id, name, role_code, tone_code, system_prompt, status)
                VALUES (?, '도슨트', 'PROJECT_DOCENT', 'FRIENDLY', '문서를 근거로 답한다.', 'ACTIVE')
                RETURNING id
                """, Long.class, boothId);
        String objectKey = "withdraw/doc/" + UUID.randomUUID();
        Long documentId = jdbc.queryForObject("""
                INSERT INTO ai_documents (booth_id, agent_id, original_filename, content_type,
                    size_bytes, s3_key, processing_status, uploaded_by_user_id, content_sha256,
                    storage_provider, storage_bucket, uploaded_at)
                VALUES (?, ?, 'seed.pdf', 'application/pdf', 1024, ?, 'READY', ?, ?,
                    'R2', 'test-ai-documents', now())
                RETURNING id
                """, Long.class, boothId, agentId, objectKey, userId,
                "%064x".formatted(objectKey.hashCode() & 0xffff));
        jdbc.update("""
                INSERT INTO ai_document_chunks (document_id, booth_id, agent_id, chunk_no, content,
                    embedding, embedding_model_id)
                VALUES (?, ?, ?, 0, '조각', ?::vector, 'text-embedding-3-small')
                """, documentId, boothId, agentId, zeroVector());
        return new Seeded(userId, documentId, objectKey);
    }

    /**
     * 게임 에셋 한 개. 문서와 같은 큐·같은 sweeper 를 쓰는지 함께 보기 위한 것이다.
     *
     * <p>버킷을 문서와 같은 것으로 둔다 — {@link FakeObjectStorage} 는 활성 버킷 하나만 들여다보게
     * 되어 있고, 여기서 보는 것은 버킷 라우팅이 아니라 "큐에 실린 좌표가 실제로 지워지는가" 다.
     */
    private String seedGameWithAsset(Long userId) {
        Long gameId = jdbc.queryForObject(
                "INSERT INTO games (owner_user_id, title) VALUES (?, '탈퇴할 게임') RETURNING id",
                Long.class, userId);
        String objectKey = "withdraw/asset/" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO game_assets (game_id, asset_id, kind, status, provider, storage_bucket,
                    object_key, declared_content_type, declared_byte_size, upload_expires_at,
                    created_by_user_id)
                VALUES (?, ?, 'IMAGE', 'UPLOADING', 'R2', 'test-ai-documents', ?, 'image/png', 2048,
                    now() + interval '10 minutes', ?)
                """, gameId, "a" + UUID.randomUUID().toString().replace("-", "").substring(0, 20),
                objectKey, userId);
        return objectKey;
    }

    /** 1536 차원 0 벡터. 값은 아무래도 좋다 — 여기서 보는 것은 그 행이 사라지는가다. */
    private static String zeroVector() {
        StringBuilder builder = new StringBuilder(1536 * 2 + 2).append('[');
        for (int index = 0; index < 1536; index++) {
            builder.append(index == 0 ? "0" : ",0");
        }
        return builder.append(']').toString();
    }

    /** 이 회원 id 로 만들어지는 auth 키 셋 — active·session·family. */
    private long redisKeys(Long userId) {
        String prefix = keyspace.prefix() + "auth:";
        return java.util.stream.Stream.of("active:", "session:", "family:")
                .map(part -> prefix + part + userId)
                .filter(key -> Boolean.TRUE.equals(redis.hasKey(key)))
                .count();
    }

    /** {@code MemberSessionService} 와 같은 방식이어야 한다 — hex 가 아니라 URL-safe Base64 다. */
    private static String sha256(String value) {
        try {
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private record Seeded(Long userId, Long documentId, String objectKey) { }
}
