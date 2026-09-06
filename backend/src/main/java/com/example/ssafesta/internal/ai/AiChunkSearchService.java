package com.example.ssafesta.internal.ai;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a chunk search request, enforces the contract on it, and returns what the scope allows
 * (S15P21A604-398, contract {@code specs/008-ai-conversation-rag/contracts/spring-chunk-search-api.yaml}).
 *
 * <p>FastAPI computes the query embedding and nothing else. Every filter that decides <i>which</i>
 * chunks may be seen is a server constant — the request cannot widen it, because no such field
 * exists on it (GitLab #119).
 *
 * <p>An empty result is {@code 200} with an empty list, never a 404. "No chunks in this scope" and
 * "the document is not READY yet" are deliberately indistinguishable to the caller.
 */
@Service
public class AiChunkSearchService {

    /** 헌법 18조·FR-009. Not a tunable — the column is {@code vector(1536)}. */
    private static final int EMBEDDING_DIMENSIONS = 1536;

    private static final int MAX_TOP_K = 20;

    /**
     * Unknown fields are rejected rather than dropped.
     *
     * <p>Spring Boot's shared {@code ObjectMapper} has {@code FAIL_ON_UNKNOWN_PROPERTIES} off, so
     * binding the body as a {@code @RequestBody} record would silently discard a field the caller
     * believed it sent — the caller then reads a full result set as proof that its filter applied.
     * That is the shape of T-24. A private strict mapper closes it without changing the global one,
     * which would alter every other endpoint's behaviour (same reasoning as
     * {@code booth.LayoutJson}).
     */
    private static final JsonMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final AiChunkSearchRepository chunks;

    AiChunkSearchService(AiChunkSearchRepository chunks) {
        this.chunks = chunks;
    }

    /**
     * @param rawBody the request body, parsed here rather than bound by the controller — see
     *                {@link #STRICT}
     */
    @Transactional(readOnly = true)
    public ChunkSearchResponse search(String rawBody) {
        ChunkSearchRequest request = parse(rawBody);
        long boothId = positive(request.boothId(), "boothId");
        long agentId = positive(request.agentId(), "agentId");
        int topK = topK(request.topK());
        String embedding = embedding(request.queryEmbedding());

        return new ChunkSearchResponse(chunks.search(boothId, agentId, embedding, topK));
    }

    private static ChunkSearchRequest parse(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "요청 본문이 비어 있습니다.");
        }
        try {
            return STRICT.readValue(rawBody, ChunkSearchRequest.class);
        } catch (UnrecognizedPropertyException unknown) {
            // The field name is the whole value of this rejection: without it the caller cannot
            // tell which key to remove.
            throw ApiException.fieldInvalid(unknown.getPropertyName(), "계약에 없는 필드입니다.");
        } catch (JacksonException exception) {
            // Jackson's own text is English and names the mapped class, so it is not passed through.
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "요청 JSON 형식이 올바르지 않습니다.");
        }
    }

    private static long positive(Long value, String field) {
        if (value == null || value <= 0) {
            throw ApiException.fieldInvalid(field, "1 이상의 값이어야 합니다.");
        }
        return value;
    }

    private static int topK(Integer value) {
        if (value == null || value < 1 || value > MAX_TOP_K) {
            throw ApiException.fieldInvalid("topK", "1 이상 " + MAX_TOP_K + " 이하여야 합니다.");
        }
        return value;
    }

    /**
     * Renders the embedding as a pgvector literal, refusing anything the column cannot hold.
     *
     * <p>The finiteness check is not defensive noise: {@code 1e400} is valid JSON and parses to
     * {@code Infinity}, which would reach Postgres as the text {@code Infinity} and fail there — a
     * 500 for what is a bad request. Rejecting it here keeps the answer ours.
     */
    private static String embedding(List<Double> values) {
        if (values == null || values.size() != EMBEDDING_DIMENSIONS) {
            throw ApiException.fieldInvalid("queryEmbedding",
                    EMBEDDING_DIMENSIONS + "개의 값이어야 합니다.");
        }
        StringBuilder vector = new StringBuilder(EMBEDDING_DIMENSIONS * 8).append('[');
        for (int index = 0; index < values.size(); index++) {
            Double value = values.get(index);
            if (value == null || !Double.isFinite(value)) {
                throw ApiException.fieldInvalid("queryEmbedding",
                        "유한한 수만 넣을 수 있습니다.");
            }
            if (index > 0) {
                vector.append(',');
            }
            vector.append(value.doubleValue());
        }
        return vector.append(']').toString();
    }

    /** The request as the contract defines it. There is no filter field, and that is the point. */
    public record ChunkSearchRequest(Long boothId, Long agentId, List<Double> queryEmbedding,
                                     Integer topK) { }

    public record ChunkSearchResponse(List<ChunkSearchItem> items) { }

    /**
     * One hit, in the contract's field order and its camelCase names.
     *
     * <p>{@code distance} is the pgvector cosine distance ({@code <=>}) as the database computed it
     * — lower is nearer. The server neither inverts nor normalises it, and applies no minimum
     * threshold (GitLab #119, settled 2026-09-03). {@code pageNumber} and {@code section} are
     * nullable because V21 leaves them nullable; the contract types them the same way.
     */
    public record ChunkSearchItem(String content, int chunkNo, Integer pageNumber, String section,
                                  long documentId, String originalFilename, double distance) { }
}
