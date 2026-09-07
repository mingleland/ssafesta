package com.example.ssafesta.internal.ai;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Server-to-server only: where a FastAPI worker sends what it produced (S15P21A604-400).
 *
 * <p>Same prefix and therefore the same chain as {@link AiBoothAccessController} —
 * {@link AiInternalSecurityConfiguration} already matches {@code /internal/ai/**} and requires the
 * FastAPI→Spring service token.
 *
 * <p>Both operations answer {@code 204}: the caller sent the state, and there is nothing to tell it
 * back that it does not already know. What it must branch on is the status — {@code 409} for a
 * stale attempt, {@code 410} for a Job that no longer exists.
 *
 * <p>Bodies arrive as text, not bound records: they are parsed by {@link StrictJsonReader} so a
 * field the contract does not define is refused instead of dropped.
 */
@Hidden
@RestController
@RequestMapping("/internal/ai/document-jobs")
class AiDocumentResultController {

    private final AiDocumentResultService results;

    AiDocumentResultController(AiDocumentResultService results) {
        this.results = results;
    }

    @PostMapping(path = "/{jobId}/chunk-batches", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void chunkBatch(@PathVariable long jobId, @RequestBody(required = false) String body) {
        results.acceptBatch(jobId, body);
    }

    @PostMapping(path = "/{jobId}/finalize", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void finalizeJob(@PathVariable long jobId, @RequestBody(required = false) String body) {
        results.finalizeJob(jobId, body);
    }
}
