package com.example.ssafesta.auth;

import com.example.ssafesta.common.ApiErrorWriter;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.common.RequestIdFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.ExceptionHandlingConfigurer;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.List;

@Configuration
@EnableWebSecurity
class SecurityConfiguration {

    /**
     * OAuth handoff is authenticated by its one-time HttpOnly cookie, not by an access token.
     * Keep it out of the resource-server chain so an absent/stale Swagger Bearer header cannot
     * reject the handoff before the controller can report its real state.
     */
    @Bean
    @Order(1)
    SecurityFilterChain oauthCompletionSecurityFilterChain(HttpSecurity http, ApiErrorWriter errors) throws Exception {
        RequestMatcher completion = request -> "/api/v1/auth/oauth/complete".equals(
                request.getRequestURI().substring(request.getContextPath().length()));
        return http.securityMatcher(completion)
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(requests -> requests.requestMatchers(completion).permitAll())
                .exceptionHandling(handling -> apiErrors(handling, errors))
                .build();
    }

    /**
     * Rejections raised in the filter chain never reach {@code @RestControllerAdvice} — the request
     * is refused before a controller is chosen. Without this the API would answer 401 and 403 in
     * Spring's default shape while everything else used the documented envelope (docs/08 §1.3).
     */
    private void apiErrors(ExceptionHandlingConfigurer<HttpSecurity> handling, ApiErrorWriter errors) {
        handling
                .authenticationEntryPoint((request, response, exception) ->
                        errors.write(response, ErrorCode.UNAUTHORIZED, null))
                .accessDeniedHandler((request, response, exception) ->
                        errors.write(response, ErrorCode.FORBIDDEN, null));
    }

    /**
     * "/swagger-ui.html" is listed separately on purpose: it is springdoc's entry point and only
     * redirects to /swagger-ui/index.html, so the "/swagger-ui/**" pattern does not cover it and
     * the URL people actually type would be rejected with 401 before the redirect happens.
     */
    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http, OAuthLoginSuccessHandler successHandler,
                                            MemberSessionService sessions, ApiErrorWriter errors) throws Exception {
        return http.cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/v1/**"))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/api/v1/auth/guest", "/api/v1/auth/refresh", "/api/v1/auth/oauth/**", "/oauth2/**", "/login/**").permitAll()
                        // Slot browsing is open: a guest session exists to look around (헌법 12조).
                        // Leasing under /booth-slots/{id}/leases stays authenticated.
                        .requestMatchers(HttpMethod.GET, "/api/v1/booth-slots", "/api/v1/booths/*").permitAll()
                        // Unity and every visitor read the published layout on entering a booth
                        // (spec 005 FR-006). The draft and publish paths under the same prefix stay
                        // authenticated — only this exact suffix is open.
                        .requestMatchers(HttpMethod.GET, "/api/v1/booths/*/layouts/published").permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(oauth -> oauth.successHandler(successHandler))
                // The resource server installs its own entry point for bearer-token failures, so an
                // expired or malformed token would bypass the one set below and answer with an empty
                // body. It has to be overridden here as well.
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((request, response, exception) ->
                                errors.write(response, ErrorCode.UNAUTHORIZED, null))
                        .accessDeniedHandler((request, response, exception) ->
                                errors.write(response, ErrorCode.FORBIDDEN, null)))
                .exceptionHandling(handling -> apiErrors(handling, errors))
                .addFilterAfter(new SessionRevocationFilter(sessions), BearerTokenAuthenticationFilter.class)
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AuthProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(properties.frontendBaseUrl()));
        cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cors.setExposedHeaders(List.of("Set-Cookie", RequestIdFilter.HEADER));
        cors.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
