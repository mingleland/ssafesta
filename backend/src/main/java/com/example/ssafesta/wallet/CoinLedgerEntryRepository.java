package com.example.ssafesta.wallet;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CoinLedgerEntryRepository extends JpaRepository<CoinLedgerEntry, Long> {

    Optional<CoinLedgerEntry> findByIdempotencyKey(String idempotencyKey);

    @Query("select e from CoinLedgerEntry e where e.walletId = :walletId order by e.createdAt desc, e.id desc")
    Page<CoinLedgerEntry> findPageByWalletId(@Param("walletId") Long walletId, Pageable pageable);

    @Query("select coalesce(sum(e.amount), 0) from CoinLedgerEntry e where e.walletId = :walletId")
    long sumAmountByWalletId(@Param("walletId") Long walletId);

    /**
     * Per-wallet ledger totals for reconciliation (spec 003 FR-014). Wallets with no entries are
     * absent from the result, so the caller must treat a missing row as a ledger sum of 0 rather
     * than skipping the wallet — a wallet with a non-zero balance and no entries is exactly the
     * kind of mismatch this check exists to find.
     */
    @Query("select e.walletId, sum(e.amount) from CoinLedgerEntry e group by e.walletId")
    List<Object[]> sumAmountGroupedByWalletId();
}
