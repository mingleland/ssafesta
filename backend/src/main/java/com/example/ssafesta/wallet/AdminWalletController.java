package com.example.ssafesta.wallet;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.user.AdminGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrator access to another member's wallet (S15P21A604-806, spec 003 FR-013).
 *
 * <p>Separate from {@code AdminController}, which owns the roster under
 * {@code /api/v1/admin/admins}. That one lives in the user package and answers who may act; this
 * one is the wallet's own administrative surface.
 *
 * <p>Reading is not acting, so balance and ledger are allowed for every target including the
 * master. Adjustment is refused for a master target — except by the master itself, which may
 * adjust its own wallet.
 */
@RestController
@RequestMapping("/api/v1/admin/wallets")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin")
public class AdminWalletController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminGuard guard;
    private final AdminWalletOperationService operations;
    private final WalletService wallets;

    public AdminWalletController(AdminGuard guard, AdminWalletOperationService operations,
                                 WalletService wallets) {
        this.guard = guard;
        this.operations = operations;
        this.wallets = wallets;
    }

    @Operation(summary = "코인 지급·회수 (관리자 조정)",
            description = "회원의 코인을 더하거나 뺀다. `signedAmount` 가 **양수면 지급, 음수면 회수**이고 0 은 거절한다. "
                    + "원장에 `ADMIN_ADJUSTMENT` 사유로 한 행이 남으며, 참조 칸에는 **수행한 관리자**가 들어간다.\n\n"
                    + "### 재시도는 `Idempotency-Key` 로 한다\n\n"
                    + "헤더에 **UUID** 를 담는다. 이 값은 *조정 한 건*의 이름이라, 같은 조정을 다시 보낼 때는 "
                    + "**같은 값을 그대로** 보내야 한다. 매번 새로 만들면 재시도가 아니라 새 조정이 되어 두 번 반영된다.\n\n"
                    + "- 같은 키 + 같은 내용 → 아무 일도 일어나지 않고 처음 결과를 돌려준다 (`alreadyApplied: true`)\n"
                    + "- 같은 키 + 다른 금액 → `409 IDEMPOTENCY_CONFLICT`. 재시도가 아니라 키 재사용이다\n"
                    + "- `note` 는 이 비교에 넣지 않는다. 자유 서술이고 원장에 저장되지 않아 비교할 근거가 없다 — "
                    + "다른 문구로 다시 보내도 충돌이 아니며 **처음 것이 남는다**\n\n"
                    + "멱등 범위는 **대상 회원별**이다. 대상이 다르면 같은 UUID 를 써도 별개의 조정이다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조정 결과와 조정 후 잔액"),
            @ApiResponse(responseCode = "400",
                    description = "`VALIDATION_FAILED` — `signedAmount` 가 0 이거나 `Idempotency-Key` 가 UUID 가 아니다"),
            @ApiResponse(responseCode = "403",
                    description = "`FORBIDDEN`(관리자가 아니다) 또는 `MASTER_PROTECTED`(대상이 마스터다 — 단, 마스터 본인의 자기 조정은 허용)"),
            @ApiResponse(responseCode = "404",
                    description = "`ADMIN_TARGET_NOT_FOUND`(회원이 없다) 또는 `WALLET_NOT_FOUND`(회원인데 지갑 행이 없다)"),
            @ApiResponse(responseCode = "409",
                    description = "`IDEMPOTENCY_CONFLICT`(같은 키에 다른 요청) · `INSUFFICIENT_COIN`(잔액보다 많이 회수) "
                            + "· `COIN_BALANCE_OVERFLOW`(지급 후 잔액이 표현 범위를 넘는다)")})
    @PostMapping("/{userId}/adjustments")
    public AdjustmentResponse adjust(@AuthenticationPrincipal Jwt jwt,
                                     @Parameter(description = "조정 대상 회원", example = "12")
                                     @PathVariable Long userId,
                                     @Parameter(description = "이 조정 한 건의 UUID. 재시도에 같은 값을 다시 보낸다",
                                             example = "3f1b2c44-0a7e-4f6b-9a11-2c5d8e7f0a31")
                                     @RequestHeader("Idempotency-Key") String idempotencyKey,
                                     @RequestBody AdjustmentRequest request) {
        Long actorUserId = guard.requireAdmin(jwt);
        String operationId = requireUuid(idempotencyKey);
        if (request == null || request.signedAmount() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "signedAmount는 필수입니다.");
        }
        if (request.signedAmount() == 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "조정 금액은 0일 수 없습니다.");
        }

        LedgerResult result = operations.adjust(userId, actorUserId, request.signedAmount(),
                request.note(), operationId);
        return new AdjustmentResponse(userId, result.entryId(), result.balanceAfter(),
                result.alreadyApplied());
    }

    @Operation(summary = "회원 코인 잔액 조회",
            description = "조회는 조치가 아니라서 마스터 계정도 대상이 된다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "현재 잔액"),
            @ApiResponse(responseCode = "403", description = "`FORBIDDEN` — 관리자가 아니다"),
            @ApiResponse(responseCode = "404", description = "`WALLET_NOT_FOUND` — 지갑 행이 없다")})
    @GetMapping("/{userId}")
    public BalanceResponse balance(@AuthenticationPrincipal Jwt jwt,
                                   @Parameter(description = "조회 대상 회원", example = "12")
                                   @PathVariable Long userId) {
        guard.requireAdmin(jwt);
        Wallet wallet = wallets.requireWallet(userId);
        return new BalanceResponse(userId, wallet.getBalance(), wallet.getUpdatedAt());
    }

    @Operation(summary = "회원 코인 거래 내역 조회 (최신순 페이지)",
            description = "회원 본인이 보는 것과 같은 원장을 관리자가 본다. `amount` 는 **지급이 양수, 차감이 음수**이고 "
                    + "합계는 항상 잔액과 같다. 페이지는 `page`(0부터)·`size`(1~100)다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "최신순 한 페이지와 전체 개수"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — `page` 가 음수이거나 `size` 가 1~100 밖이다"),
            @ApiResponse(responseCode = "403", description = "`FORBIDDEN` — 관리자가 아니다"),
            @ApiResponse(responseCode = "404", description = "`WALLET_NOT_FOUND` — 지갑 행이 없다")})
    @GetMapping("/{userId}/ledger")
    public LedgerPageResponse ledger(@AuthenticationPrincipal Jwt jwt,
                                     @Parameter(description = "조회 대상 회원", example = "12")
                                     @PathVariable Long userId,
                                     @Parameter(description = "0부터 시작하는 페이지 번호", example = "0")
                                     @RequestParam(defaultValue = "0") int page,
                                     @Parameter(description = "한 페이지 크기. 1~100", example = "20")
                                     @RequestParam(defaultValue = "20") int size) {
        guard.requireAdmin(jwt);
        if (page < 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "page는 0 이상이어야 합니다.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "size는 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다.");
        }
        wallets.requireWallet(userId);
        Page<CoinLedgerEntryView> entries = wallets.history(userId, PageRequest.of(page, size));
        return new LedgerPageResponse(entries.getContent(), entries.getNumber(), entries.getSize(),
                entries.getTotalElements(), entries.getTotalPages());
    }

    /**
     * Rejects a non-UUID key here rather than letting it reach the database. The ledger column is
     * {@code VARCHAR(100)}, so a long free-form key fails the constraint and surfaces as a 500 that
     * looks like a server fault when it is in fact a malformed request.
     */
    private static String requireUuid(String value) {
        try {
            return UUID.fromString(value.trim()).toString();
        } catch (IllegalArgumentException | NullPointerException notUuid) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key는 UUID여야 합니다.");
        }
    }

    public record AdjustmentRequest(
            @Schema(description = "양수면 지급, 음수면 회수. 0은 거절한다", example = "100") Integer signedAmount,
            @Schema(description = "조정 사유. 감사 기록에 그대로 남는다", example = "이벤트 보상 누락 보정")
            @Size(max = 500) String note) { }

    public record AdjustmentResponse(
            @Schema(description = "조정 대상 회원", example = "12") Long userId,
            @Schema(description = "원장 항목 식별자", example = "3391") Long entryId,
            @Schema(description = "조정 후 잔액", example = "350") int balanceAfter,
            @Schema(description = "같은 키로 이미 처리된 요청이라 이번에는 아무 일도 하지 않았다", example = "false")
            boolean alreadyApplied) { }

    public record BalanceResponse(
            @Schema(description = "지갑 주인", example = "12") Long userId,
            @Schema(description = "현재 잔액", example = "250") int balance,
            @Schema(description = "마지막 변경 시각(UTC)", example = "2026-09-15T05:00:00Z") Instant updatedAt) { }

    public record LedgerPageResponse(List<CoinLedgerEntryView> content, int page, int size,
                                     long totalElements, int totalPages) { }
}
