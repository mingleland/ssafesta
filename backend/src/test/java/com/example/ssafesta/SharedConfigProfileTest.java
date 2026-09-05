package com.example.ssafesta;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import com.example.ssafesta.storage.ObjectStorageProperties;
import com.example.ssafesta.common.RedisCredentialCheck;
import com.example.ssafesta.common.RedisKeyspaceProperties;

/**
 * The {@code infra} profile has to resolve every setting the application needs to boot.
 *
 * <p>Spring does not accumulate {@code application-{profile}.yml} across profiles. When the shared
 * settings lived only in {@code application-local.yml}, {@code SPRING_PROFILES_ACTIVE=infra} dropped
 * the datasource and the application could not start no matter how many environment variables the
 * deployment injected (S15P21A604-347, GitLab #117). These two tests fail if that regresses.
 *
 * <p>No container is needed — this loads the config files and asks the environment what it resolved.
 */
class SharedConfigProfileTest {

    /** Everything the deployment is expected to inject, minus the one each test leaves out. */
    private static final String[] DEPLOY_ENV = {
        "spring.profiles.active=infra",
        "POSTGRES_HOST=db.internal",
        "POSTGRES_PORT=5432",
        "POSTGRES_DB=festa",
        "POSTGRES_USER=festa",
        "POSTGRES_PASSWORD=injected-secret",
        "REDIS_HOST=redis.internal",
        "REDIS_PORT=6379",
        // Redis ACL 자격증명 (S15P21A604-422). infra-002 T015 가 default 사용자를 끄므로
        // 배포는 반드시 주입한다 — infra 프로파일에 기본값이 없다.
        "REDIS_USERNAME=dev_back",
        "REDIS_PASSWORD=injected-redis-secret",
        "JWT_SECRET=aW5qZWN0ZWQtand0LXNlY3JldA==",
        "CONNECTION_TOKEN_SECRET=injected-connection-secret",
        "INTERNAL_AI_TO_SPRING_TOKENS=injected-ai-token",
        "GOOGLE_CLIENT_ID=google-id",
        "GOOGLE_CLIENT_SECRET=google-secret",
        "GOOGLE_REDIRECT_URI=https://api.example.test/login/oauth2/code/google",
        "KAKAO_REST_API_KEY=kakao-key",
        "KAKAO_CLIENT_SECRET=kakao-secret",
        "KAKAO_REDIRECT_URI=https://api.example.test/login/oauth2/code/kakao",
        "ROOT_DOMAIN=example.test",
        "FRONTEND_BASE_URL=https://example.test",
        "AUTH_COOKIE_SECURE=true",
        // Redis 키 네임스페이스 (S15P21A604-349). 기본값이 없어 배포가 반드시 주입한다.
        "FESTA_ENVIRONMENT=dev",
        // 문서 저장소 (S15P21A604-106, GitLab #100). MinIO 4종은 일부러 빼 둔다 — R2 만 쓰는
        // 배포가 fallback 자격증명 없이 뜨는 것이 계약이고, 아래 R2-only 테스트가 그것을 고정한다.
        "AI_STORAGE_UPLOAD_GATE=OPEN",
        "AI_STORAGE_ACTIVE_WRITE_PROVIDER=R2",
        "R2_ENDPOINT=https://account.r2.cloudflarestorage.test",
        "R2_BUCKET=festa-documents",
        "R2_ACCESS_KEY_ID=r2-access-key",
        "R2_SECRET_ACCESS_KEY=r2-secret-key",
    };

    private ApplicationContextRunner runner(String... propertyValues) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(propertyValues);
    }

    @Test
    @DisplayName("infra 프로파일이 기동에 필요한 설정을 모두 해석한다")
    void infraProfileResolvesEverySettingNeededToBoot() {
        runner(DEPLOY_ENV).run(context -> {
            Environment env = context.getEnvironment();

            // The datasource is the one that used to disappear with the local profile.
            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://db.internal:5432/festa");
            assertThat(env.getProperty("spring.datasource.password")).isEqualTo("injected-secret");

            assertThat(env.getProperty("spring.data.redis.host")).isEqualTo("redis.internal");
            assertThat(env.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration");
            assertThat(env.getProperty("spring.security.oauth2.client.registration.google.client-id"))
                    .isEqualTo("google-id");

            assertThat(env.getProperty("app.auth.jwt-secret")).isEqualTo("aW5qZWN0ZWQtand0LXNlY3JldA==");
            assertThat(env.getProperty("app.internal.ai-to-spring-tokens")).isEqualTo("injected-ai-token");
            assertThat(env.getProperty("app.agent.per-booth-limit")).isEqualTo("1");

            // The deployment values win over the local defaults left in application.yml.
            assertThat(env.getProperty("app.auth.frontend-base-url")).isEqualTo("https://example.test");
            assertThat(env.getProperty("app.auth.cookie-secure")).isEqualTo("true");

            // application-infra.yml owns the world address and overrides the ws/127.0.0.1 defaults.
            assertThat(env.getProperty("app.world.scheme")).isEqualTo("wss");
            assertThat(env.getProperty("app.world.host")).isEqualTo("world.example.test");
        });
    }

    @Test
    @DisplayName("배포에서 AUTH_COOKIE_SECURE 를 빠뜨리면 로컬 기본값으로 조용히 뜨지 않는다")
    void missingCookieSecureDoesNotFallBackToTheLocalDefault() {
        String[] withoutCookieSecure = java.util.Arrays.stream(DEPLOY_ENV)
                .filter(value -> !value.startsWith("AUTH_COOKIE_SECURE="))
                .toArray(String[]::new);

        runner(withoutCookieSecure).run(context -> {
            // Either the resolver refuses the unresolvable placeholder or it hands the raw
            // placeholder back. Both are loud. What must never happen is a quiet "false", which is
            // what application-local.yml supplies and what shipped Refresh cookies without Secure.
            String resolved;
            try {
                resolved = context.getEnvironment().getProperty("app.auth.cookie-secure");
            } catch (IllegalArgumentException refusedUnresolvable) {
                return;
            }
            assertThat(resolved).isNotEqualTo("false");
            assertThat(resolved).contains("${");
        });
    }

    // ── 문서 저장소 설정이 배포 계층에서 실제로 묶이는가 (S15P21A604-106) ──────────────
    //
    // 위 두 테스트는 Environment 만 본다. 그것만으로는 "기동 실패" 를 검증할 수 없다 — 해석되지
    // 않은 placeholder 는 값을 읽을 때만 시끄럽고 컨텍스트는 멀쩡히 뜬다. 바인딩을 실제로 태워야
    // 한다. T-101 이 정확히 이 층에서 났다.

    @EnableConfigurationProperties(ObjectStorageProperties.class)
    static class StorageBinding {
    }

    private ApplicationContextRunner storageRunner(String... propertyValues) {
        return runner(propertyValues).withUserConfiguration(StorageBinding.class);
    }

    private static String[] withoutEnv(String prefix) {
        return java.util.Arrays.stream(DEPLOY_ENV)
                .filter(value -> !value.startsWith(prefix + "="))
                .toArray(String[]::new);
    }

    /**
     * R2 만 주입한 배포가 뜬다.
     *
     * <p>{@code application.yml} 이 MinIO 를 목록에 적어 두는 것은 fallback 을 설정 변경으로 하기
     * 위해서다. 네 값이 전부 비면 미구성으로 보고 목록에서 빠져야 한다 — 그러지 않으면 R2 전용
     * 배포가 있지도 않은 자격증명을 요구받는다.
     */
    @Test
    @DisplayName("infra 프로파일이 R2 만으로 문서 저장소를 묶는다")
    void infraProfileBindsTheDocumentStorageWithR2Alone() {
        storageRunner(DEPLOY_ENV).run(context -> {
            assertThat(context).hasNotFailed();

            ObjectStorageProperties storage = context.getBean(ObjectStorageProperties.class);
            assertThat(storage.uploadGate()).isEqualTo(ObjectStorageProperties.UploadGate.OPEN);
            assertThat(storage.activeWriteProvider()).isEqualTo("R2");
            assertThat(storage.providers()).containsOnlyKeys("R2");
            assertThat(storage.providers().get("R2").bucket()).isEqualTo("festa-documents");
        });
    }

    /**
     * 게이트를 빠뜨리면 <b>기동이 실패한다</b>.
     *
     * <p>기본값이 "허용" 이었다면 배포에서 키를 빠뜨렸을 때 차단이 열린 채로 떴을 것이다. 안전
     * 장치는 오타로 열려서는 안 된다.
     */
    @Test
    @DisplayName("배포에서 AI_STORAGE_UPLOAD_GATE 를 빠뜨리면 기동이 실패한다")
    void missingUploadGateFailsToStart() {
        storageRunner(withoutEnv("AI_STORAGE_UPLOAD_GATE")).run(context -> {
            assertThat(context).hasFailed();
            // 해석되지 않은 placeholder 가 enum 변환에서 죽는다 — 메시지가 빠진 변수를 그대로 든다.
            assertThat(rootCauseOf(context.getStartupFailure())).contains("AI_STORAGE_UPLOAD_GATE");
        });
    }

    /**
     * 쓰기 provider 와 R2 자격증명도 같은 취급이다 — 하나라도 빠지면 뜨지 않는다.
     *
     * <p>이 테스트가 처음 잡은 것이 그 반대였다. 해석되지 않은 {@code ${R2_ENDPOINT}} 는 <b>비어
     * 있지 않은 문자열</b>이라 완전성 검사를 그냥 통과했고, 서버는 서명 키가 그 문자열인 채로
     * 기동했다. 모든 업로드가 저장소에서 서명 오류로 죽는데 원인은 오타 하나다 — 오설정이
     * 장애처럼 보이는 모양이라 {@code ObjectStorageProperties} 가 placeholder 를 따로 거절한다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"AI_STORAGE_ACTIVE_WRITE_PROVIDER", "R2_ENDPOINT", "R2_BUCKET",
            "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY"})
    void everyRequiredStorageVariableFailsToStartWhenMissing(String variable) {
        storageRunner(withoutEnv(variable)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCauseOf(context.getStartupFailure())).contains(variable);
        });
    }

    // ── Redis 키 네임스페이스 (S15P21A604-349) ────────────────────────────────────────

    @EnableConfigurationProperties(RedisKeyspaceProperties.class)
    static class KeyspaceBinding {
    }

    private ApplicationContextRunner keyspaceRunner(String... propertyValues) {
        return runner(propertyValues).withUserConfiguration(KeyspaceBinding.class);
    }

    @Test
    @DisplayName("infra 프로파일이 FESTA_ENVIRONMENT 를 Redis 네임스페이스로 묶는다")
    void infraProfileBindsTheRedisKeyspaceFromTheEnvironment() {
        keyspaceRunner(DEPLOY_ENV).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RedisKeyspaceProperties.class).prefix()).isEqualTo("dev:");
        });
    }

    /**
     * 환경 id 를 빠뜨리면 <b>기동이 실패한다</b>.
     *
     * <p>이 자리가 조용히 뚫려 있었다. 해석되지 않은 {@code ${FESTA_ENVIRONMENT}} 는 비어 있지
     * 않고 앞뒤 공백도 없고 {@code ':'} 도 없어서 {@code RedisKeyspaceProperties} 의 검사를 전부
     * 통과했다. 그러면 dev·demo 가 그 문자열 하나를 네임스페이스로 공유하고, 티켓이 막으려던
     * 세션·일일 지급 충돌이 격리된 척하면서 그대로 돌아온다 — T-101 과 같은 모양이다.
     */
    @Test
    @DisplayName("배포에서 FESTA_ENVIRONMENT 를 빠뜨리면 기동이 실패한다")
    void missingEnvironmentNamespaceFailsToStart() {
        keyspaceRunner(withoutEnv("FESTA_ENVIRONMENT")).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCauseOf(context.getStartupFailure())).contains("FESTA_ENVIRONMENT");
        });
    }

    // ── Redis ACL 자격증명 (S15P21A604-422) ───────────────────────────────────────

    @EnableConfigurationProperties(DataRedisProperties.class)
    @Import(RedisCredentialCheck.class)
    static class RedisCredentialBinding {
    }

    private ApplicationContextRunner redisRunner(String... propertyValues) {
        return runner(propertyValues).withUserConfiguration(RedisCredentialBinding.class);
    }

    @Test
    @DisplayName("infra 프로파일이 Redis ACL 자격증명을 묶는다")
    void infraProfileBindsTheRedisAclCredentials() {
        redisRunner(DEPLOY_ENV).run(context -> {
            assertThat(context).hasNotFailed();

            DataRedisProperties redis = context.getBean(DataRedisProperties.class);
            assertThat(redis.getUsername()).isEqualTo("dev_back");
            assertThat(redis.getPassword()).isEqualTo("injected-redis-secret");
        });
    }

    /**
     * 자격증명을 빠뜨리면 <b>기동이 실패한다</b>.
     *
     * <p>기본값을 지운 것만으로는 이 자리가 닫히지 않는다. 바인더는 해석되지 않은
     * {@code ${REDIS_PASSWORD}} 를 "값이 있다" 로 통과시키고, Lettuce 는 지연 연결이라 서버가
     * 그대로 뜬다. 실패는 첫 Redis 사용에서 {@code WRONGPASS} 로 나는데 health 본문에는 원인이
     * 없다({@code show-details} 기본값이 {@code never}) — 뜬 서버에서 로그인만 안 되는 모양이다.
     * {@code RedisCredentialCheck} 가 그것을 기동 시점으로 앞당긴다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"REDIS_USERNAME", "REDIS_PASSWORD"})
    void missingRedisAclCredentialFailsToStart(String variable) {
        redisRunner(withoutEnv(variable)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCauseOf(context.getStartupFailure())).contains(variable);
        });
    }

    private static String rootCauseOf(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }
}
