package com.example.ssafesta.internal.storage;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static com.example.ssafesta.booth.BoothTestSupport.releaseAllSlots;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * 배포에 없는 provider 를 가리키는 결과 — {@code 500}, 그리고 그것이 <b>이미 처리된 재전송에는
 * 닿지 않는다</b>는 것 (S15P21A604-500).
 *
 * <p>기본 테스트 설정은 R2 만 구성한다. {@code MINIO_LOCAL} 을 뺀 것이 실수가 아니라 계약이다 —
 * R2 만 쓰는 배포가 fallback 자격증명 없이 뜬다({@code application.yml} 의 providers 주석,
 * {@code ObjectStorageProperties.withoutUnconfigured}). 그래서 이 컨텍스트가 곧 "target provider 가
 * 없는 배포"다.
 *
 * <p><b>왜 별도 클래스인가</b>: {@code ObjectStorageProperties} 는 기동 시 바인딩되므로 테스트
 * 도중에 provider 설정을 뺄 수 없다. 없는 컨텍스트를 따로 띄우는 것이 유일한 방법이고, 덕분에
 * 같은 컨텍스트에서 "신규 요청은 500" 과 "이미 기록된 재전송은 204" 를 나란히 볼 수 있다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class StorageReconciliationUnconfiguredProviderIntegrationTest {

    private static final String INFRA_TOKEN = "test-infra-to-spring";
    private static final String R2_BUCKET = "test-ai-documents";
    private static final String SHA = "%064x".formatted(13);
    private static final String CHECKED_AT = "2026-09-10T03:00:00Z";

    @Autowired private MockMvc mockMvc;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;

    @BeforeEach
    void freeSlots() {
        releaseAllSlots(jdbc);
    }

    /**
     * 계약상 유효한 값인데 이 배포가 그 provider 를 모른다 — 요청 오류가 아니라 설정 오류다.
     *
     * <p>422 로 답하면 Infra 는 payload 를 고치려 든다. 고쳐야 하는 것은 Spring 배포다.
     */
    @Test
    @DisplayName("target provider 가 이 배포에 없으면 500 이고 결과가 적재되지 않는다")
    void anUnconfiguredTargetProviderIsAServerConfigurationError() throws Exception {
        Document document = seed("target없음");

        mockMvc.perform(submit(payload(document, "R2", "MINIO_LOCAL")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_CONFIGURATION_ERROR"));

        assertEquals(0, logCount(document), "처리하지 못한 요청은 기록도 남기지 않는다");
        assertEquals("R2", providerOf(document));
    }

    /**
     * 문서가 서 있는 provider 쪽도 없을 수 있다.
     *
     * <p>{@code withoutUnconfigured()} 가 미구성 provider 를 목록에서 빼므로 target 만 확인하면
     * 현재 provider 조회가 {@code null} 을 돌려주고 그 다음 줄에서 터진다.
     */
    @Test
    @DisplayName("문서의 현재 provider 가 이 배포에 없으면 500 이다")
    void anUnconfiguredCurrentProviderIsAServerConfigurationError() throws Exception {
        Document document = seed("current없음");
        jdbc.update("UPDATE ai_documents SET storage_provider = 'MINIO_LOCAL',"
                + " storage_bucket = 'test-fallback' WHERE id = ?", document.id());

        mockMvc.perform(submit(payload(document, "MINIO_LOCAL", "R2")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_CONFIGURATION_ERROR"));

        assertEquals(0, logCount(document));
        assertEquals("MINIO_LOCAL", providerOf(document));
    }

    /**
     * 행의 버킷이 설정과 다르면 설정이 현실을 못 따라간 것이다.
     *
     * <p>그 상태에서 target 버킷을 설정에서 뽑아 쓰면 추측한 좌표를 문서에 적게 된다. 반영하지
     * 않고 멈추는 쪽이 맞다.
     */
    @Test
    @DisplayName("문서의 bucket 이 설정과 다르면 500 이다")
    void aDocumentBucketThatDisagreesWithTheSettingsIsAServerConfigurationError() throws Exception {
        Document document = seed("버킷불일치");
        jdbc.update("UPDATE ai_documents SET storage_bucket = 'renamed-bucket' WHERE id = ?",
                document.id());

        mockMvc.perform(submit(payload(document, "R2", "R2")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_CONFIGURATION_ERROR"));

        assertEquals(0, logCount(document));
        assertEquals("renamed-bucket", bucketOf(document));
    }

    /**
     * 이미 처리된 요청의 재전송은 설정을 다시 보지 않는다.
     *
     * <p>재전송 fast-path 가 문서 잠금과 설정 확인을 건너뛰는 이유다. 그러지 않으면 배포 설정이
     * 그 사이 바뀌었다는 이유로 같은 요청이 204 였다가 500 이 된다.
     */
    @Test
    @DisplayName("이미 APPLIED 로 기록된 요청의 재전송은 설정이 없어도 204 다")
    void anAlreadyRecordedResultIsReplayedWithoutReadingTheSettings() throws Exception {
        Document document = seed("설정변경후재전송");
        Map<String, Object> payload = payload(document, "R2", "MINIO_LOCAL");
        recordAsApplied(document, payload);

        mockMvc.perform(submit(payload)).andExpect(status().isNoContent());

        assertEquals(1, logCount(document));
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    /** 이 배포가 MINIO_LOCAL 을 알던 시절에 반영된 결과를 흉내낸다. */
    private void recordAsApplied(Document document, Map<String, Object> payload) {
        jdbc.update("""
                INSERT INTO storage_reconciliation_log (run_id, document_id, object_key,
                    source_provider, target_provider, status, apply_result, attempt_count, checked_at)
                VALUES (?, ?, ?, 'R2', 'MINIO_LOCAL', 'VERIFIED', 'APPLIED', 1, ?)
                """, payload.get("runId"), document.id(), document.objectKey(),
                Timestamp.from(Instant.parse(CHECKED_AT)));
    }

    private RequestBuilder submit(Map<String, Object> payload) {
        return post("/internal/storage/reconciliation-runs")
                .header("Authorization", "Bearer " + INFRA_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(payload));
    }

    private static Map<String, Object> payload(Document document, String source, String target) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", "run-" + UUID.randomUUID());
        payload.put("documentId", document.id());
        payload.put("objectKey", document.objectKey());
        payload.put("sourceProvider", source);
        payload.put("targetProvider", target);
        payload.put("status", "VERIFIED");
        payload.put("attemptCount", 1);
        payload.put("checkedAt", CHECKED_AT);
        return payload;
    }

    private String providerOf(Document document) {
        return jdbc.queryForObject("SELECT storage_provider FROM ai_documents WHERE id = ?",
                String.class, document.id());
    }

    private String bucketOf(Document document) {
        return jdbc.queryForObject("SELECT storage_bucket FROM ai_documents WHERE id = ?",
                String.class, document.id());
    }

    private int logCount(Document document) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM storage_reconciliation_log WHERE document_id = ?",
                Integer.class, document.id());
    }

    private Document seed(String prefix) {
        String objectKey = "seed/unconfigured/" + prefix + "/" + UUID.randomUUID();
        Long userId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(userId, prefix + " 부스")).getId();
        long agentId = jdbc.queryForObject("""
                INSERT INTO ai_agents (booth_id, name, role_code, tone_code, system_prompt, status)
                VALUES (?, '도슨트', 'PROJECT_DOCENT', 'FRIENDLY', '문서를 근거로 답한다.', 'ACTIVE')
                RETURNING id
                """, Long.class, boothId);
        Long documentId = jdbc.queryForObject("""
                INSERT INTO ai_documents (booth_id, agent_id, original_filename, content_type,
                    size_bytes, s3_key, processing_status, uploaded_by_user_id, content_sha256,
                    storage_provider, storage_bucket, uploaded_at)
                VALUES (?, ?, 'seed.pdf', 'application/pdf', 1024, ?, 'READY', ?, ?,
                    'R2', '%s', now())
                RETURNING id
                """.formatted(R2_BUCKET), Long.class, boothId, agentId, objectKey, userId, SHA);
        assertNotNull(documentId);
        return new Document(documentId, objectKey);
    }

    private record Document(Long id, String objectKey) { }
}
