package com.example.ssafesta.booth;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Booth reads (spec 004 contracts/lease-api.md). */
@RestController
@RequestMapping("/api/v1/booths")
public class BoothController {

    private final BoothQueryService queries;

    public BoothController(BoothQueryService queries) {
        this.queries = queries;
    }

    /** My booth and its lease. 204 when the member has never leased. */
    @GetMapping("/mine")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<BoothQueryService.MyBoothView> mine(@AuthenticationPrincipal Jwt jwt) {
        Long userId = BoothPrincipal.requireMemberId(jwt);
        return queries.findMyBooth(userId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * A visitor's view. An expired booth is refused with its reason rather than returned as an
     * empty shell — expiry is not pushed to the world, so this is where the visitor finds out
     * (spec 004 FR-019).
     */
    @GetMapping("/{boothId}")
    public BoothQueryService.PublicBoothView booth(@PathVariable Long boothId) {
        // No translation needed: both failures carry their own ErrorCode now.
        return queries.findPublicBooth(boothId);
    }
}
