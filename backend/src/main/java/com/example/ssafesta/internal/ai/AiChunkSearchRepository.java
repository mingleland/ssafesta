package com.example.ssafesta.internal.ai;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The one query behind {@code POST /internal/ai/chunk-search} (S15P21A604-398).
 *
 * <p>Plain JDBC rather than JPA: {@code ai_document_chunks.embedding} is {@code vector(1536)} and
 * there is no pgvector Hibernate type on the classpath, so an entity would need the one column that
 * matters marked unmappable. The query is read-only and singular — a mapper is all it needs. The
 * query embedding crosses as text and is cast in SQL, which is also what removes the need for a
 * pgvector driver dependency.
 */
@Repository
class AiChunkSearchRepository {

    private static final Logger log = LoggerFactory.getLogger(AiChunkSearchRepository.class);

    /**
     * Scope is enforced <b>on both sides of the join</b>.
     *
     * <p>Nothing constrains {@code ai_document_chunks.booth_id}/{@code agent_id} to agree with the
     * document the row points at — V21 leaves them independent columns. A query that trusts the
     * chunk's own scope therefore serves another booth's document text under this booth's name the
     * moment a mismatched row exists. Checking {@code d.booth_id}/{@code d.agent_id} as well costs
     * nothing here and closes that path.
     *
     * <p><b>The vector index is deliberately not used</b>, and {@link #EXACT_SCAN} is what makes
     * that true rather than hoped for. With these filters and the join, HNSW post-filters: it takes
     * its nearest candidates first and drops the ones that fail the WHERE clause, which can return
     * fewer than {@code topK} rows even though matching chunks exist. A short result set with no
     * error is the worst failure available here — an answer quietly loses its citations.
     */
    // ponytail: 정확 스캔을 GUC 로 강제한다. 규모가 커져 느려지면 hnsw.iterative_scan =
    //           strict_order (pgvector 0.8+) 가 후필터 손실을 줄여 주지만 recall 을 보장하지는
    //           않는다 — hnsw.max_scan_tuples·scan_mem_multiplier 에서 멈추므로 여전히 topK 보다
    //           적게 올 수 있다. 정확성이 계약인 동안은 이 GUC 가 유일하게 보장하는 수단이다.
    static final String SEARCH = """
            -- 질의 벡터를 MATERIALIZED CTE 에서 딱 한 번만 vector 로 바꾼다 (S15P21A604-654).
            -- 본문에 CAST(:queryEmbedding AS vector) 를 그대로 두면 pgjdbc 가
            -- prepareThreshold=5 로 statement 를 서버 측으로 승격시킨 뒤부터 Postgres 가
            -- generic plan 을 쓰고, 거기서는 이 캐스트가 상수로 접히지 않아 3,000자짜리
            -- 리터럴을 vector_in 이 행마다 다시 파싱한다. 실측에서 6번째 호출부터 3초
            -- statement 상한에 붙었고 회복하지 않았다 (145,646행 기준 395ms -> 3,000ms+).
            -- 비용 모델은 그 파싱을 못 보므로 계획을 바꾸는 것으로는 막을 수 없다.
            -- MATERIALIZED 는 최적화 울타리라 계획 모드와 무관하게 한 번만 평가된다.
            WITH q AS MATERIALIZED (
                SELECT CAST(:queryEmbedding AS vector) AS v
            )
            SELECT c.content,
                   c.chunk_no,
                   c.page_number,
                   c.section,
                   c.document_id,
                   d.original_filename,
                   c.embedding <=> q.v AS distance
              FROM ai_document_chunks c
              JOIN ai_documents d ON d.id = c.document_id
             CROSS JOIN q
             WHERE c.booth_id = :boothId
               AND c.agent_id = :agentId
               AND c.searchable = TRUE
               AND d.processing_status = 'READY'
               AND d.booth_id = :boothId
               AND d.agent_id = :agentId
               -- 거리가 NaN 인 행을 버린다. Jackson 이 NaN 을 문자열 "NaN" 으로 쓰기 때문이다 —
               -- 계약이 number 로 선언한 자리에 문자열이 가므로 타입을 지키는 소비자는 응답
               -- 전체를 버린다. 질의 쪽은 서비스가 400 으로 막지만 저장 쪽은 V21 이 금지하지
               -- 않는다 — embedding 은 NOT NULL 일 뿐이고, 값은 FastAPI 가 계산해 보낸 것이다.
               -- Postgres 는 float8 에서 NaN = NaN 을 참으로 보므로 <> 'NaN' 이 거짓인 행이
               -- 정확히 NaN 인 행이다.
               --
               -- 이 조건이 덮는 것은 노름이 0 인 저장 벡터다 — 전부 0 인 행과, float 로
               -- 누적하면 0 이 되는 행 둘 다 코사인 나눗셈에서 NaN 이 된다. 덮지 못하는 것은
               -- 노름이 float 범위를 넘어 Infinity 가 되는 저장 벡터다: 거리가 NaN 이 되면
               -- 함께 걸러지지만 유한값으로 떨어지면 아무 뜻 없는 거리로 순위에 낀다.
               -- 저장 벡터 자체의 검증은 chunk 를 쓰는 쪽 몫이다 (S15P21A604-400).
               -- 검색이 할 수 있는 것은 여기까지이며, 이 줄을 그 검증의 대체물로 읽으면 안 된다.
               AND (c.embedding <=> q.v) <> 'NaN'::float8
             ORDER BY distance, c.document_id, c.chunk_no
             LIMIT :topK
            """;

    /** The contract's ceiling (`spring-chunk-search-api.yaml`), applied to this statement only. */
    private static final int QUERY_TIMEOUT_SECONDS = 3;

    /**
     * Blocks the HNSW index for this statement, and only for it.
     *
     * <p>pgvector's HNSW is reachable only as an <i>ordered index scan</i>, so turning that node
     * off removes it from the planner's choices while leaving bitmap scans available — which is how
     * {@code ix_ai_document_chunks_scope} and the {@code ai_documents} primary key still get used.
     * The result is exact: every chunk matching the scope is considered before {@code LIMIT}.
     *
     * <p>{@code SET LOCAL} lasts to the <b>end of the transaction</b>, not the end of the
     * statement, so the previous value is read first and put back as soon as the query returns:
     * everything else running in the caller's transaction would otherwise lose index scans too.
     * <b>Reading it beats {@code = DEFAULT}</b>, which resets to the session value and would
     * discard a {@code SET LOCAL} the caller had made for its own reasons earlier in the same
     * transaction.
     *
     * <p><b>It requires an open transaction</b> — Postgres warns and ignores it otherwise, which
     * would drop the guarantee silently. {@code search} is therefore only ever called from within
     * {@code AiChunkSearchService}'s read-only transaction.
     *
     * <p>This is insurance, not something load-bearing today: at present volumes the planner picks
     * {@code ix_ai_document_chunks_scope} plus a sort on its own — one agent's chunks are a narrow
     * slice, so the scope index beats an HNSW walk even with sequential scans disabled. What the
     * setting removes is the day that stops being true.
     */
    private static final String EXACT_SCAN = "SET LOCAL enable_indexscan = off";

    private static final String READ_INDEX_SCANS = "SELECT current_setting('enable_indexscan')";

    /** {@code is_local = true} is {@code SET LOCAL}, and the value can be a parameter this way. */
    private static final String RESTORE_INDEX_SCANS =
            "SELECT set_config('enable_indexscan', ?, true)";

    private final JdbcTemplate template;
    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Builds its own template so the timeout stays local.
     *
     * <p>{@code setQueryTimeout} is per-{@code JdbcTemplate}, so injecting the shared bean and
     * setting it would put a three-second ceiling on every other query in the application.
     */
    AiChunkSearchRepository(DataSource dataSource) {
        this.template = new JdbcTemplate(dataSource);
        this.template.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        this.jdbc = new NamedParameterJdbcTemplate(template);
    }

    List<AiChunkSearchService.ChunkSearchItem> search(long boothId, long agentId,
                                                      String queryEmbedding, int topK) {
        String previousIndexScans = template.queryForObject(READ_INDEX_SCANS, String.class);
        template.execute(EXACT_SCAN);
        try {
            return jdbc.query(SEARCH,
                    Map.of("boothId", boothId, "agentId", agentId,
                            "queryEmbedding", queryEmbedding, "topK", topK),
                    (row, index) -> new AiChunkSearchService.ChunkSearchItem(
                            row.getString("content"),
                            row.getInt("chunk_no"),
                            // getInt reads 0 for SQL NULL; both columns are nullable in V21 and the
                            // contract types them as nullable, so the object accessor is the honest
                            // one.
                            row.getObject("page_number", Integer.class),
                            row.getString("section"),
                            row.getLong("document_id"),
                            row.getString("original_filename"),
                            row.getDouble("distance")));
        } finally {
            // Also on the timeout path: the ceiling firing must not leave the caller's transaction
            // planning everything else without index scans.
            restoreIndexScans(previousIndexScans);
        }
    }

    /**
     * Puts {@code enable_indexscan} back without ever replacing the exception that brought us here.
     *
     * <p>An unguarded restore in {@code finally} is a trap on exactly the path it exists for. The
     * three-second ceiling cancels the statement, Postgres marks the transaction aborted, and every
     * later statement in it fails with {@code current transaction is aborted} — so the restore
     * throws, and <b>that</b> is what the caller sees instead of the timeout. The real failure is
     * gone and the replacement names nothing useful.
     *
     * <p>Swallowing it is safe because the restore is not what protects the caller in that case:
     * {@code SET LOCAL} dies with the transaction, so an aborted one has already undone it. The
     * restore matters only when the caller's transaction survives to run more queries, and that is
     * precisely when this statement succeeds.
     */
    private void restoreIndexScans(String previousIndexScans) {
        try {
            template.queryForObject(RESTORE_INDEX_SCANS, String.class, previousIndexScans);
        } catch (DataAccessException exception) {
            log.warn("enable_indexscan 복원에 실패했습니다 — 트랜잭션 종료가 SET LOCAL 을 되돌립니다.",
                    exception);
        }
    }

    /**
     * The ceiling as the template actually carries it — not the constant.
     *
     * <p>Reading the field back is what makes the test able to fail: an assertion against
     * {@link #QUERY_TIMEOUT_SECONDS} would still pass with the {@code setQueryTimeout} call
     * deleted.
     */
    int queryTimeoutSeconds() {
        return template.getQueryTimeout();
    }
}
