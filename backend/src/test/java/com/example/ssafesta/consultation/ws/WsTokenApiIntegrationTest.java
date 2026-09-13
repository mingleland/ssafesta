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

    /** {@code CONNECT} 가 아닌 프레임은 그대로 지나간다 — 구독·해제까지 막으면 연결이 죽는다. */
    @Test
    void otherFramesPassThrough() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setLeaveMutable(true);
        Message<?> frame = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

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
}
