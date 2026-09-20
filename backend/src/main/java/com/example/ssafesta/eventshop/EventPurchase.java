package com.example.ssafesta.eventshop;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One purchase — a member spending coins on an {@link EventPrize} (S15P21A604-836).
 *
 * <p>{@code ledgerEntryId} is a reference for admins reading the row, not the source of truth for
 * whether the charge happened; the ledger entry is created in the same transaction as this row, so
 * the two can never disagree in a committed state.
 */
@Entity
@Table(name = "event_purchases")
public class EventPurchase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "prize_id", nullable = false, updatable = false)
    private Long prizeId;

    @Column(name = "buyer_user_id", nullable = false, updatable = false)
    private Long buyerUserId;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(name = "coin_spent", nullable = false, updatable = false)
    private int coinSpent;

    @Column(name = "ledger_entry_id", updatable = false)
    private Long ledgerEntryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PurchaseFulfillment fulfillment;

    @Column(columnDefinition = "text")
    private String note;

    /**
     * The draw's verdict (S15P21A604-922). {@code null} means the draw has not run, or this is an
     * ordinary prize that is never drawn at all; {@code true} won, {@code false} lost.
     *
     * <p>Deliberately not folded into {@link #fulfillment}: {@code CANCELLED} reads as "the order
     * was called off", while losing a raffle is a normal, completed outcome whose coins are gone
     * for good. Winners go on through the usual {@code PENDING}/{@code FULFILLED} hand-off.
     */
    @Column
    private Boolean won;

    /**
     * Who the prize goes to (GitLab #239). Nullable because purchases made before this existed have
     * no answer, and inventing one would read as "a buyer who left the field blank". New purchases
     * are required to carry all three — {@link EventShopService#purchase} enforces that.
     */
    @Column(length = 20, updatable = false)
    private String campus;

    @Column(name = "team_name", length = 50, updatable = false)
    private String teamName;

    @Column(name = "recipient_name", length = 50, updatable = false)
    private String recipientName;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100, updatable = false)
    private String idempotencyKey;

    @Column(name = "purchased_at", nullable = false, updatable = false)
    private Instant purchasedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EventPurchase() {
    }

    public EventPurchase(Long prizeId, Long buyerUserId, int quantity, int coinSpent,
                         PurchaseRecipient recipient, Long ledgerEntryId, String idempotencyKey, Instant now) {
        this.prizeId = prizeId;
        this.buyerUserId = buyerUserId;
        this.quantity = quantity;
        this.coinSpent = coinSpent;
        this.campus = recipient.campus();
        this.teamName = recipient.teamName();
        this.recipientName = recipient.recipientName();
        this.ledgerEntryId = ledgerEntryId;
        this.fulfillment = PurchaseFulfillment.PURCHASED;
        this.idempotencyKey = idempotencyKey;
        this.purchasedAt = now;
        this.updatedAt = now;
    }

    /** Caller has already validated the transition against {@link PurchaseFulfillment#canTransitionTo}. */
    public void transitionTo(PurchaseFulfillment next, String note) {
        this.fulfillment = next;
        this.note = note;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getPrizeId() {
        return prizeId;
    }

    public Long getBuyerUserId() {
        return buyerUserId;
    }

    public int getQuantity() {
        return quantity;
    }

    public int getCoinSpent() {
        return coinSpent;
    }

    /** Records this entry's draw result. Called once, under the prize's row lock. */
    public void recordDraw(boolean won, Instant now) {
        this.won = won;
        this.updatedAt = now;
    }

    public Boolean getWon() {
        return won;
    }

    public Long getLedgerEntryId() {
        return ledgerEntryId;
    }

    public PurchaseFulfillment getFulfillment() {
        return fulfillment;
    }

    public String getNote() {
        return note;
    }

    /** All three fields are {@code null} together on purchases made before GitLab #239. */
    public PurchaseRecipient getRecipient() {
        return new PurchaseRecipient(campus, teamName, recipientName);
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getPurchasedAt() {
        return purchasedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
