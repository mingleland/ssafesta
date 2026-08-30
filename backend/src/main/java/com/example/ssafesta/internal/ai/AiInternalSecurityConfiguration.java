package com.example.ssafesta.internal.ai;

import com.example.ssafesta.common.ApiErrorWriter;
import com.example.ssafesta.common.ErrorCode;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * The chain for server-to-server calls into Spring (spec 007 T020, spec 008 FR-024).
 *
 * <p>Ordered ahead of the user-facing chains and matching <b>all</b> of {@code /internal/**}: these
 * paths are authenticated by a service token, never by a user's access token, and falling through to
 * the resource-server chain would let a member's token reach an internal endpoint.
 *
 * <p><b>Infra's {@code /internal/storage/**} (spec 007 T078) extends this chain — it must not add a
 * separate one.</b> This matcher consumes every internal request first, so a later chain with a
 * lower precedence would never be reached. T078 adds its own
 * {@code INTERNAL_INFRA_TO_SPRING_TOKENS} filter and a {@code /internal/storage/**} rule here,
 * keeping the credentials and scopes separate as GitLab #102 requires.
 *
 * <p>Anything under {@code /internal/**} without a rule is denied. Fail-closed matters here because
 * a path added before its authentication would otherwise inherit whatever the last rule happened to
 * be.
 */
@Configuration
@EnableConfigurationProperties(InternalTokenProperties.class)
class AiInternalSecurityConfiguration {

    @Bean
    @Order(0)
    SecurityFilterChain internalSecurityFilterChain(HttpSecurity http, ApiErrorWriter errors,
                                                    InternalTokenProperties tokens) throws Exception {
        return http.securityMatcher("/internal/**")
                // No browser reaches these paths: no CORS, no CSRF token, no session, and no saved
                // request to replay after a login that will never happen.
                .cors(cors -> cors.disable())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .anonymous(anonymous -> anonymous.disable())
                .addFilterBefore(new InternalServiceTokenFilter(tokens),
                        UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/internal/ai/**")
                        .hasAuthority(InternalServiceTokenFilter.AUTHORITY)
                        .anyRequest().denyAll())
                // Rejections here never reach @RestControllerAdvice — the request is refused before
                // a controller is chosen — so the envelope has to be written by hand, as the
                // user-facing chain does. That method is private to another package, so the same
                // two handlers are built from ApiErrorWriter directly.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, exception) ->
                                errors.write(response, ErrorCode.UNAUTHORIZED, null))
                        .accessDeniedHandler((request, response, exception) ->
                                errors.write(response, ErrorCode.FORBIDDEN, null)))
                .build();
    }
}
