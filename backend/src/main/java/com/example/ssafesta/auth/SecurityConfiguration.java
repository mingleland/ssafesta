package com.example.ssafesta.auth;

import com.example.ssafesta.common.ApiErrorWriter;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.common.RequestIdFilter;
import jakarta.servlet.DispatcherType;
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
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/v1/**", "/ws/**"))
                .authorizeHttpRequests(requests -> requests
                        // The container's ERROR dispatch, not a route anyone calls. Refusing it
                        // replaced every failure that lands there with a misleading 401, and let it
                        // through only for a request that happened to carry a session — which is
                        // how one unregistered OAuth provider read as Whitelabel HTML for a browser
                        // and as UNAUTHORIZED for curl. ApiErrorController answers it in the
                        // envelope and exposes nothing the original response did not already say.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/api/v1/auth/guest", "/api/v1/auth/refresh", "/api/v1/auth/oauth/**", "/oauth2/**", "/login/**").permitAll()
                        // 상담 STOMP 핸드셰이크는 HTTP 인증을 타지 않는다 — 신원은 CONNECT 프레임의
                        // WS Token 으로 확인하고(FR-019, StompAuthChannelInterceptor), 그 검증에
                        // 실패한 연결은 거부된다. 여기서 막으면 핸드셰이크 단계에서 토큰을 URL 로
                        // 넘겨야 하는데 그것이 바로 FR-019 가 금지하는 것이다.
                        // /ws 는 범용 엔드포인트이고 /ws/consultation 은 이미 통보한 경로라 함께 연다.
                        // 등록하지 않으면 STOMP CONNECT 검증 전에 핸드셰이크가 401 로 끊긴다.
                        .requestMatchers("/ws", "/ws/**").permitAll()
                        // Slot browsing is open: a guest session exists to look around (헌법 12조).
                        // Leasing under /booth-slots/{id}/leases stays authenticated.
                        .requestMatchers(HttpMethod.GET, "/api/v1/booth-slots", "/api/v1/booths/*").permitAll()
                        // Unity and every visitor read the published layout on entering a booth
                        // (spec 005 FR-006). The draft and publish paths under the same prefix stay
                        // authenticated — only this exact suffix is open.
                        .requestMatchers(HttpMethod.GET, "/api/v1/booths/*/layouts/published").permitAll()
                        // The same layout keyed by room instead of by booth — Unity's anchors are
                        // slots (#62, contract §11). Open for the same reason as the line above.
                        .requestMatchers(HttpMethod.GET, "/api/v1/booth-slots/*/layouts/published").permitAll()
                        // A published game is playable by guests (spec 019 FR-023). Only this exact
                        // suffix is open — the draft, publish and lifecycle paths under the same
                        // prefix stay authenticated, and Authoring refuses guests separately with
                        // MEMBER_ONLY rather than with a 401.
                        .requestMatchers(HttpMethod.GET, "/api/v1/games/*/published").permitAll()
                        // Arcade machines are world fixtures a guest walks up to, and playing a
                        // published game is a guest's to do (FR-023) — the same reason the line
                        // above is open. Resolution reads only which game is bound and whether it
                        // is public; there is no owner or lease in this path to judge
                        // (S15P21A604-602).
                        .requestMatchers(HttpMethod.GET, "/api/v1/arcade-machines",
                                "/api/v1/arcade-machines/*").permitAll()
                        // A published game's assets are read without a session: the play page
                        // resolves asset:// for a guest, and the filter runs before the service can
                        // decide anything (GitLab #69, 2026-08-28 — "필터에서는 경로를 열고 판정은
                        // 서비스에서 optional authentication으로"). Opening the path is not making
                        // it public — GameAssetService still requires the game to be PUBLIC and the
                        // asset to be referenced by the published revision, so an unpublished
                        // draft's image is refused with 403 rather than served.
                        .requestMatchers(HttpMethod.GET, "/api/v1/games/*/assets/*/content").permitAll()
                        // A published booth's project exhibition is what the visitor came to read
                        // (spec 009 FR-005, 계약 §6). Only this exact suffix is open — the editor
                        // read at /booths/*/projects stays authenticated, and "*" spans one segment
                        // so it cannot reach it.
                        .requestMatchers(HttpMethod.GET, "/api/v1/booths/*/projects/published").permitAll()
                        // 방문자의 <img> 가 들어오는 자리다 (GitLab #241). 게임 Asset 과 같은 구조로
                        // 경로만 열고 판정은 서비스가 한다 — 게시된 프로젝트가 그 로고를 참조하고
                        // 임대가 유효할 때만 누구나 볼 수 있고, 그 밖의 로고는 부스 편집자만이며
                        // 나머지는 404 다. "*" 는 한 세그먼트라 다른 업로드 경로에 닿지 않는다.
                        .requestMatchers(HttpMethod.GET, "/api/v1/booths/*/project-logos/*/content").permitAll()
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
                .addFilterAfter(new SessionRevocationFilter(sessions, errors), BearerTokenAuthenticationFilter.class)
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AuthProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(properties.frontendBaseUrl()));
        cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        cors.setExposedHeaders(List.of("Set-Cookie", RequestIdFilter.HEADER));
        cors.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
