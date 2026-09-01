package com.example.ssafesta;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

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
        "JWT_SECRET=aW5qZWN0ZWQtand0LXNlY3JldA==",
        "CONNECTION_TOKEN_SECRET=injected-connection-secret",
        "INTERNAL_AI_TO_SPRING_TOKENS=injected-ai-token",
        "GOOGLE_CLIENT_ID=google-id",
        "GOOGLE_CLIENT_SECRET=google-secret",
        "GOOGLE_REDIRECT_URI=https://api.example.test/login/oauth2/code/google",
        "KAKAO_REST_API_KEY=kakao-key",
        "KAKAO_CLIENT_SECRET=kakao-secret",
        "KAKAO_REDIRECT_URI=https://api.example.test/login/oauth2/code/kakao",
        "SSAFY_CLIENT_ID=ssafy-id",
        "SSAFY_CLIENT_SECRET=ssafy-secret",
        "SSAFY_REDIRECT_URI=https://api.example.test/login/oauth2/code/ssafy",
        "ROOT_DOMAIN=example.test",
        "FRONTEND_BASE_URL=https://example.test",
        "AUTH_COOKIE_SECURE=true",
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

            // SSAFY is the only provider whose endpoints live in our own config rather than in
            // Spring's built-ins, so a typo here would surface as a redirect_uri_mismatch or a 404
            // from project.ssafy.com during a real login, not at boot. Pin the whole registration.
            String ssafy = "spring.security.oauth2.client.";
            assertThat(env.getProperty(ssafy + "registration.ssafy.client-id")).isEqualTo("ssafy-id");
            assertThat(env.getProperty(ssafy + "registration.ssafy.client-authentication-method"))
                    .isEqualTo("client_secret_post");
            assertThat(env.getProperty(ssafy + "registration.ssafy.scope")).isNull();
            assertThat(env.getProperty(ssafy + "provider.ssafy.authorization-uri"))
                    .isEqualTo("https://project.ssafy.com/oauth/sso-check");
            assertThat(env.getProperty(ssafy + "provider.ssafy.token-uri"))
                    .isEqualTo("https://project.ssafy.com/ssafy/oauth2/token");
            assertThat(env.getProperty(ssafy + "provider.ssafy.user-info-uri"))
                    .isEqualTo("https://project.ssafy.com/ssafy/resources/userInfo");
            // getName() reads this attribute, and OAuthLoginSuccessHandler stores it as the
            // provider subject. Wrong value here means every login registers a new account.
            assertThat(env.getProperty(ssafy + "provider.ssafy.user-name-attribute")).isEqualTo("userId");

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
}
