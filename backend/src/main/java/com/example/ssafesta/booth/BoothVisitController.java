package com.example.ssafesta.booth;

import com.example.ssafesta.common.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 부스 방문·체류 계측 (S15P21A604-240, GitLab #94).
 *
 * <p><b>발신자는 React 호스트다.</b> Unity 는 Spring 에 아무 신호도 보내지 않으므로(2026-09-07
 * 확정), 부스 구역 진입·이탈을 브릿지 이벤트로 받은 React 가 이 경로를 부른다.
 *
 * <p><b>발신 지점 합의는 끝났다</b> (GitLab #186, 2026-09-13~14). 게임 파트가
 * <i>"{@code POST /visits}·{@code /exit} 를 FE 에서 이 전이에 걸면 Unity 쪽 추가 작업 없이 된다"</i>
 * 고 회신했고(Unity 는 부스 안팎 전이를 {@code WORLD_BOOTH_CONTEXT} 로 이미 FE 에 보낸다), FE 는
 * 같은 이슈에서 채널 값을 서버가 파생하는 안으로 확정했다.
 */
@RestController
@RequestMapping("/api/v1/booths/{boothId}")
@Tag(name = "Booth Metrics")
public class BoothVisitController {

    private final BoothVisitService visits;

    public BoothVisitController(BoothVisitService visits) {
        this.visits = visits;
    }

    @Operation(summary = "부스 방문 시작",
            description = """
                    방문자가 부스 구역에 들어왔다는 기록을 남긴다. **회원과 게스트 모두 센다** —
                    부스 구경은 게스트에게 열려 있고(헌법 12조가 막는 것은 소유·결제다), 통계에서
                    그들을 빼면 실제 트래픽을 절반만 보게 된다.

                    **회원의 입장 신호가 두 번 오면 새 기록을 만들지 않는다.** 브릿지 이벤트는
                    재전송될 수 있고 그때마다 행이 늘면 방문 수가 실제보다 커진다. 같은
                    `visitId` 가 돌아온다. 게스트는 식별자가 없어 합치지 못한다.

                    공개되지 않았거나 임대가 끝난 부스는 거부된다 — 들어갈 수 없는 부스에
                    들어갔다는 기록은 집계를 오염시킨다.

                    **요청 본문이 없다.** 어느 월드 채널에서 들어왔는지는 서버가 정한다 — 클라이언트는
                    그 값을 가질 길이 없고, 채널 정체성을 클라이언트 주장에서 받지 않는 것이 이
                    시스템의 기존 판단이다(헌법 16조).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "`visitId` 와 `enteredAt`. 재전송이면 기존 방문"),
            @ApiResponse(responseCode = "404", description = "`BOOTH_NOT_FOUND` · `LAYOUT_NOT_PUBLISHED`"),
            @ApiResponse(responseCode = "409", description = "`BOOTH_LEASE_EXPIRED`")})
    @PostMapping("/visits")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    public BoothVisitService.VisitView enter(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "부스 식별자", example = "7") @PathVariable Long boothId) {
        return visits.enter(boothId, MemberPrincipal.optionalMemberId(jwt));
    }

    @Operation(summary = "부스 방문 종료",
            description = """
                    부스 구역을 떠났다는 기록. **본인 방문만** 닫을 수 있다.

                    **닫히지 않은 방문이 정상이다** — 브라우저를 그냥 닫으면 이 신호가 오지 않는다.
                    서버는 그런 행을 오류로 다루지 않고, 평균 체류 계산에서 빼고 그 수를 따로
                    보고한다. 임의의 timeout 으로 닫으면 그 값은 실제 체류가 아니라 서버가 고른
                    상수가 된다.

                    두 번째 종료 신호는 무시한다 — 첫 신호가 실제 퇴장에 가깝다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "기록됨"),
            @ApiResponse(responseCode = "403", description = "`FORBIDDEN` — 내 방문 기록이 아니다"),
            @ApiResponse(responseCode = "404", description = "`BOOTH_NOT_FOUND` — 그런 방문 기록이 없다")})
    @PostMapping("/visits/{visitId}/exit")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = "bearerAuth")
    public void exit(@AuthenticationPrincipal Jwt jwt,
                     @PathVariable Long boothId,
                     @PathVariable Long visitId) {
        visits.exit(boothId, visitId, MemberPrincipal.optionalMemberId(jwt));
    }

    @Operation(summary = "부스 방문 집계",
            description = """
                    부스 운영자(Owner·`ADMIN`·`CONTENT_EDITOR`)가 자기 부스의 트래픽을 본다.

                    `averageDwellSeconds` 는 **닫힌 방문만**의 평균이고, 열린 방문 수는
                    `openVisits` 로 따로 나온다. `uniqueVisitors` 는 **회원 기준**이다 — 게스트는
                    식별자가 없어 각 방문이 따로 세어진다.

                    **플랫폼 전체 집계는 여기 없다.** 전역 관리자 권한 모델이 미정이고
                    `docs/26` 에 결정 요청으로 올라가 있다 (`S15P21A604-165`).

                    코인 순환량도 여기 없다 — 이미 원장에 사유 코드와 함께 남고 있어서 같은 사실을
                    두 곳에 쓰지 않는다. 부스별 코인 집계의 모양은 `spec 015` contracts 가 서는
                    `S15P21A604-501` 의 몫이다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "기간 집계"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — `from` 이 `to` 보다 뒤다"),
            @ApiResponse(responseCode = "403", description = "`BOOTH_EDITOR_FORBIDDEN`"),
            @ApiResponse(responseCode = "404", description = "`BOOTH_NOT_FOUND`")})
    @GetMapping("/visit-metrics")
    @SecurityRequirement(name = "bearerAuth")
    public BoothVisitService.MetricsView metrics(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long boothId,
            @Parameter(description = "조회 시작 (ISO-8601)", example = "2026-09-13T00:00:00Z")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "조회 끝 (열린 구간)", example = "2026-09-14T00:00:00Z")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return visits.metrics(boothId, MemberPrincipal.requireMemberId(jwt), from, to);
    }
}
