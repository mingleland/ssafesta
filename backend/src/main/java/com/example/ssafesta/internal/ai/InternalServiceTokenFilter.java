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
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Grants one authority to a request that carries a valid service token <b>on that authority's own
 * paths</b>.
 *
 * <p>Deliberately <b>not</b> a {@code @Component}: a filter bean is picked up by Boot's servlet
 * registration as well, which would run it on every {@code /api/**} request too. Instances are
 * constructed inside {@link AiInternalSecurityConfiguration} and installed only on that chain.
 *
 * <p>It also does not write errors. A request without a valid token simply stays anonymous, and the
 * chain's entry point answers 401 in the documented envelope — the same shape every other rejection
 * uses (docs/08 §1.3).
 *
 * <p>Token verification runs regardless of network configuration (GitLab #102). A Security Group
 * rule is one line away from being changed, and in that moment the token is the only defence left.
 *
 * <h2>Why the scope is a {@code shouldNotFilter} and not an {@code if} inside the body</h2>
 *
 * <p>Two instances of this class live on one chain — AI's and Infra's (S15P21A604-500) — and
 * {@code OncePerRequestFilter} names its "already filtered" request attribute after
 * {@code getFilterName()}, which falls back to the <b>class name</b> for a filter that is not a
 * bean. Both instances therefore share one attribute name. The attribute is set before
 * {@code doFilterInternal} and removed only in its {@code finally}, so it is still present while
 * the first instance calls down the chain: an out-of-scope instance that entered the body would
 * make the in-scope one <b>skip its body entirely</b> and a valid token would answer 401.
 * {@code shouldNotFilter} is checked before the attribute is set, so an out-of-scope instance
 * leaves no marker. Spring Security's own {@code AuthenticationFilter} overrides the attribute name
 * for the same reason.
 *
 * <p><b>The scopes must not overlap.</b> That is what keeps exactly one instance in scope per
 * request, and it is why nothing here merges authorities.
 */
public class InternalServiceTokenFilter extends OncePerRequestFilter {

    /** The authority for {@code /internal/ai/**} — FastAPI's direction. */
    public static final String AI_AUTHORITY = "INTERNAL_AI";

    /** The authority for {@code /internal/storage/**} — Infra's reconcile direction. */
    public static final String INFRA_AUTHORITY = "INTERNAL_INFRA";

    private static final String PREFIX = "Bearer ";

    private final RequestMatcher scope;
    private final List<byte[]> accepted;
    private final String authority;

    InternalServiceTokenFilter(RequestMatcher scope, List<String> accepted, String authority) {
        this.scope = scope;
        this.accepted = accepted.stream()
                .map(token -> token.getBytes(StandardCharsets.UTF_8))
                .toList();
        this.authority = authority;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !scope.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Never overwrite a decision another filter already made. The scopes do not overlap, so
        // this cannot happen today; leaving the guard out would make an overlap added later fail
        // as a silent authority swap instead of a visible one.
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String presented = bearerOf(request);
            if (presented != null && matches(presented)) {
                UsernamePasswordAuthenticationToken authentication =
                        UsernamePasswordAuthenticationToken.authenticated(authority, null,
                                List.of(new SimpleGrantedAuthority(authority)));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
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
