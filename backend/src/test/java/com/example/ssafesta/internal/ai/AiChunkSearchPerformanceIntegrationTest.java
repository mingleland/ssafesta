package com.example.ssafesta.internal.ai;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 최대 부하에서의 chunk 검색 — 속도(SC-006)와 정확 스캔 유지(SC-007a), T098 / `S15P21A604-521`.
 *
 * <p>fixture 는 `N_total` = <b>145,646</b> chunk 다. 100MiB ÷ stride 720 토큰 + 문서당 꼬리 창
 * 10 개로 나온 보수적 상한이고 산출 근거는 [plan.md §3] 에 있다. 이 값보다 작은 fixture 로는
 * SC-006 을 잰 것이 아니다.
 *
 * <p><b>왜 {@code stress} 인가.</b> 시드가 수 분, 측정이 100회라 기본 빌드에 넣을 수 없다
 * ({@code pom.xml} 의 {@code test.excludedGroups}). 실행:
 * {@code ./mvnw test -Dtest.excludedGroups= -Dgroups=stress}
 *
 * <p><b>MockMvc 를 쓰지 않는다.</b> {@code @AutoConfigureMockMvc} 는 컨텍스트 캐시 키를 바꿔
 * 컨테이너가 한 벌 더 뜬다. 여기서 재는 것은 HTTP 가 아니라 쿼리이므로
 * {@link AiChunkSearchService#search}를 직접 부른다 — 그쪽이 {@code @Transactional} 이라
 * {@code SET LOCAL enable_indexscan = off} 가 실제로 사는 유일한 경로이기도 하다.
 *
 * <p><b>임베딩은 DB 안에서 만든다.</b> 1536차 × 145,646행을 자바에서 리터럴로 찍으면 수백 MB
 * 짜리 SQL 이 된다. 기준 벡터 하나를 SQL 로 만들고 {@code subvector} 로 창을 밀어 쓴다.
 *
 * <p><b>SC-006 은 현재 붉다. 스캔이 느려서가 아니다</b> (2026-09-11 실측, `S15P21A604-521`).
 * 같은 커넥션에서 같은 문장을 10번째 부르는 순간 지연이 ~395ms 에서 3초 상한 초과로 떨어진다 —
 * pgjdbc 가 서버 prepared statement 로 올리고(기본 {@code prepareThreshold=5}) Postgres 가
 * generic plan 으로 갈아타면서({@code plan_cache_mode=auto}) {@code CAST(:queryEmbedding AS
 * vector)} 를 상수로 접지 못하게 되고, 3,000자짜리 벡터 텍스트를 145,646행마다 다시 파싱한다.
 * {@code SET LOCAL plan_cache_mode = force_custom_plan} 을 걸면 14회 전부 ~0.6초로 돌아온다.
 * 즉 <b>정확 스캔 자체는 SC-006 안에 있고</b>, 무너지는 것은 계획 캐시 경로다. 임계값을 올려
 * 통과시키지 마라 — 고칠 것은 쿼리 쪽이며 그 결정은 팀 몫이다.
 */
@Tag("stress")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiChunkSearchPerformanceIntegrationTest {

    private static final Logger log =
            LoggerFactory.getLogger(AiChunkSearchPerformanceIntegrationTest.class);

    /** plan.md §3 이 확정한 값. 낮추면 SC-006 을 잰 것이 아니게 된다. */
    private static final int N_TOTAL = 145_646;

    /** FR-018 의 AI 직원당 문서 상한. `N_total` 은 이 개수로 나뉜다. */
    private static final int DOCUMENTS = 10;

    /** 헌법 18조·FR-009 로 고정. */
    private static final int DIMENSIONS = 1536;

    /**
     * 기준 벡터에서 잘라낼 창의 개수 — 행마다 다른 임베딩을 주되 기준 벡터 하나로 끝낸다.
     *
     * <p>거리 계산 비용은 값이 아니라 차원 수가 정한다. 창이 몇 개든 P95 는 같고, 창을 두는
     * 이유는 모든 행이 똑같은 벡터일 때 생기는 전역 동률을 피하는 것뿐이다.
     */
    private static final int WINDOWS = 256;

    /** SC-007a 의 질의–정답 쌍 수. 문서 10개 × 앞 10개 chunk 에 심는다. */
    private static final int PAIRS = 100;

    private static final int TOP_K = 10;
    private static final int WARMUP = 5;
    private static final int MEASURED = 100;

    /** SC-006 의 상한. 넘으면 붉은 채로 둔다 — 임계값은 팀 결정이다. */
    private static final long P95_CEILING_MILLIS = 1_000;

    /** {@code AiChunkSearchRepository.QUERY_TIMEOUT_SECONDS} 가 statement 에 건 상한. */
    private static final long TIMEOUT_CEILING_MILLIS = 3_000;

    /**
     * 저선택도 범위: 검색 가능한 행은 12개뿐이고 나머지 50,000행은 필터에 걸린다.
     *
     * <p>필터에 걸리는 쪽이 이렇게 커야 하는 이유는 <b>플래너</b>다. 8,000행으로 재 봤더니
     * 플래너가 {@code ix_ai_document_chunks_scope} 비트맵 스캔 + 정렬을 골라서, 정확 스캔 GUC 를
     * 떼어 낸 변이에서도 10건이 그대로 돌아왔다 — 잡으라고 만든 회귀를 못 잡는 장식이었다.
     * 범위가 커져 정렬 비용이 HNSW ordered scan 을 넘어야 플래너가 인덱스를 고르고, 그제서야
     * post-filter 가 Top-K 를 깎는 실제 실패 모드가 재현된다.
     */
    private static final int SPARSE_SEARCHABLE = 12;
    private static final int SPARSE_FILTERED = 50_000;
    private static final int SPARSE_AXIS = 1_000;

    private static final String READ_INDEX_SCANS = "SELECT current_setting('enable_indexscan')";

    @Autowired private AiChunkSearchService search;
    @Autowired private AiChunkSearchRepository chunkSearch;
    @Autowired private UserRepository users;
    @Autowired private BoothRepository booths;
    @Autowired private WalletService wallets;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    private Scope load;
    private Scope sparse;
    private List<Long> loadDocumentIds;
    private long seededChunkRows;
    private long seededScopeRows;
    private long seedMillis;

    /**
     * 한 번만 시드한다 — PER_CLASS 라 {@code @BeforeAll} 이 non-static 이고 주입된 빈을 쓴다.
     *
     * <p>HNSW 인덱스가 걸린 테이블에 145,646 행을 넣는 일이라 수 분이 걸린다. 인덱스를 떼고
     * 넣은 뒤 다시 만들면 빠르지만, 그러면 재는 대상이 운영 스키마가 아니게 된다 — 저선택도
     * 회귀 검출은 HNSW 가 실제로 거기 있어야 의미가 있다.
     */
    @BeforeAll
    void seedMaximumLoad() {
        long startedAt = System.nanoTime();
        load = newScope("load");
        loadDocumentIds = new ArrayList<>();
        for (int document = 0; document < DOCUMENTS; document++) {
            // 나머지는 앞쪽 문서에 1행씩 얹는다 — 합계가 정확히 N_total 이 된다.
            int rows = N_TOTAL / DOCUMENTS + (document < N_TOTAL % DOCUMENTS ? 1 : 0);
            long documentId = insertDocument(load, document);
            loadDocumentIds.add(documentId);
            insertChunks(load, documentId, rows, true);
        }
        plantAnswerChunks();
        analyze();
        seededChunkRows = count("SELECT count(*) FROM ai_document_chunks");
        seededScopeRows = count("SELECT count(*) FROM ai_document_chunks WHERE booth_id = %d AND agent_id = %d"
                .formatted(load.boothId(), load.agentId()));
        seedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        log.info("시드 완료 — 전체 {} 행, scope {} 행, {} ms", seededChunkRows, seededScopeRows, seedMillis);

        seedSparseScope();
        logPlans();
    }

    // ── SC-006 ──────────────────────────────────────────────────────────────

    /**
     * P95 가 1초를 넘어도 단정을 낮추지 않는다. 이 테스트는 {@code stress} 라 기본 빌드·CI 에서
     * 빠지므로 붉은 채로 두어도 남의 파이프라인을 멈추지 않는다 — 인덱스 전략을 바꿀지 상한을
     * 조정할지는 팀이 측정값을 보고 정할 일이고, 통과시키려고 임계값을 만지면 그 결정 자체가
     * 사라진다.
     */
    @Test
    @DisplayName("최대 부하에서 검색 P95 가 1초 이하다 (SC-006)")
    void searchStaysWithinOneSecondAtP95() {
        String body = requestBody(load, basis(0), TOP_K);
        long[] all = new long[WARMUP + MEASURED];
        int failures = 0;
        String firstFailure = "";
        int lastItems = -1;
        for (int attempt = 0; attempt < all.length; attempt++) {
            long startedAt = System.nanoTime();
            try {
                lastItems = search.search(body).items().size();
            } catch (RuntimeException failure) {
                failures++;
                firstFailure = firstFailure.isEmpty() ? " 첫 실패=" + failure : firstFailure;
            }
            all[attempt] = (System.nanoTime() - startedAt) / 1_000_000L;
        }

        // 정렬하면 사라지는 것이 순서다. 같은 문장을 반복하면 pgjdbc 가 서버 prepared statement
        // 로 올리고 Postgres 가 generic plan 으로 갈아타므로, 앞 몇 회만 빠르고 그 뒤가 통째로
        // 느려지는 계단이 나올 수 있다 — 분위수만 보면 "전부 느리다" 로 보인다.
        log.info("측정 순서 앞 20회(ms): {}", Arrays.toString(Arrays.copyOf(all, Math.min(20, all.length))));

        long[] measured = Arrays.copyOfRange(all, WARMUP, all.length);
        Arrays.sort(measured);
        long p50 = percentile(measured, 0.50);
        long p95 = percentile(measured, 0.95);
        long max = measured[measured.length - 1];
        // 스캔 비용은 행 수에 선형이므로 P95 가 1초에 닿는 지점을 비례로 민다. 상한을 넘었을 때
        // "얼마나 넘었나" 보다 "몇 행까지가 1초인가" 가 팀이 볼 값이다.
        long crossover = p95 == 0 ? -1 : seededScopeRows * P95_CEILING_MILLIS / p95;
        String report = ("SC-006 측정 — scope %d 행 (N_total=%d), topK=%d, n=%d: "
                + "P50=%dms P95=%dms max=%dms 실패=%d, 1초 교차점 추정 ≈ %d 행, 시드 %dms")
                .formatted(seededScopeRows, N_TOTAL, TOP_K, measured.length,
                        p50, p95, max, failures, crossover, seedMillis) + firstFailure;
        log.info(report);

        assertEquals(0, failures, report);
        assertEquals(TOP_K, lastItems, report);
        assertTrue(max < TIMEOUT_CEILING_MILLIS,
                "statement 상한 3초 안에 끝나야 한다 — " + report);
        assertTrue(p95 <= P95_CEILING_MILLIS, "SC-006: P95 1초 이하여야 한다 — " + report);
    }

    @Test
    @DisplayName("fixture 가 정확히 N_total 행이다")
    void theFixtureIsExactlyNTotalRows() {
        assertEquals(N_TOTAL, seededChunkRows, "시드 직후 전체 chunk 행 수");
        assertEquals(N_TOTAL, seededScopeRows, "AI 직원 한 명의 scope 안 chunk 행 수");
    }

    // ── SC-007a ─────────────────────────────────────────────────────────────

    /**
     * 쌍마다 축이 다르다. 하나의 축을 100번 물으면 같은 첫 행이 100번 돌아오고 100/100 이 되는데,
     * 그건 정확 스캔이 아니라 동률 하나를 100번 센 것이다.
     */
    @Test
    @DisplayName("최대 부하에서 정답 청크 100/100 이 Top-1 이다 (SC-007a)")
    void everyPlantedAnswerChunkComesBackFirst() {
        List<String> misses = new ArrayList<>();
        for (int pair = 0; pair < PAIRS; pair++) {
            long expectedDocument = loadDocumentIds.get(pair / DOCUMENTS);
            int expectedChunkNo = pair % DOCUMENTS;
            List<AiChunkSearchService.ChunkSearchItem> items;
            try {
                items = search.search(requestBody(load, basis(pair), TOP_K)).items();
            } catch (RuntimeException failure) {
                // 3초 상한에 걸린 질의도 "정답 청크를 못 찾았다" 로 센다. 여기서 예외를 그대로
                // 올리면 몇 쌍이 성공했는지가 보고서에서 사라진다.
                misses.add("쌍 %d: 질의 실패 %s".formatted(pair, failure));
                continue;
            }
            if (items.size() != TOP_K) {
                misses.add("쌍 %d: %d건만 돌아옴".formatted(pair, items.size()));
                continue;
            }
            AiChunkSearchService.ChunkSearchItem top = items.get(0);
            // 동일 벡터의 코사인 거리는 0 이지만 float 누산이라 정확히 0 임에 기대지 않는다.
            // 가장 가까운 다른 행이 0.97 대라 어떤 작은 epsilon 이든 둘을 가른다.
            if (top.documentId() != expectedDocument || top.chunkNo() != expectedChunkNo
                    || top.distance() > 1e-6) {
                misses.add("쌍 %d: 기대 (doc %d, chunk %d) / 실제 (doc %d, chunk %d, 거리 %s)"
                        .formatted(pair, expectedDocument, expectedChunkNo,
                                top.documentId(), top.chunkNo(), top.distance()));
            }
        }
        assertEquals(0, misses.size(),
                "SC-007a: 질의–정답 쌍 %d개 중 Top-1 이 아닌 쌍의 수. 앞 3개: %s"
                        .formatted(PAIRS, misses.stream().limit(3).toList()));
    }

    // ── 정확 스캔 회귀 검출 ───────────────────────────────────────────────────

    /**
     * 동일 벡터 Top-1 은 HNSW 로 바뀌어도 통과한다 — 그래서 결과가 아니라 <b>경로</b>를 본다.
     *
     * <p>{@code AiChunkSearchRepository.EXACT_SCAN} 이 지워지거나 {@code SET LOCAL} 이 사라지면
     * {@code applied} 가 {@code on} 이 되어 규모·플래너 선택과 무관하게 붉어진다. 복원 단정은
     * 반대쪽 — 검색이 호출자의 트랜잭션에 {@code off} 를 남기고 나가면 그 트랜잭션의 나머지
     * 쿼리가 전부 인덱스 없이 계획된다.
     *
     * <p><b>최대 부하가 아니라 작은 범위에서 본다.</b> 보는 것은 GUC 를 밟았는가이지 얼마나
     * 걸렸는가가 아니라 규모와 무관하고, 최대 부하에서 돌리면 이 단정이 3초 statement 상한에
     * 인질로 잡힌다 — SC-006 이 붉은 동안 질의가 취소되어 이 테스트가 {@code QueryTimeoutException}
     * 으로 죽고, 정작 회귀를 잡으라고 만든 단정이 한 번도 평가되지 않는다. 실측했다.
     */
    @Test
    @DisplayName("운영 검색 경로가 enable_indexscan 을 off 로 적용하고 원래 값으로 되돌린다")
    void theProductionPathAppliesAndRestoresTheExactScanSetting() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            String before = jdbc.queryForObject(READ_INDEX_SCANS, String.class);
            AiChunkSearchRepository.ExactScan<List<AiChunkSearchService.ChunkSearchItem>> observed =
                    chunkSearch.searchWithDiagnostics(sparse.boothId(), sparse.agentId(),
                            basis(SPARSE_AXIS), TOP_K);

            assertEquals("off", observed.applied(),
                    "검색이 도는 동안 enable_indexscan 이 off 여야 정확 스캔이 보장된다");
            assertEquals(before, observed.restored(), "검색 뒤 원래 값으로 되돌려야 한다");
            assertEquals(before, jdbc.queryForObject(READ_INDEX_SCANS, String.class),
                    "호출자의 트랜잭션에 off 가 남으면 안 된다");
            assertEquals(TOP_K, observed.value().size(), "진단 진입점도 같은 결과를 준다");
        });
    }

    /**
     * HNSW 가 실제로 실패하는 방식 — post-filter 로 Top-K 가 조용히 짧아지는 것.
     *
     * <p>범위 안 12행만 {@code searchable} 이고 50,000행은 필터에 걸리며, 테이블 전체는 다른 부스의
     * 145,646행이다. 질의 벡터에 가장 가까운 전역 이웃은 전부 남의 부스 행이라, 인덱스가 먼저
     * 고르고 나중에 거르는 순서가 되면 topK=10 을 채우지 못한다. 정확 스캔은 범위 안 12행을 모두
     * 채점하므로 10건이 온다.
     *
     * <p>{@link #SPARSE_FILTERED} 가 이 테스트를 장식이 아니게 만드는 값이다 — 그 주석 참조.
     */
    @Test
    @DisplayName("선택도가 낮은 범위에서도 topK 를 채운다 — post-filter 회귀 검출")
    void aLowSelectivityScopeStillFillsTopK() {
        List<AiChunkSearchService.ChunkSearchItem> items =
                search.search(requestBody(sparse, basis(SPARSE_AXIS), TOP_K)).items();
        assertEquals(TOP_K, items.size(),
                "범위 안 검색 가능 행이 %d개인데 %d건만 돌아왔다 — post-filter 로 Top-K 가 짧아진 것이다"
                        .formatted(SPARSE_SEARCHABLE, items.size()));
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private void seedSparseScope() {
        sparse = newScope("sparse");
        long documentId = insertDocument(sparse, 0);
        insertChunks(sparse, documentId, SPARSE_FILTERED, false);
        for (int offset = 0; offset < SPARSE_SEARCHABLE; offset++) {
            jdbc.update("""
                    INSERT INTO ai_document_chunks (document_id, booth_id, agent_id, chunk_no,
                        content, embedding, embedding_model_id, page_number, section, searchable)
                    VALUES (?, ?, ?, ?, '저선택도 정답 후보', CAST(? AS vector),
                        'test-embedding-model', 1, 'sec-0', TRUE)
                    """, documentId, sparse.boothId(), sparse.agentId(), SPARSE_FILTERED + offset,
                    basis(SPARSE_AXIS + offset));
        }
        analyze();
    }

    /** 참고용이다 — 계획 문구는 Postgres 마이너 버전마다 바뀌므로 아무것도 단정하지 않는다. */
    private void logPlans() {
        logPlan("최대 부하", load, basis(0));
        logPlan("저선택도", sparse, basis(SPARSE_AXIS));
    }

    private void logPlan(String label, Scope scope, String embedding) {
        // 각자 자기 트랜잭션에서 돈다. EXPLAIN ANALYZE 는 쿼리를 실제로 한 번 더 돌리므로 3초
        // statement 상한에 걸릴 수 있고, 그러면 트랜잭션이 abort 되어 뒤따르는 문장이 전부 죽는다.
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> log.info("{} 계획:\n{}",
                    label, String.join("\n", chunkSearch.explainSearch(
                            scope.boothId(), scope.agentId(), embedding, TOP_K))));
        } catch (RuntimeException failure) {
            log.warn("{} 계획을 얻지 못했다 — 참고용이라 넘어간다.", label, failure);
        }
    }

    /**
     * 기준 벡터 하나를 SQL 로 만들고 {@code subvector} 로 창을 밀어 한 문장에 {@code rows} 행을
     * 넣는다. 자바에서 1536차 리터럴을 찍으면 문서 하나가 100MB 짜리 SQL 이 된다.
     */
    private void insertChunks(Scope scope, long documentId, int rows, boolean searchable) {
        jdbc.update("""
                WITH base AS (
                  SELECT ('[' || string_agg((0.1 + mod(i * 37, 97) / 100.0)::text, ',' ORDER BY i) || ']')::vector AS v
                    FROM generate_series(1, ?) AS i
                )
                INSERT INTO ai_document_chunks (document_id, booth_id, agent_id, chunk_no, content,
                    embedding, embedding_model_id, page_number, section, searchable)
                SELECT ?, ?, ?, n,
                       '최대 부하 픽스처 청크 ' || n,
                       subvector(base.v, 1 + mod(n, ?), ?),
                       'test-embedding-model', 1 + mod(n, 40), 'sec-' || mod(n, 7), ?
                  FROM generate_series(0, ? - 1) AS n, base
                """, DIMENSIONS + WINDOWS, documentId, scope.boothId(), scope.agentId(),
                WINDOWS, DIMENSIONS, searchable, rows);
    }

    /** 쌍 k → 문서 k/10 의 chunk k%10 을 기저 벡터 e_k 로 바꾼다. 100개가 서로 다른 축이다. */
    private void plantAnswerChunks() {
        for (int pair = 0; pair < PAIRS; pair++) {
            int updated = jdbc.update("""
                    UPDATE ai_document_chunks SET embedding = CAST(? AS vector)
                     WHERE document_id = ? AND chunk_no = ?
                    """, basis(pair), loadDocumentIds.get(pair / DOCUMENTS), pair % DOCUMENTS);
            assertEquals(1, updated, "정답 청크 %d 를 심지 못했다".formatted(pair));
        }
    }

    private void analyze() {
        jdbc.execute("ANALYZE ai_document_chunks");
        jdbc.execute("ANALYZE ai_documents");
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private Scope newScope(String prefix) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(userId, prefix + " 부스")).getId();
        long agentId = jdbc.queryForObject("""
                INSERT INTO ai_agents (booth_id, name, role_code, tone_code, system_prompt,
                    response_length, status)
                VALUES (?, '도슨트', 'PROJECT_DOCENT', 'FRIENDLY', '문서를 근거로 답한다.', 'MEDIUM', 'ACTIVE')
                RETURNING id
                """, Long.class, boothId);
        return new Scope(userId, boothId, agentId);
    }

    private long insertDocument(Scope scope, int ordinal) {
        return jdbc.queryForObject("""
                INSERT INTO ai_documents (booth_id, agent_id, original_filename, content_type,
                    size_bytes, s3_key, processing_status, uploaded_by_user_id, content_sha256,
                    storage_provider, storage_bucket, uploaded_at)
                VALUES (?, ?, ?, 'text/markdown', 10485760, ?, 'READY', ?, ?,
                    'R2', 'test-ai-documents', now())
                RETURNING id
                """, Long.class, scope.boothId(), scope.agentId(), "load-%d.md".formatted(ordinal),
                "seed/%d/%d".formatted(scope.agentId(), ordinal), scope.userId(),
                "%064x".formatted(scope.agentId() * 1_000L + ordinal));
    }

    private static String requestBody(Scope scope, String embedding, int topK) {
        return """
                {"boothId":%d,"agentId":%d,"queryEmbedding":%s,"topK":%d}
                """.formatted(scope.boothId(), scope.agentId(), embedding, topK);
    }

    /**
     * {@code axis} 자리만 1 인 단위 기저 벡터. JSON 배열이자 pgvector 리터럴이라 요청 본문과
     * {@code CAST(? AS vector)} 양쪽에 그대로 쓴다.
     */
    private static String basis(int axis) {
        StringBuilder vector = new StringBuilder(DIMENSIONS * 2 + 2).append('[');
        for (int index = 0; index < DIMENSIONS; index++) {
            vector.append(index == 0 ? "" : ",").append(index == axis ? '1' : '0');
        }
        return vector.append(']').toString();
    }

    /** {@code ceil(p·n) - 1} — 정렬된 표본에서 p 분위가 놓이는 자리. */
    private static long percentile(long[] sorted, double quantile) {
        return sorted[(int) Math.ceil(quantile * sorted.length) - 1];
    }

    private record Scope(Long userId, Long boothId, long agentId) { }
}
