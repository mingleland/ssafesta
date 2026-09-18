package com.example.ssafesta.eventshop;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.user.AdminActionRecorder;
import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administrator side of the event shop — register prizes, work the purchase queue
 * (S15P21A604-836, GitLab #217 4번).
 *
 * <p>Every state change is audited in the same transaction, the same shape
 * {@code AdminAccountService} and {@code AdminWalletOperationService} use — a prize's price
 * changing or an order's fulfillment flipping is exactly the kind of thing "who did this and why"
 * has to answer later.
 */
@Service
public class AdminEventShopService {

    private final EventPrizeRepository prizes;
    private final EventPurchaseRepository purchases;
    private final UserRepository users;
    private final AdminActionRecorder audit;

    public AdminEventShopService(EventPrizeRepository prizes, EventPurchaseRepository purchases,
                                 UserRepository users, AdminActionRecorder audit) {
        this.prizes = prizes;
        this.purchases = purchases;
        this.users = users;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<AdminPrizeView> listPrizes() {
        return prizes.findAllByOrderByIdAsc().stream().map(AdminPrizeView::of).toList();
    }

    @Transactional
    public AdminPrizeView createPrize(Long actorUserId, String name, int priceCoin, Integer stock) {
        validatePrizeFields(name, priceCoin, stock);
        EventPrize saved = prizes.save(new EventPrize(name.trim(), priceCoin, stock));
        audit.record(actorUserId, AdminActionRecorder.PRIZE_CREATE, AdminActionRecorder.TARGET_EVENT_PRIZE,
                saved.getId(), name.trim());
        return AdminPrizeView.of(saved);
    }

    /** Edits every field at once, including {@code active} — there is no separate toggle endpoint. */
    @Transactional
    public AdminPrizeView updatePrize(Long actorUserId, Long prizeId, String name, int priceCoin, Integer stock,
                                      boolean active) {
        validatePrizeFields(name, priceCoin, stock);
        EventPrize prize = prizes.findById(prizeId)
                .orElseThrow(() -> new ApiException(ErrorCode.EVENT_PRIZE_NOT_FOUND));
        prize.update(name.trim(), priceCoin, stock, active);
        audit.record(actorUserId, AdminActionRecorder.PRIZE_UPDATE, AdminActionRecorder.TARGET_EVENT_PRIZE,
                prizeId, name.trim());
        return AdminPrizeView.of(prize);
    }

    @Transactional(readOnly = true)
    public Page<AdminPurchaseView> listPurchases(PurchaseFulfillment filter, Pageable pageable) {
        Page<EventPurchase> page = filter == null
                ? purchases.findAllByOrderByPurchasedAtDesc(pageable)
                : purchases.findAllByFulfillmentOrderByPurchasedAtDesc(filter, pageable);

        Map<Long, String> prizeNames = new HashMap<>();
        prizes.findAllById(page.getContent().stream().map(EventPurchase::getPrizeId).distinct().toList())
                .forEach(prize -> prizeNames.put(prize.getId(), prize.getName()));
        Map<Long, String> nicknames = new HashMap<>();
        users.findAllById(page.getContent().stream().map(EventPurchase::getBuyerUserId).distinct().toList())
                .forEach(user -> nicknames.put(user.getId(), user.getNickname()));

        return page.map(purchase -> decorate(purchase, prizeNames, nicknames));
    }

    /**
     * @throws ApiException {@code NOT_FOUND} no such purchase, {@code
     *                      EVENT_PURCHASE_FULFILLMENT_INVALID} the transition is not allowed from
     *                      the current state (see {@link PurchaseFulfillment#canTransitionTo})
     */
    @Transactional
    public AdminPurchaseView updateFulfillment(Long actorUserId, Long purchaseId, PurchaseFulfillment next,
                                               String note) {
        EventPurchase purchase = purchases.findById(purchaseId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!purchase.getFulfillment().canTransitionTo(next)) {
            throw new ApiException(ErrorCode.EVENT_PURCHASE_FULFILLMENT_INVALID);
        }
        purchase.transitionTo(next, note);
        audit.record(actorUserId, AdminActionRecorder.PRIZE_FULFILLMENT_UPDATE,
                AdminActionRecorder.TARGET_EVENT_PURCHASE, purchaseId, next.name());
        return decorate(purchase);
    }

    private void validatePrizeFields(String name, int priceCoin, Integer stock) {
        if (name == null || name.isBlank()) {
            throw ApiException.fieldInvalid("name", "경품 이름을 입력해 주세요.");
        }
        if (name.length() > 200) {
            throw ApiException.fieldInvalid("name", "경품 이름은 200자 이하여야 합니다.");
        }
        if (priceCoin < 0) {
            throw ApiException.fieldInvalid("priceCoin", "0 이상이어야 합니다.");
        }
        if (stock != null && stock < 0) {
            throw ApiException.fieldInvalid("stock", "0 이상이거나 무제한(생략)이어야 합니다.");
        }
    }

    /** One row, batched-lookup free — used where only a single purchase needs decorating. */
    private AdminPurchaseView decorate(EventPurchase purchase) {
        String prizeName = prizes.findById(purchase.getPrizeId()).map(EventPrize::getName).orElse(null);
        String buyerNickname = users.findById(purchase.getBuyerUserId()).map(User::getNickname).orElse(null);
        return viewOf(purchase, prizeName, buyerNickname);
    }

    /**
     * The same decoration, batched for a page of rows — one {@code IN} query each for prize names
     * and buyer nicknames instead of one query per row (the same shape
     * {@code AdminUserOperationService.summariesOf} uses for providers).
     */
    private AdminPurchaseView decorate(EventPurchase purchase, Map<Long, String> prizeNames,
                                       Map<Long, String> nicknames) {
        return viewOf(purchase, prizeNames.get(purchase.getPrizeId()), nicknames.get(purchase.getBuyerUserId()));
    }

    private static AdminPurchaseView viewOf(EventPurchase purchase, String prizeName, String buyerNickname) {
        PurchaseRecipient recipient = purchase.getRecipient();
        return new AdminPurchaseView(purchase.getId(), purchase.getPrizeId(), prizeName,
                purchase.getBuyerUserId(), buyerNickname, purchase.getQuantity(), purchase.getCoinSpent(),
                purchase.getLedgerEntryId(), purchase.getPurchasedAt(), purchase.getFulfillment().name(),
                purchase.getNote(), purchase.getUpdatedAt(), recipient.campus(), recipient.teamName(),
                recipient.recipientName());
    }

    public record AdminPrizeView(Long prizeId, String name, int priceCoin, Integer stock, boolean active,
                                 Instant createdAt, Instant updatedAt) {
        static AdminPrizeView of(EventPrize prize) {
            return new AdminPrizeView(prize.getId(), prize.getName(), prize.getPriceCoin(), prize.getStock(),
                    prize.isActive(), prize.getCreatedAt(), prize.getUpdatedAt());
        }
    }

    /**
     * {@code campus}·{@code teamName}·{@code recipientName} are the hand-off details (GitLab #239)
     * and are {@code null} together on purchases made before that field existed — the screen has to
     * render that row rather than assume every purchase names a recipient.
     */
    public record AdminPurchaseView(Long purchaseId, Long prizeId, String prizeName, Long buyerUserId,
                                    String buyerNickname, int quantity, int coinSpent, Long ledgerEntryId,
                                    Instant purchasedAt, String fulfillment, String note, Instant updatedAt,
                                    @Schema(nullable = true) String campus,
                                    @Schema(nullable = true) String teamName,
                                    @Schema(nullable = true) String recipientName) {
    }
}
