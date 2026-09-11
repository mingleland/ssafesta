package com.example.ssafesta.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;

/**
 * 인증 경계가 "이 origin 을 믿어도 되는가"를 판단하는 한 곳 (GitLab #177).
 *
 * <p>규칙은 하나다. {@code frontendBaseUrl} 정확 일치는 언제나 믿고, 그 밖에는 <b>요청이 로컬로
 * 들어왔을 때에 한해</b> 요청 자신의 origin 만 믿는다. 로컬 개발에서 FE dev 서버 포트가 5173 에서
 * 밀리면(5174·5175·5199) 그 순간 refresh 가 {@code UNTRUSTED_ORIGIN} 이 되고 OAuth 복귀가 엉뚱한
 * 포트로 가는데, 그 하나를 없애려고 설정·CORS 목록·wildcard 를 늘리지 않기 위한 seam 이다.
 *
 * <p><b>경계 조건 둘을 알고 쓴다.</b>
 *
 * <p>하나. {@code getServerName()} 은 서버의 속성이 아니라 <b>요청이 보낸 Host 헤더</b>다. nginx 가
 * 세 환경 모두 {@code proxy_set_header Host $host} 로 원본을 그대로 넘기므로, 배포에 도달한 요청이
 * {@code Host: localhost} 를 달고 있으면 아래 로컬 가지가 열린다. 브라우저는 Host 를 주소창에서
 * 만들어 임의 값을 못 보내니 실사용 공격 경로는 아니지만, <b>"배포에서는 이 가지가 안 열린다"를
 * 보증하는 것은 이 코드가 아니라 앞단 인프라</b>다. 코드로 닫으려면 조건에
 * {@code frontendBaseUrl 의 host 가 localhost 인가}(= 로컬 배포인가)를 함께 걸면 된다 — #177 리뷰에서
 * 제안했고, FE 제안 원안대로 가기로 해 여기서는 넣지 않았다.
 *
 * <p>둘. Host 에 포트가 없으면 {@code getServerPort()} 는 80 이 아니라 <b>커넥터 포트</b>(로컬 8080)를
 * 돌려준다. 그래서 {@code Host: localhost} 요청의 ownOrigin 은 {@code http://localhost:8080} 이고
 * 브라우저가 보낸 {@code Origin: http://localhost} 와 어긋나 거부된다. 안전한 쪽으로 어긋나므로
 * 그대로 둔다 — 느슨해지는 방향이 아니다.
 */
final class RequestOrigins {

    /** 로컬 가지를 여는 Host. 이름 해석이 아니라 문자열이다 — {@code contains} 나 접미사 비교로 넓히지 않는다. */
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
        return LOCAL_HOSTS.contains(request.getServerName()) && candidate.equals(ownOrigin(request));
    }
}
