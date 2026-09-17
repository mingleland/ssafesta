package com.example.ssafesta.eventshop;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.eventshop.ws.EventShopEventPublisher;
import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.CoinReason;
import com.example.ssafesta.wallet.CoinSpendCommand;
import com.example.ssafesta.wallet.LedgerResult;
import com.example.ssafesta.wallet.WalletService;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The member-facing half of the event shop — browse and buy (S15P21A604-836, GitLab #217 4번).
 *
 * <p>Mirrors {@code InventoryService.purchase}'s shape (lock the wallet first, then check the
 * business state, then spend), but a prize is not a one-per-user item: the same member may buy the
 * same prize more than once, so the idempotency key cannot be derived from
 * {@code (userId, prizeId)} the way catalog purchase does — a legitimate second order would be
 * swallowed as a retry of the first. The caller supplies an operation id instead, the same shape
 * {@code AdminWalletController}'s {@code Idempotency-Key} uses.
 */
@Service
public class EventShopService {

    private final EventPrizeRepository prizes;
    private final EventPurchaseRepository purchases;
    private final UserRepository users;
    private final WalletService wallets;
    private final EventShopEventPublisher events;

    public EventShopService(EventPrizeRepository prizes, EventPurchaseRepository purchases,
                            UserRepository users, WalletService wallets, EventShopEventPublisher events) {
        this.prizes = prizes;
        this.purchases = purchases;
        this.users = users;
        this.wallets = wallets;
        this.events = events;
    }

    /** Prizes a member may buy right now — inactive prizes are not merchandise. */
    @Transactional(readOnly = true)
    public List<PrizeView> list() {
        return prizes.findAllByActiveTrueOrderByIdAsc().stream().map(PrizeView::of).toList();
    }

    /**
     * @param operationId this purchase's identity, reused verbatim on every retry of the same
     *                    attempt — a fresh value per retry charges twice and double-spends stock
     * @throws ApiException {@code EVENT_PRIZE_NOT_FOUND}, {@code EVENT_PRIZE_INACTIVE},
     *                      {@code EVENT_PRIZE_OUT_OF_STOCK}, {@code INSUFFICIENT_COIN}, or
     *                      {@code IDEMPOTENCY_CONFLICT} if the same key names a different prize or
     *                      quantity
     */
    @Transactional
    public PurchaseView purchase(Long userId, Long prizeId, int quantity, String operationId) {
        if (quantity < 1) {
            throw ApiException.fieldInvalid("quantity", "1 이상이어야 합니다.");
        }
        String idempotencyKey = purchaseKey(userId, operationId);

        // Wallet lock first, matching InventoryService.purchase — every coin-spending purchase in
        // this codebase serializes on the buyer's wallet before touching the thing being bought.
        //
        // The idempotency replay check has to happen AFTER this lock, not before: two truly
        // concurrent requests with the same key would otherwise both see "not recorded yet", both
        // proceed, and the second would collide on event_purchases' unique idempotency_key
        // constraint instead of returning the first request's result. Locking first serializes them,
        // so the second request's check runs only after the first has committed and is visible.
        wallets.lockOwner(userId);

        var replay = purchases.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            EventPurchase existing = replay.get();
            if (!existing.getBuyerUserId().equals(userId) || !existing.getPrizeId().equals(prizeId)
                    || existing.getQuantity() != quantity) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT);
            }
            return viewOf(existing, prizeNameOf(existing.getPrizeId()));
        }

        EventPrize prize = prizes.findByIdForUpdate(prizeId)
                .orElseThrow(() -> new ApiException(ErrorCode.EVENT_PRIZE_NOT_FOUND));
        if (!prize.isActive()) {
            throw new ApiException(ErrorCode.EVENT_PRIZE_INACTIVE);
        }
        if (!prize.reserve(quantity)) {
            throw new ApiException(ErrorCode.EVENT_PRIZE_OUT_OF_STOCK);
        }

        int coinSpent = prize.getPriceCoin() * quantity;
        LedgerResult result = wallets.spend(new CoinSpendCommand(userId, coinSpent, CoinReason.PRIZE_PURCHASE,
                CoinReason.EVENT_PRIZE_REFERENCE_TYPE, prizeId.toString(), idempotencyKey));

        Instant now = Instant.now();
        EventPurchase saved = purchases.save(new EventPurchase(prizeId, userId, quantity, coinSpent,
                result.entryId(), idempotencyKey, now));

        String buyerNickname = users.findById(userId).map(User::getNickname).orElse(null);
        events.purchased(saved.getId(), prizeId, prize.getName(), buyerNickname, quantity, coinSpent, now);

        return viewOf(saved, prize.getName());
    }

    private String prizeNameOf(Long prizeId) {
        return prizes.findById(prizeId).map(EventPrize::getName).orElse(null);
    }

    private static PurchaseView viewOf(EventPurchase purchase, String prizeName) {
        return new PurchaseView(purchase.getId(), purchase.getPrizeId(), prizeName, purchase.getQuantity(),
                purchase.getCoinSpent(), purchase.getFulfillment().name(), purchase.getPurchasedAt());
    }

    /** {@code PRIZE_PURCHASE:{userId}:{operationId}} — idempotency scoped per buyer. */
    static String purchaseKey(Long userId, String operationId) {
        return CoinReason.PRIZE_PURCHASE + ":" + userId + ":" + operationId;
    }

    public record PrizeView(Long prizeId, String name, int priceCoin, Integer stock, boolean active) {
        static PrizeView of(EventPrize prize) {
            return new PrizeView(prize.getId(), prize.getName(), prize.getPriceCoin(), prize.getStock(),
                    prize.isActive());
        }
    }

    public record PurchaseView(Long purchaseId, Long prizeId, String prizeName, int quantity, int coinSpent,
                               String fulfillment, Instant purchasedAt) {
    }
}
