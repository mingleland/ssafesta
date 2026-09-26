package com.example.ssafesta.internal.ai;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static com.example.ssafesta.booth.BoothTestSupport.releaseAllSlots;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
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

/**
 * {@code POST /internal/ai/document-jobs/{jobId}/project-facts} — AI 가 문서에서 뽑은 프로젝트
 * 정형 정보를 Spring 이 받는 경로 (S15P21A604-597, GitLab #169).
 *
 * <p>고정하는 것은 <b>오래된 추출이 최신 값을 덮지 않는다</b>는 것이다. 워커는 재시도하고 재전송하며
 * 그 순서는 보장되지 않는다 — 도착 순서로 쓰면 어제 문서에서 뽑은 값이 오늘 값을 지운다. 응답이
 * 204 인지만 보면 그 모든 경우가 초록이다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AiProjectFactsApiIntegrationTest {

    /** {@code src/test/resources/application-local.properties} 가 넣는 값과 같아야 한다. */
    private static final String SERVICE_TOKEN = "test-ai-to-spring";

    private static final java.util.concurrent.atomic.AtomicInteger HASH_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void freeSlots() {
        releaseAllSlots(jdbc);
    }

    @Test
    @DisplayName("추출 결과가 그 부스의 프로젝트에 저장되고 출처 Job 이 함께 남는다")
    void extractedFactsLandOnTheBoothsProject() throws Exception {
        Job job = seedJob("정상");
        Long projectId = seedProject(job.boothId(), "정상 프로젝트");

        mockMvc.perform(facts(job, "신입 개발자", "Spring Boot, PostgreSQL"))
                .andExpect(status().isNoContent());

        assertEquals("신입 개발자", column(projectId, "target_audience"));
        assertEquals("Spring Boot, PostgreSQL", column(projectId, "tech_stack"));
        assertEquals(String.valueOf(job.id()), column(projectId, "facts_job_id"),
                "다음 결과의 최신성을 판정하려면 출처 Job 이 남아야 합니다.");
    }

    /**
     * <b>오래된 Job 의 결과는 최신 값을 덮지 않는다.</b>
     *
     * <p>워커는 재시도하고, 늦게 도착한 응답이 먼저 도착한 것보다 오래된 문서에서 나올 수 있다.
     * 도착 순서로 쓰면 어제 문서의 추출이 오늘 값을 지운다.
     */
    @Test
    void anOlderJobsResultDoesNotOverwrite() throws Exception {
        Job older = seedJob("먼저");
        Job newer = seedJobIn(older.boothId(), older.agentId(), "나중");
        Long projectId = seedProject(older.boothId(), "덮어쓰기 프로젝트");

        mockMvc.perform(facts(newer, "최신 대상", "최신 기술")).andExpect(status().isNoContent());
        mockMvc.perform(facts(older, "옛날 대상", "옛날 기술")).andExpect(status().isNoContent());

        assertEquals("최신 대상", column(projectId, "target_audience"),
                "늦게 도착한 옛 Job 의 결과가 최신 값을 덮었습니다.");
    }

    /** 같은 Job 의 재전송은 같은 결과다 — 워커가 응답을 잃는 것은 흔한 일이라 실패로 답하지 않는다. */
    @Test
    void aResendOfTheSameJobIsAccepted() throws Exception {
        Job job = seedJob("재전송");
        Long projectId = seedProject(job.boothId(), "재전송 프로젝트");

        mockMvc.perform(facts(job, "대상", "기술")).andExpect(status().isNoContent());
        mockMvc.perform(facts(job, "대상", "기술")).andExpect(status().isNoContent());

        assertEquals("대상", column(projectId, "target_audience"));
    }

    /** 취소된 Job 의 결과는 받지 않는다 — 취소는 "이 처리 결과를 쓰지 말라" 는 뜻이다. */
    @Test
    void aCancelledJobsResultIsGone() throws Exception {
        Job job = seedJob("취소");
        Long projectId = seedProject(job.boothId(), "취소 프로젝트");
        jdbc.update("UPDATE ai_document_jobs SET status = 'CANCELLED' WHERE id = ?", job.id());

        mockMvc.perform(facts(job, "대상", "기술")).andExpect(status().isGone());

        assertNull(column(projectId, "target_audience"));
    }

    /**
     * 성공한 Job 의 결과는 받는다 — 나머지 네 경로와 다른 점이다.
     *
     * <p>추출은 임베딩이 <b>끝난 뒤</b>에 온다. 다른 경로처럼 {@code SUCCEEDED} 를 끝난 Job 으로
     * 막으면 이 기능은 한 번도 동작하지 않는다.
     */
    @Test
    void aSucceededJobsResultIsAccepted() throws Exception {
        Job job = seedJob("성공후");
        Long projectId = seedProject(job.boothId(), "성공후 프로젝트");
        jdbc.update("UPDATE ai_document_jobs SET status = 'SUCCEEDED' WHERE id = ?", job.id());

        mockMvc.perform(facts(job, "대상", "기술")).andExpect(status().isNoContent());

        assertEquals("대상", column(projectId, "target_audience"));
    }

    /** 밀려난 attempt 의 결과는 409 다 — 다른 네 경로와 같은 판정이다. */
    @Test
    void aFencedAttemptIsRefused() throws Exception {
        Job job = seedJob("밀려남");
        seedProject(job.boothId(), "밀려남 프로젝트");
        jdbc.update("UPDATE ai_document_jobs SET attempt_no = 3 WHERE id = ?", job.id());

        mockMvc.perform(facts(job, "대상", "기술")).andExpect(status().isConflict());
    }

    /** Job 이 처리한 파일과 다른 원본에서 뽑았다면 받지 않는다. */
    @Test
    void aDifferentSourceHashIsRefused() throws Exception {
        Job job = seedJob("다른원본");
        seedProject(job.boothId(), "다른원본 프로젝트");

        mockMvc.perform(request(job, """
                        {"attemptNo":0,"sourceHash":"%064x","targetAudience":"대상"}""".formatted(99)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("sourceHash"));
    }

    /** 보낸 쪽이 다른 문서를 말하면 받지 않는다 — 엉뚱한 부스의 프로젝트가 바뀐다. */
    @Test
    void aMismatchedDocumentIdIsRefused() throws Exception {
        Job job = seedJob("다른문서");
        seedProject(job.boothId(), "다른문서 프로젝트");

        mockMvc.perform(request(job, """
                        {"attemptNo":0,"documentId":%d,"sourceHash":"%s","targetAudience":"대상"}"""
                        .formatted(job.documentId() + 9999, job.sourceHash())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("documentId"));
    }

    /** 빈 요청이 기존 값을 지우지 못한다 — 둘 다 없는 문서라면 보내지 않으면 된다. */
    @Test
    void anEmptyExtractionCannotWipeWhatIsThere() throws Exception {
        Job job = seedJob("빈요청");
        Long projectId = seedProject(job.boothId(), "빈요청 프로젝트");
        mockMvc.perform(facts(job, "대상", "기술")).andExpect(status().isNoContent());

        Job later = seedJobIn(job.boothId(), job.agentId(), "빈요청2");
        mockMvc.perform(request(later,
                        """
                        {"attemptNo":0,"sourceHash":"%s"}""".formatted(later.sourceHash())))
                .andExpect(status().isBadRequest());

        assertEquals("대상", column(projectId, "target_audience"));
    }

    /**
     * 프로젝트가 없는 부스는 204 다.
     *
     * <p>정형 정보는 프로젝트의 속성이고, 프로젝트를 만들지 않은 부스에는 담을 곳이 없다. 보낸 쪽이
     * 고칠 수 있는 잘못이 아니라 실패로 답하지 않는다.
     */
    @Test
    void aBoothWithoutAProjectIsAccepted() throws Exception {
        Job job = seedJob("프로젝트없음");

        mockMvc.perform(facts(job, "대상", "기술")).andExpect(status().isNoContent());
    }

    /**
     * Agent 설정 응답이 {@code projectFacts} 를 함께 준다 — rule-based 단축 응답이 쓸 값이다.
     *
     * <p>{@code introduction} 은 운영자가 쓴 소개이고, 나머지 둘은 AI 가 추출한 것이다.
     */
    @Test
    void theAgentConfigCarriesTheFacts() throws Exception {
        Job job = seedJob("설정");
        seedProject(job.boothId(), "설정 프로젝트");
        mockMvc.perform(facts(job, "행사 방문자", "Unity, WebGL")).andExpect(status().isNoContent());

        mockMvc.perform(get("/internal/ai/agent-config")
                        .param("boothId", String.valueOf(job.boothId()))
                        .param("agentId", String.valueOf(job.agentId()))
                        .header("Authorization", "Bearer " + SERVICE_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.projectFacts.introduction").value("소개글"))
                .andExpect(jsonPath("$.projectFacts.targetAudience").value("행사 방문자"))
                .andExpect(jsonPath("$.projectFacts.techStack").value("Unity, WebGL"));
    }

    @Test
    @DisplayName("RAG 후처리 결과는 AI 소개·근거·버전을 한 세트로 저장한다")
    void ragEnrichmentReplacesTheWholeFactSet() throws Exception {
        Job job = seedJob("RAG후처리");
        Long projectId = seedProject(job.boothId(), "RAG 프로젝트");

        mockMvc.perform(request(job, """
                        {"attemptNo":0,"documentId":%d,"sourceHash":"%s",
                         "introduction":"AI가 생성한 소개","targetAudience":"전시 방문자",
                         "techStack":"FastAPI, Spring Boot",
                         "sources":[{"documentId":%d,"chunkId":4}],
                         "generationVersion":"rag-v1"}
                        """.formatted(job.documentId(), job.sourceHash(), job.documentId())))
                .andExpect(status().isNoContent());

        assertEquals("AI가 생성한 소개", column(projectId, "ai_introduction"));
        assertEquals("rag-v1", column(projectId, "facts_generation_version"));
        assertEquals("[{\"chunkId\": 4, \"documentId\": %d}]".formatted(job.documentId()),
                column(projectId, "facts_sources"));

        mockMvc.perform(get("/internal/ai/agent-config")
                        .param("boothId", String.valueOf(job.boothId()))
                        .param("agentId", String.valueOf(job.agentId()))
                        .header("Authorization", "Bearer " + SERVICE_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectFacts.introduction").value("AI가 생성한 소개"));
    }

    @Test
    void aSuccessfulGroundedGenerationMayStoreNullableAnswers() throws Exception {
        Job job = seedJob("근거없음");
        Long projectId = seedProject(job.boothId(), "근거없음 프로젝트");
        mockMvc.perform(facts(job, "기존 대상", "기존 기술")).andExpect(status().isNoContent());

        Job later = seedJobIn(job.boothId(), job.agentId(), "근거없음2");
        mockMvc.perform(request(later, """
                        {"attemptNo":0,"documentId":%d,"sourceHash":"%s",
                         "introduction":null,"targetAudience":null,"techStack":null,
                         "sources":[{"documentId":%d,"chunkId":0}],
                         "generationVersion":"rag-v1"}
                        """.formatted(later.documentId(), later.sourceHash(), later.documentId())))
                .andExpect(status().isNoContent());

        assertNull(column(projectId, "target_audience"));
        assertNull(column(projectId, "tech_stack"));
        assertEquals("소개글", jdbc.queryForObject(
                "SELECT description FROM projects WHERE id = ?", String.class, projectId));
    }

    @Test
    void aLegacyRequestDoesNotEraseNewEnrichmentMetadata() throws Exception {
        Job job = seedJob("구버전호환");
        Long projectId = seedProject(job.boothId(), "구버전 호환 프로젝트");
        jdbc.update("""
                UPDATE projects
                   SET ai_introduction = '보존할 AI 소개',
                       facts_sources = '[{"documentId":1,"chunkId":0}]'::jsonb,
                       facts_generation_version = 'rag-v1'
                 WHERE id = ?
                """, projectId);

        mockMvc.perform(facts(job, "새 대상", "새 기술")).andExpect(status().isNoContent());

        assertEquals("보존할 AI 소개", column(projectId, "ai_introduction"));
        assertEquals("rag-v1", column(projectId, "facts_generation_version"));
    }

    /**
     * 추출 전에는 {@code projectFacts} 가 소개만 들고 나온다.
     *
     * <p>없는 값을 빈 문자열로 채우면 받는 쪽이 "아직 추출 안 됨" 과 "추출했는데 비어 있음" 을
     * 구분하지 못한다. 기존 RAG 흐름은 이 상태로도 깨지지 않아야 한다.
     */
    @Test
    void beforeExtractionOnlyTheIntroductionIsThere() throws Exception {
        Job job = seedJob("추출전");
        seedProject(job.boothId(), "추출전 프로젝트");

        mockMvc.perform(get("/internal/ai/agent-config")
                        .param("boothId", String.valueOf(job.boothId()))
                        .param("agentId", String.valueOf(job.agentId()))
                        .header("Authorization", "Bearer " + SERVICE_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectFacts.introduction").value("소개글"))
                .andExpect(jsonPath("$.projectFacts.targetAudience").doesNotExist())
                .andExpect(jsonPath("$.projectFacts.techStack").doesNotExist());
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private RequestBuilder facts(Job job, String targetAudience, String techStack) {
        return request(job, """
                {"attemptNo":0,"documentId":%d,"sourceHash":"%s","targetAudience":"%s","techStack":"%s"}"""
                .formatted(job.documentId(), job.sourceHash(), targetAudience, techStack));
    }

    private RequestBuilder request(Job job, String body) {
        return post("/internal/ai/document-jobs/" + job.id() + "/project-facts")
                .header("Authorization", "Bearer " + SERVICE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String column(Long projectId, String name) {
        return jdbc.queryForObject("SELECT " + name + "::text FROM projects WHERE id = ?",
                String.class, projectId);
    }

    private Long seedProject(Long boothId, String name) {
        return jdbc.queryForObject("""
                INSERT INTO projects (booth_id, name, description) VALUES (?, ?, '소개글')
                RETURNING id
                """, Long.class, boothId, name);
    }

    private Job seedJob(String prefix) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(userId, prefix + " 부스")).getId();
        long agentId = jdbc.queryForObject("""
                INSERT INTO ai_agents (booth_id, name, role_code, tone_code, system_prompt, status)
                VALUES (?, '도슨트', 'PROJECT_DOCENT', 'FRIENDLY', '문서를 근거로 답한다.', 'ACTIVE')
                RETURNING id
                """, Long.class, boothId);
        return seedJobIn(boothId, agentId, prefix);
    }

    /**
     * 문서마다 다른 해시를 쓴다 — {@code ux_ai_documents_agent_active_sha} 가 한 agent 아래 같은
     * 내용의 문서를 둘 두지 못하게 한다(같은 파일을 두 번 올린 것이므로 옳다).
     */
    private static String nextHash() {
        return "%064x".formatted(HASH_SEQ.incrementAndGet());
    }

    /** 같은 부스에 문서와 Job 을 하나 더 만든다 — 최신성 판정에는 Job 이 둘 이상 필요하다. */
    private Job seedJobIn(Long boothId, long agentId, String prefix) {
        String objectKey = "seed/facts/" + prefix + "/" + UUID.randomUUID();
        String sourceHash = nextHash();
        Long uploaderId = jdbc.queryForObject(
                "SELECT owner_user_id FROM booths WHERE id = ?", Long.class, boothId);
        Long documentId = jdbc.queryForObject("""
                INSERT INTO ai_documents (booth_id, agent_id, original_filename, content_type,
                    size_bytes, s3_key, processing_status, uploaded_by_user_id, content_sha256,
                    storage_provider, storage_bucket, uploaded_at)
                VALUES (?, ?, 'seed.pdf', 'application/pdf', 1024, ?, 'QUEUED', ?, ?,
                    'R2', 'test-ai-documents', now())
                RETURNING id
                """, Long.class, boothId, agentId, objectKey, uploaderId, sourceHash);
        long jobId = jdbc.queryForObject("""
                INSERT INTO ai_document_jobs (document_id, booth_id, agent_id, source_hash,
                    original_filename, content_type, file_size_bytes, storage_provider,
                    storage_bucket, object_key, status, attempt_no)
                VALUES (?, ?, ?, ?, 'seed.pdf', 'application/pdf', 1024, 'R2',
                    'test-ai-documents', ?, 'QUEUED', 0)
                RETURNING id
                """, Long.class, documentId, boothId, agentId, sourceHash, objectKey);
        return new Job(jobId, documentId, boothId, agentId, sourceHash);
    }

    private record Job(long id, Long documentId, Long boothId, long agentId, String sourceHash) { }
}
