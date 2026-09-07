package com.example.ssafesta.internal.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes back document Jobs whose worker stopped sending heartbeats (S15P21A604-400, GitLab #119 §4).
 *
 * <p>FastAPI holds no database credential, so a worker that dies mid-attempt cannot release
 * anything itself — the Job would sit {@code RUNNING} forever and its document would never become
 * {@code READY}. Worse, the frozen process could wake past its lease and still be treated as the
 * current attempt. Reclaiming bumps {@code attempt_no}, which is what turns that late result into a
 * {@code 409}.
 */
@Component
class AiDocumentJobLeaseSweeper {

    private static final Logger log = LoggerFactory.getLogger(AiDocumentJobLeaseSweeper.class);

    /**
     * One pass every 30 seconds, at most {@value #BATCH} Jobs.
     *
     * <p>The lease is 90 seconds, so a dead worker is noticed within two passes of its expiry. The
     * cap keeps one pass bounded when many workers die at once (a deploy); the rest are taken on the
     * next pass.
     */
    private static final int BATCH = 100;

    private final AiDocumentJobRepository jobs;

    AiDocumentJobLeaseSweeper(AiDocumentJobRepository jobs) {
        this.jobs = jobs;
    }

    @Scheduled(fixedDelayString = "PT30S")
    @Transactional
    public void reclaimExpiredLeases() {
        int reclaimed = jobs.reclaimExpiredLeases(BATCH);
        if (reclaimed > 0) {
            // 조용히 지나가면 워커가 계속 죽고 있어도 아무도 모른다 — 재시도가 흡수해 버린다.
            log.warn("lease 가 만료된 문서 Job {}건을 회수했습니다.", reclaimed);
        }
    }
}
