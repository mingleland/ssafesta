package com.example.ssafesta.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The route that starts a social login had no test at all.
 *
 * <p>It used to keep its own {@code Set.of("google", "kakao")}, a second copy of the provider list
 * that {@link com.example.ssafesta.user.OAuthProvider} already holds. Adding SSAFY to the enum and
 * to {@code application.yml} would have left this route answering 404, which reads like a missing
 * registration rather than a stale list. These tests pin the enum as the only list.
 */
class OAuthAuthorizationControllerTest {

    private final OAuthAuthorizationController controller = new OAuthAuthorizationController();

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
        ApiException refused = assertThrows(ApiException.class, () -> controller.authorize("naver"));

        assertEquals(ErrorCode.OAUTH_PROVIDER_NOT_SUPPORTED, refused.errorCode(),
                "지원하지 않는 provider 는 전용 코드로 거부돼야 한다.");
    }

    private URI location(String provider) {
        ResponseEntity<Void> response = controller.authorize(provider);

        assertEquals(HttpStatus.FOUND, response.getStatusCode(), "브라우저 내비게이션이라 302 여야 한다.");
        return response.getHeaders().getLocation();
    }
}
