package com.example.ssafesta.consultation.ws;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.AccessTokenService;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.booth.BoothStaff;
import com.example.ssafesta.booth.BoothStaffRepository;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import com.example.ssafesta.world.chat.WorldChatController;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
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
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
    @Autowired private BoothRepository booths;
    @Autowired private BoothStaffRepository staffs;

    /** 떨군 이유가 어디로 가는지 본다 — 브로커를 띄우지 않는다. */
    @MockitoBean private SimpMessagingTemplate messaging;

    @Test
    void aMemberGetsAFiveMinuteToken() throws Exception {
        Long userId = createMemberWithWallet(users, wallets, "토큰발급");

        mockMvc.perform(post("/api/v1/consultation/ws-token")
                        .header("Authorization", "Bearer " + sessions.issue(userId).accessToken()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.expiresInSeconds").value(300));
    }

    /**
     * <b>게스트도 받는다</b> (S15P21A604-727). 게스트가 연결하지 못하면 부스 변경 방송이 게스트
     * 화면에 닿지 않아, 게스트는 세션 내내 낡은 부스를 본다. 받는 것은 <b>읽기 전용</b> 연결이고
     * 그 경계는 아래 두 테스트가 잠근다.
     */
    @Test
    void aGuestGetsAReadOnlyToken() throws Exception {
        mockMvc.perform(post("/api/v1/consultation/ws-token")
                        .header("Authorization", "Bearer " + accessTokens.issueGuestToken().token()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.expiresInSeconds").value(300));
    }

    /** 토큰 없는 요청은 여전히 거부다 — 게스트를 연 것이지 익명을 연 것이 아니다. */
    @Test
    void anAnonymousRequestGetsNoToken() throws Exception {
        mockMvc.perform(post("/api/v1/consultation/ws-token"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * <b>게스트 주체는 숫자가 아니다.</b> 이 불변식 하나가 회원 게이트 전부를 떠받친다 — 숫자
     * 주체를 가진 게스트 연결이 하나라도 생기면 그 연결이 남의 부스 대기열을 여는 회원 행세를
     * 한다 ({@code WsTokenController}).
     */
    @Test
    void aGuestSubjectIsNeverNumeric() throws Exception {
        String response = mockMvc.perform(post("/api/v1/realtime/ws-token")
                        .header("Authorization", "Bearer " + accessTokens.issueGuestToken().token()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String wsToken = jsonMapper.readTree(response).get("token").asString();

        String subject = tokens.resolve(wsToken).orElseThrow();

        assertTrue(subject.startsWith("guest:"), "게스트 주체는 guest: 로 시작해야 합니다: " + subject);
        assertThrows(NumberFormatException.class, () -> Long.valueOf(subject));
    }

    /** 발급마다 다른 값이다 — 같은 값을 돌려주면 한 사람의 토큰이 영영 유효해진다. */
    @Test
    void eachIssueGivesADifferentToken() {
        Long userId = createMemberWithWallet(users, wallets, "토큰중복");

        assertNotEquals(tokens.issue(String.valueOf(userId)).token(),
                tokens.issue(String.valueOf(userId)).token());
    }

    // ── CONNECT 검증 ────────────────────────────────────────────────────────

    @Test
    void aValidTokenConnectsAndCarriesTheMemberId() {
        Long userId = createMemberWithWallet(users, wallets, "연결성공");
        String token = tokens.issue(String.valueOf(userId)).token();

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
        String token = tokens.issue(String.valueOf(userId)).token();

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

    /**
     * <b>{@code STOMP} 프레임도 {@code CONNECT} 다.</b> STOMP 1.2 가 동의어로 규정하고 Spring 은
     * 별도 enum 상수로 둔다 — {@code StompCommand} 로 분기하면 이 표기가 토큰 검증을 지나간다
     * (S15P21A604-692).
     */
    @Test
    void aStompFrameWithoutATokenIsRefusedLikeConnect() {
        assertThrows(IllegalArgumentException.class,
                () -> interceptor.preSend(connectLike(StompCommand.STOMP, null), null));
    }

    /** 유효한 토큰을 실은 {@code STOMP} 프레임은 {@code CONNECT} 와 똑같이 연결된다. */
    @Test
    void aStompFrameWithAValidTokenConnects() {
        Long userId = createMemberWithWallet(users, wallets, "STOMP프레임");
        String token = tokens.issue(String.valueOf(userId)).token();

        Message<?> connected = interceptor.preSend(
                connectLike(StompCommand.STOMP, "Bearer " + token), null);

        assertEquals(String.valueOf(userId),
                StompHeaderAccessor.wrap(connected).getUser().getName());
    }

    /**
     * <b>서버 전용 command 는 인바운드에서 거부한다.</b>
     *
     * <p>{@code MESSAGE} 가 특히 그렇다 — {@code SEND} 와 같은 {@code SimpMessageType.MESSAGE} 라,
     * 막지 않으면 그 표기로 destination 차단을 지나 브로커까지 간다 (S15P21A604-692).
     */
    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"MESSAGE", "CONNECTED", "RECEIPT", "ERROR"})
    void serverOnlyFramesAreRefusedInbound(StompCommand command) {
        assertThrows(IllegalArgumentException.class,
                () -> interceptor.preSend(frame(command, "/topic/world/chat"), null));
    }

    /**
     * <b>전 command 를 훑는다.</b>
     *
     * <p>-686·-687 의 테스트는 정상 클라이언트가 쓰는 command 만 고정했고, 규격에는 있지만 아무도
     * 안 쓰는 {@code STOMP}·{@code MESSAGE} 가 그 사이로 새어 나갔다. 여기서 표 전체를 돌아,
     * 새 command 가 생겨도 <b>판정 없이 통과하는 일이 없게</b> 한다.
     */
    @Test
    void everyCommandHasAVerdict() {
        // destination 은 **게스트도 구독할 수 있는** 토픽으로 둔다. 채팅 토픽을 쓰면 SUBSCRIBE 가
        // "떨궈져서" 통과로 세어져(S15P21A604-727), 이 표가 무엇을 재는지 흐려진다.
        Set<StompCommand> passed = EnumSet.noneOf(StompCommand.class);
        for (StompCommand command : StompCommand.values()) {
            try {
                interceptor.preSend(frame(command, "/topic/world/booths"), null);
                passed.add(command);
            } catch (RuntimeException refused) {
                // 거부는 판정이다.
            }
        }

        assertEquals(EnumSet.of(StompCommand.DISCONNECT, StompCommand.SUBSCRIBE,
                        StompCommand.UNSUBSCRIBE, StompCommand.ACK, StompCommand.NACK,
                        StompCommand.BEGIN, StompCommand.COMMIT, StompCommand.ABORT),
                passed,
                "통과하는 command 목록이 바뀌었습니다. 새 command 가 판정 없이 지나가고 있지 않은지 보세요.");
    }

    /** raw queue 는 user destination 변환을 우회하므로 직접 구독하지 못한다. */
    @Test
    void aRawQueueSubscriptionIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/queue/consultation-user42"), null));
    }

    /** 방문자 개인 큐는 user destination 변환이 주인을 고르므로 여기서 더 볼 것이 없다. */
    @Test
    void theVisitorQueueSubscriptionPassesThrough() {
        Message<?> subscription = frame(StompCommand.SUBSCRIBE, "/user/queue/consultation");

        assertEquals(subscription, interceptor.preSend(subscription, null));
    }

    // ── 부스 토픽 구독 게이트 (S15P21A604-693) ─────────────────────────────
    //
    // CONNECT 에서 신원을 확정하고, SUBSCRIBE 에서 boothId 별 구성원을 본다. CONNECT 시점에는
    // boothId 가 없어 멤버십을 검사할 수 없다. GitLab #133(2026-09-07) 에서 FE 에 약속한 동작이다.

    @Test
    void theBoothOwnerMaySubscribeToTheBoothTopic() {
        Long ownerId = createMemberWithWallet(users, wallets, "토픽소유자");
        Long boothId = booths.save(new Booth(ownerId, "토픽 부스")).getId();
        Message<?> subscription = frameAs(ownerId, StompCommand.SUBSCRIBE, boothTopic(boothId));

        assertEquals(subscription, interceptor.preSend(subscription, null));
    }

    /** 상담원은 콘텐츠는 못 고치지만 대기열은 봐야 한다 — 역할과 무관하게 구성원이면 통과다. */
    @Test
    void aConsultantMaySubscribeToTheBoothTopic() {
        Long ownerId = createMemberWithWallet(users, wallets, "토픽소유자");
        Long consultantId = createMemberWithWallet(users, wallets, "토픽상담원");
        Long boothId = booths.save(new Booth(ownerId, "토픽 부스")).getId();
        staffs.save(new BoothStaff(boothId, consultantId, "CONSULTANT"));
        Message<?> subscription = frameAs(consultantId, StompCommand.SUBSCRIBE, boothTopic(boothId));

        assertEquals(subscription, interceptor.preSend(subscription, null));
    }

    /**
     * 구성원이 아닌 회원은 거부된다 — WS Token 만 있으면 남의 부스 대기열(방문자 닉네임·AI 대화
     * 요약)을 읽을 수 있던 자리다.
     */
    @Test
    void aStrangerIsRefusedTheBoothTopic() {
        Long ownerId = createMemberWithWallet(users, wallets, "토픽소유자");
        Long strangerId = createMemberWithWallet(users, wallets, "토픽남");
        Long boothId = booths.save(new Booth(ownerId, "토픽 부스")).getId();

        assertThrows(IllegalArgumentException.class,
                () -> interceptor.preSend(frameAs(strangerId, StompCommand.SUBSCRIBE, boothTopic(boothId)), null));
    }

    /** 주체가 없는 SUBSCRIBE 는 판정할 대상이 없으므로 거부다 — 조용히 통과시키면 게이트가 없는 것과 같다. */
    @Test
    void aSubscriptionWithoutAPrincipalIsRefusedTheBoothTopic() {
        Long ownerId = createMemberWithWallet(users, wallets, "토픽소유자");
        Long boothId = booths.save(new Booth(ownerId, "토픽 부스")).getId();

        assertThrows(IllegalArgumentException.class,
                () -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, boothTopic(boothId)), null));
    }

    /** 없는 부스도 거부다 — 존재하지 않는 대기열을 미리 구독해 두는 경로를 남기지 않는다. */
    @Test
    void anUnknownBoothTopicIsRefused() {
        Long memberId = createMemberWithWallet(users, wallets, "토픽없는부스");

        assertThrows(IllegalArgumentException.class,
                () -> interceptor.preSend(frameAs(memberId, StompCommand.SUBSCRIBE, boothTopic(999_999L)), null));
    }

    // ── 게스트 연결의 경계 (S15P21A604-727) ────────────────────────────────

    /** 게스트 연결도 주체를 싣는다 — 그 이름이 회원인지 아닌지를 이후 게이트가 읽는다. */
    @Test
    void aGuestTokenConnects() {
        String token = tokens.issue("guest:11111111-2222-3333-4444-555555555555").token();

        Message<?> connected = interceptor.preSend(connectWith("Authorization", "Bearer " + token), null);

        assertEquals("guest:11111111-2222-3333-4444-555555555555",
                StompHeaderAccessor.wrap(connected).getUser().getName());
    }

    /** 게스트는 부스 구성원일 수 없다 — 대기열에는 방문자 닉네임과 AI 대화 요약이 실린다. */
    @Test
    void aGuestIsRefusedTheBoothTopic() {
        Long ownerId = createMemberWithWallet(users, wallets, "토픽소유자");
        Long boothId = booths.save(new Booth(ownerId, "토픽 부스")).getId();

        assertThrows(IllegalArgumentException.class,
                () -> interceptor.preSend(frameAsSubject("guest:abc", StompCommand.SUBSCRIBE,
                        boothTopic(boothId)), null));
    }

    /**
     * <b>부스 변경 방송은 게스트도 구독한다</b> — 이 기능의 존재 이유다(GitLab #193).
     *
     * <p>거부만 고정하면 게이트를 조금 조이는 것만으로 게스트가 통째로 닫혀도 초록이 된다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"guest:abc", "42"})
    void theBoothChangeTopicIsOpenToEveryConnection(String subject) {
        Message<?> subscription = frameAsSubject(subject, StompCommand.SUBSCRIBE, "/topic/world/booths");

        assertEquals(subscription, interceptor.preSend(subscription, null));
    }

    // ── 게스트의 채팅 구독 (S15P21A604-727, GitLab #193 FE 요청) ──────────────
    //
    // WS Token 을 게스트에게 연 것은 **연결 자격**이지 **채팅 자격**이 아니다. 그리고 이 거부는
    // 연결을 끊지 않는다 — 같은 소켓이 부스 변경 방송을 나르므로, 채팅 프레임 하나로 월드
    // 동기화까지 죽으면 제재가 사건에 비해 크다.

    @Test
    void aGuestChatSubscriptionIsDroppedNotThrown() {
        Message<?> subscription = frameAsSubject("guest:abc", StompCommand.SUBSCRIBE,
                "/topic/world/chat");

        assertNull(interceptor.preSend(subscription, null),
                "게스트의 채팅 구독은 브로커에 도달하면 안 됩니다.");
    }

    /**
     * <b>연결이 살아 있어야 한다.</b> 이 단정이 이 기능의 전부다 — 예외를 던지는 구현으로
     * 되돌아가도 위 테스트는 (그것도 통과가 아니라 예외로) 초록이 되지 않지만, 무엇이 잘못됐는지는
     * 여기서만 드러난다: 같은 연결로 이어지는 부스 방송 구독이 계속 성립해야 한다.
     */
    @Test
    void theConnectionSurvivesARefusedChatSubscription() {
        interceptor.preSend(frameAsSubject("guest:abc", StompCommand.SUBSCRIBE, "/topic/world/chat"), null);

        Message<?> booths = frameAsSubject("guest:abc", StompCommand.SUBSCRIBE, "/topic/world/booths");
        assertEquals(booths, interceptor.preSend(booths, null));
    }

    /** 조용히 떨구지 않는다 — 구독에는 성공 응답이 없어서, 말없이 버리면 영영 기다린다 (T-24). */
    @Test
    void aGuestIsToldWhyTheChatSubscriptionWasDropped() {
        interceptor.preSend(frameAsSubject("guest:abc", StompCommand.SUBSCRIBE, "/topic/world/chat"), null);

        verify(messaging).convertAndSendToUser(eq("guest:abc"), eq(WorldChatController.ERROR_QUEUE),
                eq(new WorldChatController.WorldChatError(ErrorCode.MEMBER_ONLY.name(),
                        "회원 계정만 채팅을 볼 수 있습니다.")));
    }

    /** 회원의 채팅 구독은 그대로 지나간다 — 게이트를 조이다 채팅을 통째로 닫으면 안 된다. */
    @Test
    void aMemberMaySubscribeToChat() {
        Message<?> subscription = frameAsSubject("42", StompCommand.SUBSCRIBE, "/topic/world/chat");

        assertEquals(subscription, interceptor.preSend(subscription, null));
    }

    private static String boothTopic(Long boothId) {
        return "/topic/booths/" + boothId + "/consultation";
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

    /** {@code CONNECT} 계열 프레임 — 헤더 유무만 다르게 준다. */
    private Message<?> connectLike(StompCommand command, String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (authorization != null) {
            accessor.setNativeHeader("Authorization", authorization);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<?> frame(StompCommand command, String destination) {
        return frameAs(null, command, destination);
    }

    /** 문자열 주체로 프레임을 만든다 — 게스트 주체는 숫자가 아니다. */
    private Message<?> frameAsSubject(String subject, StompCommand command, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        accessor.setUser(new StompAuthChannelInterceptor.ConsultationPrincipal(subject));
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    /** CONNECT 가 심어 둔 주체를 흉내낸다 — SUBSCRIBE 는 그 이름으로 구성원을 판정한다. */
    private Message<?> frameAs(Long userId, StompCommand command, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (userId != null) {
            accessor.setUser(new StompAuthChannelInterceptor.ConsultationPrincipal(String.valueOf(userId)));
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
