package com.example.ssafesta.world.booth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 부스 변경을 월드에 있는 모두에게 알린다 (S15P21A604-727, GitLab #193).
 *
 * <p>이 전에는 게시한 사람의 브라우저만 알았다. FE 가 자기 게시 성공 직후 Unity 브리지를 불렀고,
 * 다른 사람의 브라우저에는 아무 신호도 가지 않아 <b>같은 공간에 있는 두 사람이 다른 것을 보는
 * 상태가 세션 내내</b> 이어졌다(docs/HDD/부스_변경_신호_계약.md §1).
 *
 * <p>경로는 그 문서의 <b>B(FE 경유)</b>다. A(게임 서버 경유)는 Spring 이 게임 서버를 직접 부르는
 * 모양이라 쓰지 않는다 — Spring 은 항상 FE 를 지난다. 받는 쪽은 이미 있다: FE 가 이 토픽을 듣고
 * {@code SendMessage('BoothLayoutBridge', 'ReloadBoothSlot', slotId)} 로 Unity 에 넘기면,
 * {@code WorldBoothPublishedBootstrap.RequestReload} 가 그 슬롯만 다시 읽고 서명이 다를 때만
 * 다시 짓는다. <b>Unity 수정은 0이다.</b>
 *
 * <p><b>커밋 뒤에 보낸다.</b> 트랜잭션 안에서 보내면 받은 쪽이 커밋 전에 재조회해 <i>옛 값</i>을
 * 읽고, 그 값의 서명을 캐시해 버린다 — 그러면 다음 변경이 올 때까지 낡은 부스가 굳는다. 방송
 * 자체가 실패해도 이미 커밋된 변경을 되돌리지 않는 것도 같은 이유로 맞다: 방문자는 다음 신호나
 * 포탈 진입 시 재조회로 따라잡는다(유실은 계약이 인정한다, docs/16 §19).
 *
 * <p>{@code fallbackExecution} 을 켠 것은 트랜잭션 밖에서 부르는 자리가 생겼을 때 <b>조용히 아무
 * 일도 일어나지 않는 것</b>을 막기 위해서다(T-24 의 모양).
 */
@Component
public class BoothChangeBroadcaster {

    /** 계약값 — docs/16. 서버에서 클라이언트로만 간다. */
    public static final String TOPIC = "/topic/world/booths";

    private static final Logger log = LoggerFactory.getLogger(BoothChangeBroadcaster.class);

    private final SimpMessagingTemplate messaging;

    public BoothChangeBroadcaster(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void broadcast(BoothChanged changed) {
        try {
            messaging.convertAndSend(TOPIC, (Object) changed);
        } catch (RuntimeException failure) {
            // 커밋은 이미 끝났다. 여기서 다시 던지면 이 변경을 만든 요청이 실패한 것처럼 보이는데,
            // 실제로는 저장이 됐고 전파만 못 한 것이다. 드러내되 흐름은 막지 않는다.
            log.warn("슬롯 {} 변경 방송에 실패했습니다 — 저장은 끝났고 전파만 실패했습니다.",
                    changed.slotId(), failure);
        }
    }
}
