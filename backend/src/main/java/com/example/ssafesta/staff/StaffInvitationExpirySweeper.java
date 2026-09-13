package com.example.ssafesta.staff;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 48시간이 지난 대기 초대를 {@code EXPIRED} 로 옮긴다 (spec 011 C-07, research R-07).
 *
 * <p><b>정확성은 읽는 쪽이 진다.</b> {@link StaffInvitation#isAcceptableAt} 이 기한을 함께 보므로
 * 이 배치가 멈춰도 만료된 초대가 수락되지 않는다 — {@code BoothLeaseExpirySweeper} 와 같은 구조다.
 * 이 패스가 고치는 것은 목록·통계에서 {@code PENDING} 이 영영 쌓이는 쪽이다.
 *
 * <p>얇게 두는 것도 같은 이유다: 트랜잭션 경계를 프록시로 타야 해서 실제 갱신은
 * {@link StaffInvitationService#expireStale()} 에 있다.
 */
@Component
class StaffInvitationExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(StaffInvitationExpirySweeper.class);

    private final StaffInvitationService invitations;

    StaffInvitationExpirySweeper(StaffInvitationService invitations) {
        this.invitations = invitations;
    }

    /** 초대 기한이 48시간이라 10분이면 넉넉하다. 주기는 운영 손잡이, 48시간은 계약이다.
     *
     * <p><b>부팅 즉시는 돌지 않는다.</b> 기한이 48시간인 일을 배포 때마다 즉시 쓸어낼 이유가 없고,
     * 부팅 순간은 다른 초기화가 몰려 있는 시간이다. 실제로 전체 테스트에서 이 즉시 실행이
     * {@code ArcadeMachineSingleQueryTest} 의 전역 문장 카운터에 섞여 그 테스트를 깨뜨렸다.
     */
    @Scheduled(initialDelayString = "${app.staff.invitation-expiry-initial-delay:PT1M}",
            fixedDelayString = "${app.staff.invitation-expiry-scan-interval:PT10M}")
    void expireStaleInvitations() {
        int moved = invitations.expireStale();
        if (moved > 0) {
            log.info("직원 초대 만료 — count={}", moved);
        }
    }
}
