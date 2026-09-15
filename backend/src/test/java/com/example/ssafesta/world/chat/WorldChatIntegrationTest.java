package com.example.ssafesta.world.chat;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.RedisKeyspaceProperties;
import com.example.ssafesta.user.AccountLifecycleService;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
class WorldChatIntegrationTest {

    @Autowired private WorldChatService chat;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private AccountLifecycleService lifecycle;

    /** 발행을 가로채 무엇이 나갔는지 본다 — 브로커를 띄우지 않아 결과가 흔들리지 않는다. */
    @MockitoBean private SimpMessagingTemplate messaging;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private RedisKeyspaceProperties keyspace;

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
        StringRedisTemplate downRedis = mock(StringRedisTemplate.class);
        when(downRedis.opsForValue()).thenThrow(new RedisConnectionFailureException("redis down"));
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

    /** 3초 안 두 번째는 거부되고 <b>토픽에 나가지 않는다</b>. */
    @Test
    void aSecondMessageWithinThreeSecondsIsRefused() {
        Long sender = member("도배");

        chat.say(sender, new WorldChatSend("첫 줄"));
        assertThrows(WorldChatTooFastException.class,
                () -> chat.say(sender, new WorldChatSend("둘째 줄")));

        assertEquals(1, allCaptured().size(), "거부된 줄이 토픽에 나갔습니다.");
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
     * 거절된 줄은 3초 제한을 <b>소비하지 않는다</b>.
     *
     * <p>내용 검증이 도배 판정보다 먼저이기 때문이다. 순서가 뒤집히면 오탐 한 번에 3초를 기다려야
     * 한다 — 막힌 이유를 모르는 사람은 그 3초 동안 같은 말을 다시 친다.
     */
    @Test
    void aRefusedProfanityDoesNotConsumeTheCooldown() {
        Long sender = member("쿨다운");

        assertThrows(ApiException.class, () -> chat.say(sender, new WorldChatSend("씨발")));
        chat.say(sender, new WorldChatSend("죄송합니다"));

        assertEquals(1, allCaptured().size());
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

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
