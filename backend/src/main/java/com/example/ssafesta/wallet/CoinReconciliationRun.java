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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Result of one balance/ledger reconciliation (spec 003 FR-014).
 *
 * <p>A record of what was found, nothing more: a mismatch is never corrected automatically
 * (spec 003 C-05). Silently repairing a balance would destroy the evidence of whatever produced
 * the discrepancy.
 */
@Entity
@Table(name = "coin_reconciliation_runs")
public class CoinReconciliationRun {

    public enum Status { RUNNING, COMPLETED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.RUNNING;

    @Column(name = "checked_wallet_count", nullable = false)
    private int checkedWalletCount;

    @Column(name = "mismatched_wallet_count", nullable = false)
    private int mismatchedWalletCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "mismatches", columnDefinition = "jsonb")
    private String mismatches;

    @Column(name = "failure_reason")
    private String failureReason;

    protected CoinReconciliationRun() {
    }

    void complete(int checkedWalletCount, int mismatchedWalletCount, String mismatches) {
        this.status = Status.COMPLETED;
        this.checkedWalletCount = checkedWalletCount;
        this.mismatchedWalletCount = mismatchedWalletCount;
        this.mismatches = mismatches;
        this.finishedAt = Instant.now();
    }

    void fail(String failureReason) {
        this.status = Status.FAILED;
        this.failureReason = failureReason;
        this.finishedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Status getStatus() { return status; }
    public int getCheckedWalletCount() { return checkedWalletCount; }
    public int getMismatchedWalletCount() { return mismatchedWalletCount; }
    public String getMismatches() { return mismatches; }
    public String getFailureReason() { return failureReason; }

    public boolean isConsistent() {
        return status == Status.COMPLETED && mismatchedWalletCount == 0;
    }
}
