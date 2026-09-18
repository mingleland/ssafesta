package com.example.ssafesta.world.chat;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
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
 * 에서 WS Token 을 검증하고 심어 둔 회원 id 다. <b>요청 본문에서 신원을 읽지 않는다.</b>
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
        if (failure instanceof WorldChatTooFastException tooFast) {
            return new WorldChatError(tooFast.errorCode().name(), tooFast.getMessage(),
                    tooFast.retryAfterMs());
        }
        if (failure instanceof WorldChatUnavailableException unavailable) {
            // 판정을 못 했을 뿐 "영구히 막혔다" 는 뜻이 아니다. 클라이언트가 다시 보낼 시점이
            // 필요하므로 고정값을 준다 — 기존 거절 동작을 그대로 두는 값이다 (GitLab #223).
            return new WorldChatError(unavailable.errorCode().name(), unavailable.getMessage(),
                    WorldChatRateLimiter.UNAVAILABLE_RETRY_AFTER_MS);
        }
        if (failure instanceof ApiException api) {
            return new WorldChatError(api.errorCode().name(), api.getMessage());
        }
        // 알 수 없는 실패의 내부 사정은 클라이언트로 새지 않게 한다 — 대신 여기 남긴다. 클라이언트에는
        // CHAT_UNAVAILABLE 로 뭉개져 나가므로, 이 줄이 없으면 서버 오류가 아무 흔적 없이 사라진다 (T-24).
        log.error("월드 채팅 처리 실패 — 알 수 없는 예외를 CHAT_UNAVAILABLE 로 답합니다.", failure);
        return new WorldChatError(ErrorCode.CHAT_UNAVAILABLE.name(),
                ErrorCode.CHAT_UNAVAILABLE.defaultMessage());
    }

    /**
     * 거절 봉투.
     *
     * <p>{@code retryAfterMs} 는 <b>밀리초 정수</b>다 (GitLab #223). {@code Duration} 을 그대로
     * 직렬화하면 {@code "PT5S"} 가 나가고, 그 문자열을 파싱하는 일이 클라이언트마다 반복된다.
     * 대기가 없는 거절(내용 검증 실패 등)에는 필드를 내리지 않는다 — 기다리면 통과하는 것처럼
     * 읽히면 클라이언트가 같은 줄을 다시 보낸다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorldChatError(String code, String message, Long retryAfterMs) {

        WorldChatError(String code, String message) {
            this(code, message, null);
        }
    }
}
