package com.example.ssafesta.world.chat;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.RedisKeyspaceProperties;
import com.example.ssafesta.user.AccountLifecycleService;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 월드 공용 채팅 (S15P21A604-687).
 *
 * <p>고정하는 것은 <b>무엇이 방송되지 않는가</b>다. 메시지가 나가는지만 보면, 클라이언트가 보낸
 * 닉네임이 그대로 실려도·도배가 통과해도·정지된 계정이 말해도 전부 초록이 된다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class WorldChatIntegrationTest {

    @Autowired private WorldChatService chat;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private AccountLifecycleService lifecycle;

    /** 발행을 가로채 무엇이 나갔는지 본다 — 브로커를 띄우지 않아 결과가 흔들리지 않는다. */
    @MockitoBean private SimpMessagingTemplate messaging;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private RedisKeyspaceProperties keyspace;
    @Autowired private StringRedisTemplate redis;

    /**
     * Redis 가 답하지 않으면 <b>막는다</b>(fail-closed, S15P21A604-693 테스트 공백).
     *
     * <p>도배 판정이 불가능할 때 열어 두면 Redis 가 흔들리는 순간 광장이 도배된다. 그래서 거부이고,
     * 거부된 줄은 토픽에 나가지 않는다. 판정 불가는 도배(429)와 다른 코드라 클라이언트가 "잠시 뒤"
     * 와 "지금은 채팅 불가" 를 가른다.
     */
    @Test
    void whenRedisIsDownTheMessageIsRefusedNotBroadcast() {
        Long sender = member("레디스장애");
        // 어떤 호출이든 연결 실패로 답하는 Redis — 판정 경로가 바뀌어도 이 테스트는 계속 같은
        // 것을 본다(스크립트 실행이든 단일 명령이든 "Redis 가 답하지 않는다" 가 전제다).
        StringRedisTemplate downRedis = mock(StringRedisTemplate.class, invocation -> {
            throw new RedisConnectionFailureException("redis down");
        });
        WorldChatService withoutRedis = new WorldChatService(messaging, users,
                new WorldChatRateLimiter(downRedis, keyspace));

        WorldChatUnavailableException refused = assertThrows(WorldChatUnavailableException.class,
                () -> withoutRedis.say(sender, new WorldChatSend("들리세요?")));

        assertEquals(com.example.ssafesta.common.ErrorCode.CHAT_UNAVAILABLE, refused.errorCode());
        assertInstanceOf(RedisConnectionFailureException.class, refused.getCause(),
                "원인이 붙어 있어야 Redis 가 왜 답하지 않았는지 로그에서 추적할 수 있다.");
        assertNothingSent();
    }

    @Test
    void aMessageGoesOutWithTheNicknameTheServerLookedUp() {
        Long sender = member("말하는이");
        String nickname = users.findById(sender).orElseThrow().getNickname();

        chat.say(sender, new WorldChatSend("안녕하세요"));

        WorldChatMessage sent = captured();
        assertEquals(sender, sent.senderUserId());
        assertEquals(nickname, sent.nickname());
        assertEquals("안녕하세요", sent.content());
    }

    @Test
    void aJoinIsBroadcastAsASystemNoticeWithTheServerLookedUpNickname() {
        Long userId = member("입장하는이");

        chat.announceJoin(userId);

        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(messaging).convertAndSend(
                org.mockito.Mockito.eq(WorldChatService.TOPIC), captor.capture());
        WorldChatJoinNotice sent = assertInstanceOf(WorldChatJoinNotice.class, captor.getValue());
        assertEquals(WorldChatJoinNotice.TYPE, sent.type());
        assertEquals(users.findById(userId).orElseThrow().getNickname(), sent.nickname());
    }

    /**
     * <b>재연결을 반복해도 입장 알림은 간격에 한 번이다</b> (S15P21A604-915 / GitLab #223 §5-5).
     *
     * <p>입장 알림에는 본문이 없어 {@code say} 의 창·벌칙에 걸리지 않는다. 막지 않으면 연결을
     * 끊고 다시 붙는 것만으로 같은 토픽을 밀어 올릴 수 있다.
     */
    @Test
    void repeatedReconnectsAnnounceTheJoinOnlyOnce() {
        Long userId = member("재연결");

        for (int attempt = 0; attempt < 5; attempt++) {
            chat.announceJoin(userId);
        }

        assertEquals(1, allJoinNotices().size(), "재연결마다 입장 알림이 나갔습니다.");
    }

    /** 간격이 지나면 다시 알린다 — 영구히 막는 것이 아니라 도배만 자른다. */
    @Test
    void theJoinIsAnnouncedAgainOnceTheIntervalHasPassed() {
        Long userId = member("재입장");
        MutableClock clock = new MutableClock();
        WorldChatService rejoining = new WorldChatService(messaging, users,
                new WorldChatRateLimiter(redis, keyspace, clock));

        rejoining.announceJoin(userId);
        clock.advance(WorldChatRateLimiter.JOIN_NOTICE_INTERVAL_MS - 1);
        rejoining.announceJoin(userId);
        assertEquals(1, allJoinNotices().size(), "간격 안의 재연결이 알림을 냈습니다.");

        clock.advance(2);
        rejoining.announceJoin(userId);

        assertEquals(2, allJoinNotices().size(), "간격이 지났는데도 알림이 나가지 않았습니다.");
    }

    /** 판정은 사람 단위다 — 한 사람의 재연결이 다른 사람의 입장을 막지 않는다. */
    @Test
    void oneMembersJoinDoesNotSilenceAnothers() {
        chat.announceJoin(member("먼저"));
        chat.announceJoin(member("나중"));

        assertEquals(2, allJoinNotices().size());
    }

    /**
     * Redis 가 답하지 않으면 <b>알리지 않는다</b>(fail-closed) — 그리고 예외는 연결 이벤트 밖으로
     * 새지 않는다.
     *
     * <p>입장 알림은 없어도 기능이 성립하는 반면, 판정할 수 없을 때 열어 두면 Redis 가 흔들리는
     * 순간 토픽이 밀린다. 예외를 던지면 STOMP 연결 이벤트 처리로 새어 나가는데, 알림 하나 때문에
     * 연결을 흔들 이유가 없다.
     */
    @Test
    void whenRedisIsDownTheJoinIsNotAnnouncedAndNothingIsThrown() {
        Long userId = member("판정불가입장");
        StringRedisTemplate downRedis = mock(StringRedisTemplate.class, invocation -> {
            throw new RedisConnectionFailureException("redis down");
        });
        WorldChatService withoutRedis = new WorldChatService(messaging, users,
                new WorldChatRateLimiter(downRedis, keyspace));

        assertDoesNotThrow(() -> withoutRedis.announceJoin(userId));

        assertEquals(List.of(), allJoinNotices(), "판정하지 못한 입장 알림이 토픽에 나갔습니다.");
    }

    /**
     * 앞뒤 공백은 다듬어 내보낸다.
     *
     * <p>그대로 실어 나르면 같은 말이 사람마다 다르게 보이고, 공백만으로 줄을 밀어 올리는 도배가
     * 가능해진다.
     */
    @Test
    void theContentIsStrippedBeforeBroadcast() {
        chat.say(member("공백"), new WorldChatSend("  안녕  "));

        assertEquals("안녕", captured().content());
    }

    /** 빈 내용과 공백만은 거부한다 — 다듬고 나면 남는 것이 없다. */
    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n"})
    void anEmptyMessageIsRefused(String content) {
        Long sender = member("빈내용" + content.length());

        ApiException refused = assertThrows(ApiException.class,
                () -> chat.say(sender, new WorldChatSend(content)));

        assertEquals("VALIDATION_FAILED", refused.errorCode().name());
        assertNothingSent();
    }

    /**
     * 길이는 <b>code point</b> 로 센다.
     *
     * <p>{@code String.length()} 는 UTF-16 단위라 이모지 하나가 2자다 — 그 기준이면 이모지 100개가
     * 거부되고, 상한이 사람이 보는 길이와 어긋난다.
     */
    @Test
    void aHundredEmojiPassWhileAHundredAndOneCharactersDoNot() {
        Long sender = member("길이");

        chat.say(sender, new WorldChatSend("😀".repeat(100)));
        assertEquals(100, captured().content().codePointCount(0, captured().content().length()));

        assertThrows(ApiException.class, () -> chat.say(member("길이초과"), new WorldChatSend("가".repeat(101))));
    }

    /**
     * 최소 간격(0.8초) 안의 두 번째는 거부되고 <b>토픽에 나가지 않는다</b>.
     *
     * <p>서버가 대기 시간을 지정한다 — 클라이언트는 벌칙 단계를 모르므로 자기 계산으로 그 값을
     * 알 수 없다 (GitLab #223).
     */
    @Test
    void aSecondMessageInsideTheMinimumIntervalIsRefused() {
        Long sender = member("도배");

        chat.say(sender, new WorldChatSend("첫 줄"));
        WorldChatTooFastException refused = assertThrows(WorldChatTooFastException.class,
                () -> chat.say(sender, new WorldChatSend("둘째 줄")));

        assertEquals(WorldChatRateLimiter.PENALTY_MS[0], refused.retryAfterMs(),
                "첫 초과의 대기는 5초다.");
        assertEquals(1, allCaptured().size(), "거부된 줄이 토픽에 나갔습니다.");
    }

    /**
     * 짧은 연속 입력은 사람의 대화다 — 0.8초를 지키면 10초 안 5회까지 통과하고 6번째가 막힌다.
     *
     * <p>시각을 밖에서 넣어 판정한다. 실제로 기다리면 이 한 케이스가 10초를 먹고, 그 대기는
     * 무엇도 증명하지 않는다.
     */
    @Test
    void fiveMessagesInTenSecondsPassAndTheSixthIsRefused() {
        Long sender = member("버스트");
        MutableClock clock = new MutableClock();
        WorldChatService burst = new WorldChatService(messaging, users,
                new WorldChatRateLimiter(redis, keyspace, clock));

        for (int index = 0; index < WorldChatRateLimiter.SHORT_WINDOW_MAX; index++) {
            burst.say(sender, new WorldChatSend("연속 " + index));
            clock.advance(WorldChatRateLimiter.MIN_INTERVAL_MS + 10);
        }
        WorldChatTooFastException refused = assertThrows(WorldChatTooFastException.class,
                () -> burst.say(sender, new WorldChatSend("여섯 번째")));

        assertEquals(WorldChatRateLimiter.SHORT_WINDOW_MAX, allCaptured().size());
        assertEquals(WorldChatRateLimiter.PENALTY_MS[0], refused.retryAfterMs());
    }

    /**
     * 벌칙은 5 → 10 → 30초로 오르고 상한을 넘지 않는다. <b>벌칙 중 재요청은 단계를 올리지 않고</b>
     * 남은 대기만 돌려준다 (GitLab #223).
     */
    @Test
    void thePenaltyClimbsToTheCapAndARetryInsideItDoesNotEscalate() {
        Long sender = member("벌칙");
        MutableClock clock = new MutableClock();
        WorldChatService limited = new WorldChatService(messaging, users,
                new WorldChatRateLimiter(redis, keyspace, clock));

        limited.say(sender, new WorldChatSend("첫 줄"));
        int allowed = 1;
        long[] expected = {WorldChatRateLimiter.PENALTY_MS[0], WorldChatRateLimiter.PENALTY_MS[1],
                WorldChatRateLimiter.PENALTY_MS[2], WorldChatRateLimiter.PENALTY_MS[2]};
        for (long wait : expected) {
            // 직전 줄과 최소 간격 안이라 초과다.
            WorldChatTooFastException refused = assertThrows(WorldChatTooFastException.class,
                    () -> limited.say(sender, new WorldChatSend("너무 빠름")));
            assertEquals(wait, refused.retryAfterMs(), "벌칙 단계가 계약과 다릅니다.");

            // 벌칙이 아직 도는 중에 한 번 더 — 단계는 그대로이고 남은 시간만 줄어든다.
            clock.advance(1_000);
            WorldChatTooFastException insideBlock = assertThrows(WorldChatTooFastException.class,
                    () -> limited.say(sender, new WorldChatSend("기다리는 중")));
            assertEquals(wait - 1_000, insideBlock.retryAfterMs(),
                    "벌칙 중 재요청이 단계를 올렸습니다.");

            // 대기가 끝나면 한 줄은 통과한다. 그 줄이 다음 초과의 기준 시각이 되고, 단계는
            // 내려가지 않는다 — 정상 한 줄로 초기화되면 매크로가 영구히 첫 단계에 머문다.
            clock.advance(wait - 1_000);
            limited.say(sender, new WorldChatSend("대기 후 한 줄"));
            allowed++;
        }

        assertEquals(allowed, allCaptured().size(), "통과한 줄 수가 다릅니다.");
    }

    /**
     * 벌칙이 끝난 뒤 조용히 있으면 단계가 0으로 돌아간다. <b>정상 메시지 한 줄로는 돌아가지
     * 않는다</b> — 그러면 "대기 → 한 줄 → 대기" 매크로가 영구히 첫 단계에 머물며 통과한다.
     */
    @Test
    void theStageResetsAfterQuietTimeButNotAfterASingleMessage() {
        MutableClock clock = new MutableClock();
        WorldChatRateLimiter limiter = new WorldChatRateLimiter(redis, keyspace, clock);
        WorldChatService limited = new WorldChatService(messaging, users, limiter);

        Long macro = member("매크로");
        limited.say(macro, new WorldChatSend("첫 줄"));
        assertEquals(WorldChatRateLimiter.PENALTY_MS[0], refusalWait(limited, macro));
        clock.advance(WorldChatRateLimiter.PENALTY_MS[0]);
        limited.say(macro, new WorldChatSend("한 줄"));     // 벌칙이 끝나자마자 한 줄
        assertEquals(WorldChatRateLimiter.PENALTY_MS[1], refusalWait(limited, macro),
                "정상 한 줄로 단계가 초기화되면 매크로가 5초마다 한 줄을 영구히 보낸다.");

        Long quiet = member("조용한이");
        limited.say(quiet, new WorldChatSend("첫 줄"));
        assertEquals(WorldChatRateLimiter.PENALTY_MS[0], refusalWait(limited, quiet));
        clock.advance(WorldChatRateLimiter.PENALTY_MS[0] + WorldChatRateLimiter.STAGE_RESET_MS + 10);
        limited.say(quiet, new WorldChatSend("한참 뒤 한 줄"));
        assertEquals(WorldChatRateLimiter.PENALTY_MS[0], refusalWait(limited, quiet),
                "벌칙 뒤 조용했으면 첫 단계로 돌아가야 한다.");
    }

    /** 제한은 사람마다 따로다 — 한 사람이 말했다고 옆 사람이 막히면 광장이 한 명짜리가 된다. */
    @Test
    void oneMembersLimitDoesNotBlockAnother() {
        chat.say(member("먼저말한이"), new WorldChatSend("첫 줄"));
        chat.say(member("나중말한이"), new WorldChatSend("둘째 줄"));

        assertEquals(2, allCaptured().size());
    }

    /**
     * 정지된 계정은 말하지 못한다.
     *
     * <p>WS Token 은 5분을 살고 연결은 그보다 오래 간다 — 발급 뒤 정지된 사람의 경로가 남는다.
     */
    @Test
    void aSuspendedMemberCannotSpeak() {
        Long sender = member("정지될이");
        lifecycle.suspend(sender, member("관리자역"), "테스트");

        ApiException refused = assertThrows(ApiException.class,
                () -> chat.say(sender, new WorldChatSend("아직 말할 수 있나")));

        assertEquals("MEMBER_ONLY", refused.errorCode().name());
        assertNothingSent();
    }

    /** 탈퇴해 사라진 계정도 마찬가지다. */
    @Test
    void aWithdrawnMemberCannotSpeak() {
        Long sender = member("탈퇴할이");
        lifecycle.withdraw(sender);

        assertThrows(ApiException.class, () -> chat.say(sender, new WorldChatSend("사라진 뒤")));
        assertNothingSent();
    }

    /**
     * 비속어가 든 줄은 <b>토픽에 나가지 않는다</b> (S15P21A604-792).
     *
     * <p>우회 표기까지 본다 — 닉네임과 같은 정규화({@code ProfanityFilter})를 거치므로
     * {@code 씨---1---발} 과 {@code F_U_C_K} 가 각각 {@code 씨i발}·{@code fuck} 이 되어 걸린다.
     *
     * <p>코드를 새로 만들지 않았다. 길이·빈 내용과 같은 {@code VALIDATION_FAILED} 라 프런트엔드가
     * 이미 "보낼 수 없는 내용입니다" 로 그린다 — 어느 단어가 걸렸는지는 알려 주지 않는다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"씨발", "씨---1---발", "F_U_C_K", "이 개새끼야"})
    void aProfaneMessageIsRefusedAndNeverBroadcast(String content) {
        Long sender = member("욕설" + content.length());

        ApiException refused = assertThrows(ApiException.class,
                () -> chat.say(sender, new WorldChatSend(content)));

        assertEquals("VALIDATION_FAILED", refused.errorCode().name());
        assertNothingSent();
    }

    /**
     * 금칙어를 품고 있지만 욕이 아닌 말은 통과한다.
     *
     * <p>정규화가 공백까지 지우기 때문에 {@code 고추장} 이 {@code 고추} 에, {@code 해보지} 가
     * {@code 보지} 에 걸린다 — 닉네임에서는 드물지만 100자 문장에서는 흔하다. 예외 목록이 없으면
     * 이 두 줄이 막히고, 그건 축제 광장에서 매일 나오는 말이다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"고추장 파는 부스 어디예요", "한번 해보지 뭐", "analysis 자료 올렸어요"})
    void anOrdinaryMessageThatLooksLikeAProfanityStillGoesOut(String content) {
        chat.say(member("오탐" + content.length()), new WorldChatSend(content));

        assertEquals(content, captured().content(), "예외 목록에 있는 말이 막혔습니다.");
    }

    /**
     * 거절된 줄은 전송 제한을 <b>소비하지 않는다</b>.
     *
     * <p>내용 검증이 도배 판정보다 먼저이기 때문이다. 순서가 뒤집히면 오탐 한 번에 벌칙 대기를
     * 물어야 한다 — 막힌 이유를 모르는 사람은 그 시간 동안 같은 말을 다시 친다.
     */
    @Test
    void aRefusedProfanityDoesNotConsumeTheCooldown() {
        Long sender = member("쿨다운");

        assertThrows(ApiException.class, () -> chat.say(sender, new WorldChatSend("씨발")));
        chat.say(sender, new WorldChatSend("죄송합니다"));

        assertEquals(1, allCaptured().size());
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    /** 한 번 거절시키고 서버가 지정한 대기를 돌려준다. */
    private long refusalWait(WorldChatService service, Long sender) {
        WorldChatTooFastException refused = assertThrows(WorldChatTooFastException.class,
                () -> service.say(sender, new WorldChatSend("또 보냄")));
        return refused.retryAfterMs();
    }

    /**
     * 창과 벌칙을 기다리지 않고 지나가게 한다.
     *
     * <p>이 클래스에는 원래 시각 조작이 없었다 — 케이스마다 새 회원을 만들어 창을 피했다. 벌칙
     * 단계와 창 만료는 그 방법으로 볼 수 없어서 시각을 밖에서 넣는다. 슬립은 쓰지 않는다: 10초·60초
     * 창을 실제로 기다리면 이 테스트 하나가 스위트 전체보다 오래 걸린다.
     */
    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-09-18T00:00:00Z");

        void advance(long millis) {
            now = now.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private Long member(String prefix) {
        return createMemberWithWallet(users, wallets, prefix);
    }

    private WorldChatMessage captured() {
        List<WorldChatMessage> sent = allCaptured();
        assertTrue(!sent.isEmpty(), "토픽으로 나간 메시지가 없습니다.");
        return sent.getLast();
    }

    private void assertNothingSent() {
        assertEquals(List.of(), allCaptured(), "거부된 메시지가 토픽에 나갔습니다.");
    }

    private List<WorldChatJoinNotice> allJoinNotices() {
        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        var destination = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(messaging, org.mockito.Mockito.atLeast(0))
                .convertAndSend(destination.capture(), captor.capture());
        List<WorldChatJoinNotice> notices = new ArrayList<>();
        for (Object value : captor.getAllValues()) {
            if (value instanceof WorldChatJoinNotice notice) {
                notices.add(notice);
            }
        }
        return notices;
    }

    private List<WorldChatMessage> allCaptured() {
        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        var destination = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(messaging, org.mockito.Mockito.atLeast(0))
                .convertAndSend(destination.capture(), captor.capture());
        List<WorldChatMessage> sent = new ArrayList<>();
        for (int index = 0; index < captor.getAllValues().size(); index++) {
            assertEquals(WorldChatService.TOPIC, destination.getAllValues().get(index));
            sent.add(assertInstanceOf(WorldChatMessage.class, captor.getAllValues().get(index)));
        }
        return sent;
    }

    /**
     * <b>클라이언트가 보낸 닉네임은 메시지 변환 경계를 넘지 못한다.</b>
     *
     * <p>서비스를 직접 부르면 {@code WorldChatSend} 에 자리가 없어 애초에 넣을 수도 없다 — 그건
     * 아무것도 증명하지 않는다. 실제 JSON 을 변환해서 그 값이 사라지는지 봐야 한다.
     */
    @Test
    void aNicknameInTheRequestJsonNeverReachesTheTopic() {
        Long sender = member("사칭시도");
        String realNickname = users.findById(sender).orElseThrow().getNickname();

        WorldChatSend parsed = jsonMapper.readValue(
                "{\"content\":\"안녕\",\"nickname\":\"관리자\",\"senderUserId\":1}",
                WorldChatSend.class);
        chat.say(sender, parsed);

        WorldChatMessage sent = captured();
        assertEquals(realNickname, sent.nickname(), "클라이언트가 보낸 이름이 실렸습니다.");
        assertEquals(sender, sent.senderUserId());
    }

    /** 서로 다른 회원의 닉네임이 겹치지 않는 것은 이 테스트들의 전제다. */
    @Test
    void twoMembersHaveDifferentNicknames() {
        assertNotEquals(users.findById(member("가")).orElseThrow().getNickname(),
                users.findById(member("나")).orElseThrow().getNickname());
    }
}
