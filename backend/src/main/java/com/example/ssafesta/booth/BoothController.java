package com.example.ssafesta.booth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Booth")
public class BoothController {

    private final BoothQueryService queries;

    public BoothController(BoothQueryService queries) {
        this.queries = queries;
    }

    /** My booth and its lease. 204 when the member has never leased. */
    @Operation(summary = "내 부스 조회 — 임대 상태와 남은 시간",
            description = """
                    로그인한 회원이 가진 부스와 현재 임대를 돌려준다. 부스 스튜디오의 첫 화면이 읽는 값이다.

                    **임대한 적이 없으면 `204 No Content` 이고 본문이 없다.** 오류가 아니다 — 아직 부스를 사지 않은
                    정상 상태이며, FE 는 이 응답으로 "부스 만들기" 안내를 띄운다.

                    `homepageUrl` 은 **공개 여부와 무관하게 항상 저장값**이다 (spec 016). 미공개 상태에서도 스튜디오 폼을
                    프리필해야 하기 때문이며, 방문자용 `GET /api/v1/booths/{boothId}` 와 규칙이 다르다.

                    **관리자 부스는 여기에 나오지 않는다** (spec 004 FR-022, `S15P21A604-905`). 관리자는 슬롯마다 부스를
                    하나씩 들 수 있어 "내 부스 하나" 라는 이 응답 모양에 담기지 않는다. 관리자가 자기 부스를 찾을 때는
                    `GET /api/v1/booth-slots` 의 `mine` 이 `true` 인 칸을 읽는다 — 그 응답이 슬롯마다 `boothId` 를 함께 준다.
                    관리자가 일반 회원으로서 따로 임대한 부스가 있으면 그것은 여기에 그대로 나온다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "내 부스와 임대 정보"),
            @ApiResponse(responseCode = "204", description = "임대 이력이 없다. 본문 없음 — 오류가 아니다"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY` — 게스트 토큰이다")})
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
    @Operation(summary = "부스 상세 조회 — 방문자가 보는 정보",
            description = """
                    부스의 이름·외관·공개 회차·홈페이지 주소를 돌려준다. **토큰이 없어도 호출된다.**

                    **만료된 부스는 빈 껍데기가 아니라 `409` 로 거절된다.** 만료를 월드에 실시간으로 전파하지 않기 때문에,
                    방문자는 여기서 그 사실을 알게 된다 (spec 004 FR-019). 빈 값으로 200 을 주면 "빈 부스"와
                    "끝난 부스"를 구별할 수 없다.

                    `homepageUrl` 은 **공개된 배치가 있을 때만** 값이 온다 — 노트북은 공개 배치 안에만 있으므로
                    방문자가 그 주소를 쓸 수 있는 순간과 일치시킨다 (spec 016 FR-003).

                    **등록값이 없으면 그 부스 프로젝트의 `deployUrl`(서비스 주소)이 대신 내려간다** (2026-09-14 결정).
                    `booths.homepage_url` 은 등록 화면이 없어 실제로는 늘 비어 있었고, 소유자가 자기 서비스 주소를
                    입력하는 칸은 프로젝트 관리의 "서비스 주소" 뿐이다. 등록값이 있으면 **등록값이 이긴다** —
                    폴백은 빈 자리만 메운다. 둘 다 없으면 `null` 이라 FE 는 여전히 `null` 하나로 안내를 끝낸다.

                    ⚠️ 회차 필드 이름이 endpoint 마다 다르다 — 여기는 **`publishedLayoutVersion`**, 배치 Draft 조회와
                    Publish 결과는 **`publishedVersion`** 이다. 합치지 않기로 확정했다 (#97).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "부스 상세"),
            @ApiResponse(responseCode = "404", description = "`BOOTH_NOT_FOUND` — 그런 부스가 없다"),
            @ApiResponse(responseCode = "409", description = "`BOOTH_LEASE_EXPIRED` — 임대가 끝난 부스다. 방문자에게 종료를 안내한다")})
    @GetMapping("/{boothId}")
    public BoothQueryService.PublicBoothView booth(
            @Parameter(description = "부스 식별자. 슬롯 목록의 `boothId` 또는 임대 응답의 `boothId`", example = "7")
            @PathVariable Long boothId) {
        // No translation needed: both failures carry their own ErrorCode now.
        return queries.findPublicBooth(boothId);
    }
}
