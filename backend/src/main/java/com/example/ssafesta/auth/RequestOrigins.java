package com.example.ssafesta.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Set;

/**
 * 인증 경계가 "이 origin 을 믿어도 되는가"를 판단하는 한 곳 (GitLab #177).
 *
 * <p>규칙은 하나다. {@code frontendBaseUrl} 정확 일치는 언제나 믿고, 그 밖에는 <b>요청이 로컬로
 * 들어왔을 때에 한해</b> 요청 자신의 origin 만 믿는다. 로컬 개발에서 FE dev 서버 포트가 5173 에서
 * 밀리면(5174·5175·5199) 그 순간 refresh 가 {@code UNTRUSTED_ORIGIN} 이 되고 OAuth 복귀가 엉뚱한
 * 포트로 가는데, 그 하나를 없애려고 설정·CORS 목록·wildcard 를 늘리지 않기 위한 seam 이다.
 *
 * <p><b>로컬 가지는 요청이 아니라 배포로 연다.</b> {@code getServerName()} 은 서버의 속성이 아니라
 * <b>요청이 보낸 Host 헤더</b>이고, nginx 가 세 환경 모두 {@code proxy_set_header Host $host} 로 원본을
 * 그대로 넘긴다. 그래서 요청의 Host 만 보고 판단하면 배포에 도달한 {@code Host: localhost} 요청에도
 * 가지가 열리고, "배포에서는 안 열린다"를 보증하는 것이 코드가 아니라 앞단 인프라
 * (Cloudflare·server_name·allowlist)가 된다. {@link #isLocalDeployment} 를 함께 거는 이유가 그것이다 —
 * {@code frontendBaseUrl} 은 요청자가 못 바꾸므로 배포에서는 Host 가 무엇이든 가지가 영구히 닫힌다
 * (#177 리뷰 지적 → 2026-09-11 FE 합의).
 *
 * <p><b>경계 조건 하나.</b> Host 에 포트가 없으면 {@code getServerPort()} 는 80 이 아니라 <b>커넥터 포트</b>(로컬 8080)를
 * 돌려준다. 그래서 {@code Host: localhost} 요청의 ownOrigin 은 {@code http://localhost:8080} 이고
 * 브라우저가 보낸 {@code Origin: http://localhost} 와 어긋나 거부된다. 안전한 쪽으로 어긋나므로
 * 그대로 둔다 — 느슨해지는 방향이 아니다.
 */
final class RequestOrigins {

    /** 로컬로 치는 host. 이름 해석이 아니라 문자열이다 — {@code contains} 나 접미사 비교로 넓히지 않는다. */
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1");

    private RequestOrigins() {
    }

    /** 요청이 스스로 밝힌 origin. {@code scheme://host[:port]}, 기본 포트는 생략한다. */
    static String ownOrigin(HttpServletRequest request) {
        String scheme = request.getScheme();
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return defaultPort
                ? scheme + "://" + request.getServerName()
                : scheme + "://" + request.getServerName() + ":" + port;
    }

    /**
     * {@code candidate} 를 신뢰할 수 있는가. 비교는 전부 <b>문자열 정확 일치</b>다 —
     * {@code startsWith}·{@code contains}·wildcard 를 쓰면 {@code http://localhost.evil.example} 이
     * 통과한다.
     */
    static boolean isTrusted(HttpServletRequest request, String candidate, AuthProperties properties) {
        if (candidate == null) {
            return false;
        }
        if (candidate.equals(properties.frontendBaseUrl())) {
            return true;
        }
        return isLocalDeployment(properties)
                && isLocalHost(request.getServerName())
                && candidate.equals(ownOrigin(request));
    }

    /**
     * 이 인스턴스가 로컬 개발 스택인가. 판단 재료는 <b>요청이 못 건드리는 설정값</b>이어야 한다 —
     * 요청의 Host 로 판단하면 배포에서도 가지가 열린다.
     */
    private static boolean isLocalDeployment(AuthProperties properties) {
        return isLocalHost(hostOf(properties.frontendBaseUrl()));
    }

    private static boolean isLocalHost(String host) {
        // Set.of 의 contains 는 null 에 NPE 를 던진다 — 값이 없으면 "로컬 아님"으로 닫는다.
        return host != null && LOCAL_HOSTS.contains(host);
    }

    /** 파싱에 실패하면 null 이다. 신뢰 판단에서 그것은 곧 "로컬 아님"이라 안전한 쪽으로 닫힌다. */
    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
