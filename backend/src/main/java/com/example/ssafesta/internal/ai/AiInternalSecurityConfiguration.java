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
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The chain for server-to-server calls into Spring (spec 007 T020·T078, spec 008 FR-024).
 *
 * <p>Ordered ahead of the user-facing chains and matching <b>all</b> of {@code /internal/**}: these
 * paths are authenticated by a service token, never by a user's access token, and falling through to
 * the resource-server chain would let a member's token reach an internal endpoint.
 *
 * <p><b>There is one chain, and callers extend it rather than adding their own.</b> This matcher
 * consumes every internal request first, so a later chain with a lower precedence would never be
 * reached. Infra's {@code /internal/storage/**} (T078, S15P21A604-500) followed that rule: it added
 * a second filter with its own {@code INTERNAL_INFRA_TO_SPRING_TOKENS} and a second rule here,
 * keeping the credentials and scopes separate as GitLab #102 requires.
 *
 * <p>The two filters are <b>scoped to disjoint path prefixes</b>, which is what makes a token open
 * only its own direction: presenting the AI token on {@code /internal/storage/**} authenticates
 * nothing at all and answers 401, the status the storage contract documents for a reused AI-side
 * token. Scoping also has to stay in {@code shouldNotFilter} — see
 * {@link InternalServiceTokenFilter} for why two instances of one {@code OncePerRequestFilter}
 * class cannot decide their scope inside the body.
 *
 * <p>Anything under {@code /internal/**} without a rule is denied. Fail-closed matters here because
 * a path added before its authentication would otherwise inherit whatever the last rule happened to
 * be.
 *
 * <p>The two refusals report different statuses, and that follows from how each is decided rather
 * than from a choice made here. A rule that names an authority asks for the {@code Authentication},
 * so an unauthenticated request raises {@code AuthenticationCredentialsNotFoundException} and the
 * entry point answers <b>401</b>. {@code denyAll} never asks, so it ends in the access-denied
 * handler and answers <b>403</b> whether or not a token was presented.
 */
@Configuration
@EnableConfigurationProperties(InternalTokenProperties.class)
class AiInternalSecurityConfiguration {

    private static final String AI_PREFIX = "/internal/ai";
    private static final String STORAGE_PREFIX = "/internal/storage";

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
                .addFilterBefore(new InternalServiceTokenFilter(under(AI_PREFIX),
                                tokens.aiToSpringTokenList(), InternalServiceTokenFilter.AI_AUTHORITY),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new InternalServiceTokenFilter(under(STORAGE_PREFIX),
                                tokens.infraToSpringTokenList(), InternalServiceTokenFilter.INFRA_AUTHORITY),
                        UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(AI_PREFIX + "/**")
                        .hasAuthority(InternalServiceTokenFilter.AI_AUTHORITY)
                        .requestMatchers(STORAGE_PREFIX + "/**")
                        .hasAuthority(InternalServiceTokenFilter.INFRA_AUTHORITY)
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

    /**
     * The same set of paths the {@code prefix + "/**"} authorization rule covers, as a matcher a
     * filter can hold.
     *
     * <p>A lambda over {@code getRequestURI}, as {@code auth/SecurityConfiguration} already does.
     * The bare prefix is included because {@code /**} matches zero segments too — leaving it out
     * would put {@code /internal/ai} under a rule whose filter never runs.
     */
    private static RequestMatcher under(String prefix) {
        return request -> {
            String path = request.getRequestURI();
            return path.equals(prefix) || path.startsWith(prefix + "/");
        };
    }
}
