package com.example.ssafesta.booth;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Editing the booth exterior (spec 005 FR-018, contracts/layout-api.md §6).
 *
 * <p>Not in docs/08 — this endpoint is new. An addition rather than a breaking change, but still
 * something FE and Unity have to be told about (헌법 24조), and docs/08 is updated alongside it.
 *
 * <p>Reading the facade goes through {@code GET /booths/{boothId}}, which spec 004 already owns.
 */
@RestController
@RequestMapping("/api/v1/booths/{boothId}/facade")
public class BoothFacadeController {

    private final BoothFacadeService facades;

    public BoothFacadeController(BoothFacadeService facades) {
        this.facades = facades;
    }

    @PutMapping
    @SecurityRequirement(name = "bearerAuth")
    public BoothFacadeService.FacadeView update(@AuthenticationPrincipal Jwt jwt,
                                                @PathVariable Long boothId,
                                                @RequestBody BoothFacadeService.FacadeCommand command) {
        Long userId = BoothPrincipal.requireMemberId(jwt);
        return facades.update(boothId, userId, command);
    }
}
