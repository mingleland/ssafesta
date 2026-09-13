package com.example.ssafesta.consultation.ws;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.AccessTokenService;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * WS Token 발급과 STOMP {@code CONNECT} 검증 (spec 011 FR-019·FR-020, 헌법 13조).
 *
 * <p>여기서 잠그는 것은 <b>무엇이 거부되는가</b>다. 발급이 되는지만 보면 Access Token 재사용이나
 * URL query 전달 같은, 계약이 명시적으로 금지한 경로가 열려 있어도 초록이 된다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class WsTokenApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private AccessTokenService accessTokens;
    @Autowired private WsTokenService tokens;
    @Autowired private StompAuthChannelInterceptor interceptor;
    @Autowired private JsonMapper jsonMapper;

    @Test
    void aMemberGetsAFiveMinuteToken() throws Exception {
        Long userId = createMemberWithWallet(users, wallets, "토큰발급");

        mockMvc.perform(post("/api/v1/consultation/ws-token")
                        .header("Authorization", "Bearer " + sessions.issue(userId).accessToken()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.expiresInSeconds").value(300));
    }

    /** 게스트는 사람 상담 자체를 이용할 수 없다 (FR-014, 헌법 12조). */
    @Test
    void aGuestGetsNoToken() throws Exception {
        mockMvc.perform(post("/api/v1/consultation/ws-token")
                        .header("Authorization", "Bearer " + accessTokens.issueGuestToken().token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_ONLY"));
    }

    /** 발급마다 다른 값이다 — 같은 값을 돌려주면 한 사람의 토큰이 영영 유효해진다. */
    @Test
    void eachIssueGivesADifferentToken() {
        Long userId = createMemberWithWallet(users, wallets, "토큰중복");

        assertNotEquals(tokens.issue(userId).token(), tokens.issue(userId).token());
    }

    // ── CONNECT 검증 ────────────────────────────────────────────────────────

    @Test
    void aValidTokenConnectsAndCarriesTheMemberId() {
        Long userId = createMemberWithWallet(users, wallets, "연결성공");
        String token = tokens.issue(userId).token();

        Message<?> connected = interceptor.preSend(connectWith("Authorization", "Bearer " + token), null);

        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(connected);
        assertTrue(accessor.getUser() != null, "검증된 연결에는 주체가 실려야 합니다.");
        assertEquals(String.valueOf(userId), accessor.getUser().getName(),
                "user destination 이 이 이름으로 방문자를 고른다.");
    }

    @Test
    void aConnectWithoutAnyHeaderIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> interceptor.preSend(connectWith(null, null), null));
    }

    /**
     * <b>Access Token 을 그대로 실은 연결은 거부된다</b> (헌법 13조).
     *
     * <p>토큰을 4계층으로 가른 이유가 여기 있다. 이 단정이 없으면 "아무 Bearer 나 통과" 하는
     * 구현으로 되돌아가도 나머지 테스트가 전부 초록이다.
     */
    @Test
    void anAccessTokenIsNotAWsToken() {
        Long userId = createMemberWithWallet(users, wallets, "액세스토큰");
        String accessToken = sessions.issue(userId).accessToken();

        assertThrows(IllegalArgumentException.class,
                () -> interceptor.preSend(connectWith("Authorization", "Bearer " + accessToken), null));
    }

    /** <b>URL query 로 넘긴 토큰은 보지 않는다</b> (FR-019) — 그 자리의 값은 로그에 남는다. */
    @Test
    void aTokenInTheQueryStringIsIgnored() {
        Long userId = createMemberWithWallet(users, wallets, "쿼리토큰");
        String token = tokens.issue(userId).token();

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setDestination("/ws/consultation?token=" + token);
        accessor.setLeaveMutable(true);

        assertThrows(IllegalArgumentException.class,
                () -> interceptor.preSend(MessageBuilder.createMessage(new byte[0],
                        accessor.getMessageHeaders()), null));
    }

    @Test
    void anUnknownTokenIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> interceptor.preSend(connectWith("Authorization", "Bearer 없는토큰"), null));
    }

    /**
     * 클라이언트는 어떤 destination 으로도 직접 SEND 할 수 없다.
     *
     * <p>{@code /topic}·{@code /queue} 는 컨트롤러를 거치지 않고 broker 로 갈 수 있어
     * 서버 이벤트를 위조한다. {@code /app} 아래라고 열리는 것도 아니다 — allowlist 는 접두가
     * 아니라 <b>정확히 일치</b>라서, 등록된 핸들러가 없는 {@code /app/...} 도 거부된다.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/topic/world/chat",
            "/topic/booths/7/consultation",
            "/queue/consultation",
            "/user/queue/consultation",
            "/app/world/consultation",
            "/unregistered"
    })
    void everyClientSendIsRefused(String destination) {
        assertThrows(IllegalArgumentException.class,
                () -> interceptor.preSend(frame(StompCommand.SEND, destination), null));
    }

    /**
     * allowlist 에 오른 destination 하나는 통과한다 (S15P21A604-687 월드 채팅).
     *
     * <p>거부만 고정하면 목록을 통째로 비워도 초록이 된다 — 그러면 채팅이 조용히 죽는다.
     */
    @Test
    void theAllowlistedChatDestinationPassesThrough() {
        Message<?> send = frame(StompCommand.SEND, "/app/world/chat");

        assertEquals(send, interceptor.preSend(send, null));
    }

    /** raw queue 는 user destination 변환을 우회하므로 직접 구독하지 못한다. */
    @Test
    void aRawQueueSubscriptionIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/queue/consultation-user42"), null));
    }

    /** 기존 상담 계약이 쓰는 두 구독은 그대로 유지한다. */
    @ParameterizedTest
    @ValueSource(strings = {
            "/user/queue/consultation",
            "/topic/booths/7/consultation"
    })
    void consultationContractSubscriptionsPassThrough(String destination) {
        Message<?> subscription = frame(StompCommand.SUBSCRIBE, destination);

        assertEquals(subscription, interceptor.preSend(subscription, null));
    }

    /** 구독 해제와 정상 종료는 destination 정책의 대상이 아니다. */
    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"UNSUBSCRIBE", "DISCONNECT"})
    void lifecycleFramesPassThrough(StompCommand command) {
        Message<?> frame = frame(command, null);

        assertEquals(frame, interceptor.preSend(frame, null));
    }

    private Message<?> connectWith(String header, String value) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (header != null) {
            accessor.setNativeHeader(header, value);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<?> frame(StompCommand command, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
