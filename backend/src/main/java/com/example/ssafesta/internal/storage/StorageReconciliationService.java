package com.example.ssafesta.internal.storage;

import com.example.ssafesta.common.ApiErrorDetail;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.internal.ai.StrictJsonReader;
import com.example.ssafesta.internal.storage.StorageReconciliationRepository.DocumentRow;
import com.example.ssafesta.internal.storage.StorageReconciliationRepository.Outcome;
import com.example.ssafesta.internal.storage.StorageReconciliationRepository.Payload;
import com.example.ssafesta.internal.storage.StorageReconciliationRepository.StoredResult;
import com.example.ssafesta.storage.ObjectStorageProperties;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records one reconcile result and, when it is a still-valid {@code VERIFIED}, moves the document to
 * the verified storage location (spec 007 FR-035, S15P21A604-500).
 *
 * <h2>Where a rejection is thrown and where it is returned</h2>
 *
 * <p>Everything decided <b>before</b> the result is recorded — 422, 404, 500 — throws, and the
 * transaction rolls back with nothing written. Everything decided <b>after</b> is returned as an
 * {@link Outcome} instead: a {@code STALE} result was received correctly and its record is the whole
 * point of the response, so throwing there would delete the evidence the 409 is about.
 */
@Service
class StorageReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(StorageReconciliationService.class);

    /**
     * The contract's {@code sourceProvider}/{@code targetProvider} enum.
     *
     * <p>Spelled out again rather than borrowed from {@code ObjectStorageProperties}: that set says
     * which providers this deployment may be configured with, this one says which values the wire
     * contract allows. A deployment configured for only one of them still has to refuse a third name
     * as a contract violation rather than as a missing setting.
     */
    private static final Set<String> PROVIDERS = Set.of("R2", "MINIO_LOCAL");

    private static final Set<String> STATUSES = Set.of("VERIFIED", "MISMATCH", "MISSING");

    private static final String VERIFIED = "VERIFIED";
    private static final String MISSING = "MISSING";

    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");

    private final StorageReconciliationRepository results;
    private final ObjectStorageProperties storage;

    StorageReconciliationService(StorageReconciliationRepository results,
                                 ObjectStorageProperties storage) {
        this.results = results;
        this.storage = storage;
    }

    /**
     * The record and the document update are one write.
     *
     * <p>The old rule of thumb says {@code @Transactional} is ignored on a non-public method
     * ({@code publicMethodsOnly}). On this version it is <b>not</b> — class-based proxies advise
     * package-private methods too, and {@code aFailedDocumentUpdateRollsBackTheRecord} was measured
     * both ways to confirm it. That test is what makes the visibility here a free choice rather than
     * a silent one: if the transaction ever stops applying, the record survives a failed update and
     * that test goes red.
     */
    @Transactional
    Outcome accept(String rawBody) {
        Payload payload = validate(
                StrictJsonReader.read(rawBody, Request.class, ErrorCode.RECONCILIATION_INVALID));

        // Replay fast-path. A result already recorded is answered from what was recorded, without
        // locking the document and without reading the storage settings: a deployment whose
        // settings changed since must not turn an already-processed request into a different
        // answer.
        Optional<StoredResult> recorded = results.find(payload.runId(), payload.documentId());
        if (recorded.isPresent()) {
            return replayOutcome(recorded.get(), payload);
        }

        DocumentRow document = results.lockDocument(payload.documentId())
                .orElseThrow(() -> new ApiException(ErrorCode.DOCUMENT_NOT_FOUND));
        Decision decision = judge(payload, document);

        if (results.insertIfAbsent(payload, decision.outcome()) == 0) {
            // Another request for the same key won the race between the fast-path read and this
            // insert. Its record is the answer, by the same rule the fast-path uses.
            return replayOutcome(results.find(payload.runId(), payload.documentId()).orElseThrow(),
                    payload);
        }
        if (decision.outcome() == Outcome.APPLIED) {
            results.applyStorageLocation(payload.documentId(), payload.targetProvider(),
                    decision.targetBucket());
        }
        return decision.outcome();
    }

    /**
     * What this result does to the document.
     *
     * <p>The source check is what keeps a late result from undoing a newer one: a run that started
     * from a provider the document has since left describes a state that no longer exists.
     *
     * <p>It only holds while <b>one document has at most one reconcile run in flight</b> — the
     * premise the contract states. Without it a document that moved away and back would look
     * untouched here, and {@code checkedAt} cannot stand in for the missing revision because it says
     * when Infra finished checking, not which state the run started from.
     */
    private Decision judge(Payload payload, DocumentRow document) {
        if (!VERIFIED.equals(payload.status())) {
            return new Decision(Outcome.LOGGED_ONLY, null);
        }
        if (!payload.objectKey().equals(document.objectKey())
                || !payload.sourceProvider().equals(document.provider())) {
            return new Decision(Outcome.STALE, null);
        }
        // The deployment settings are the authority for where a provider's objects live. A document
        // row that disagrees with them means the settings no longer describe reality, so the target
        // bucket cannot be inferred from them either — refuse rather than write a guess.
        String currentBucket = configuredBucket(document.provider(), "문서의 현재");
        if (!currentBucket.equals(document.bucket())) {
            log.error("문서 저장 위치가 설정과 다릅니다 — provider={} 행의 bucket={} 설정의 bucket={}. "
                            + "reconcile 결과를 반영하지 않습니다.",
                    document.provider(), document.bucket(), currentBucket);
            throw configurationError();
        }
        return new Decision(Outcome.APPLIED,
                configuredBucket(payload.targetProvider(), "요청의 target"));
    }

    private String configuredBucket(String provider, String role) {
        ObjectStorageProperties.Provider configured = storage.providers().get(provider);
        if (configured == null) {
            // A valid contract value this deployment was not configured for. That is a deployment
            // problem, not a bad request, so it is not a 422.
            log.error("{} provider {} 가 app.ai.storage.providers 에 없습니다. 구성된 것: {}",
                    role, provider, storage.providers().keySet());
            throw configurationError();
        }
        return configured.bucket();
    }

    private static ApiException configurationError() {
        return new ApiException(ErrorCode.RECONCILIATION_CONFIGURATION_ERROR);
    }

    /**
     * The answer to a resend: the first processing's answer, repeated.
     *
     * <p>Not a flat {@code 204}. The first attempt at a stale result answers 409, and a resend that
     * answered 204 would make one request mean two different things depending on when it was sent.
     */
    private static Outcome replayOutcome(StoredResult recorded, Payload sent) {
        // The verdict is record equality, not the field list below. A Payload field added later is
        // then compared whether or not anyone remembers to name it: forgetting a line costs a
        // vaguer log message, never a different result silently accepted as a resend.
        if (recorded.payload().equals(sent)) {
            return recorded.applyResult();
        }
        // Accepting it silently would hide the sender's bug: idempotency means the same request is
        // safe to repeat, not that the same key may carry different content.
        log.warn("같은 reconcile 키에 다른 내용이 도착했습니다 — runId={} documentId={} 다른 필드={}. "
                        + "먼저 저장된 결과를 유지합니다.",
                sent.runId(), sent.documentId(), differences(recorded.payload(), sent));
        return Outcome.REPLAY_CONFLICT;
    }

    /**
     * Which fields differ, for the log line only — the decision is made by record equality above.
     *
     * <p>{@code runId} and {@code documentId} are the key, so they are equal by construction.
     */
    private static List<String> differences(Payload recorded, Payload sent) {
        List<String> fields = new ArrayList<>();
        diff(fields, "objectKey", recorded.objectKey(), sent.objectKey());
        diff(fields, "sourceProvider", recorded.sourceProvider(), sent.sourceProvider());
        diff(fields, "targetProvider", recorded.targetProvider(), sent.targetProvider());
        diff(fields, "status", recorded.status(), sent.status());
        diff(fields, "expectedSize", recorded.expectedSize(), sent.expectedSize());
        diff(fields, "actualSize", recorded.actualSize(), sent.actualSize());
        diff(fields, "expectedContentType", recorded.expectedContentType(), sent.expectedContentType());
        diff(fields, "actualContentType", recorded.actualContentType(), sent.actualContentType());
        diff(fields, "expectedSha256", recorded.expectedSha256(), sent.expectedSha256());
        diff(fields, "actualSha256", recorded.actualSha256(), sent.actualSha256());
        diff(fields, "attemptCount", recorded.attemptCount(), sent.attemptCount());
        diff(fields, "failureReason", recorded.failureReason(), sent.failureReason());
        diff(fields, "checkedAt", recorded.checkedAt(), sent.checkedAt());
        diff(fields, "resolvedAt", recorded.resolvedAt(), sent.resolvedAt());
        return fields;
    }

    private static void diff(List<String> fields, String name, Object recorded, Object sent) {
        if (!Objects.equals(recorded, sent)) {
            fields.add(name);
        }
    }

    // ── 검증 ────────────────────────────────────────────────────────────────

    private static Payload validate(Request request) {
        Instant checkedAt = requiredInstant(timestamp(request.checkedAt(), "checkedAt"), "checkedAt");
        Instant resolvedAt = timestamp(request.resolvedAt(), "resolvedAt");
        if (resolvedAt != null && resolvedAt.isBefore(checkedAt)) {
            throw invalid("resolvedAt", "checkedAt 보다 앞설 수 없습니다.");
        }
        Payload payload = new Payload(
                text(request.runId(), "runId", 200),
                requiredId(request.documentId(), "documentId"),
                text(request.objectKey(), "objectKey", 1024),
                oneOf(request.sourceProvider(), PROVIDERS, "sourceProvider"),
                oneOf(request.targetProvider(), PROVIDERS, "targetProvider"),
                oneOf(request.status(), STATUSES, "status"),
                size(request.expectedSize(), "expectedSize"),
                size(request.actualSize(), "actualSize"),
                optionalText(request.expectedContentType(), "expectedContentType", 100),
                optionalText(request.actualContentType(), "actualContentType", 100),
                sha256(request.expectedSha256(), "expectedSha256"),
                sha256(request.actualSha256(), "actualSha256"),
                atLeastOne(request.attemptCount(), "attemptCount"),
                optionalText(request.failureReason(), "failureReason", 500),
                checkedAt, resolvedAt);
        rejectContradictions(payload);
        return payload;
    }

    /**
     * Refuses a payload that contradicts itself, and nothing more.
     *
     * <p>Which fields each status <i>requires</i> is not in the contract, and inventing that rule
     * here would reject payloads Infra is entitled to send. Spring receives Infra's verdict and
     * applies it; it does not re-run the verification. What it can say is that a verdict disagreeing
     * with its own evidence cannot be acted on.
     */
    private static void rejectContradictions(Payload payload) {
        if (VERIFIED.equals(payload.status())) {
            bothPresentAndEqual(payload.expectedSize(), payload.actualSize(), "actualSize");
            bothPresentAndEqual(payload.expectedContentType(), payload.actualContentType(),
                    "actualContentType");
            bothPresentAndEqual(payload.expectedSha256(), payload.actualSha256(), "actualSha256");
            if (payload.failureReason() != null) {
                throw invalid("failureReason", "VERIFIED 결과에는 실패 사유가 없어야 합니다.");
            }
        }
        if (MISSING.equals(payload.status())) {
            absent(payload.actualSize(), "actualSize");
            absent(payload.actualContentType(), "actualContentType");
            absent(payload.actualSha256(), "actualSha256");
        }
    }

    private static void bothPresentAndEqual(Object expected, Object actual, String field) {
        if (expected != null && actual != null && !expected.equals(actual)) {
            throw invalid(field, "VERIFIED 인데 기대값과 실측값이 다릅니다.");
        }
    }

    private static void absent(Object value, String field) {
        if (value != null) {
            throw invalid(field, "MISSING 결과에는 실측값이 없어야 합니다.");
        }
    }

    // isBlank, not isEmpty: a runId of spaces is a valid idempotency key that no one can look up,
    // and an object key of spaces names nothing.
    private static String text(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw invalid(field, "값이 필요합니다.");
        }
        return withinLength(value, field, maxLength);
    }

    private static String optionalText(String value, String field, int maxLength) {
        return value == null ? null : withinLength(value, field, maxLength);
    }

    private static String withinLength(String value, String field, int maxLength) {
        if (value.length() > maxLength) {
            throw invalid(field, maxLength + "자를 넘을 수 없습니다.");
        }
        return value;
    }

    private static String oneOf(String value, Set<String> allowed, String field) {
        if (value == null || !allowed.contains(value)) {
            throw invalid(field, "허용되지 않는 값입니다.");
        }
        return value;
    }

    private static String sha256(String value, String field) {
        if (value != null && !SHA256.matcher(value).matches()) {
            throw invalid(field, "소문자 16진수 64자여야 합니다.");
        }
        return value;
    }

    private static Long size(Long value, String field) {
        if (value != null && value < 0) {
            throw invalid(field, "0 이상이어야 합니다.");
        }
        return value;
    }

    private static int atLeastOne(Integer value, String field) {
        if (value == null || value < 1) {
            throw invalid(field, "1 이상이어야 합니다.");
        }
        return value;
    }

    private static long requiredId(Long value, String field) {
        if (value == null) {
            throw invalid(field, "값이 필요합니다.");
        }
        return value;
    }

    private static Instant requiredInstant(Instant value, String field) {
        if (value == null) {
            throw invalid(field, "값이 필요합니다.");
        }
        return value;
    }

    /**
     * Parses a contract timestamp, truncated to what the column can hold.
     *
     * <p>{@code TIMESTAMPTZ} keeps microseconds. Storing a value with nanoseconds and then comparing
     * the original against what comes back would make an identical resend look like different
     * content and answer 409. Truncating on the way in makes the round trip exact.
     */
    private static Instant timestamp(String value, String field) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value).truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException malformed) {
            throw invalid(field, "ISO-8601 UTC 시각이어야 합니다.");
        }
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.RECONCILIATION_INVALID, message,
                List.of(ApiErrorDetail.field(field, message)), null);
    }

    private record Decision(Outcome outcome, String targetBucket) { }

    /**
     * The wire shape. Every field is boxed and every timestamp is a {@code String} so that a missing
     * value and a malformed one are both refused here with the field's name, rather than by Jackson
     * with the mapped class's.
     */
    public record Request(String runId, Long documentId, String objectKey, String sourceProvider,
                          String targetProvider, String status, Long expectedSize, Long actualSize,
                          String expectedContentType, String actualContentType,
                          String expectedSha256, String actualSha256, Integer attemptCount,
                          String failureReason, String checkedAt, String resolvedAt) { }
}
