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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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
    @Autowired private org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired private com.example.ssafesta.common.RedisKeyspaceProperties keyspace;
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
     * 탭을 하나 더 열어 생긴 재사용은 재시도 가능한 코드로 나간다 (S15P21A604-764, GitLab #198).
     *
     * <p>여기가 FE 가 실제로 보는 자리다. 서비스 층 테스트는 세션이 살아 있다는 것까지만 말하고,
     * 봉투의 {@code code} 와 쿠키 처분은 보지 않는다. 그 둘이 FE 분기의 전부다.
     */
    @Test
    void aReplayInsideTheGraceWindowIsRetryableAndKeepsTheCookie() throws Exception {
        Long userId = users.save(new User("다중탭_" + UUID.randomUUID().toString().substring(0, 6))).getId();
        MemberSessionService.MemberSession first = sessions.issue(userId);
        MemberSessionService.MemberSession second = sessions.refresh(first.refreshToken());

        // 진 쪽 탭이 옛 쿠키로 들어온다.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header(HttpHeaders.ORIGIN, trustedOrigin)
                        .cookie(new Cookie("refresh_token", first.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_ROTATED"))
                // 쿠키를 지우면 재시도할 것이 없어진다. Set-Cookie 자체를 내지 않는다.
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        // 갱신된 쿠키로 한 번 더 보내면 성공한다 — 그것이 이 코드가 약속하는 것이다.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header(HttpHeaders.ORIGIN, trustedOrigin)
                        .cookie(new Cookie("refresh_token", second.refreshToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString());
    }

    /** 유예 밖은 그대로 일반 401 이다 — 재시도하라는 뜻이 아니다 (spec 001 시나리오 7). */
    @Test
    void aReplayOutsideTheGraceWindowIsTheGenericRefusal() throws Exception {
        Long userId = users.save(new User("유예밖_" + UUID.randomUUID().toString().substring(0, 6))).getId();
        MemberSessionService.MemberSession first = sessions.issue(userId);
        sessions.refresh(first.refreshToken());
        ageRotationMarker(first.refreshToken());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header(HttpHeaders.ORIGIN, trustedOrigin)
                        .cookie(new Cookie("refresh_token", first.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_MEMBER_TOKEN"));
    }

    /** 30초를 기다리지 않는다 — 표식의 회전 시각만 과거로 옮긴다. */
    private void ageRotationMarker(String rawToken) {
        String markerKey = keyspace.prefix() + "auth:refresh:used:" + sha256(rawToken);
        String[] fields = redis.opsForValue().get(markerKey).split(":", -1);
        assertEquals(4, fields.length, "회전 표식에 시각 칸이 없다");
        fields[3] = String.valueOf(Instant.now().minusSeconds(600).toEpochMilli());
        redis.opsForValue().set(markerKey, String.join(":", fields), Duration.ofMinutes(10));
    }

    private static String sha256(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
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
