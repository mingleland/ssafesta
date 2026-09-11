package com.example.ssafesta.minigame;

import com.example.ssafesta.common.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The timing-stop minigame (spec 014, {@code contracts/minigame-api.yaml}).
 *
 * <p>Member-only. {@code /api/v1/**} is merely {@code authenticated()}, so a guest token reaches
 * this class and is refused here rather than by a security rule — the same door
 * {@code SurveyController} uses. Refusing at issuance is deliberate: letting a guest play and only
 * then telling them there was never a reward is worse than saying so up front.
 *
 * <p>Only the timing-stop game exists. There is no slot-machine endpoint: 헌법 28조 and FR-009 cap
 * the festival at one minigame, and widening that is a lead decision filed in {@code docs/26}
 * (S15P21A604-569), not something this class can settle.
 */
@RestController
@RequestMapping("/api/v1/minigames/timer-stop")
@Tag(name = "Minigame")
@SecurityRequirement(name = "bearerAuth")
public class MinigameController {

    private static final String MEMBER_ONLY = "회원 계정만 미니게임 보상을 받을 수 있습니다.";

    private final TimerStopService timerStop;

    public MinigameController(TimerStopService timerStop) {
        this.timerStop = timerStop;
    }

    @Operation(summary = "타이밍 스톱 세션 발급",
            description = """
                    한 판을 시작한다. **목표 시간은 서버가 5~10초에서 무작위로 뽑아 내려준다** —
                    클라이언트가 정하지 않는다 (FR-001a, C-06). `sessionId` 는 결과 제출의 경로이자
                    멱등성 키다 (FR-004).

                    `failAfterSeconds` 를 넘기면 그 판은 실패다 (FR-001d). 이 값도 서버가 정한다 —
                    몇 초를 "크게 초과" 로 볼지는 기획 결정이라 클라이언트가 상수로 박으면 안 된다.

                    `serverStartedAt` 은 서버가 계측을 시작한 시각이다. 클라이언트의 타이머는 네트워크
                    한 홉만큼 늦게 시작하고, 결과 제출의 허용 오차가 그 차이를 덮는다.

                    **일일 한도에 도달했어도 세션은 발급된다.** 게임은 할 수 있고 보상만 없다
                    (Acceptance Scenario 4).

                    **요청 본문은 읽지 않는다.** 미니게임이 1종뿐이라(FR-009) 식별할 대상이 없다.
                    본문이 실려 와도 받아만 두고 무시한다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "`sessionId`·`targetSeconds`·`failAfterSeconds`·`serverStartedAt`"),
            @ApiResponse(responseCode = "401", description = "`UNAUTHORIZED` — 토큰이 없거나 유효하지 않다"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY` — 게스트는 보상 게임에 참여할 수 없다")})
    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public TimerStopService.SessionIssued start(@AuthenticationPrincipal Jwt jwt) {
        return timerStop.issue(MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY));
    }

    @Operation(summary = "타이밍 스톱 결과 제출",
            description = """
                    **서버가 읽는 것은 `stoppedSeconds` 하나다.** 오차·구간·보상·일일 한도는 전부
                    서버가 계산한다 (C-06, FR-008, 헌법 16조). 클라이언트가 `targetSeconds`·
                    `errorSeconds`·`timedOut` 을 함께 보내도 **조용히 무시**된다 — 받는 쪽을 좁혀서
                    얻을 것이 없어 거부하지 않는다.

                    ### 판정 실패는 HTTP 오류가 아니다
                    아래는 **전부 `200`** 이고 본문으로 구분한다:
                    - `accepted: false` — 신고한 정지 시각이 서버 경과 시간과 맞지 않는다. 세션은 소진된다
                    - `timedOut: true` — `failAfterSeconds` 를 넘겼다. 정상 플레이이고 보상만 없다
                    - `dailyLimitReached: true` — 오늘 더 받을 코인이 없다. **`429` 는 이 계약에서 나오지 않는다**
                    - 같은 세션 재제출 — 최초 판정을 그대로 돌려주고 다시 지급하지 않는다 (FR-004)

                    ### 일일 한도
                    한도는 **50 Coin/일**, 기준일은 **제출 시각의 KST 날짜**다 (C-04). 남은 한도보다
                    보상이 크면 **남은 만큼만 잘라서** 지급한다 — 누적 48 에 5짜리 판이면 2가 들어온다.

                    `dailyLimitReached` 는 `dailyRemainingCoins == 0` 과 같은 뜻이다. "이번 판이
                    잘렸다" 가 아니라 **"지금 더 받을 수 없다"** 이므로, 45에서 5를 온전히 받아도 `true` 다.

                    재제출 응답에서 `dailyRemainingCoins`·`dailyLimitReached` 만은 **최초값이 아니라
                    현재 상태**다. 나머지 판정 필드는 최초 그대로다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "판정 결과. 거부·실패·한도 도달·재제출도 모두 이 상태다"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — `stoppedSeconds` 누락·음수·범위 밖, 또는 `sessionId` 가 UUID 가 아니다. 세션은 그대로 남는다"),
            @ApiResponse(responseCode = "401", description = "`UNAUTHORIZED`"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY`"),
            @ApiResponse(responseCode = "404", description = "`MINIGAME_SESSION_NOT_FOUND` — 없는 세션이거나 남의 세션이다")})
    @PostMapping("/sessions/{sessionId}/result")
    public TimerStopService.SubmitResult submit(@AuthenticationPrincipal Jwt jwt,
                                                @Parameter(description = "세션 발급 응답의 `sessionId`", example = "3f1a6d2c-8b5e-4c11-9a77-2d0e5f8c4b31")
                                                @PathVariable UUID sessionId,
                                                @RequestBody(required = false)
                                                TimerStopService.SubmitCommand command) {
        return timerStop.submit(MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY), sessionId, command);
    }
}
