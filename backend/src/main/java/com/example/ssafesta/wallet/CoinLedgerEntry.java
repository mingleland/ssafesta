package com.example.ssafesta.wallet;

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
 * One coin movement (spec 003 FR-004). Append-only: there is intentionally no setter and no
 * mutating method, so a recorded entry can never be rewritten (invariant I-6). A wrong entry is
 * corrected by adding an opposite entry, not by editing this one.
 *
 * <p>{@code amount} is signed — grants positive, spends negative — so that a wallet's balance is
 * simply {@code SUM(amount)} (spec 003 research R-02).
 */
@Entity
@Table(name = "coin_ledger_entries")
public class CoinLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "wallet_id", nullable = false, updatable = false)
    private Long walletId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 20, updatable = false)
    private LedgerEntryType entryType;

    @Column(nullable = false, updatable = false)
    private int amount;

    @Column(name = "balance_after", nullable = false, updatable = false)
    private int balanceAfter;

    @Column(name = "reason_type", nullable = false, length = 40, updatable = false)
    private String reasonType;

    @Column(name = "reference_type", length = 40, updatable = false)
    private String referenceType;

    @Column(name = "reference_id", length = 100, updatable = false)
    private String referenceId;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100, updatable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected CoinLedgerEntry() {
    }

    CoinLedgerEntry(Long walletId, LedgerEntryType entryType, int amount, int balanceAfter, String reasonType,
                    String referenceType, String referenceId, String idempotencyKey) {
        if (entryType.isCredit() != (amount > 0)) {
            throw new IllegalArgumentException(
                    "원장 유형과 금액 부호가 어긋납니다 — entryType=" + entryType + ", amount=" + amount);
        }
        this.walletId = walletId;
        this.entryType = entryType;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.reasonType = reasonType;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.idempotencyKey = idempotencyKey;
    }

    public Long getId() { return id; }
    public Long getWalletId() { return walletId; }
    public LedgerEntryType getEntryType() { return entryType; }
    public int getAmount() { return amount; }
    public int getBalanceAfter() { return balanceAfter; }
    public String getReasonType() { return reasonType; }
    public String getReferenceType() { return referenceType; }
    public String getReferenceId() { return referenceId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
}
