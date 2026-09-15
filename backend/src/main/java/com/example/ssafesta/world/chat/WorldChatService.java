package com.example.ssafesta.world.chat;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.user.AccountStatus;
import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import java.time.Instant;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * 월드 공용 채팅 (S15P21A604-687).
 *
 * <p><b>저장하지 않는다.</b> 표도 마이그레이션도 없다 — 광장 잡담은 스크롤백 가치가 낮고, 남기면
 * 보존 기간과 탈퇴 삭제 경로가 함께 붙는다(D11 의 "사용자별 상세 로그를 축적하지 않는다"). 대신
 * <b>재접속하면 이전 대화가 없다</b>는 것이 이 결정의 딸린 결과다.
 *
 * <p><b>토픽은 하나다.</b> 채널별로 가르지 않는다 — 지금 채널 식별자가 상수이고, 채널을
 * 클라이언트가 지정하게 하면 <b>서버가 누가 어느 채널에 있는지 몰라 검증할 수 없다</b>. 채널
 * 배정({@code S15P21A604-499})이 서면 그때 토픽을 쪼갠다.
 *
 * <p><b>회원 전용은 공짜로 성립한다.</b> STOMP 연결에 WS Token 이 필요하고 그 토큰은 회원에게만
 * 발급된다. 여기에 게이트를 따로 두지 않는다.
 */
@Service
public class WorldChatService {

    /** 계약값. 오버레이 한 줄에 들어가는 길이다. */
    static final int MAX_CONTENT_LENGTH = 100;

    static final String TOPIC = "/topic/world/chat";

    private final SimpMessagingTemplate messaging;
    private final UserRepository users;
    private final WorldChatRateLimiter rateLimiter;

    public WorldChatService(SimpMessagingTemplate messaging, UserRepository users,
                            WorldChatRateLimiter rateLimiter) {
        this.messaging = messaging;
        this.users = users;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 한 줄을 광장에 던진다.
     *
     * <p>순서가 계약이다 — 내용 검증 → 도배 판정 → 신원 확인 → 방송. 도배 판정을 먼저 하면 빈
     * 문자열을 보낸 사람이 3초를 기다려야 하고, 신원을 먼저 확인하면 거부될 요청이 DB 를 읽는다.
     */
    public void say(Long senderUserId, WorldChatSend command) {
        String content = normalized(command);
        if (!rateLimiter.tryAcquire(senderUserId)) {
            throw new WorldChatTooFastException();
        }
        messaging.convertAndSend(TOPIC, (Object) new WorldChatMessage(
                senderUserId, nicknameOf(senderUserId), content, Instant.now()));
    }

    /** 인증된 STOMP 연결이 열린 사실을 시스템 알림으로 방송한다. 일반 채팅 rate limit은 적용하지 않는다. */
    public void announceJoin(Long userId) {
        messaging.convertAndSend(TOPIC, (Object) WorldChatJoinNotice.of(nicknameOf(userId), Instant.now()));
    }

    /**
     * 보낸 내용을 다듬고 길이를 본다.
     *
     * <p><b>code point 로 센다.</b> {@code String.length()} 는 UTF-16 단위라 이모지 하나가 2자로
     * 계산된다 — 그러면 이모지로 쓴 50자가 거부되고, 상한이 사람이 보는 길이와 어긋난다.
     *
     * <p>방송되는 것은 {@code strip()} 을 거친 값이다. 앞뒤 공백을 그대로 실어 나르면 같은 말이
     * 사람마다 다르게 보이고, 공백만으로 줄을 밀어 올리는 도배가 가능해진다.
     */
    private static String normalized(WorldChatSend command) {
        String content = command == null || command.content() == null ? "" : command.content().strip();
        if (content.isEmpty()) {
            throw ApiException.fieldInvalid("content", "내용이 필요합니다.");
        }
        if (content.codePointCount(0, content.length()) > MAX_CONTENT_LENGTH) {
            throw ApiException.fieldInvalid("content", MAX_CONTENT_LENGTH + "자 이하여야 합니다.");
        }
        return content;
    }

    /**
     * 말하는 사람의 이름.
     *
     * <p><b>계정이 살아 있는지 함께 본다.</b> WS Token 은 5분을 살고 연결은 그보다 오래 가므로,
     * 발급 뒤 정지되거나 탈퇴한 사람의 경로가 남는다. 여기서 보지 않으면 정지된 계정이 계속
     * 말한다.
     *
     * <p>{@code @Transactional} 을 붙이지 않는다. 같은 클래스 안에서 부르면 프록시를 거치지 않아
     * 어차피 동작하지 않고, 읽기 한 번은 트랜잭션 없이도 성립한다 — 붙여 두면 걸려 있다고 믿게 된다.
     */
    private String nicknameOf(Long senderUserId) {
        User user = users.findById(senderUserId)
                .filter(found -> found.getStatus() == AccountStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(
                        com.example.ssafesta.common.ErrorCode.MEMBER_ONLY, "채팅할 수 없는 계정입니다."));
        return user.getNickname();
    }
}
