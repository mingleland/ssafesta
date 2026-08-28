package com.example.ssafesta.common;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A {@code @Valid} endpoint that exists only under test.
 *
 * <p>No production controller uses Bean Validation yet, so the {@code FIELD_INVALID} contract
 * (docs/08 §1.3-1) would otherwise be asserted against a code path nothing exercises — and the
 * first DTO to grow a {@code @NotBlank} would be the one to discover it was wrong. This gives
 * {@link GlobalExceptionHandler}'s Bean Validation branch a real request to answer, through the
 * real filter chain, security chain and {@code ObjectMapper}.
 *
 * <p>It lives in test sources and is picked up by the application's own component scan, which keeps
 * every integration test on the one shared context — a nested {@code @TestConfiguration} would fork
 * it and start a second Postgres and Redis for this one route.
 */
@RestController
class BeanValidationProbeController {

    static final String PATH = "/api/v1/test-only/bean-validation-probe";

    @PostMapping(PATH)
    String probe(@Valid @RequestBody Probe body) {
        return body.nickname();
    }

    /**
     * Two fields, both with Korean messages — the constraint has to carry its own text or the
     * client gets the framework's English ("must not be blank").
     */
    record Probe(@NotBlank(message = "닉네임을 입력해 주세요.") String nickname,
                 @Size(max = 8, message = "메모는 8자까지입니다.") String memo) { }
}
