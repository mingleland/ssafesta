package com.example.ssafesta.ai;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static com.example.ssafesta.booth.BoothTestSupport.releaseAllSlots;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.user.AccountDeletionService;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V21 가 실제로 보장해야 하는 것 (S15P21A604-397, GitLab #119).
 *
 * <p>컬럼이 생겼는지가 아니라 <b>삭제가 뚫리는지</b>를 본다. AI 파트 초안 그대로 적용했다면
 * 문서 삭제와 회원 탈퇴가 FK 에서 막혔을 자리다 — 청크 FK 가 NO ACTION 이라 문서를 잡고,
 * Job 과 staging 이 생기면 탈퇴 경로에 삭제가 빠진다. 그 셋을 확정해 반영한 것이 V21 이고
 * 이 테스트가 그 확정을 고정한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AiDocumentJobSchemaIntegrationTest {

    private static final String EMBEDDING = embedding();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private BoothRepository booths;
    @Autowired private WalletService wallets;
    @Autowired private AccountDeletionService accountDeletion;

    @BeforeEach
    void clearSlots() {
        releaseAllSlots(jdbc);
    }

    @Test
    @DisplayName("문서를 지우면 Job·청크·staging 이 함께 지워진다")
    void deletingTheDocumentTakesJobsChunksAndStagingWithIt() {
        Fixture fixture = seedDocumentWithJobAndChunks("cascade");

        jdbc.update("DELETE FROM ai_documents WHERE id = ?", fixture.documentId());

        assertEquals(0, countJobs(fixture.documentId()));
        assertEquals(0, countChunks(fixture.documentId()));
        assertEquals(0, countStaging(fixture.jobId()));
    }

    @Test
    @DisplayName("회원 탈퇴가 새 테이블을 포함해 동작한다")
    void withdrawalDeletesTheWholeDocumentGraph() {
        Fixture fixture = seedDocumentWithJobAndChunks("withdraw");

        accountDeletion.deleteUserGraph(fixture.userId());

        assertEquals(0, countJobs(fixture.documentId()));
        assertEquals(0, countChunks(fixture.documentId()));
        assertEquals(0, countStaging(fixture.jobId()));
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT count(*) FROM ai_documents WHERE id = ?", Integer.class, fixture.documentId()));
    }

    @Test
    @DisplayName("한 문서에 살아 있는 Job 은 하나뿐이다")
    void onlyOneActiveJobPerDocument() {
        Fixture fixture = seedDocumentWithJobAndChunks("active");

        assertThrows(DuplicateKeyException.class, () -> insertJob(fixture, "QUEUED"));

        // 끝난 Job 은 몇 개든 남는다 — 재처리 이력이 곧 감사 기록이다.
        insertJob(fixture, "DEAD");
        insertJob(fixture, "SUCCEEDED");
        assertEquals(3, countJobs(fixture.documentId()));
    }

    @Test
    @DisplayName("유사도 검색이 hnsw 코사인 인덱스를 탄다")
    void theVectorIndexIsUsedBySimilaritySearch() {
        seedDocumentWithJobAndChunks("explain");

        // 행이 몇 개뿐이면 계획기가 순차 스캔을 고른다 — 인덱스가 아예 없는 경우와 구분되지
        // 않는다. 순차 스캔을 끄고 물어야 "이 연산자로 이 인덱스를 탈 수 있는가" 를 본다.
        jdbc.execute("SET enable_seqscan = off");
        try {
            String plan = String.join("\n", jdbc.queryForList("""
                    EXPLAIN SELECT id FROM ai_document_chunks
                     ORDER BY embedding <=> CAST(? AS vector)
                     LIMIT 5
                    """, String.class, EMBEDDING));

            assertTrue(plan.contains("ix_ai_document_chunks_embedding_cosine"),
                    "코사인 인덱스를 타지 않는다. 실제 계획:\n" + plan);
        } finally {
            jdbc.execute("SET enable_seqscan = on");
        }
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private Fixture seedDocumentWithJobAndChunks(String prefix) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(userId, prefix + " 부스")).getId();
        grantLease(jdbc, boothId, userId);
        Long agentId = jdbc.queryForObject("""
                INSERT INTO ai_agents (booth_id, name, role_code, tone_code, system_prompt, response_length, status)
                VALUES (?, '도슨트', 'PROJECT_DOCENT', 'FRIENDLY', '문서를 근거로 답한다.', 'MEDIUM', 'ACTIVE')
                RETURNING id
                """, Long.class, boothId);
        Long documentId = jdbc.queryForObject("""
                INSERT INTO ai_documents (booth_id, agent_id, original_filename, content_type,
                    size_bytes, s3_key, processing_status, uploaded_by_user_id, content_sha256,
                    storage_provider, storage_bucket, uploaded_at)
                VALUES (?, ?, 'seed.pdf', 'application/pdf', 1024, ?, 'READY', ?, ?,
                    'R2', 'test-ai-documents', now())
                RETURNING id
                """, Long.class, boothId, agentId, "seed/" + prefix, userId,
                "%064x".formatted(Math.abs(prefix.hashCode())));

        Long jobId = insertJob(new Fixture(userId, boothId, agentId, documentId, null), "RUNNING");
        Fixture fixture = new Fixture(userId, boothId, agentId, documentId, jobId);

        jdbc.update("""
                INSERT INTO ai_document_chunks (document_id, booth_id, agent_id, chunk_no, content,
                    embedding, embedding_model_id, job_id, page_number, section, searchable)
                VALUES (?, ?, ?, 0, '본문', CAST(? AS vector), 'test-embedding-model', ?, 1, '개요', TRUE)
                """, documentId, boothId, agentId, EMBEDDING, jobId);
        jdbc.update("""
                INSERT INTO ai_document_chunk_staging (job_id, batch_seq, chunk_no, content,
                    embedding, embedding_model_id)
                VALUES (?, 0, 0, '본문', CAST(? AS vector), 'test-embedding-model')
                """, jobId, EMBEDDING);
        return fixture;
    }

    private Long insertJob(Fixture fixture, String status) {
        return jdbc.queryForObject("""
                INSERT INTO ai_document_jobs (document_id, booth_id, agent_id, source_hash,
                    original_filename, content_type, file_size_bytes, storage_provider,
                    storage_bucket, object_key, status)
                VALUES (?, ?, ?, 'sha', 'seed.pdf', 'application/pdf', 1024, 'R2',
                    'test-ai-documents', ?, ?)
                RETURNING id
                """, Long.class, fixture.documentId(), fixture.boothId(), fixture.agentId(),
                "seed/" + fixture.documentId() + "/" + status + "/" + System.nanoTime(), status);
    }

    private int countJobs(Long documentId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ai_document_jobs WHERE document_id = ?", Integer.class, documentId);
    }

    private int countChunks(Long documentId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ai_document_chunks WHERE document_id = ?", Integer.class, documentId);
    }

    private int countStaging(Long jobId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ai_document_chunk_staging WHERE job_id = ?", Integer.class, jobId);
    }

    /** 1536 차원은 헌법 18조·FR-009 로 고정이다. 값 자체에는 의미가 없다. */
    private static String embedding() {
        StringBuilder vector = new StringBuilder("[");
        for (int i = 0; i < 1536; i++) {
            vector.append(i == 0 ? "" : ",").append("0.001");
        }
        return vector.append(']').toString();
    }

    private record Fixture(Long userId, Long boothId, Long agentId, Long documentId, Long jobId) { }
}
