package com.example.ssafesta.booth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Booth Homepage")
public class BoothHomepageController {

    private final BoothHomepageService homepages;

    public BoothHomepageController(BoothHomepageService homepages) {
        this.homepages = homepages;
    }

    @Operation(summary = "부스 노트북이 열 홈페이지 주소 등록·해제",
            description = """
                    부스 안 노트북 오브젝트를 클릭했을 때 방문자에게 열어줄 외부 주소를 저장한다.

                    **해제는 `{"homepageUrl": null}` 이다.** 필드를 아예 빼거나 본문을 생략한 `{}` 는 **거부된다** —
                    직렬화 실수로 필드를 빠뜨린 클라이언트가 등록된 페이지를 지워 버리는 일을 막는다. 빈 문자열도
                    같은 이유로 거부한다.

                    `http` 도 허용한다. 이것은 페이지에 끼워 넣는 자원이 아니라 **새 탭으로 보내는 목적지**여서,
                    `https` 만 허용하면 실제로 열리는 링크를 규칙 때문에 막는 셈이 된다. (부스 로고는 반대로
                    페이지에 박히므로 `https` 전용이다.)

                    저장한 값은 두 곳에서 읽는다 — 소유자는 `GET /api/v1/booths/mine` 으로 **항상** 보고,
                    방문자는 `GET /api/v1/booths/{boothId}` 로 **공개된 배치가 있을 때만** 본다 (spec 016 FR-003).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장(또는 해제)된 주소"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — 필드 누락(`{}`)·빈 문자열·2048자 초과·`http(s)` 가 아닌 스킴·사용자 정보(@) 포함·잘못된 포트. 사유마다 메시지가 다르고 `errors[0].field` 가 `homepageUrl` 이다"),
            @ApiResponse(responseCode = "403", description = "`BOOTH_FORBIDDEN` — 내 부스도, 내가 스태프인 부스도 아니다"),
            @ApiResponse(responseCode = "404", description = "`BOOTH_NOT_FOUND` — 그런 부스가 없다"),
            @ApiResponse(responseCode = "409", description = "`BOOTH_LEASE_EXPIRED` — 임대가 끝난 부스는 수정할 수 없다")})
    @PutMapping
    @SecurityRequirement(name = "bearerAuth")
    public BoothHomepageService.HomepageView update(@AuthenticationPrincipal Jwt jwt,
                                                    @Parameter(description = "내 부스 식별자", example = "7")
                                                    @PathVariable Long boothId,
                                                    @RequestBody(required = false)
                                                    BoothHomepageService.HomepageCommand command) {
        Long userId = BoothPrincipal.requireMemberId(jwt);
        return homepages.update(boothId, userId, command);
    }
}
