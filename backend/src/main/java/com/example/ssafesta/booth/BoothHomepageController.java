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
 * Registering the page the booth's laptop opens (spec 016 FR-001, contracts/homepage-api.md §2).
 *
 * <p>New endpoint, additive — no existing consumer changes — but still something FE has to be told
 * about (헌법 24조), and docs/08 §3·§4 is updated alongside it.
 *
 * <p>Reading goes through {@code GET /booths/{boothId}} (visitors, behind the published gate) and
 * {@code GET /booths/mine} (the owner's prefill); both live in {@link BoothQueryService}.
 */
@RestController
@RequestMapping("/api/v1/booths/{boothId}/homepage")
public class BoothHomepageController {

    private final BoothHomepageService homepages;

    public BoothHomepageController(BoothHomepageService homepages) {
        this.homepages = homepages;
    }

    @PutMapping
    @SecurityRequirement(name = "bearerAuth")
    public BoothHomepageService.HomepageView update(@AuthenticationPrincipal Jwt jwt,
                                                    @PathVariable Long boothId,
                                                    @RequestBody(required = false)
                                                    BoothHomepageService.HomepageCommand command) {
        Long userId = BoothPrincipal.requireMemberId(jwt);
        return homepages.update(boothId, userId, command);
    }
}
