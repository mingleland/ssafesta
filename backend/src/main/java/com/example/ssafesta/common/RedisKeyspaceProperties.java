package com.example.ssafesta.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The environment namespace every Redis key is written under (infra-002 T059·T060).
 *
 * <p>dev and demo share one Redis instance on a single EC2 host
 * ({@code specs/infra-002-environments/plan.md:59}), so keys that carry no environment marker
 * collide across environments: one environment's {@code auth:refresh:{hash}} invalidates the
 * other's session, and one environment's daily grant suppresses the other's.
 *
 * <p>There is no default. A blank namespace is not "no isolation configured yet" but "isolation
 * silently gone", and the only symptom would be two environments quietly sharing sessions — so a
 * missing value fails the boot instead, the same treatment {@code jwt-secret} and
 * {@code ai-to-spring-tokens} get.
 *
 * @param namespace the environment id, e.g. {@code dev} or {@code demo}
 */
@ConfigurationProperties("app.redis")
public record RedisKeyspaceProperties(String namespace) {

    private static final char SEPARATOR = ':';

    public RedisKeyspaceProperties {
        if (namespace == null || namespace.isEmpty()) {
            throw new IllegalStateException(
                    "app.redis.namespace 가 비어 있습니다. 환경 id(dev·demo 등)를 설정해 주세요.");
        }
        // 해석되지 않은 placeholder 는 위 검사를 전부 통과한다 — "${FESTA_ENVIRONMENT}" 는 비어
        // 있지 않고, 앞뒤 공백도 없고, ':' 도 없다. 그대로 두면 dev·demo 가 그 문자열 하나를
        // 네임스페이스로 공유해 이 클래스가 막으려던 키 충돌이 그대로 돌아온다. T-101 이 저장소
        // 설정에서 낸 것과 같은 모양이라 여기서도 따로 거절하고 빠진 변수 이름을 알려 준다.
        if (namespace.startsWith("${") && namespace.endsWith("}")) {
            throw new IllegalStateException("app.redis.namespace 가 해석되지 않았습니다 — 배포에서 "
                    + namespace + " 를 주입해야 합니다.");
        }
        // Refused rather than trimmed: a trimmed value differs from what the operator wrote, and
        // the two environments would still be isolated, so nobody would ever find out the config
        // was wrong. A boot failure says it at deploy time.
        if (!namespace.equals(namespace.strip())) {
            throw new IllegalStateException(
                    "app.redis.namespace 에 앞뒤 공백이 있습니다. 공백 없이 설정해 주세요.");
        }
        // A namespace containing the separator is not isolation, it is a second namespace level:
        // "dev:auth" would put its keys exactly where the "dev" environment's auth keys live.
        if (namespace.indexOf(SEPARATOR) >= 0) {
            throw new IllegalStateException(
                    "app.redis.namespace 에 '" + SEPARATOR + "' 를 쓸 수 없습니다. 키 구분자와 겹치면 "
                            + "다른 환경의 하위 키와 충돌합니다. 현재 값: " + namespace);
        }
    }

    /**
     * The prefix to put in front of a key, separator included.
     *
     * <p>It goes at the very front — {@code dev:auth:refresh:{hash}}, not
     * {@code auth:dev:refresh:{hash}} — so one {@code SCAN dev:*} covers a whole environment.
     */
    public String prefix() {
        return namespace + SEPARATOR;
    }
}
