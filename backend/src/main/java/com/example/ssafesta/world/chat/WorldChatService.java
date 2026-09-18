package com.example.ssafesta.world.chat;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ProfanityFilter;
import com.example.ssafesta.user.AccountStatus;
import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(WorldChatService.class);

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
     * 문자열을 보낸 사람이 대기를 물고, 신원을 먼저 확인하면 거부될 요청이 DB 를 읽는다.
     *
     * <p>그래서 <b>창을 소비하는 것은 "내용 검증을 통과해 도배 판정이 허용한 전송 시도"</b> 다.
     * 그 뒤 신원 확인에서 떨어진 요청은 이미 소비한 것으로 둔다 — 되돌리는 코드를 넣으면 판정이
     * 원자적이지 않게 되고, 정지된 계정이 창을 공짜로 소모할 수 있게 된다 (GitLab #223).
     */
    public void say(Long senderUserId, WorldChatSend command) {
        String content = normalized(command);
        WorldChatRateLimiter.Decision decision = rateLimiter.tryAcquire(senderUserId);
        if (!decision.allowed()) {
            throw new WorldChatTooFastException(decision.retryAfterMs());
        }
        messaging.convertAndSend(TOPIC, (Object) new WorldChatMessage(
                senderUserId, nicknameOf(senderUserId), content, Instant.now()));
    }

    /**
     * 인증된 STOMP 연결이 열린 사실을 시스템 알림으로 방송한다.
     *
     * <p><b>채팅의 창·벌칙이 아니라 간격 하나로 막는다</b> (S15P21A604-915 / GitLab #223 §5-5).
     * 이 경로를 열어 두면 연결을 끊고 다시 붙기를 반복하는 것만으로 같은 토픽을 밀어 올릴 수 있다 —
     * 본문을 보내지 않으므로 {@link #say} 의 제한에는 걸리지 않는다.
     *
     * <p><b>거절은 조용히 방송하지 않는 것으로 끝난다.</b> 입장 알림은 SEND 가 아니라 연결 이벤트라
     * 클라이언트에 거절을 돌려줄 응답 경로가 없고, 연결 자체는 정상이다.
     *
     * <p><b>Redis 가 답하지 않으면 방송하지 않는다</b>(fail-closed). 입장 알림은 없어도 기능이
     * 성립하는 반면, 판정할 수 없을 때 열어 두면 Redis 가 흔들리는 순간 토픽이 밀린다. 대신 흔적을
     * 남긴다 — 조용히 삼키면 알림이 사라진 이유를 아무 데서도 찾을 수 없다 (T-24).
     */
    public void announceJoin(Long userId) {
        try {
            if (!rateLimiter.tryAnnounceJoin(userId)) {
                return;
            }
        } catch (WorldChatUnavailableException unavailable) {
            log.warn("월드 채팅 입장 알림을 방송하지 않습니다 — 도배 판정이 불가능합니다. userId={}",
                    userId, unavailable);
            return;
        }
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
     *
     * <p><b>금칙어는 정규화본으로 보고 원문을 방송한다</b> (S15P21A604-792). 마스킹하지 않는
     * 이유가 그것이다 — 정규화에서 공백과 기호가 지워져 일치 위치를 원문으로 되돌릴 수 없고,
     * {@code 씨---1---발} 같은 우회 표기는 원문에 가릴 자리 자체가 없다. 걸린 단어를 알려 주지도
     * 않는다. 알려 주면 통과하는 표기를 찾는 데 쓰인다({@code nickname-policy.md} 4항).
     */
    private static String normalized(WorldChatSend command) {
        String content = command == null || command.content() == null ? "" : command.content().strip();
        if (content.isEmpty()) {
            throw ApiException.fieldInvalid("content", "내용이 필요합니다.");
        }
        if (content.codePointCount(0, content.length()) > MAX_CONTENT_LENGTH) {
            throw ApiException.fieldInvalid("content", MAX_CONTENT_LENGTH + "자 이하여야 합니다.");
        }
        if (ProfanityFilter.contains(content)) {
            throw ApiException.fieldInvalid("content", "보낼 수 없는 표현이 있습니다.");
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
