package com.example.ssafesta.mission;

import com.example.ssafesta.common.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST boundary for member-only daily-mission progress and reward claims. */
@RestController
@RequestMapping("/api/v1/missions/daily")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Daily Mission")
public class DailyMissionController {

    private static final String MEMBER_ONLY = "회원 계정만 일일 미션 보상을 받을 수 있습니다.";

    private final DailyMissionService missions;

    public DailyMissionController(DailyMissionService missions) {
        this.missions = missions;
    }

    @Operation(summary = "오늘의 일일 미션 진행도 조회",
            description = "KST 기준 오늘의 아홉 가지 활동 사실을 조회 시점에 계산한다. 진행도 전용 테이블이나 클라이언트 신고 값은 사용하지 않으며, `WORLD_ENTER`만 성공한 월드 세션 발급의 Redis 사실 마커를 읽는다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "KST 기준 오늘의 미션·진행도·수령 상태"),
            @ApiResponse(responseCode = "403", description = "MEMBER_ONLY — 게스트에게는 코인 지갑이 없다")})
    @GetMapping
    public DailyMissionService.DailyMissionsView findToday(@AuthenticationPrincipal Jwt jwt) {
        return missions.findToday(memberId(jwt));
    }

    @Operation(summary = "완료한 일일 미션 보상 수령",
            description = "완료한 미션 하나의 15 Coin을 수령한다. 지갑 행 잠금과 `DAILY_MISSION:{userId}:{missionId}:{KST 날짜}` 원장 멱등성 키로 동시 요청도 한 번만 지급하며, 하루 합계는 135 Coin을 넘지 않는다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "15 Coin 보상과 수령 뒤 잔액"),
            @ApiResponse(responseCode = "400", description = "NOT_COMPLETED"),
            @ApiResponse(responseCode = "409", description = "ALREADY_CLAIMED 또는 DAILY_CAP_REACHED"),
            @ApiResponse(responseCode = "403", description = "MEMBER_ONLY — 게스트 토큰이다")})
    @PostMapping("/{missionId}/claims")
    public DailyMissionService.ClaimView claim(@AuthenticationPrincipal Jwt jwt,
                                                @PathVariable String missionId) {
        return missions.claim(memberId(jwt), missionId);
    }

    private Long memberId(Jwt jwt) {
        return MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
    }
}
