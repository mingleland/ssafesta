package com.example.ssafesta.auth;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Public API entrypoint for starting an OAuth browser redirect. */
@RestController
@RequestMapping("/api/v1/auth/oauth")
public class OAuthAuthorizationController {

    private static final Set<String> SUPPORTED_PROVIDERS = Set.of("google", "kakao");

    @GetMapping("/{provider}")
    public ResponseEntity<Void> authorize(@PathVariable String provider) {
        String normalized = provider.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_PROVIDERS.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "지원하지 않는 소셜 로그인 제공자입니다.");
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/oauth2/authorization/" + normalized))
                .build();
    }
}
