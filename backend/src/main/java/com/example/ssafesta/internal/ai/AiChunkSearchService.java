package com.example.ssafesta.internal.ai;

import com.example.ssafesta.common.ApiException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private static final int MAX_TOP_K = 20;

    private final AiChunkSearchRepository chunks;

    AiChunkSearchService(AiChunkSearchRepository chunks) {
        this.chunks = chunks;
    }

    /**
     * @param rawBody the request body, parsed here rather than bound by the controller — a field
     *                the contract does not define is refused instead of dropped
     *                ({@link StrictJsonReader})
     */
    @Transactional(readOnly = true)
    public ChunkSearchResponse search(String rawBody) {
        ChunkSearchRequest request = StrictJsonReader.read(rawBody, ChunkSearchRequest.class);
        long boothId = positive(request.boothId(), "boothId");
        long agentId = positive(request.agentId(), "agentId");
        int topK = topK(request.topK());
        String embedding = EmbeddingVector.literal(request.queryEmbedding(), "queryEmbedding");

        return new ChunkSearchResponse(chunks.search(boothId, agentId, embedding, topK));
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
