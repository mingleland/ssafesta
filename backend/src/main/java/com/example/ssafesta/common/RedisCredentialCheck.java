package com.example.ssafesta.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Redis ACL 자격증명이 배포에 실제로 주입됐는지 <b>기동 시점에</b> 확인한다
 * (S15P21A604-422, GitLab #123).
 *
 * <p>infra-002 T015 가 Redis 의 default 사용자를 끄고 ACL 로 dev_back·dev_ai·demo_back·demo_ai 를
 * 나눈다. 그래서 배포는 두 값을 반드시 주입해야 하는데, {@code application-infra.yml} 이 기본값
 * 없이 선언하는 것만으로는 부족하다 — <b>설정 바인더가 해석되지 않은 placeholder 를 그대로
 * 통과시킨다.</b> 비밀번호가 {@code "${REDIS_PASSWORD}"} 라는 문자열인 채로 바인딩되고, Lettuce 는
 * 지연 연결이라 그 상태로도 기동에 성공한다.
 *
 * <p>그 다음이 이 클래스가 막으려는 모양이다. 첫 Redis 사용(로그인·Refresh·일일 지급)에서
 * {@code WRONGPASS} 로 죽고, health 는 DOWN 이 되지만 {@code show-details} 기본값이 {@code never}
 * 라 본문에 원인이 없다. 배포는 통과했는데 로그인만 안 되는 서버가 남는다.
 *
 * <p>{@code ObjectStorageProperties} 가 R2 자격증명에 같은 검사를 두고 있다 — 거기서 실제로
 * 겪은 뒤 추가한 방어다(T-101). 로컬은 무인증 Redis 를 그대로 쓰므로 이 검사는 {@code infra}
 * 프로파일에서만 돈다.
 */
@Component
@Profile("infra")
public class RedisCredentialCheck {

    public RedisCredentialCheck(@Value("${spring.data.redis.username:}") String username,
                                @Value("${spring.data.redis.password:}") String password) {
        require(username, "REDIS_USERNAME");
        require(password, "REDIS_PASSWORD");
    }

    private static void require(String value, String variable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(variable + " 이(가) 비어 있습니다. Redis 는 ACL 이 켜져 "
                    + "있어 자격증명 없이는 연결이 거부됩니다.");
        }
        // 값 자체는 절대 메시지에 넣지 않는다 — 이 예외는 로그로 나간다. 여기서 출력하는 것은
        // 주입되지 않은 환경변수 이름뿐이다.
        if (value.startsWith("${") && value.endsWith("}")) {
            throw new IllegalStateException(
                    variable + " 이(가) 해석되지 않았습니다 — 배포에서 주입해야 합니다.");
        }
    }
}
