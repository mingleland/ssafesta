package com.example.ssafesta.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The two auth endpoints nothing ever called: {@code POST /auth/guest} and {@code POST /auth/logout}
 * (S15P21A604-388).
 *
 * <p>Ten test classes use a guest token, and every one of them mints it straight from
 * {@code AccessTokenService} — so the issuing endpoint, the response body the frontend reads, and
 * the whole logout path had no test at either layer. {@code /auth/refresh} is not here: it is
 * already covered by {@code OAuthCompletionApiIntegrationTest}, and is used below only as the way
 * to observe that logout actually revoked the session.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class GuestAuthApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberSessionService sessions;
    @Autowired private UserRepository users;
    @Autowired private JwtDecoder jwtDecoder;
    @Autowired private JwtEncoder jwtEncoder;
    @Value("${app.auth.frontend-base-url}") private String trustedOrigin;

    /**
     * FE dev 게이트웨이 경유 요청이 Origin 관문을 통과한다 (GitLab #177).
     *
     * <p>Vite 프록시는 {@code changeOrigin} 없이 돌므로 BE 가 보는 Host 와 Origin 이 둘 다 FE 포트다.
     * 그 둘이 같으면 Spring 은 CORS 요청으로 보지도 않고, 남는 관문은 컨트롤러의 Origin 검사
     * 하나다. 통과의 증거는 200 이 아니라 <b>401</b> 이다 — 쿠키가 없으니 다음 단계에서 떨어지는
     * 것이 정상이고, 403 이면 관문에서 막힌 것이다.
     */
    @Test
    void aRequestThroughTheLocalDevGatewayPassesTheOriginGate() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5175")
                        .with(request -> {
                            request.setServerName("localhost");
                            request.setServerPort(5175);
                            return request;
                        }))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_MEMBER_TOKEN"));
    }

    /**
     * Host 와 Origin 이 같다는 것만으로는 믿지 않는다.
     *
     * <p>이 한 줄이 없으면 규칙이 "요청자가 신뢰 기준을 정한다"로 넓어진다
     * ({@code docs/25_트러블슈팅.md} T-102).
     */
    @Test
    void aNonLocalHostMatchingItsOwnOriginIsStillRefused() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header(HttpHeaders.ORIGIN, "http://evil.example")
                        .with(request -> {
                            request.setServerName("evil.example");
                            request.setServerPort(80);
                            return request;
                        }))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("UNTRUSTED_ORIGIN"));
    }

    /**
     * The body is asserted through the decoder rather than by shape alone.
     *
     * <p>{@code accessToken} being a non-empty string is what a broken issuer would also produce.
     * What the caller depends on is the {@code role} claim — every guest-versus-member branch in
     * the codebase reads it — so that is what is pinned, along with the expiry actually being in
     * the future.
     */
    @Test
    void theGuestEndpointIssuesAUsableGuestToken() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/guest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.expiresAt").isString())
                .andReturn().getResponse().getContentAsString();

        Jwt jwt = jwtDecoder.decode(JsonPath.read(body, "$.accessToken"));
        assertEquals("GUEST", jwt.getClaimAsString("role"), "게스트 토큰의 role 이 GUEST 여야 합니다: " + body);
        assertTrue(jwt.getExpiresAt() != null && jwt.getExpiresAt().isAfter(Instant.now()),
                "만료가 미래여야 합니다: " + jwt.getExpiresAt());
    }

    /** A guest has no server-side session, so logout is a no-op that still has to answer 204. */
    @Test
    void aGuestCanLogOutWithoutASession() throws Exception {
        String guestToken = JsonPath.read(mockMvc.perform(post("/api/v1/auth/guest"))
                .andReturn().getResponse().getContentAsString(), "$.accessToken");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.ORIGIN, trustedOrigin)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + guestToken))
                .andExpect(status().isNoContent());
    }

    /**
     * Logout has to end the session on the server, not just clear the browser's cookie.
     *
     * <p>Three things are asserted because each fails independently: the cleared cookie (the
     * browser stops sending it), the access token being refused afterwards
     * ({@code SessionRevocationFilter}, which had no test of its own), and the refresh token no
     * longer minting a new access token. A logout that only cleared the cookie would keep both
     * tokens alive for anyone who copied them.
     */
    @Test
    void loggingOutRevokesTheSessionBehindBothTokens() throws Exception {
        Long userId = users.save(new User("로그아웃_" + UUID.randomUUID().toString().substring(0, 6))).getId();
        MemberSessionService.MemberSession session = sessions.issue(userId);
        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.ORIGIN, trustedOrigin)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken()))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));

        // The status alone let this slip: the filter used to answer with response.sendError,
        // which has no body at all — the client saw a 401 with nothing to branch on
        // (S15P21A604-693, HDD T-157).
        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("로그인 세션이 종료되었습니다."))
                .andExpect(jsonPath("$.requestId").isString());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header(HttpHeaders.ORIGIN, trustedOrigin)
                        .cookie(new Cookie("refresh_token", session.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_MEMBER_TOKEN"));
    }

    /**
     * The other branch of {@code SessionRevocationFilter}: a MEMBER token whose subject is not a
     * member id. {@code AccessTokenService} cannot mint one, so the token is signed here directly.
     * It has to land in the envelope too, and with the code the refresh path already uses for a
     * token that cannot identify a member (S15P21A604-693).
     */
    @Test
    void aMemberTokenWhoseSubjectIsNotANumberIsRefusedInsideTheEnvelope() throws Exception {
        Instant now = Instant.now();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                .subject("not-a-member-id").issuedAt(now).expiresAt(now.plusSeconds(60))
                .id(UUID.randomUUID().toString()).claim("role", "MEMBER").claim("sid", "sid")
                .build())).getTokenValue();

        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_MEMBER_TOKEN"))
                .andExpect(jsonPath("$.requestId").isString());
    }
}
