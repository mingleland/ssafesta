package com.example.ssafesta.eventshop;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.common.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The event shop's member-facing surface — browse and buy (S15P21A604-836, GitLab #217 4번). */
@RestController
@RequestMapping("/api/v1/event-shop")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Event Shop")
public class EventShopController {

    private static final String MEMBER_ONLY = "회원 계정만 이벤트 상점을 이용할 수 있습니다.";

    private final EventShopService shop;

    public EventShopController(EventShopService shop) {
        this.shop = shop;
    }

    @Operation(summary = "경품 목록 조회", description = "지금 구매할 수 있는 경품만 돌려준다 — 판매 중단된 경품은 목록에 없다.")
    @GetMapping("/prizes")
    public PrizeListResponse prizes() {
        return new PrizeListResponse(shop.list());
    }

    @Operation(summary = "경품 구매 — 코인이 차감된다",
            description = """
                    경품을 코인으로 구매한다. 재고 차감과 코인 차감이 한 트랜잭션이라 코인만 빠지고
                    구매 기록이 없는 상태가 생기지 않는다.

                    ### 재시도는 `Idempotency-Key` 로 한다

                    헤더에 **UUID** 를 담는다. 같은 구매 시도를 다시 보낼 때는 **같은 값을 그대로**
                    보내야 한다 — 매번 새로 만들면 재시도가 아니라 새 구매가 되어 두 번 차감된다.

                    - 같은 키 + 같은 내용(경품·수량) → 아무 일도 일어나지 않고 처음 결과를 돌려준다
                    - 같은 키 + 다른 경품·수량 → `409 IDEMPOTENCY_CONFLICT`

                    가격은 서버가 정한다 — 요청에 금액을 넣지 않는다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "구매 성공"),
            @ApiResponse(responseCode = "400",
                    description = "`VALIDATION_FAILED` — `quantity` 가 1 미만이거나 `Idempotency-Key` 가 UUID 가 아니다"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY` — 게스트는 구매할 수 없다"),
            @ApiResponse(responseCode = "404",
                    description = "`EVENT_PRIZE_NOT_FOUND`(그런 경품이 없다) 또는 `WALLET_NOT_FOUND`(회원인데 지갑 행이 없다)"),
            @ApiResponse(responseCode = "409",
                    description = "`EVENT_PRIZE_INACTIVE`(판매 중단) · `EVENT_PRIZE_OUT_OF_STOCK`(재고 부족) · "
                            + "`INSUFFICIENT_COIN`(잔액 부족) · `IDEMPOTENCY_CONFLICT`(같은 키에 다른 요청)")})
    @PostMapping("/purchases")
    public ResponseEntity<EventShopService.PurchaseView> purchase(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "이 구매 시도의 UUID. 재시도에 같은 값을 다시 보낸다") @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PurchaseRequest request) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        String operationId = requireUuid(idempotencyKey);
        if (request == null || request.prizeId() == null) {
            throw ApiException.fieldInvalid("prizeId", "필수입니다.");
        }
        int quantity = request.quantity() == null ? 1 : request.quantity();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(shop.purchase(userId, request.prizeId(), quantity, operationId));
    }

    /** 지갑 조정과 같은 이유로 여기서 자른다 — 원장 칼럼이 {@code VARCHAR(100)} 이라 UUID 가 아니면 500 으로 샌다. */
    private static String requireUuid(String value) {
        try {
            return UUID.fromString(value.trim()).toString();
        } catch (IllegalArgumentException | NullPointerException notUuid) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key는 UUID여야 합니다.");
        }
    }

    public record PrizeListResponse(List<EventShopService.PrizeView> prizes) { }

    public record PurchaseRequest(
            @Schema(description = "구매할 경품", example = "3") Long prizeId,
            @Schema(description = "수량. 생략하면 1", example = "1") Integer quantity) {
    }
}
