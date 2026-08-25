package com.example.ssafesta.common;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Writes {@link ApiErrorResponse} straight to the servlet response.
 *
 * <p>Needed because <b>errors raised inside the Spring Security filter chain never reach
 * {@code @RestControllerAdvice}</b> — the request is rejected before any controller is selected.
 * Without this the API would answer 401 and 403 in one shape and everything else in another, which
 * is exactly the split spec 005 set out to remove (research R-09).
 */
@Component
public class ApiErrorWriter {

    private final ObjectMapper objectMapper;

    public ApiErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(),
                ApiErrorResponse.of(code, message == null ? code.defaultMessage() : message,
                        RequestIdFilter.current()));
    }
}
