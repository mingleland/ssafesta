package com.example.ssafesta.internal.ai;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Grants {@link #AUTHORITY} to a request carrying a valid FastAPI→Spring service token.
 *
 * <p>Deliberately <b>not</b> a {@code @Component}: a filter bean is picked up by Boot's servlet
 * registration as well, which would run it on every {@code /api/**} request too. It is constructed
 * inside {@link AiInternalSecurityConfiguration} and installed only on that chain.
 *
 * <p>It also does not write errors. A request without a valid token simply stays anonymous, and the
 * chain's entry point answers 401 in the documented envelope — the same shape every other rejection
 * uses (docs/08 §1.3).
 *
 * <p>Token verification runs regardless of network configuration (GitLab #102). A Security Group
 * rule is one line away from being changed, and in that moment the token is the only defence left.
 */
public class InternalServiceTokenFilter extends OncePerRequestFilter {

    public static final String AUTHORITY = "INTERNAL_AI";

    private static final String PREFIX = "Bearer ";

    private final List<byte[]> accepted;

    InternalServiceTokenFilter(InternalTokenProperties properties) {
        this.accepted = properties.aiToSpringTokenList().stream()
                .map(token -> token.getBytes(StandardCharsets.UTF_8))
                .toList();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String presented = bearerOf(request);
        if (presented != null && matches(presented)) {
            UsernamePasswordAuthenticationToken authentication =
                    UsernamePasswordAuthenticationToken.authenticated(AUTHORITY, null,
                            List.of(new SimpleGrantedAuthority(AUTHORITY)));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        chain.doFilter(request, response);
    }

    /**
     * Compares against <b>every</b> accepted token with no early return.
     *
     * <p>{@code MessageDigest.isEqual} is constant time for a single pair, but stopping at the first
     * match would make the loop's duration depend on which position matched — that leaks which token
     * of a rotating pair the caller holds. The accumulator keeps the work constant.
     */
    private boolean matches(String presented) {
        byte[] bytes = presented.getBytes(StandardCharsets.UTF_8);
        boolean matched = false;
        for (byte[] candidate : accepted) {
            matched |= MessageDigest.isEqual(candidate, bytes);
        }
        return matched;
    }

    private static String bearerOf(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && header.startsWith(PREFIX) ? header.substring(PREFIX.length()) : null;
    }
}
