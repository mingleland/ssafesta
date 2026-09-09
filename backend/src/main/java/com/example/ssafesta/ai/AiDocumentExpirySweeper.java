package com.example.ssafesta.ai;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expires upload grants that were issued and never used (FR-026, S15P21A604-174).
 *
 * <p><b>Without this pass an abandoned grant holds its slot forever.</b> A row is created
 * {@code QUEUED} the moment a presigned URL is handed out, and {@code QUEUED} counts toward the ten
 * documents FR-018 allows an agent ({@link AiDocumentRepository#countActive}). Ten URLs that were
 * requested and never uploaded therefore lock that agent out with no way back: completing answers
 * {@code DOCUMENT_UPLOAD_INCOMPLETE} because the object is not there, and asking for the same file
 * again reissues on the same row rather than freeing one.
 *
 * <p><b>What expiring does not do is throw the upload away.</b> FR-027 keeps the original for 24
 * hours, and a late {@code /complete} inside that window puts the same document back in the queue
 * ({@link AiDocument#recover}). The row leaves the active set immediately — that is what frees the
 * slot — and comes back only if its bytes actually arrive.
 *
 * <p>Deleting the original once the recovery window closes (FR-028) is not here. Nothing does it
 * yet.
 */
@Component
class AiDocumentExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(AiDocumentExpirySweeper.class);

    /**
     * FR-026. Fixed, not configurable — the contract states one number, the same reasoning as
     * {@code MAX_FILE_BYTES} and {@code RECOVERY_WINDOW} in {@link AiDocumentService}. FR-026 makes
     * exactly one number operational, and it is the scan interval below, not this.
     */
    private static final Duration GRANT_TTL = Duration.ofHours(1);

    private final AiDocumentRepository documents;

    AiDocumentExpirySweeper(AiDocumentRepository documents) {
        this.documents = documents;
    }

    /**
     * One pass every five minutes by default.
     *
     * <p>The interval is a placeholder rather than a properties record because nothing in Java
     * reads it — the scheduler resolves it itself, and a record would exist only to be injected
     * nowhere. FR-026 asks for the period to be adjustable in operation; SC-010 then wants a grant
     * expired within its hour plus one period, which is what a five-minute default buys.
     *
     * <p>Both instants come from one {@link Instant#now()}: the cutoff and the stamp written on the
     * rows describe the same pass, and reading the clock twice would let them disagree.
     */
    @Scheduled(fixedDelayString = "${app.ai.document.expiry-scan-interval:PT5M}")
    @Transactional
    public void expireAbandonedGrants() {
        Instant now = Instant.now();
        int expired = documents.expireAbandonedGrants(now, now.minus(GRANT_TTL));
        if (expired > 0) {
            // 조용히 지나가면 발급만 받고 안 올리는 흐름이 늘어도 아무도 모른다 — 사용자에게는
            // "슬롯이 왜 없지" 로만 보이고, 그 원인이 여기에 있다는 단서가 없다.
            log.info("업로드가 완료되지 않은 문서 {}건을 만료했습니다 (FR-026).", expired);
        }
    }
}
