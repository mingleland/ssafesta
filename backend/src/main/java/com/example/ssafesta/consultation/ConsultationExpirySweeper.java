package com.example.ssafesta.consultation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 10분이 지난 대기 요청을 {@code EXPIRED} 로 옮긴다 (spec 011 C-01, research R-07).
 *
 * <p><b>정확성은 읽는 쪽이 진다.</b> 수락과 대기열 조회가 기한을 함께 보므로 이 배치가 멈춰도
 * 만료된 요청이 수락되거나 대기열에 남지 않는다. 이 패스가 고치는 것은 {@code REQUESTED} 가
 * 영영 쌓이는 쪽이다 — {@code BoothLeaseExpirySweeper} 와 같은 구조다.
 *
 * <p>부팅 즉시는 돌지 않는다. 부팅 순간은 다른 초기화가 몰리는 시간이고, 전역 통계를 재는
 * 테스트와 겹쳐 그 테스트를 깨뜨린 적이 있다 (T-154).
 */
@Component
class ConsultationExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(ConsultationExpirySweeper.class);

    private final ConsultationService consultations;

    ConsultationExpirySweeper(ConsultationService consultations) {
        this.consultations = consultations;
    }

    /** 요청 기한이 10분이라 1분이면 충분하다. 주기는 운영 손잡이, 10분은 계약이다. */
    @Scheduled(initialDelayString = "${app.consultation.expiry-initial-delay:PT1M}",
            fixedDelayString = "${app.consultation.expiry-scan-interval:PT1M}")
    void expireStaleRequests() {
        int moved = consultations.expireStale();
        if (moved > 0) {
            log.info("상담 요청 만료 — count={}", moved);
        }
    }
}
