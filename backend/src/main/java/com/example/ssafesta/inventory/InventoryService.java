package com.example.ssafesta.inventory;

import com.example.ssafesta.common.ApiErrorDetail;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.CoinReason;
import com.example.ssafesta.wallet.CoinSpendCommand;
import com.example.ssafesta.wallet.WalletService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns catalog ownership decisions so purchase, palette and avatar save cannot disagree. */
@Service
public class InventoryService {

    public static final String AVATAR_PART = "AVATAR_PART";
    private static final String PURCHASE_REFERENCE_TYPE = "CATALOG_ITEM";

    private final CatalogItemRepository catalog;
    private final UserInventoryItemRepository inventory;
    private final UserRepository users;
    private final WalletService wallets;

    public InventoryService(CatalogItemRepository catalog, UserInventoryItemRepository inventory,
                            UserRepository users, WalletService wallets) {
        this.catalog = catalog;
        this.inventory = inventory;
        this.users = users;
        this.wallets = wallets;
    }

    @Transactional(readOnly = true)
    public List<CatalogItemView> catalogWithOwnership(Long userId, String itemType) {
        if (itemType == null || itemType.isBlank()) {
            throw ApiException.fieldInvalid("type", "카탈로그 품목 유형을 입력해 주세요.");
        }
        Set<Long> ownedIds = userId == null
                ? Set.of()
                : Set.copyOf(inventory.findCatalogItemIdsByUserId(userId));
        return catalog.findAllByItemTypeOrderById(itemType).stream()
                .map(item -> CatalogItemView.of(item, item.getPrice() == 0 || ownedIds.contains(item.getId())))
                .toList();
    }

    /**
     * Serializes purchases per member by the wallet lock before checking ownership. Without that
     * ordering two requests can both see "not owned", one reuse the other's ledger key, and then
     * collide on the inventory unique constraint instead of returning ITEM_ALREADY_OWNED.
     */
    @Transactional
    public CatalogItemView purchase(Long userId, Long itemId) {
        if (!users.existsById(userId)) {
            throw new ApiException(ErrorCode.USER_NOT_FOUND);
        }
        CatalogItem item = catalog.findById(itemId)
                .orElseThrow(() -> new ApiException(ErrorCode.CATALOG_ITEM_NOT_FOUND));
        if (!item.isOnSale()) {
            throw new ApiException(ErrorCode.ITEM_NOT_ON_SALE);
        }

        wallets.lockOwner(userId);
        if (item.getPrice() == 0 || inventory.existsByUserIdAndCatalogItemId(userId, itemId)) {
            throw new ApiException(ErrorCode.ITEM_ALREADY_OWNED);
        }
        wallets.spend(new CoinSpendCommand(userId, item.getPrice(), CoinReason.PURCHASE,
                PURCHASE_REFERENCE_TYPE, itemId.toString(), purchaseKey(userId, itemId)));
        inventory.save(new UserInventoryItem(userId, itemId));
        return CatalogItemView.of(item, true);
    }

    @Transactional(readOnly = true)
    public void requireOwned(Long userId, List<String> assetKeys) {
        LinkedHashSet<String> claimed = new LinkedHashSet<>(assetKeys);
        if (claimed.isEmpty()) {
            return;
        }
        Map<String, CatalogItem> itemsByAssetKey = catalog
                .findAllByItemTypeAndAssetKeyIn(AVATAR_PART, claimed).stream()
                .collect(Collectors.toMap(CatalogItem::getAssetKey, Function.identity()));
        Set<Long> ownedIds = Set.copyOf(inventory.findCatalogItemIdsByUserId(userId));
        List<String> missing = claimed.stream()
                .filter(assetKey -> {
                    CatalogItem item = itemsByAssetKey.get(assetKey);
                    return item == null || item.getPrice() != 0 && !ownedIds.contains(item.getId());
                })
                .toList();
        if (!missing.isEmpty()) {
            List<ApiErrorDetail> details = missing.stream()
                    .map(assetKey -> ApiErrorDetail.of("ITEM_NOT_OWNED", assetKey,
                            "보유하지 않은 아바타 파츠입니다: " + assetKey))
                    .toList();
            throw new ApiException(ErrorCode.AVATAR_ITEM_NOT_OWNED,
                    ErrorCode.AVATAR_ITEM_NOT_OWNED.defaultMessage(), details, null);
        }
    }

    static String purchaseKey(Long userId, Long itemId) {
        return CoinReason.PURCHASE + ":" + userId + ":" + itemId;
    }

    public record CatalogItemView(Long itemId, String code, String name, String equipSlot,
                                  String assetKey, int price, boolean onSale, boolean owned) {
        static CatalogItemView of(CatalogItem item, boolean owned) {
            return new CatalogItemView(item.getId(), item.getItemCode(), item.getName(), item.getEquipSlot(),
                    item.getAssetKey(), item.getPrice(), item.isOnSale(), owned);
        }
    }
}
