package com.example.ssafesta.internal.storage;

import com.example.ssafesta.common.ApiErrorResponse;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.common.RequestIdFilter;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Server-to-server only: where Infra reports what its reconcile run verified (spec 007 FR-035,
 * S15P21A604-500). Contract: {@code specs/007-ai-agent-document/contracts/
 * spring-storage-reconciliation-api.yaml} v0.2.0.
 *
 * <p>{@code AiInternalSecurityConfiguration} already matches all of {@code /internal/**} and
 * requires the Infra→Spring service token on this prefix. The AI direction's token authenticates
 * nothing here.
 *
 * <p>{@code @Hidden} is load-bearing: springdoc scans every {@code @RestController} and
 * {@code /v3/api-docs} is public, so without it this internal path would be published.
 *
 * <p>The body arrives as text, not a bound record: it is parsed by {@code StrictJsonReader} so a
 * field the contract does not define is refused instead of dropped.
 *
 * <h2>Why 409 is built here and not thrown</h2>
 *
 * <p>{@code STALE} and {@code REPLAY_CONFLICT} are decided after the result has been recorded, and
 * that record is what the 409 is telling Infra about. Throwing inside the service's transaction
 * would roll it back. So the service returns an outcome and this method turns it into a status,
 * using the same envelope factory {@code GlobalExceptionHandler} uses.
 */
@Hidden
@RestController
@RequestMapping("/internal/storage")
class StorageReconciliationController {

    private final StorageReconciliationService reconciliation;

    StorageReconciliationController(StorageReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @PostMapping(path = "/reconciliation-runs", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ApiErrorResponse> submit(@RequestBody(required = false) String body) {
        return switch (reconciliation.accept(body)) {
            case APPLIED, LOGGED_ONLY -> ResponseEntity.noContent().build();
            case STALE -> conflict(ErrorCode.RECONCILIATION_STALE);
            case REPLAY_CONFLICT -> conflict(ErrorCode.RECONCILIATION_REPLAY_CONFLICT);
        };
    }

    private static ResponseEntity<ApiErrorResponse> conflict(ErrorCode code) {
        return ResponseEntity.status(code.status())
                .body(ApiErrorResponse.of(code, code.defaultMessage(), RequestIdFilter.current()));
    }
}
