package com.example.ssafesta.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an id that appears in three places at once: the {@code X-Request-Id} response
 * header, the {@code requestId} field of an error body, and the server log line via MDC.
 *
 * <p>Without this the {@code requestId} in {@link ApiErrorResponse} would be a field with nothing
 * to put in it. With it, a user quoting the id from a failed request is enough to find the log.
 *
 * <p>Runs at the highest precedence so the id exists before Spring Security can reject the
 * request — a 401 from the filter chain needs an id just as much as a 409 from a controller.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    static final String MDC_KEY = "requestId";
    private static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";

    /** The current request's id, or {@code null} outside a request (scheduled jobs, tests). */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /**
     * Run on the ERROR dispatch too — {@code OncePerRequestFilter} skips it by default.
     *
     * <p>Skipping it left the MDC empty for everything {@code ApiErrorController} answers, so those
     * envelopes carried {@code requestId: null} and could not be tied back to the log line of the
     * request that failed. The id is carried on the request rather than regenerated, so the header
     * already sent and the body written here name the same request.
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = (String) request.getAttribute(ATTRIBUTE);
        if (requestId == null) {
            requestId = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            request.setAttribute(ATTRIBUTE, requestId);
            response.setHeader(HEADER, requestId);
        }
        MDC.put(MDC_KEY, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Threads are pooled — a leftover id would attach itself to somebody else's request.
            MDC.remove(MDC_KEY);
        }
    }
}
