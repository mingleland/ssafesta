package com.example.ssafesta.world.chat;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.common.MemberPrincipal;
import java.security.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

/**
 * 월드 공용 채팅의 유일한 SEND 자리 (S15P21A604-687).
 *
 * <p>보내는 사람은 {@link Principal} 로 온다 — {@code StompAuthChannelInterceptor} 가 {@code CONNECT}
 * 에서 WS Token 을 검증하고 심어 둔 주체다. <b>요청 본문에서 신원을 읽지 않는다.</b>
 *
 * <p><b>게스트는 여기서 막는다</b>(S15P21A604-727). 게스트도 연결은 하지만 그 연결은 읽기
 * 전용이다. 인터셉터의 destination 정책으로 막지 않는 이유는 <b>그 거부가 연결을 끊기</b>
 * 때문이다 — 같은 소켓이 부스 변경 방송을 나르므로, 채팅 프레임 하나 때문에 월드 동기화까지
 * 죽으면 대가가 사건에 비해 크다. 여기서 막으면 오류 큐로 사유만 가고 연결은 산다.
 */
@Controller
public class WorldChatController {

    /** 오류가 가는 자리. 다른 SEND 기능이 생겨도 겹치지 않도록 기능 이름을 경로에 넣는다. */
    static final String ERROR_QUEUE = "/queue/world/chat/errors";

    private static final Logger log = LoggerFactory.getLogger(WorldChatController.class);

    private final WorldChatService chat;

    public WorldChatController(WorldChatService chat) {
        this.chat = chat;
    }

    @MessageMapping("/world/chat")
    public void say(Principal sender, @Payload WorldChatSend command) {
        Long senderUserId = MemberPrincipal.optionalMemberId(sender);
        if (senderUserId == null) {
            throw new ApiException(ErrorCode.MEMBER_ONLY, "회원 계정만 채팅할 수 있습니다.");
        }
        chat.say(senderUserId, command);
    }

    /**
     * 거절 사유를 <b>보낸 사람에게만</b> 돌려준다.
     *
     * <p>{@code broadcast = false} 가 핵심이다. 켜 두면 같은 계정으로 열어 둔 다른 탭까지 오류가
     * 퍼진다 — 한쪽 탭에서 도배하다 막힌 것이 다른 탭에 뜬다.
     *
     * <p>SEND 에는 응답이 없어서 이 경로가 필요하다. 클라이언트는
     * {@code /user/queue/world/chat/errors} 를 구독한다.
     */
    @MessageExceptionHandler
    @SendToUser(destinations = ERROR_QUEUE, broadcast = false)
    public WorldChatError onFailure(Exception failure) {
        if (failure instanceof ApiException api) {
            return new WorldChatError(api.errorCode().name(), api.getMessage());
        }
        // 알 수 없는 실패의 내부 사정은 클라이언트로 새지 않게 한다 — 대신 여기 남긴다. 클라이언트에는
        // CHAT_UNAVAILABLE 로 뭉개져 나가므로, 이 줄이 없으면 서버 오류가 아무 흔적 없이 사라진다 (T-24).
        log.error("월드 채팅 처리 실패 — 알 수 없는 예외를 CHAT_UNAVAILABLE 로 답합니다.", failure);
        return new WorldChatError(ErrorCode.CHAT_UNAVAILABLE.name(),
                ErrorCode.CHAT_UNAVAILABLE.defaultMessage());
    }

    public record WorldChatError(String code, String message) { }
}
