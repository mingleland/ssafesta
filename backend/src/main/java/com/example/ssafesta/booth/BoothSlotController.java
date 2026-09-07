package com.example.ssafesta.booth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Slot listing and leasing (spec 004 contracts/lease-api.md). */
@RestController
@RequestMapping("/api/v1/booth-slots")
@Tag(name = "Booth Slot")
public class BoothSlotController {

    private final BoothQueryService queries;
    private final BoothLeaseService leases;
    private final BoothLayoutQueryService layouts;

    public BoothSlotController(BoothQueryService queries, BoothLeaseService leases,
                               BoothLayoutQueryService layouts) {
        this.queries = queries;
        this.leases = leases;
        this.layouts = layouts;
    }

    /** Open to guests: browsing the world is what a guest session is for (헌법 12조). */
    @Operation(summary = "슬롯 목록 조회 — 어느 자리가 비었는지 본다",
            description = """
                    월드의 모든 부스 자리와 현재 점유 상태를 돌려준다. **토큰이 없어도 호출된다** —
                    지나가는 방문자가 읽는 정보다.

                    임대를 하려면 여기서 `status` 가 `AVAILABLE` 인 `USER_RENTAL` 슬롯의 `slotId` 를 골라
                    `POST /api/v1/booth-slots/{slotId}/leases` 를 호출한다.

                    `status` 는 **만료를 반영한 값**이다. 임대가 시간으로 끝난 슬롯은 별도 정리 없이 `AVAILABLE` 로 보인다.

                    ⚠️ **만료는 3D 월드에 실시간으로 전파되지 않는다.** 목록이 `AVAILABLE` 인데 월드에는 부스가 아직 서
                    있을 수 있다 — 그때는 **이 API 응답이 권위다** (spec 004 FR-019).
                    """)
    @ApiResponse(responseCode = "200", description = "슬롯 전체 목록. 층·자리 순으로 정렬된다")
    @GetMapping
    public List<BoothQueryService.SlotView> slots(@AuthenticationPrincipal Jwt jwt) {
        return queries.listSlots(BoothPrincipal.optionalMemberId(jwt));
    }

    /**
     * The published layout of whatever booth currently holds this room (spec 005 contract §11,
     * #62). Unauthenticated, exactly like the booth-keyed path — every visitor reads it.
     *
     * <p>Unity calls this one because its anchors are rooms; the editor calls
     * {@code /booths/{boothId}/layouts/published} because it edits a booth. Same resource, two
     * natural keys, identical body.
     */
    @Operation(summary = "슬롯 기준 공개 배치 조회 — Unity 가 방을 열 때 쓴다",
            description = """
                    이 자리를 현재 점유한 부스의 **공개된** 내부 배치를 돌려준다. 토큰이 없어도 호출된다.

                    같은 자원에 두 개의 자연키가 있다. Unity 는 앵커가 방이라 이 슬롯 경로를 쓰고,
                    편집기는 부스를 편집하므로 `GET /api/v1/booths/{boothId}/layouts/published` 를 쓴다 —
                    **응답 본문은 완전히 같다** (spec 005 계약 §11).

                    작업본(Draft)은 여기로 나오지 않는다. 방문자에게 보이는 것은 공개본뿐이다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "공개된 배치"),
            @ApiResponse(responseCode = "404", description = "`BOOTH_SLOT_NOT_FOUND`(그런 슬롯이 없다) 또는 `LAYOUT_NOT_PUBLISHED`(점유한 부스가 아직 공개한 배치가 없다)")})
    @GetMapping("/{slotId}/layouts/published")
    public BoothLayoutQueryService.PublishedView published(
            @Parameter(description = "슬롯 식별자. `GET /api/v1/booth-slots` 의 `slotId`", example = "5")
            @PathVariable Long slotId) {
        return layouts.findPublishedBySlot(slotId);
    }

    @Operation(summary = "부스 임대 — 코인을 내고 빈 자리를 24시간 차지한다",
            description = """
                    빈 슬롯을 코인으로 임대하고 그 자리에 내 부스를 붙인다. 회원 전용이며 게스트는 `403` 이다.

                    **차감과 임대가 하나의 트랜잭션이다.** 둘 중 하나라도 실패하면 둘 다 취소된다 —
                    "코인만 사라지고 부스는 못 얻는" 상태가 생기지 않는다 (spec 003 FR-003). 잔액 부족으로 거부된
                    요청도 코인을 건드리지 않는다.

                    **기간은 24시간 고정이다.** `durationDays` 는 1만 허용하고, 본문을 생략하면 1로 본다.
                    P0 에는 연장이 없다 (D02·D05).

                    **1인 1부스다** (D01). 활성 임대가 있는 회원이 다른 자리를 요청하면 `409 ACTIVE_LEASE_LIMIT` 이며,
                    만료된 뒤에 다시 임대할 수 있다.

                    **재시도가 안전하다.** 이미 내가 임차인인 슬롯에 같은 요청을 보내면 `201` 이 아니라 **`200`** 과 함께
                    기존 임대가 돌아오고 **추가 차감이 없다** (FR-018). 네트워크 타임아웃 뒤 재시도가 이중 결제로
                    이어지지 않는다.

                    **동시 요청**에서는 같은 빈 슬롯에 여러 명이 동시에 요청해도 정확히 한 명만 `201` 을 받고,
                    나머지는 트랜잭션이 롤백되어 코인이 그대로다.

                    임대 후 `GET /api/v1/wallets/me/transactions` 에 `LEASE_PAYMENT` 항목이 남는다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "임대 성공. 차감액과 차감 후 잔액이 함께 온다"),
            @ApiResponse(responseCode = "200", description = "**내가 이미 그 슬롯의 임차인이다.** 기존 임대를 그대로 돌려주고 추가 차감하지 않는다"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — `durationDays` 가 1이 아니다"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY` — 게스트 토큰이다"),
            @ApiResponse(responseCode = "404", description = "`BOOTH_SLOT_NOT_FOUND`(그런 슬롯이 없다) 또는 `WALLET_NOT_FOUND`(회원인데 지갑 행이 없다 — 정상 상태가 아니며 서버 로그에 근거가 남는다)"),
            @ApiResponse(responseCode = "409", description = """
                    사유가 네 가지이고 **`code` 로만 구분된다** — 상태 코드로 분기하면 안 된다.

                    | `code` | 뜻 |
                    |---|---|
                    | `BOOTH_SLOT_NOT_RENTABLE` | 임대용 자리가 아니거나 운영상 잠긴 자리다 |
                    | `BOOTH_SLOT_ALREADY_LEASED` | **다른 사람이** 임대 중이다. 다른 자리를 고르면 된다 |
                    | `ACTIVE_LEASE_LIMIT` | **내게** 이미 활성 임대가 있다. 자리를 바꿔도 안 되고 만료를 기다려야 한다 |
                    | `INSUFFICIENT_COIN` | 잔액 부족. 메시지에 필요액과 잔액이 들어 있고 **코인은 차감되지 않았다** |
                    """)})
    @PostMapping("/{slotId}/leases")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<LeaseResponse> lease(@AuthenticationPrincipal Jwt jwt,
                                               @Parameter(description = "임대할 슬롯. 목록에서 `status=AVAILABLE` 인 것을 고른다", example = "5")
                                               @PathVariable Long slotId,
                                               @RequestBody(required = false) LeaseRequest request) {
        Long userId = BoothPrincipal.requireMemberId(jwt);
        int durationDays = request == null || request.durationDays() == null ? 1 : request.durationDays();
        try {
            BoothLeaseService.LeaseOutcome outcome = leases.lease(userId, slotId, durationDays);
            HttpStatus status = outcome.alreadyHeld() ? HttpStatus.OK : HttpStatus.CREATED;
            return ResponseEntity.status(status).body(LeaseResponse.of(outcome));
        } catch (IllegalArgumentException exception) {
            // The only refusal still translated here. It is an argument check, not a domain event:
            // there is no ErrorCode for "durationDays was not 1" and no second caller to share one
            // with. Every domain refusal below it now carries its own code (S15P21A604-402).
            throw new ApiException(ErrorCode.VALIDATION_FAILED, exception.getMessage());
        }
    }

    @Schema(description = "본문을 생략해도 된다 — 생략하면 1일로 본다")
    public record LeaseRequest(
            @Schema(description = "임대 일수. **1만 허용**한다 (P0 는 24시간 고정, 연장 없음)",
                    allowableValues = {"1"}, defaultValue = "1", example = "1")
            Integer durationDays) { }

    public record LeaseResponse(
            @Schema(description = "생성된(또는 이미 갖고 있던) 임대 식별자", example = "301") Long leaseId,
            @Schema(description = "이 임대로 자리에 붙은 내 부스 식별자. 이후 배치·프로젝트·AI API 의 `boothId` 다", example = "7") Long boothId,
            @Schema(description = "임대한 슬롯", example = "5") Long slotId,
            @Schema(description = "임대 시작 시각(UTC)", example = "2026-09-02T05:00:00Z") Instant startsAt,
            @Schema(description = "임대 종료 시각(UTC). 시작 + 24시간이다", example = "2026-09-03T05:00:00Z") Instant endsAt,
            @Schema(description = "남은 시간(초). 응답을 만든 순간 기준이라 클라이언트가 다시 계산하지 않아도 된다", example = "86400") long remainingSeconds,
            @Schema(description = "이번 요청으로 차감된 코인. **재시도(200) 응답에서는 처음 차감한 금액**이며 두 번 빠진 것이 아니다", example = "100") int chargedCoin,
            @Schema(description = "차감 후 잔액. `GET /api/v1/wallets/me` 를 다시 부르지 않아도 된다", example = "150") int balanceAfter) {

        static LeaseResponse of(BoothLeaseService.LeaseOutcome outcome) {
            BoothLease lease = outcome.lease();
            return new LeaseResponse(lease.getId(), lease.getBoothId(), lease.getSlotId(), lease.getStartsAt(),
                    lease.getEndsAt(), lease.remainingSecondsAt(Instant.now()), lease.getChargedCoin(),
                    outcome.balanceAfter());
        }
    }
}
