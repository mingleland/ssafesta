package com.example.ssafesta.wallet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 잔액-원장 정합성 점검을 주기적으로 돌린다 (spec 003 FR-014·C-05, S15P21A604-693).
 *
 * <p>{@link CoinReconciliationService#run()} 은 서비스 계층까지 구현·테스트돼 있었지만 main 코드
 * 어디서도 부르지 않았다 — SC-001("불일치 0건")의 검출이 테스트에서만 돌았다. REST 노출은 ADMIN
 * 권한 모델이 미정이라 보류가 맞지만(plan U-01, docs/26 #19), 스케줄 실행과 불일치 ERROR 로그는
 * 그 결정에 걸리지 않는다.
 *
 * <p>결과 로그는 서비스가 남긴다 — 불일치는 ERROR, 정상은 INFO. 여기서는 점검 자체가 돌지 못한
 * 경우({@code FAILED})만 한 줄 더 적는다. 자동 보정은 하지 않는다(C-05).
 *
 * <p>부팅 즉시는 돌지 않는다. 테스트에서는 두 주기를 모두 길게 덮어 배치가 픽스처와 겹치지
 * 않게 한다 — 전체 스위트가 기본 지연을 넘기면 최초 실행이 끼어들어 실행 횟수를 세는 테스트가
 * 흔들린다. 배치 자체는 {@code CoinReconciliationIntegrationTest} 가 직접 호출해 검증한다.
 */
@Component
class CoinReconciliationSweeper {
    private static final Logger log = LoggerFactory.getLogger(CoinReconciliationSweeper.class);

    private final CoinReconciliationService reconciliation;

    CoinReconciliationSweeper(CoinReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Scheduled(initialDelayString = "${app.wallet.reconciliation-initial-delay:PT5M}",
            fixedDelayString = "${app.wallet.reconciliation-scan-interval:PT1H}")
    void reconcile() {
        CoinReconciliationRun run = reconciliation.run();
        if (run.getStatus() == CoinReconciliationRun.Status.FAILED) {
            log.error("코인 정합성 점검이 돌지 못했습니다 — runId={}. 다음 주기에 다시 시도합니다.", run.getId());
        }
    }
}
