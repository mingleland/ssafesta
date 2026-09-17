package com.example.ssafesta.minigame;

import com.example.ssafesta.common.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The plaza slot machine (spec 021, GitLab #205 계약).
 *
 * <p>Member-only, refused here rather than by a security rule, for the same reason
 * {@link MinigameController} does it: {@code /api/v1/**} is only {@code authenticated()}, so a
 * guest token reaches the controller, and telling them up front beats letting them bet coins they
 * do not have.
 *
 * <p><b>A separate path from timing-stop, deliberately.</b> GitLab #205 구조 confirmed that the two
 * games keep their own endpoints and that no common minigame route is introduced — the only thing
 * the parts agreed on is the one URL below.
 */
@RestController
@RequestMapping("/api/v1/minigames/slot-machines")
@Tag(name = "Minigame")
@SecurityRequirement(name = "bearerAuth")
public class SlotMachineController {

    private static final String MEMBER_ONLY = "회원 계정만 슬롯머신을 이용할 수 있습니다.";

    private final SlotMachineService slotMachines;

    public SlotMachineController(SlotMachineService slotMachines) {
        this.slotMachines = slotMachines;
    }

    @Operation(summary = "슬롯머신 스핀",
            description = """
                    한 판을 돌린다. **베팅 차감과 당첨 지급이 한 트랜잭션**이라 코인만 빠지거나
                    지급만 되는 중간 상태가 없다 (spec 021 FR-003).

                    **판정은 서버가 한다.** 확률표는 서버 설정이고 응답의 `tier` 가 정본이다 —
                    클라이언트는 그 등급에 맞는 릴 연출만 고른다 (헌법 16조).

                    `tier` 는 **0 낙첨 · 1 ×2 · 2 ×3 · 3 ×10** 이고 `payout = bet × 배수` 다.
                    낙첨이면 `payout` 은 0 이고 지급 원장도 남지 않는다.

                    **`bet` 은 서버가 정한 값과 같아야 한다.** 요청값은 확인용이지 차감액이 아니다 —
                    다르면 `400` 이고 아무것도 차감되지 않는다.

                    **일일 한도가 없다.** RTP 가 1 미만이라 이 기계는 코인을 만들지 않고 태우기만
                    한다. 타이밍 스톱의 일일 50코인 한도는 `MINIGAME_REWARD` 사유로 집계되고
                    슬롯은 `SLOT_BET`·`SLOT_PAYOUT` 이라 서로 섞이지 않는다 (spec 021 FR-006).
                    **`429` 는 이 계약에서 나오지 않는다.**
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "`sessionId`·`bet`·`payout`·`tier`·`balanceAfter`"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — `bet` 이 누락됐거나 서버 설정값과 다르다. 차감은 일어나지 않는다"),
            @ApiResponse(responseCode = "401", description = "`UNAUTHORIZED` — 토큰이 없거나 유효하지 않다"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY` — 게스트는 코인을 쓸 수 없다"),
            @ApiResponse(responseCode = "404", description = "`SLOT_MACHINE_NOT_FOUND` — 서버가 모르는 `machineId`"),
            @ApiResponse(responseCode = "409", description = "`INSUFFICIENT_COIN` — 잔액 부족. 본문의 `balance` 가 현재 잔액이다")})
    @PostMapping("/{machineId}/spins")
    public SlotMachineService.SlotSpinResult spin(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "씬이 정한 기계 식별자", example = "plaza-slot-01")
            @PathVariable String machineId,
            @RequestBody(required = false) SlotMachineService.SlotSpinCommand command) {
        return slotMachines.spin(MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY), machineId, command);
    }
}
