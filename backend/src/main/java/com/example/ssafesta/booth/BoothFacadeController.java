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
 * Editing the booth exterior (spec 005 FR-018, contracts/layout-api.md §6).
 *
 * <p>Not in docs/08 — this endpoint is new. An addition rather than a breaking change, but still
 * something FE and Unity have to be told about (헌법 24조), and docs/08 is updated alongside it.
 *
 * <p>Reading the facade goes through {@code GET /booths/{boothId}}, which spec 004 already owns.
 */
@RestController
@RequestMapping("/api/v1/booths/{boothId}/facade")
@Tag(name = "Booth Facade")
public class BoothFacadeController {

    private final BoothFacadeService facades;

    public BoothFacadeController(BoothFacadeService facades) {
        this.facades = facades;
    }

    @Operation(summary = "부스 외관 수정 — 테마·대표색·간판 문구·로고",
            description = """
                    밖에서 보이는 부스의 네 가지를 바꾼다. 배치가 아니라 **고정된 네 필드**다 — 외벽은 공용 건물
                    프리팹으로 그려지고 이 값만 달라진다 (spec 005 FR-018).

                    `PUT` 이고 본문이 값 전체다. 보내지 않은 필드는 `null` 로 저장되므로, 하나만 바꾸려면
                    나머지 현재 값도 함께 보낸다. 현재 값은 `GET /api/v1/booths/{boothId}` 의 `facade` 에 있다.

                    **필드 규칙**

                    | 필드 | 규칙 |
                    |---|---|
                    | `themeCode` | `DEFAULT`·`SSAFY_BLUE`·`WARM`·`MONO` 중 하나. 생략하면 `DEFAULT` |
                    | `primaryColor` | `#RRGGBB` 형식이고 **12색 팔레트 안**의 값이어야 한다. 형식 오류와 팔레트 이탈은 서로 다른 메시지를 받는다 |
                    | `signText` | 최대 60자 |
                    | `logoUrl` | **`https` 만 허용**하고 최대 2048자. `http` 로고는 혼합 콘텐츠로 차단되어 아무것도 안 보이게 되고, 소유자는 그 이유를 밖에서 알 수 없다 |

                    소유자와 같은 부스 스태프만 수정할 수 있고, **임대가 만료된 부스는 수정할 수 없다** —
                    밖에서 아무에게도 보이지 않는 것을 고치는 셈이기 때문이다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장된 외관 전체"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — 테마·색 형식·팔레트·간판 길이·로고 URL 위반. 사유마다 메시지가 다르다"),
            @ApiResponse(responseCode = "403", description = "`BOOTH_FORBIDDEN` — 내 부스도, 내가 스태프인 부스도 아니다"),
            @ApiResponse(responseCode = "404", description = "`BOOTH_NOT_FOUND` — 그런 부스가 없다"),
            @ApiResponse(responseCode = "409", description = "`BOOTH_LEASE_EXPIRED` — 임대가 끝난 부스는 외관을 고칠 수 없다")})
    @PutMapping
    @SecurityRequirement(name = "bearerAuth")
    public BoothFacadeService.FacadeView update(@AuthenticationPrincipal Jwt jwt,
                                                @Parameter(description = "내 부스 식별자", example = "7")
                                                @PathVariable Long boothId,
                                                @RequestBody BoothFacadeService.FacadeCommand command) {
        Long userId = BoothPrincipal.requireMemberId(jwt);
        return facades.update(boothId, userId, command);
    }
}
