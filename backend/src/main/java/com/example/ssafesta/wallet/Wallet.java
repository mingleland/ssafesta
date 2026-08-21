package com.example.ssafesta.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A member's coin wallet (spec 003). Guests never get one (헌법 12조, spec 003 FR-003b).
 *
 * <p>The balance is only ever changed together with a ledger row in the same transaction, so that
 * {@code balance == SUM(ledger.amount)} holds at every commit (spec 003 FR-006, invariant I-1).
 */
@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true, updatable = false)
    private Long userId;

    @Column(nullable = false)
    private int balance;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Wallet() {
    }

    public Wallet(Long userId) {
        this.userId = userId;
        this.balance = 0;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public int getBalance() { return balance; }
    public Instant getUpdatedAt() { return updatedAt; }

    /**
     * Applies a signed ledger amount to the balance.
     *
     * <p>The negative-balance guard here is a last-resort invariant check, not the user-facing
     * rule: {@link WalletService} rejects an unaffordable spend with {@link InsufficientCoinException}
     * before reaching this point. Hitting this exception means a caller bypassed that check.
     */
    void apply(int signedAmount) {
        if (signedAmount == 0) {
            throw new IllegalArgumentException("금액은 0일 수 없습니다.");
        }
        long next = (long) balance + signedAmount;
        if (next < 0) {
            throw new IllegalStateException(
                    "잔액이 음수가 됩니다 — walletId=" + id + ", balance=" + balance + ", amount=" + signedAmount);
        }
        if (next > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "잔액이 표현 범위를 넘습니다 — walletId=" + id + ", balance=" + balance + ", amount=" + signedAmount);
        }
        this.balance = (int) next;
        this.updatedAt = Instant.now();
    }

    void credit(int positiveAmount) {
        if (positiveAmount <= 0) {
            throw new IllegalArgumentException("지급 금액은 양수여야 합니다: " + positiveAmount);
        }
        apply(positiveAmount);
    }

    void debit(int positiveAmount) {
        if (positiveAmount <= 0) {
            throw new IllegalArgumentException("차감 금액은 양수여야 합니다: " + positiveAmount);
        }
        apply(-positiveAmount);
    }

    boolean canAfford(int positiveAmount) {
        return balance >= positiveAmount;
    }
}
