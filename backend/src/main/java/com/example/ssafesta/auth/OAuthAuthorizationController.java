package com.example.ssafesta.auth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.user.OAuthProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public API entrypoint for starting an OAuth browser redirect. */
@RestController
@RequestMapping("/api/v1/auth/oauth")
@Tag(name = "Auth")
public class OAuthAuthorizationController {

    private final AuthProperties properties;

    public OAuthAuthorizationController(AuthProperties properties) {
        this.properties = properties;
    }

    /**
     * The supported set is {@link OAuthProvider} itself.
     *
     * <p>A second list here would let the two drift: a provider added to the enum and to
     * {@code application.yml} would still answer 404 on this route, and the failure would look like
     * a missing registration rather than a stale copy of the list.
     */
    @Operation(summary = "소셜 로그인 시작 — 제공자 동의 화면으로 리다이렉트",
            description = """
                    소셜 로그인 흐름의 시작점이다. `302` 로 Spring Security 의 OAuth 시작 경로로 넘기고,
                    거기서 다시 제공자(Google·Kakao·SSAFY)의 동의 화면으로 이동한다.

                    **fetch·axios 로 호출하면 안 된다.** 브라우저가 페이지를 이동해야 하는 흐름이라 XHR 로는 완성되지 않는다.

                    ```javascript
                    window.location.href = `${API_BASE_URL}/api/v1/auth/oauth/google`;
                    ```

                    동의가 끝나면 서버가 1회용 `oauth_handoff` 쿠키(HttpOnly, 5분)를 심고 프론트의 callback 화면으로 보낸다.
                    그 화면이 `POST /api/v1/auth/oauth/complete` 를 호출해 기존 회원 로그인과 신규 가입을 가른다.

                    Swagger UI 의 "Try it out" 으로는 리다이렉트를 따라갈 수 없으니 브라우저 주소창에서 직접 열어 확인한다.

                    **`return` 은 로컬 dev 서버 포트가 밀릴 때만 쓴다.** 붙이지 않으면 오늘과 똑같이
                    `FRONTEND_BASE_URL` 로 돌아가므로, Swagger·Bruno·8080 직행 절차는 그대로다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "302", description = "제공자 동의 화면으로 이동. `Location` 헤더를 따라간다"),
            @ApiResponse(responseCode = "404", description = "`OAUTH_PROVIDER_NOT_SUPPORTED` — 지원하지 않는 제공자 이름이다")})
    @GetMapping("/{provider}")
    public ResponseEntity<Void> authorize(
            // allowableValues 는 컴파일 상수여야 해서 enum 을 읽어올 수 없다. OAuthProvider 에
            // 값을 더하면 여기도 같이 고친다 — 안 고치면 Swagger 가 실제보다 좁게 알려준다.
            @Parameter(description = "소셜 제공자. 대소문자를 구분하지 않는다", example = "google",
                    schema = @Schema(allowableValues = {"google", "kakao", "ssafy"}))
            @PathVariable String provider,
            @Parameter(description = """
                    로그인이 끝난 뒤 돌아갈 origin. 생략하면 `FRONTEND_BASE_URL` 이다.
                    요청 자신의 origin 과 정확히 같고 그 요청이 localhost 로 들어왔을 때만 받아들이며,
                    그 밖의 값은 **에러가 아니라 무시**다 — 로컬 포트 이동을 흡수하는 것이 전부이고
                    임의 주소로 돌려보내는 용도가 아니다.""",
                    example = "http://localhost:5175")
            @RequestParam(name = "return", required = false) String returnOrigin,
            HttpServletRequest request) {
        String normalized = provider.toLowerCase(Locale.ROOT);
        try {
            OAuthProvider.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unsupported) {
            throw new ApiException(ErrorCode.OAUTH_PROVIDER_NOT_SUPPORTED);
        }
        // 신뢰 판단은 여기, 시작 요청에서만 한다. 통과한 값만 세션에 들어가므로 콜백은 꺼내 쓰기만
        // 하면 되고, 불허·부재는 저장하지 않아 기존 fallback 이 그대로 남는다 (GitLab #177).
        if (RequestOrigins.isTrusted(request, returnOrigin, properties)) {
            request.getSession(true).setAttribute(
                    OAuthLoginSuccessHandler.RETURN_ORIGIN_SESSION_ATTRIBUTE, returnOrigin);
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/oauth2/authorization/" + normalized))
                .build();
    }
}
