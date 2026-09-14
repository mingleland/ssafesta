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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 부스 운영 대시보드 (spec 015 US1, S15P21A604-501). */
@RestController
@RequestMapping("/api/v1/booths/{boothId}/dashboard")
@Tag(name = "Booth Dashboard")
public class BoothDashboardController {

    private final BoothDashboardService dashboard;

    public BoothDashboardController(BoothDashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @Operation(summary = "부스 운영 요약",
            description = """
                    부스 운영자가 자기 부스의 한 기간을 한 번에 본다. Owner·`ADMIN`·`CONTENT_EDITOR`
                    만 볼 수 있다 — `CONSULTANT` 는 상담을 하지 운영 지표를 보지 않는다.

                    **코인 칸은 "수익" 이 아니다.** 부스로 코인이 *들어오는* 경로가 없기 때문이다 —
                    원장에서 부스와 닿는 사유는 둘뿐이고 둘 다 수익이 아니다. 그래서 이 부스가
                    **쓴 코인**과 **뿌린 코인**을 그대로 준다 (2026-09-13 백엔드 결정, `docs/26`).

                    - `leaseCostCoin` — 임대료. 소유자 지갑에서 나간 값이다
                    - `surveyRewardCoin` — 이 부스 설문이 발행한 보상. **`surveyResponses` 옆에 놓고
                      읽으면** 보상 구조가 먹히는지 바로 보인다 (코인 155개 뿌려 응답 31개)

                    진짜 유입 경로가 생기면(`S15P21A604-634` AI 상담 과금) 그때 이 둘 옆에 더한다.

                    **`aiUsages` 만 `null` 이다.** AI 대화를 남기는 표가 저장소에 아직 없다(AI 파트
                    소관). `null` 은 "집계할 원천이 없다" 는 뜻이고 `0`(세어 보니 0건)과 다르다 —
                    0 으로 채우면 FE 가 "AI 이용 0건" 카드를 영원히 띄우게 된다.

                    `averageDwellSeconds` 는 **닫힌 방문만**의 평균이고 열린 방문 수는 `openVisits`
                    로 따로 나온다. `uniqueVisitors` 는 회원 기준이다 — 게스트는 식별자가 없어 각
                    방문이 따로 세어진다.

                    집계는 **실시간 계산**이다. 사전 집계 표(`booth_daily_metrics`)는 비워 두었다 —
                    갱신이 실패해도 조용히 그럴듯한 숫자를 계속 보여주기 때문이다. 느려지면 얹는다.

                    **임대가 끝난 부스도 지난 기간 숫자를 볼 수 있다.** 행사가 끝난 뒤 정산을 해야
                    하기 때문이다 (spec 015 C-03).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "기간 요약"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — `from` 이 `to` 보다 뒤다"),
            @ApiResponse(responseCode = "403", description = "`BOOTH_EDITOR_FORBIDDEN`"),
            @ApiResponse(responseCode = "404", description = "`BOOTH_NOT_FOUND`")})
    @GetMapping("/summary")
    @SecurityRequirement(name = "bearerAuth")
    public BoothDashboardService.SummaryView summary(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "부스 식별자", example = "7") @PathVariable Long boothId,
            @Parameter(description = "조회 시작 (ISO-8601, 포함)", example = "2026-09-01T00:00:00Z")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "조회 끝 (ISO-8601, 제외)", example = "2026-09-14T00:00:00Z")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return dashboard.summary(boothId, MemberPrincipal.requireMemberId(jwt), from, to);
    }
}
