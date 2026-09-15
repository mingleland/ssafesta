package com.example.ssafesta.internal.ai;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Server-to-server only: FastAPI's single search entry point (spec 008, S15P21A604-398).
 *
 * <p>Same prefix and therefore the same chain as {@link AiBoothAccessController} —
 * {@link AiInternalSecurityConfiguration} already matches {@code /internal/ai/**} and requires the
 * FastAPI→Spring service token, so this endpoint adds no security configuration of its own.
 *
 * <p>{@link Hidden} keeps it out of {@code /v3/api-docs}, which is public. Its contract lives in
 * {@code specs/008-ai-conversation-rag/contracts/spring-chunk-search-api.yaml}.
 *
 * <p>The body arrives as text, not a bound record: it is parsed by a strict mapper in the service
 * so a field the contract does not define is refused instead of dropped
 * ({@link AiChunkSearchService}).
 */
@Hidden
@RestController
@RequestMapping("/internal/ai")
class AiChunkSearchController {

    private final AiChunkSearchService search;

    AiChunkSearchController(AiChunkSearchService search) {
        this.search = search;
    }

    @PostMapping(path = "/chunk-search", consumes = MediaType.APPLICATION_JSON_VALUE)
    AiChunkSearchService.ChunkSearchResponse chunkSearch(@RequestBody(required = false) String body) {
        return search.search(body);
    }
}
