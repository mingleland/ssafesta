package com.example.ssafesta.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

/**
 * The route that starts a social login had no test at all.
 *
 * <p>It used to keep its own {@code Set.of("google", "kakao")}, a second copy of the provider list
 * that {@link com.example.ssafesta.user.OAuthProvider} already holds. Adding SSAFY to the enum and
 * to {@code application.yml} would have left this route answering 404, which reads like a missing
 * registration rather than a stale list. These tests pin the enum as the only list.
 */
class OAuthAuthorizationControllerTest {

    private static final String FRONTEND = "http://localhost:5173";

    private final AuthProperties properties = new AuthProperties("secret", Duration.ofMinutes(30),
            Duration.ofDays(14), Duration.ofMinutes(5), Duration.ofSeconds(60), Duration.ofSeconds(30),
            Duration.ofSeconds(30), "/api/v1/auth/refresh", FRONTEND, false);
    /** What the deployment actually registered — the three of {@code application.yml} when its env is complete. */
    private final OAuthAuthorizationController controller = controllerWith("google", "kakao", "ssafy");

    @Test
    void everyProviderInTheEnumStartsARedirect() {
        assertEquals(URI.create("/oauth2/authorization/ssafy"), location("ssafy"));
        assertEquals(URI.create("/oauth2/authorization/google"), location("google"));
        assertEquals(URI.create("/oauth2/authorization/kakao"), location("kakao"));
    }

    /** The path segment is user input; the registration id in Spring's config is lower case. */
    @Test
    void theProviderNameIsCaseInsensitive() {
        assertEquals(URI.create("/oauth2/authorization/ssafy"), location("SSAFY"));
    }

    @Test
    void aProviderOutsideTheEnumIsRefusedWithItsOwnCode() {
        ApiException refused = assertThrows(ApiException.class,
                () -> controller.authorize("naver", null, request("localhost", 5175)));

        assertEquals(ErrorCode.OAUTH_PROVIDER_NOT_SUPPORTED, refused.errorCode(),
                "지원하지 않는 provider 는 전용 코드로 거부돼야 한다.");
    }

    /**
     * 복귀 origin 은 <b>시작 요청에서만</b> 검증한다 (GitLab #177).
     *
     * <p>콜백은 등록된 redirect URI 때문에 8080 으로 들어오므로 그 시점에 다시 판단할 재료가 없다.
     * 통과한 값만 세션에 들어가고, 콜백은 그것을 꺼내 쓰기만 한다.
     */
    @Test
    void aLocalReturnOriginMatchingTheRequestIsKeptForTheCallback() {
        MockHttpServletRequest request = request("localhost", 5175);

        controller.authorize("google", "http://localhost:5175", request);

        assertEquals("http://localhost:5175",
                request.getSession(false).getAttribute(OAuthLoginSuccessHandler.RETURN_ORIGIN_SESSION_ATTRIBUTE),
                "프록시 경유 시작 요청의 복귀 주소가 남아야 한다.");
    }

    /**
     * 저장하지 않는 세 경우는 <b>전부 에러가 아니다.</b> 302 는 그대로 나가고 복귀만 기존
     * {@code frontendBaseUrl} 로 떨어진다 — Swagger·Bruno·8080 직행 절차가 오늘과 같아야 한다.
     */
    @Test
    void anUntrustedOrAbsentReturnOriginIsIgnoredWithoutFailing() {
        assertNull(startAndReadStoredOrigin("http://localhost:5175", "localhost", 8080),
                "8080 직행은 자기 origin 이 아닌 값을 들고 와도 저장하면 안 된다.");
        assertNull(startAndReadStoredOrigin("http://evil.example", "localhost", 5175),
                "남의 origin 은 저장하지 않는다.");
        assertNull(startAndReadStoredOrigin(null, "localhost", 5175),
                "파라미터가 없으면 세션을 만들 일도 없다.");
    }

    private Object startAndReadStoredOrigin(String returnOrigin, String host, int port) {
        MockHttpServletRequest request = request(host, port);

        ResponseEntity<Void> response = controller.authorize("google", returnOrigin, request);

        assertEquals(HttpStatus.FOUND, response.getStatusCode(), "저장 여부와 무관하게 302 여야 한다.");
        return request.getSession(false) == null ? null
                : request.getSession(false).getAttribute(OAuthLoginSuccessHandler.RETURN_ORIGIN_SESSION_ATTRIBUTE);
    }

    /**
     * 이름은 enum 에 있는데 이 서버에 registration 이 없는 경우 (S15P21A604-357 후속).
     *
     * <p>배포 env 에서 {@code SSAFY_CLIENT_ID} 3종이 빠지면 실제로 이 모양이 된다. 막지 않으면
     * 302 가 그대로 나가고 다음 홉의 Spring Security 가 {@code InvalidClientRegistrationIdException}
     * 을 던져 500 이 되는데, 그 자리에는 FE 가 읽을 코드가 없다.
     */
    @Test
    void aProviderWithNoClientRegistrationIsRefusedHereRatherThanOnTheNextHop() {
        OAuthAuthorizationController withoutSsafy = controllerWith("google", "kakao");

        ApiException refused = assertThrows(ApiException.class,
                () -> withoutSsafy.authorize("ssafy", null, request("localhost", 5175)));

        assertEquals(ErrorCode.OAUTH_PROVIDER_NOT_SUPPORTED, refused.errorCode(),
                "등록되지 않은 provider 는 302 를 내보내지 말고 여기서 거부돼야 한다.");
    }

    private OAuthAuthorizationController controllerWith(String... registrationIds) {
        Set<String> registered = Set.of(registrationIds);
        ClientRegistrationRepository repository =
                id -> registered.contains(id) ? registration(id) : null;
        return new OAuthAuthorizationController(properties, repository);
    }

    /** 컨트롤러는 존재 여부만 보므로 값은 빌더가 요구하는 최소만 채운다. */
    private ClientRegistration registration(String id) {
        return ClientRegistration.withRegistrationId(id)
                .clientId(id + "-client-id")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost:8080/login/oauth2/code/" + id)
                .authorizationUri("https://provider.test/authorize")
                .tokenUri("https://provider.test/token")
                .build();
    }

    private MockHttpServletRequest request(String host, int port) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName(host);
        request.setServerPort(port);
        return request;
    }

    private URI location(String provider) {
        ResponseEntity<Void> response = controller.authorize(provider, null, request("localhost", 5175));

        assertEquals(HttpStatus.FOUND, response.getStatusCode(), "브라우저 내비게이션이라 302 여야 한다.");
        return response.getHeaders().getLocation();
    }
}
