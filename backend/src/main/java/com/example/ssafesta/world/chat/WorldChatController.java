package com.example.ssafesta.world.chat;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import java.security.Principal;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

/**
 * 월드 공용 채팅의 유일한 SEND 자리 (S15P21A604-687).
 *
 * <p>보내는 사람은 {@link Principal} 로 온다 — {@code StompAuthChannelInterceptor} 가 {@code CONNECT}
 * 에서 WS Token 을 검증하고 심어 둔 회원 id 다. <b>요청 본문에서 신원을 읽지 않는다.</b>
 */
@Controller
public class WorldChatController {

    /** 오류가 가는 자리. 다른 SEND 기능이 생겨도 겹치지 않도록 기능 이름을 경로에 넣는다. */
    static final String ERROR_QUEUE = "/queue/world/chat/errors";

    private final WorldChatService chat;

    public WorldChatController(WorldChatService chat) {
        this.chat = chat;
    }

    @MessageMapping("/world/chat")
    public void say(Principal sender, @Payload WorldChatSend command) {
        chat.say(Long.valueOf(sender.getName()), command);
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
        // 알 수 없는 실패의 내부 사정은 클라이언트로 새지 않게 한다.
        return new WorldChatError(ErrorCode.CHAT_UNAVAILABLE.name(),
                ErrorCode.CHAT_UNAVAILABLE.defaultMessage());
    }

    public record WorldChatError(String code, String message) { }
}
