package com.example.ssafesta.auth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public API entrypoint for starting an OAuth browser redirect. */
@RestController
@RequestMapping("/api/v1/auth/oauth")
public class OAuthAuthorizationController {

    private static final Set<String> SUPPORTED_PROVIDERS = Set.of("google", "kakao");

    @GetMapping("/{provider}")
    public ResponseEntity<Void> authorize(@PathVariable String provider) {
        String normalized = provider.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_PROVIDERS.contains(normalized)) {
            throw new ApiException(ErrorCode.OAUTH_PROVIDER_NOT_SUPPORTED);
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/oauth2/authorization/" + normalized))
                .build();
    }
}
