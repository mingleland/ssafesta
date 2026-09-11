package com.example.ssafesta.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 인증 경계가 origin 을 믿는 규칙 (GitLab #177).
 *
 * <p>여기서 느슨해지면 {@code /auth/refresh}·{@code /auth/logout} 의 CSRF 방어와 OAuth 복귀 주소가
 * 동시에 넓어진다. 그래서 "통과해야 하는 것"보다 <b>통과하면 안 되는 것</b>을 더 많이 적는다.
 */
class RequestOriginsTest {

    private static final String LOCAL_BASE = "http://localhost:5173";
    private static final String DEPLOYED_BASE = "https://dev.ssafesta.world";

    @Test
    void aLocalRequestTrustsItsOwnOrigin() {
        assertTrue(RequestOrigins.isTrusted(request("http", "localhost", 5175), "http://localhost:5175", props(LOCAL_BASE)),
                "FE dev 서버 포트가 밀려도 프록시 경유 요청은 자기 자신을 믿어야 한다.");
        assertTrue(RequestOrigins.isTrusted(request("http", "127.0.0.1", 5188), "http://127.0.0.1:5188", props(LOCAL_BASE)),
                "127.0.0.1 도 로컬이다.");
    }

    @Test
    void aLocalRequestTrustsNothingElse() {
        assertFalse(RequestOrigins.isTrusted(request("http", "localhost", 5175), "http://evil.example", props(LOCAL_BASE)),
                "Host 가 로컬이어도 남의 origin 은 믿지 않는다.");
    }

    /** scheme 이 다르면 다른 origin 이다 — 여기가 뚫리면 http 페이지가 https 세션을 건드린다. */
    @Test
    void schemeMustMatchExactly() {
        assertFalse(RequestOrigins.isTrusted(request("http", "localhost", 5175), "https://localhost:5175", props(LOCAL_BASE)),
                "http 요청이 https origin 을 믿으면 안 된다.");
    }

    @Test
    void theConfiguredFrontendIsTrustedWhateverTheHostIs() {
        assertTrue(RequestOrigins.isTrusted(request("https", "api.ssafesta.world", 443), DEPLOYED_BASE, props(DEPLOYED_BASE)),
                "배포의 정본은 언제나 frontendBaseUrl 이다.");
    }

    @Test
    void anArbitraryHostEqualToItsOwnOriginIsNotEnough() {
        assertFalse(RequestOrigins.isTrusted(request("https", "evil.example", 443), "https://evil.example", props(DEPLOYED_BASE)),
                "Host==Origin 만으로 믿으면 요청자가 신뢰 기준을 정하게 된다.");
    }

    @Test
    void aMissingOriginIsNotTrusted() {
        assertFalse(RequestOrigins.isTrusted(request("http", "localhost", 5175), null, props(LOCAL_BASE)),
                "Origin 없음은 통과가 아니다.");
    }

    /**
     * <b>알려진 천장이다 — 통과가 정답이라서 통과하는 것이 아니다.</b>
     *
     * <p>{@code getServerName()} 은 요청이 보낸 Host 헤더이고 nginx 가 세 환경 모두
     * {@code proxy_set_header Host $host} 로 원본을 넘긴다. 그래서 배포 프로파일에서도
     * {@code Host: localhost} 를 단 요청에는 로컬 가지가 열린다. 브라우저는 Host 를 주소창에서
     * 만들어 임의 값을 못 보내므로 실사용 공격 경로가 아니고, 지금 이것을 막고 있는 것은 이 코드가
     * 아니라 앞단 인프라(Cloudflare·server_name·allowlist)다.
     *
     * <p>코드로 닫으려면 {@code frontendBaseUrl} 의 host 가 localhost 인지(= 로컬 배포인지)를 조건에
     * 함께 걸면 된다. #177 리뷰에서 제안했고 FE 제안 원안대로 가기로 해 넣지 않았다. 넣는 순간 이
     * 테스트는 {@code assertFalse} 로 뒤집힌다 — 그때 이 주석도 같이 지운다.
     */
    @Test
    void aDeployedProfileStillOpensTheLocalBranchForALocalHostHeader() {
        assertTrue(RequestOrigins.isTrusted(request("http", "localhost", 9999), "http://localhost:9999", props(DEPLOYED_BASE)),
                "현재 규칙의 천장을 드러내는 테스트다 — 통과를 바라는 것이 아니다.");
    }

    /** 기본 포트는 생략한다. 붙이면 브라우저가 보낸 Origin 과 문자열이 어긋나 조용히 거부된다. */
    @Test
    void theDefaultPortIsOmittedFromTheOwnOrigin() {
        assertEquals("http://localhost", RequestOrigins.ownOrigin(request("http", "localhost", 80)));
        assertEquals("https://dev.ssafesta.world", RequestOrigins.ownOrigin(request("https", "dev.ssafesta.world", 443)));
        assertEquals("http://localhost:5175", RequestOrigins.ownOrigin(request("http", "localhost", 5175)));
    }

    private MockHttpServletRequest request(String scheme, String host, int port) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme(scheme);
        request.setServerName(host);
        request.setServerPort(port);
        return request;
    }

    private AuthProperties props(String frontendBaseUrl) {
        return new AuthProperties("secret", Duration.ofMinutes(30), Duration.ofDays(14), Duration.ofMinutes(5),
                Duration.ofSeconds(60), "/api/v1/auth/refresh", frontendBaseUrl, false);
    }
}
