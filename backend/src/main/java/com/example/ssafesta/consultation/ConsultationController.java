package com.example.ssafesta.consultation;

import com.example.ssafesta.common.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사람 상담 요청·수락·종료 (spec 011 US1 P1,
 * {@code contracts/staff-consultation-api.md} §B).
 *
 * <p>경로는 2026-09-07 BE 회신(GitLab #133)으로 확정됐고 FE 가 {@code S15P21A604-519} 로
 * 선반영을 마쳤다. <b>{@code docs/08} §12 는 다른 경로를 적고 있어 정본이 아니다</b> — 정정은 이
 * 티켓의 후속 작업이다.
 *
 * <p>P1 의 STOMP 는 서버에서 클라이언트로 가는 알림 전용이고 <b>행동은 전부 여기 REST</b> 다
 * (C-12).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Consultation")
public class ConsultationController {

    /** 게스트는 사람 상담을 청할 수 없다 (FR-014, 헌법 12조). 무엇이 막혔는지 말해 준다. */
    private static final String MEMBER_ONLY = "회원 계정만 사람 상담을 이용할 수 있습니다.";

    private final ConsultationService consultations;

    public ConsultationController(ConsultationService consultations) {
        this.consultations = consultations;
    }

    @Operation(summary = "사람 상담 요청",
            description = """
                    방문자가 AI 대화 중 사람 상담을 청한다. **회원 전용이다** — 게스트는
                    `403 MEMBER_ONLY` 와 함께 소셜 로그인 안내를 받는다 (FR-014).

                    `conversationId` 는 선택이다. **요약 텍스트는 클라이언트가 만들지도 보내지도
                    않는다** — 서버가 그 id 로 FastAPI 에 요약을 청해 **요청 생성 시점의
                    스냅샷**으로 굳힌다. 이후 대화가 이어져도 갱신하지 않는다: 직원이 본 요약이
                    나중에 달라지면 안 된다.

                    **요약 생성 실패는 요청을 막지 않는다** — `handoffSummary` 가 `null` 일 뿐이다
                    (헌법 3조). `S15P21A604-139` 도착 전에는 항상 `null` 이다.

                    요청은 **10분** 유효하다 (C-01). 응답의 `expiresInSeconds` 로 잔여 시간을
                    안내하고 만료 후 재요청 버튼을 낸다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "`requestId` 와 `expiresInSeconds`"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — `boothId` 누락"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY` — 게스트다"),
            @ApiResponse(responseCode = "409", description = "`CONSULTATION_REQUEST_PENDING` — 이 부스에 걸어 둔 요청이 이미 있다 · `BOOTH_LEASE_EXPIRED` — 임대가 끝난 부스다")})
    @PostMapping("/consultation/requests")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    public ConsultationService.RequestView request(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false) ConsultationService.RequestCommand command) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        return consultations.request(userId, command);
    }

    @Operation(summary = "상담 요청 취소",
            description = "방문자가 자기 대기 중 요청을 거둔다. 이미 수락·만료된 요청은 거부된다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "취소됨"),
            @ApiResponse(responseCode = "403", description = "`CONSULTATION_FORBIDDEN` — 내 요청이 아니다"),
            @ApiResponse(responseCode = "404", description = "`CONSULTATION_NOT_FOUND`"),
            @ApiResponse(responseCode = "409", description = "`CONSULTATION_NOT_REQUESTED`")})
    @DeleteMapping("/consultation/requests/{requestId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = "bearerAuth")
    public void cancel(@AuthenticationPrincipal Jwt jwt,
                       @Parameter(description = "요청 식별자", example = "901")
                       @PathVariable Long requestId) {
        consultations.cancel(requestId, MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY));
    }

    @Operation(summary = "부스 상담 대기열",
            description = """
                    그 부스의 **대기 중이고 아직 만료되지 않은** 요청만 준다. 만료 배치를 기다리지
                    않는다 — 눌러 봐야 409 인 카드를 보여 주지 않는다.

                    부스 구성원이면 누구나 본다. 수락은 못 해도 상황은 알아야 한다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "대기 중 요청. 없으면 빈 배열"),
            @ApiResponse(responseCode = "403", description = "`STAFF_MANAGER_FORBIDDEN` — 이 부스의 구성원이 아니다"),
            @ApiResponse(responseCode = "404", description = "`BOOTH_NOT_FOUND`")})
    @GetMapping("/booths/{boothId}/consultation/requests")
    @SecurityRequirement(name = "bearerAuth")
    public List<ConsultationService.QueueItemView> queue(@AuthenticationPrincipal Jwt jwt,
                                                        @PathVariable Long boothId) {
        return consultations.queue(boothId, MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY));
    }

    @Operation(summary = "상담 요청 수락",
            description = """
                    직원이 요청을 가져간다. **정확히 한 명만 성공한다** (SC-001) — 조건부 갱신이
                    승자를 정하므로 동시 요청에서도 성립한다.

                    **직원 한 명에게 활성 상담은 1건이다** (C-06, FR-021). 이미 진행 중인 상담이
                    있으면 `409 CONSULTATION_ALREADY_ACTIVE` 이고 **기존 상담은 그대로 유지된다**.

                    응답의 `sessionId` 는 `requestId` 와 같은 값이다 — 한 행이 요청과 세션을
                    겸한다. 둘 다 싣는 것은 클라이언트가 어느 쪽을 들고 있든 종료를 부를 수 있게
                    하기 위해서다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "`requestId`·`sessionId`·방문자 닉네임·요약"),
            @ApiResponse(responseCode = "403", description = "`STAFF_MANAGER_FORBIDDEN` — 그 부스의 구성원이 아니다"),
            @ApiResponse(responseCode = "404", description = "`CONSULTATION_NOT_FOUND`"),
            @ApiResponse(responseCode = "409", description = "`CONSULTATION_ALREADY_ACTIVE` — 내게 활성 상담이 있다 · `CONSULTATION_NOT_REQUESTED` — 이미 남이 가져갔거나 만료·취소됐다")})
    @PostMapping("/consultation/requests/{requestId}/accept")
    @SecurityRequirement(name = "bearerAuth")
    public ConsultationService.AcceptedView accept(@AuthenticationPrincipal Jwt jwt,
                                                   @PathVariable Long requestId) {
        return consultations.accept(requestId, MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY));
    }

    @Operation(summary = "상담 종료",
            description = "방문자·직원 **누구나** 끝낼 수 있다 (FR-010). 상대에게는 종료 이벤트가 간다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "종료됨"),
            @ApiResponse(responseCode = "403", description = "`CONSULTATION_FORBIDDEN` — 내 상담이 아니다"),
            @ApiResponse(responseCode = "404", description = "`CONSULTATION_NOT_FOUND`"),
            @ApiResponse(responseCode = "409", description = "`CONSULTATION_NOT_REQUESTED` — 진행 중인 상담이 아니다")})
    @PostMapping("/consultation/sessions/{sessionId}/end")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = "bearerAuth")
    public void end(@AuthenticationPrincipal Jwt jwt, @PathVariable Long sessionId) {
        consultations.end(sessionId, MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY));
    }
}
