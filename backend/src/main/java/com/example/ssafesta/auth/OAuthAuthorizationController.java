package com.example.ssafesta.auth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Auth")
public class OAuthAuthorizationController {

    private static final Set<String> SUPPORTED_PROVIDERS = Set.of("google", "kakao");

    @Operation(summary = "소셜 로그인 시작 — 제공자 동의 화면으로 리다이렉트",
            description = """
                    소셜 로그인 흐름의 시작점이다. `302` 로 Spring Security 의 OAuth 시작 경로로 넘기고,
                    거기서 다시 제공자(Google·Kakao)의 동의 화면으로 이동한다.

                    **fetch·axios 로 호출하면 안 된다.** 브라우저가 페이지를 이동해야 하는 흐름이라 XHR 로는 완성되지 않는다.

                    ```javascript
                    window.location.href = `${API_BASE_URL}/api/v1/auth/oauth/google`;
                    ```

                    동의가 끝나면 서버가 1회용 `oauth_handoff` 쿠키(HttpOnly, 5분)를 심고 프론트의 callback 화면으로 보낸다.
                    그 화면이 `POST /api/v1/auth/oauth/complete` 를 호출해 기존 회원 로그인과 신규 가입을 가른다.

                    Swagger UI 의 "Try it out" 으로는 리다이렉트를 따라갈 수 없으니 브라우저 주소창에서 직접 열어 확인한다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "302", description = "제공자 동의 화면으로 이동. `Location` 헤더를 따라간다"),
            @ApiResponse(responseCode = "404", description = "`OAUTH_PROVIDER_NOT_SUPPORTED` — 지원하지 않는 제공자 이름이다")})
    @GetMapping("/{provider}")
    public ResponseEntity<Void> authorize(
            @Parameter(description = "소셜 제공자. 대소문자를 구분하지 않는다", example = "google",
                    schema = @Schema(allowableValues = {"google", "kakao"}))
            @PathVariable String provider) {
        String normalized = provider.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_PROVIDERS.contains(normalized)) {
            throw new ApiException(ErrorCode.OAUTH_PROVIDER_NOT_SUPPORTED);
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/oauth2/authorization/" + normalized))
                .build();
    }
}
