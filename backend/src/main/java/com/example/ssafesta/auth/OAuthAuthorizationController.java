package com.example.ssafesta.auth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.user.OAuthProvider;
import java.net.URI;
import java.util.Locale;
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

    /**
     * The supported set is {@link OAuthProvider} itself.
     *
     * <p>A second list here would let the two drift: a provider added to the enum and to
     * {@code application.yml} would still answer 404 on this route, and the failure would look like
     * a missing registration rather than a stale copy of the list.
     */
    @GetMapping("/{provider}")
    public ResponseEntity<Void> authorize(@PathVariable String provider) {
        String normalized = provider.toLowerCase(Locale.ROOT);
        try {
            OAuthProvider.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unsupported) {
            throw new ApiException(ErrorCode.OAUTH_PROVIDER_NOT_SUPPORTED);
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/oauth2/authorization/" + normalized))
                .build();
    }
}
