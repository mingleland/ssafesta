package com.example.ssafesta.inventory;

import com.example.ssafesta.common.MemberPrincipal;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
public class CatalogController {

    private static final String MEMBER_ONLY = "회원 계정만 아이템을 구매할 수 있습니다.";

    private final InventoryService inventory;

    public CatalogController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @GetMapping
    public CatalogResponse catalog(@AuthenticationPrincipal Jwt jwt, @RequestParam String type) {
        return new CatalogResponse(inventory.catalogWithOwnership(MemberPrincipal.optionalMemberId(jwt), type));
    }

    @PostMapping("/{itemId}/purchases")
    public ResponseEntity<InventoryService.CatalogItemView> purchase(@AuthenticationPrincipal Jwt jwt,
                                                                     @PathVariable Long itemId) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        return ResponseEntity.status(HttpStatus.CREATED).body(inventory.purchase(userId, itemId));
    }

    public record CatalogResponse(List<InventoryService.CatalogItemView> items) { }
}
