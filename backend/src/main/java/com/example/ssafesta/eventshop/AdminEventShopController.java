package com.example.ssafesta.eventshop;

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
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Administrator surface for the event shop — register prizes, work the purchase queue. */
@RestController
@RequestMapping("/api/v1/admin/event-shop")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin")
public class AdminEventShopController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminGuard guard;
    private final AdminEventShopService shop;

    public AdminEventShopController(AdminGuard guard, AdminEventShopService shop) {
        this.guard = guard;
        this.shop = shop;
    }

    @Operation(summary = "경품 목록 (관리자) — 판매 중단분 포함",
            description = "회원용 목록과 달리 `active=false` 인 경품도 함께 돌려준다 — 운영자가 판매를 다시 열 대상을 봐야 한다.")
    @GetMapping("/prizes")
    public List<AdminEventShopService.AdminPrizeView> prizes(@AuthenticationPrincipal Jwt jwt) {
        guard.requireAdmin(jwt);
        return shop.listPrizes();
    }

    @Operation(summary = "경품 등록", description = "이름·가격·재고를 정해 새 경품을 만든다. `stock` 을 생략하면 무제한이다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "등록된 경품"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — 이름이 비었거나 200자 초과, 가격·재고가 음수"),
            @ApiResponse(responseCode = "403", description = "관리자 아님")})
    @PostMapping("/prizes")
    public AdminEventShopService.AdminPrizeView createPrize(@AuthenticationPrincipal Jwt jwt,
                                                             @RequestBody PrizeRequest request) {
        Long actor = guard.requireAdmin(jwt);
        return shop.createPrize(actor, request.name(), request.priceCoin(), request.stock());
    }

    @Operation(summary = "경품 수정 — 판매 재개·중단 포함", description = "이름·가격·재고·판매 여부를 한 번에 덮어쓴다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정된 경품"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED`"),
            @ApiResponse(responseCode = "403", description = "관리자 아님"),
            @ApiResponse(responseCode = "404", description = "`EVENT_PRIZE_NOT_FOUND`")})
    @PutMapping("/prizes/{prizeId}")
    public AdminEventShopService.AdminPrizeView updatePrize(@AuthenticationPrincipal Jwt jwt,
                                                             @PathVariable Long prizeId,
                                                             @RequestBody PrizeRequest request) {
        Long actor = guard.requireAdmin(jwt);
        return shop.updatePrize(actor, prizeId, request.name(), request.priceCoin(), request.stock(),
                request.active() == null || request.active());
    }

    @Operation(summary = "구매 내역 조회", description = "`status` 를 생략하면 전체 상태를 최신순으로 돌려준다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "구매 내역 페이지"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — page·size·status 값 오류"),
            @ApiResponse(responseCode = "403", description = "관리자 아님")})
    @GetMapping("/purchases")
    public PurchasePageResponse purchases(@AuthenticationPrincipal Jwt jwt,
                                          @Parameter(description = "PURCHASED · PENDING · FULFILLED · CANCELLED")
                                          @RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        guard.requireAdmin(jwt);
        validatePage(page, size);
        PurchaseFulfillment filter = parseStatus(status);
        Page<AdminEventShopService.AdminPurchaseView> result =
                shop.listPurchases(filter, PageRequest.of(page, size));
        return new PurchasePageResponse(result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @Operation(summary = "구매 처리 상태 변경",
            description = "PURCHASED → PENDING/FULFILLED/CANCELLED, PENDING → FULFILLED/CANCELLED 만 허용한다. "
                    + "FULFILLED·CANCELLED 는 종단 상태라 되돌릴 수 없다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "갱신된 구매 내역"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — `status` 가 알 수 없는 값이다"),
            @ApiResponse(responseCode = "403", description = "관리자 아님"),
            @ApiResponse(responseCode = "404", description = "그런 구매 내역이 없다"),
            @ApiResponse(responseCode = "409", description = "`EVENT_PURCHASE_FULFILLMENT_INVALID` — 허용되지 않는 전이")})
    @PostMapping("/purchases/{purchaseId}/fulfillment")
    public AdminEventShopService.AdminPurchaseView fulfillment(@AuthenticationPrincipal Jwt jwt,
                                                                @PathVariable Long purchaseId,
                                                                @RequestBody FulfillmentRequest request) {
        Long actor = guard.requireAdmin(jwt);
        PurchaseFulfillment next = parseStatus(request.status());
        if (next == null) {
            throw ApiException.fieldInvalid("status", "필수입니다.");
        }
        return shop.updateFulfillment(actor, purchaseId, next, request.note());
    }

    private static PurchaseFulfillment parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return PurchaseFulfillment.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException notAStatus) {
            throw ApiException.fieldInvalid("status", "PURCHASED · PENDING · FULFILLED · CANCELLED 중 하나여야 합니다.");
        }
    }

    private static void validatePage(int page, int size) {
        if (page < 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "page는 0 이상이어야 합니다.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "size는 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다.");
        }
    }

    public record PrizeRequest(
            @Schema(description = "경품 이름", example = "무선 이어폰") String name,
            @Schema(description = "가격(코인)", example = "500") int priceCoin,
            @Schema(description = "재고. 생략하면 무제한", example = "10", nullable = true) Integer stock,
            @Schema(description = "판매 여부. 생성 시 생략하면 true", example = "true", nullable = true) Boolean active) {
    }

    public record FulfillmentRequest(
            @Schema(description = "PURCHASED · PENDING · FULFILLED · CANCELLED", example = "FULFILLED") String status,
            @Schema(description = "처리 메모", example = "현장 수령 완료") String note) {
    }

    public record PurchasePageResponse(List<AdminEventShopService.AdminPurchaseView> content, int page, int size,
                                       long totalElements, int totalPages) {
    }
}
