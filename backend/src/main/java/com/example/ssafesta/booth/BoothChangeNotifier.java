package com.example.ssafesta.booth;

import com.example.ssafesta.world.booth.BoothChanged;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * "이 부스가 바뀌었다" 를 월드에 알리는 한 자리 (S15P21A604-727, GitLab #193).
 *
 * <p>부스를 바꾸는 경로는 다섯이다 — 배치 공개, 외관, 홈페이지, 임대, 만료. 전부 같은 질문에
 * 답해야 한다: <b>지금 이 부스가 월드의 어느 슬롯에 서 있는가.</b> 슬롯이 없으면(미임대·만료)
 * 월드에 보이는 것이 없으므로 신호도 나가지 않는다. 그 판정이 다섯 벌이 되면 한 곳이 빠져도
 * 아무도 모른다 — 신호가 없는 것은 오류가 아니라 침묵이라서다.
 *
 * <p>실제 방송은 {@code BoothChangeBroadcaster} 가 <b>커밋 뒤에</b> 한다. 여기서는 이벤트만
 * 올린다.
 */
@Component
class BoothChangeNotifier {

    private final ApplicationEventPublisher events;

    BoothChangeNotifier(ApplicationEventPublisher events) {
        this.events = events;
    }

    /** 부스가 슬롯에 서 있을 때만 알린다. */
    void boothChanged(Booth booth) {
        if (booth != null && booth.getCurrentSlotId() != null) {
            slotChanged(booth.getCurrentSlotId());
        }
    }

    /** 슬롯을 이미 아는 경로 — 임대와 만료는 부스가 그 슬롯을 놓는 중이라 부스에서 읽을 수 없다. */
    void slotChanged(long slotId) {
        events.publishEvent(new BoothChanged(slotId));
    }
}
