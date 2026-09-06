package com.example.ssafesta.internal.ai;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static com.example.ssafesta.booth.BoothTestSupport.releaseAllSlots;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code POST /internal/ai/chunk-search} — FastAPI 가 질의 임베딩만 계산해 넘기고 Spring 이 scope 를
 * 강제한 뒤 chunk 를 돌려주는 경로 (S15P21A604-398, 계약 {@code spring-chunk-search-api.yaml} — MR
 * !344 head {@code 3e745c65}).
 *
 * <p>고정하는 것은 <b>격리</b>다. 다른 booth·agent 의 chunk, 아직 준비되지 않은 chunk, 부모 문서가
 * 남의 것인 chunk 가 응답에 섞이면 안 된다. 그 셋은 조용히 새는 종류라 테스트가 유일한 방어다.
 *
 * <p>임베딩은 단위 기저 벡터를 쓴다. 같은 축이면 코사인 거리 0, 다른 축이면 1 이라 정렬 기대값이
 * 값 선택에 좌우되지 않는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AiChunkSearchApiIntegrationTest {

    /** {@code src/test/resources/application-local.properties} 가 넣는 값과 같아야 한다. */
    private static final String SERVICE_TOKEN = "test-ai-to-spring";

    /** 헌법 18조·FR-009 로 고정. */
    private static final int DIMENSIONS = 1536;

    private static final String NEAR = vector(DIMENSIONS, "1");
    private static final String FAR = vector(DIMENSIONS, "0", "1");

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private BoothRepository booths;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AiChunkSearchRepository chunkSearch;

    @BeforeEach
    void freeSlots() {
        releaseAllSlots(jdbc);
    }

    // ── 정상 응답 ────────────────────────────────────────────────────────────

    /**
     * 임계값이 없다는 것도 여기서 고정된다 (GitLab #119, 2026-09-03 확정).
     *
     * <p>{@code FAR} 는 직교라 거리 1.0 — 사실상 무관한 조각인데도 응답에 실린다. 서버가 잘라내지
     * 않는다는 것이 계약이고, 무엇을 버릴지는 답변을 만드는 쪽이 정한다. 나중에 임계값이 들어오면
     * 아래 {@code length() == 2} 가 먼저 깨진다.
     */
    @Test
    @DisplayName("계약의 7개 필드를 camelCase 로 돌려주고 distance 오름차순이다 (임계값 없음)")
    void returnsTheSevenContractFieldsOrderedByDistance() throws Exception {
        Scope scope = seedScope("정상", "READY");
        insertChunk(scope, 0, "가까운 조각", NEAR, true, 3, "본문");
        insertChunk(scope, 1, "먼 조각", FAR, true, null, null);

        mockMvc.perform(search(scope, NEAR, 20))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].content").value("가까운 조각"))
                .andExpect(jsonPath("$.items[0].chunkNo").value(0))
                .andExpect(jsonPath("$.items[0].pageNumber").value(3))
                .andExpect(jsonPath("$.items[0].section").value("본문"))
                .andExpect(jsonPath("$.items[0].documentId").value(scope.documentId().intValue()))
                .andExpect(jsonPath("$.items[0].originalFilename").value("seed.pdf"))
                .andExpect(jsonPath("$.items[0].distance").value(0.0))
                .andExpect(jsonPath("$.items[1].content").value("먼 조각"))
                .andExpect(jsonPath("$.items[1].pageNumber").isEmpty())
                .andExpect(jsonPath("$.items[1].section").isEmpty())
                .andExpect(jsonPath("$.items[1].distance").value(1.0));
    }

    @Test
    @DisplayName("topK 가 결과 개수를 자른다")
    void topKLimitsTheResultCount() throws Exception {
        Scope scope = seedScope("상한", "READY");
        insertChunk(scope, 0, "첫째", NEAR, true, null, null);
        insertChunk(scope, 1, "둘째", NEAR, true, null, null);
        insertChunk(scope, 2, "셋째", NEAR, true, null, null);

        mockMvc.perform(search(scope, NEAR, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    /** 거리가 같을 때 순서가 흔들리면 같은 질문이 매번 다른 인용을 만든다. */
    @Test
    @DisplayName("거리 동률은 documentId·chunkNo 로 고정된다")
    void tiesAreBrokenByDocumentAndChunkNumber() throws Exception {
        Scope scope = seedScope("동률", "READY");
        insertChunk(scope, 2, "세 번째", NEAR, true, null, null);
        insertChunk(scope, 0, "첫 번째", NEAR, true, null, null);
        insertChunk(scope, 1, "두 번째", NEAR, true, null, null);

        mockMvc.perform(search(scope, NEAR, 20))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].chunkNo").value(0))
                .andExpect(jsonPath("$.items[1].chunkNo").value(1))
                .andExpect(jsonPath("$.items[2].chunkNo").value(2));
    }

    @Test
    @DisplayName("결과 0건은 404 가 아니라 200 + 빈 배열이다")
    void anEmptyResultIsTwoHundredWithAnEmptyArray() throws Exception {
        Scope scope = seedScope("빈결과", "READY");

        mockMvc.perform(search(scope, NEAR, 20))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    // ── 격리 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("다른 booth·agent 의 chunk 는 섞이지 않는다")
    void chunksOfAnotherBoothOrAgentNeverAppear() throws Exception {
        Scope mine = seedScope("내부스", "READY");
        Scope other = seedScope("남부스", "READY");
        insertChunk(mine, 0, "내 조각", NEAR, true, null, null);
        insertChunk(other, 0, "남의 조각", NEAR, true, null, null);

        // 내 부스·내 문서인데 agent_id 만 남의 직원인 행. 부스당 직원 1명 제약
        // (V15 ux_ai_agents_booth)이 있어 같은 부스에 직원을 둘 만들 수는 없으므로, agent_id 조건이
        // 실제로 걸리는지는 이렇게 확인한다.
        jdbc.update("""
                INSERT INTO ai_document_chunks (document_id, booth_id, agent_id, chunk_no, content,
                    embedding, embedding_model_id, searchable)
                VALUES (?, ?, ?, 1, '남의 직원 조각', CAST(? AS vector), 'test-embedding-model', TRUE)
                """, mine.documentId(), mine.boothId(), other.agentId(), NEAR);

        mockMvc.perform(search(mine, NEAR, 20))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].content").value("내 조각"));
    }

    @Test
    @DisplayName("searchable = false chunk 는 제외된다")
    void chunksNotYetSearchableAreExcluded() throws Exception {
        Scope scope = seedScope("미준비", "READY");
        insertChunk(scope, 0, "적재 중", NEAR, false, null, null);

        mockMvc.perform(search(scope, NEAR, 20))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    @DisplayName("부모 문서가 READY 가 아니면 제외된다")
    void chunksOfANonReadyDocumentAreExcluded() throws Exception {
        Scope scope = seedScope("처리중", "PROCESSING");
        insertChunk(scope, 0, "처리 중 문서의 조각", NEAR, true, null, null);

        mockMvc.perform(search(scope, NEAR, 20))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    /**
     * chunk 의 scope 만 보면 뚫리는 자리다 (분석 I5).
     *
     * <p>{@code ai_document_chunks} 의 {@code booth_id}·{@code agent_id} 와 {@code document_id} 사이에
     * 제약이 없다. 값이 어긋난 행이 들어오면 chunk 쪽 조건만 검사하는 쿼리는 <b>남의 문서 본문을
     * 내 부스 응답에 실어 준다.</b> 그래서 부모 문서의 scope 도 함께 검사한다.
     */
    @Test
    @DisplayName("chunk scope 는 맞지만 부모 문서가 남의 것이면 제외된다")
    void aChunkWhoseParentDocumentBelongsElsewhereIsExcluded() throws Exception {
        Scope mine = seedScope("어긋남내", "READY");
        Scope other = seedScope("어긋남남", "READY");

        // chunk 는 내 scope 를 달고 있는데 가리키는 문서는 남의 부스 것이다.
        jdbc.update("""
                INSERT INTO ai_document_chunks (document_id, booth_id, agent_id, chunk_no, content,
                    embedding, embedding_model_id, searchable)
                VALUES (?, ?, ?, 0, '남의 문서 본문', CAST(? AS vector), 'test-embedding-model', TRUE)
                """, other.documentId(), mine.boothId(), mine.agentId(), NEAR);

        mockMvc.perform(search(mine, NEAR, 20))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    // ── 요청 검증 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("차원이 1536 이 아니면 거부된다")
    void anEmbeddingOfTheWrongDimensionIsRejected() throws Exception {
        Scope scope = seedScope("차원", "READY");

        for (int dimensions : new int[] {DIMENSIONS - 1, DIMENSIONS + 1}) {
            mockMvc.perform(search(scope, vector(dimensions), 20))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].rule").value("FIELD_INVALID"))
                    .andExpect(jsonPath("$.errors[0].field").value("queryEmbedding"));
        }
    }

    @Test
    @DisplayName("topK 경계 — 0 과 21 은 거부, 20 은 통과")
    void topKBoundsAreEnforced() throws Exception {
        Scope scope = seedScope("경계", "READY");

        for (int topK : new int[] {0, 21}) {
            mockMvc.perform(search(scope, NEAR, topK))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("topK"));
        }
        mockMvc.perform(search(scope, NEAR, 20)).andExpect(status().isOk());
    }

    /**
     * 계약에 없는 필드는 버리지 않고 거부한다.
     *
     * <p>조용히 버리면 보내는 쪽은 필터가 먹었다고 믿는다 — T-24 와 같은 모양이다. 전역
     * {@code ObjectMapper} 는 unknown field 를 버리므로 이 경로는 전용 strict parser 로 읽는다.
     */
    @Test
    @DisplayName("계약에 없는 필드는 400 으로 거부된다")
    void anUnknownFieldIsRejectedRatherThanDropped() throws Exception {
        Scope scope = seedScope("미지필드", "READY");
        String body = """
                {"boothId":%d,"agentId":%d,"queryEmbedding":%s,"topK":5,"minScore":0.5}
                """.formatted(scope.boothId(), scope.agentId(), NEAR);

        mockMvc.perform(post("/internal/ai/chunk-search")
                        .header("Authorization", "Bearer " + SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("minScore"));
    }

    @Test
    @DisplayName("임베딩 원소의 null·비유한 값은 거부된다")
    void nullAndNonFiniteEmbeddingValuesAreRejected() throws Exception {
        Scope scope = seedScope("비유한", "READY");

        // 1e400 은 JSON 문법으로는 정상이고 double 로 읽으면 Infinity 가 된다 — 여기서 걸러야
        // pgvector 가 아니라 우리가 답하는 오류가 된다.
        for (String element : new String[] {"null", "1e400"}) {
            mockMvc.perform(search(scope, vector(DIMENSIONS, element), 20))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("queryEmbedding"));
        }
    }

    @Test
    @DisplayName("boothId·agentId 는 양수여야 한다")
    void scopeIdentifiersMustBePositive() throws Exception {
        String body = """
                {"boothId":0,"agentId":1,"queryEmbedding":%s,"topK":5}
                """.formatted(NEAR);

        mockMvc.perform(post("/internal/ai/chunk-search")
                        .header("Authorization", "Bearer " + SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("boothId"));
    }

    @Test
    @DisplayName("본문이 없으면 400 으로 거부된다")
    void anAbsentBodyIsRejected() throws Exception {
        mockMvc.perform(post("/internal/ai/chunk-search")
                        .header("Authorization", "Bearer " + SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ── 인증 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("토큰이 없거나 틀리면 401")
    void aMissingOrWrongServiceTokenIsUnauthorized() throws Exception {
        Scope scope = seedScope("토큰", "READY");
        String body = requestBody(scope, NEAR, 5);

        mockMvc.perform(post("/internal/ai/chunk-search")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(post("/internal/ai/chunk-search")
                        .header("Authorization", "Bearer wrong-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    /** 사용자 Access Token 으로는 내부 경로가 열리지 않는다 (헌법 17조). */
    @Test
    @DisplayName("사용자 Access Token 으로 접근할 수 없다")
    void aUserAccessTokenDoesNotOpenTheInternalPath() throws Exception {
        Scope scope = seedScope("유저토큰", "READY");

        mockMvc.perform(post("/internal/ai/chunk-search")
                        .header("Authorization", "Bearer " + sessions.issue(scope.userId()).accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(scope, NEAR, 5)))
                .andExpect(status().isUnauthorized());
    }

    // ── 타임아웃 ────────────────────────────────────────────────────────────

    /**
     * 3초 상한이 실제로 statement 에 걸려 있는지 <b>구성</b>을 고정한다.
     *
     * <p>취소가 일어나는 순간까지 재현하려면 검색 SQL 안에 지연을 심어야 하는데, 그러면 운영 쿼리에
     * 테스트용 분기가 생긴다. 여기서 지키려는 것은 "상한이 사라지지 않았다" 이고, 초과 시 응답은
     * 전역 핸들러의 {@code INTERNAL_ERROR} 500 봉투로 수렴한다.
     */
    @Test
    @DisplayName("검색 statement 에 3초 상한이 걸려 있다")
    void theSearchStatementCarriesAThreeSecondCeiling() {
        assertEquals(3, chunkSearch.queryTimeoutSeconds());
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private MockHttpServletRequestBuilder search(Scope scope, String embedding, int topK) {
        return post("/internal/ai/chunk-search")
                .header("Authorization", "Bearer " + SERVICE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(scope, embedding, topK));
    }

    private static String requestBody(Scope scope, String embedding, int topK) {
        return """
                {"boothId":%d,"agentId":%d,"queryEmbedding":%s,"topK":%d}
                """.formatted(scope.boothId(), scope.agentId(), embedding, topK);
    }

    private Scope seedScope(String prefix, String documentStatus) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(userId, prefix + " 부스")).getId();
        grantLease(jdbc, boothId, userId);
        long agentId = insertAgent(boothId);
        Long documentId = jdbc.queryForObject("""
                INSERT INTO ai_documents (booth_id, agent_id, original_filename, content_type,
                    size_bytes, s3_key, processing_status, uploaded_by_user_id, content_sha256,
                    storage_provider, storage_bucket, uploaded_at)
                VALUES (?, ?, 'seed.pdf', 'application/pdf', 1024, ?, ?, ?, ?,
                    'R2', 'test-ai-documents', now())
                RETURNING id
                """, Long.class, boothId, agentId, "seed/" + prefix, documentStatus, userId,
                "%064x".formatted(Math.abs(prefix.hashCode())));
        return new Scope(userId, boothId, agentId, documentId);
    }

    private long insertAgent(Long boothId) {
        return jdbc.queryForObject("""
                INSERT INTO ai_agents (booth_id, name, role_code, tone_code, system_prompt,
                    response_length, status)
                VALUES (?, '도슨트', 'PROJECT_DOCENT', 'FRIENDLY', '문서를 근거로 답한다.', 'MEDIUM', 'ACTIVE')
                RETURNING id
                """, Long.class, boothId);
    }

    private void insertChunk(Scope scope, int chunkNo, String content, String embedding,
                             boolean searchable, Integer pageNumber, String section) {
        jdbc.update("""
                INSERT INTO ai_document_chunks (document_id, booth_id, agent_id, chunk_no, content,
                    embedding, embedding_model_id, page_number, section, searchable)
                VALUES (?, ?, ?, ?, ?, CAST(? AS vector), 'test-embedding-model', ?, ?, ?)
                """, scope.documentId(), scope.boothId(), scope.agentId(), chunkNo, content,
                embedding, pageNumber, section, searchable);
    }

    /**
     * {@code head} 로 앞자리를 채우고 나머지를 {@code 0} 으로 둔 {@code dimensions} 차원 벡터.
     *
     * <p>{@code vector(1536, "1")} 과 {@code vector(1536, "0", "1")} 은 서로 다른 축의 단위 기저
     * 벡터라 코사인 거리가 정확히 0 과 1 이다 — 정렬 기대값이 값 선택에 좌우되지 않는다.
     * {@code dimensions} 를 1536 이 아니게 주거나 {@code head} 에 {@code null}·{@code 1e400} 을
     * 넣으면 그대로 검증 실패용 요청이 된다.
     */
    private static String vector(int dimensions, String... head) {
        StringBuilder vector = new StringBuilder("[");
        for (int i = 0; i < dimensions; i++) {
            vector.append(i == 0 ? "" : ",").append(i < head.length ? head[i] : "0");
        }
        return vector.append(']').toString();
    }

    private record Scope(Long userId, Long boothId, long agentId, Long documentId) { }
}
