package com.example.ssafesta.inventory;

import com.example.ssafesta.common.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Exposes catalog availability and transactional purchases to Unity and web clients. */
@RestController
@RequestMapping("/api/v1/catalog/items")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Inventory")
public class CatalogController {

    private static final String MEMBER_ONLY = "회원 계정만 아이템을 구매할 수 있습니다.";

    private final InventoryService inventory;

    public CatalogController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @Operation(summary = "상점 목록 조회 — 보유 여부까지 함께",
            description = """
                    구매할 수 있는 아이템과 **내가 이미 가졌는지**를 한 번에 돌려준다. 화면이 목록과 보유 상태를
                    따로 두 번 물을 필요가 없다.

                    `type` 은 필수 질의 파라미터다 — 아바타 파츠는 `AVATAR_PART` 다.

                    아바타 외형 저장(`PUT /api/v1/users/me/avatar`)은 **보유하지 않은 파츠를 거부**하므로,
                    착용 화면은 이 목록의 보유 여부를 근거로 선택 가능 여부를 그린다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "아이템 목록과 보유 여부"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — `type` 이 없거나 알 수 없는 값이다")})
    @GetMapping
    public CatalogResponse catalog(@AuthenticationPrincipal Jwt jwt,
                                   @Parameter(description = "아이템 종류", example = "AVATAR_PART", required = true)
                                   @RequestParam String type) {
        return new CatalogResponse(inventory.catalogWithOwnership(MemberPrincipal.optionalMemberId(jwt), type));
    }

    @Operation(summary = "아이템 구매 — 코인이 차감된다",
            description = """
                    아이템 하나를 코인으로 구매한다. **차감과 보유 기록이 한 트랜잭션**이라 코인만 빠지고 아이템이
                    없는 상태가 생기지 않는다.

                    가격은 서버가 정한다 — 요청에 금액을 넣지 않는다 (헌법 2·16조).

                    이미 가진 아이템을 다시 사면 `409` 이고 **코인은 차감되지 않는다.** 잔액이 부족해도 마찬가지다.

                    구매 후 `GET /api/v1/wallets/me/transactions` 에 차감 항목이 남는다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "구매 성공. 구매한 아이템 정보"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY` — 게스트는 구매할 수 없다"),
            @ApiResponse(responseCode = "404", description = "`CATALOG_ITEM_NOT_FOUND`(그런 아이템이 없다) 또는 `WALLET_NOT_FOUND`(회원인데 지갑 행이 없다 — 정상 상태가 아니며 서버 로그에 근거가 남는다)"),
            @ApiResponse(responseCode = "409", description = "이미 보유한 아이템이거나 `INSUFFICIENT_COIN`(잔액 부족). 어느 경우든 코인은 차감되지 않는다")})
    @PostMapping("/{itemId}/purchases")
    public ResponseEntity<InventoryService.CatalogItemView> purchase(@AuthenticationPrincipal Jwt jwt,
                                                                     @Parameter(description = "구매할 아이템 식별자", example = "12")
                                                                     @PathVariable Long itemId) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        return ResponseEntity.status(HttpStatus.CREATED).body(inventory.purchase(userId, itemId));
    }

    public record CatalogResponse(List<InventoryService.CatalogItemView> items) { }
}
