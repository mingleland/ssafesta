package com.example.ssafesta.minigame;

import com.example.ssafesta.common.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Exposes the post-swing high-striker fact recording contract consumed by the Unity world. */
@RestController
@RequestMapping("/api/v1/minigames/high-striker")
@Tag(name = "Minigame")
@SecurityRequirement(name = "bearerAuth")
public class HighStrikerController {

    private static final String MEMBER_ONLY = "회원 계정만 하이스트라이커 기록을 남길 수 있습니다.";

    private final HighStrikerService highStriker;

    public HighStrikerController(HighStrikerService highStriker) {
        this.highStriker = highStriker;
    }

    @Operation(summary = "하이스트라이커 플레이 기록",
            description = "Netcode 서버가 승인한 스윙 결과를 회원의 오늘 하이스트라이커 사실로 저장한다. score는 1 이상을 받고 999를 넘으면 999로 제한한다. 같은 회원의 기록은 3.2초 간격으로 제한하며, 저장된 결과는 일일 미션의 3회 플레이 및 400점 이상 달성에 사용된다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "저장한 playId·점수·오늘 플레이 수·오늘 최고 점수"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED — machineId 또는 score가 비었다"),
            @ApiResponse(responseCode = "403", description = "MEMBER_ONLY — 게스트 토큰이다"),
            @ApiResponse(responseCode = "404", description = "HIGH_STRIKER_NOT_FOUND — 서버가 모르는 machineId다"),
            @ApiResponse(responseCode = "429", description = "HIGH_STRIKER_TOO_FAST — 3.2초 쿨다운 안의 재전송이다")})
    @PostMapping("/plays")
    @ResponseStatus(HttpStatus.CREATED)
    public HighStrikerService.PlayRecorded record(@AuthenticationPrincipal Jwt jwt,
                                                   @RequestBody(required = false) HighStrikerService.PlayCommand command) {
        return highStriker.record(MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY), command);
    }
}
