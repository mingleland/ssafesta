package com.example.ssafesta.internal.ai;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
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

    /**
     * Scope is enforced <b>on both sides of the join</b>.
     *
     * <p>Nothing constrains {@code ai_document_chunks.booth_id}/{@code agent_id} to agree with the
     * document the row points at — V21 leaves them independent columns. A query that trusts the
     * chunk's own scope therefore serves another booth's document text under this booth's name the
     * moment a mismatched row exists. Checking {@code d.booth_id}/{@code d.agent_id} as well costs
     * nothing here and closes that path.
     *
     * <p><b>The vector index is deliberately not relied upon.</b> With these filters and the join,
     * HNSW post-filters: it takes its nearest candidates first and drops the ones that fail the
     * WHERE clause, which can return fewer than {@code topK} rows even though matching chunks
     * exist. A short result set with no error is the worst failure available here — an answer
     * quietly loses its citations — so the planner is left to choose the exact scan.
     */
    // ponytail: 순차 스캔에 맡긴다. 규모가 커져 느려지면 hnsw.iterative_scan = strict_order 또는
    //           (booth_id, agent_id) partial index 로 올린다. 인덱스 후필터는 topK 보다 적게
    //           돌려줄 수 있어 지금은 정확성을 택한다.
    private static final String SEARCH = """
            SELECT c.content,
                   c.chunk_no,
                   c.page_number,
                   c.section,
                   c.document_id,
                   d.original_filename,
                   c.embedding <=> CAST(:queryEmbedding AS vector) AS distance
              FROM ai_document_chunks c
              JOIN ai_documents d ON d.id = c.document_id
             WHERE c.booth_id = :boothId
               AND c.agent_id = :agentId
               AND c.searchable = TRUE
               AND d.processing_status = 'READY'
               AND d.booth_id = :boothId
               AND d.agent_id = :agentId
             ORDER BY distance, c.document_id, c.chunk_no
             LIMIT :topK
            """;

    /** The contract's ceiling (`spring-chunk-search-api.yaml`), applied to this statement only. */
    private static final int QUERY_TIMEOUT_SECONDS = 3;

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
        return jdbc.query(SEARCH,
                Map.of("boothId", boothId, "agentId", agentId,
                        "queryEmbedding", queryEmbedding, "topK", topK),
                (row, index) -> new AiChunkSearchService.ChunkSearchItem(
                        row.getString("content"),
                        row.getInt("chunk_no"),
                        // getInt reads 0 for SQL NULL; both columns are nullable in V21 and the
                        // contract types them as nullable, so the object accessor is the honest one.
                        row.getObject("page_number", Integer.class),
                        row.getString("section"),
                        row.getLong("document_id"),
                        row.getString("original_filename"),
                        row.getDouble("distance")));
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
