package com.example.ssafesta.wallet;

import java.time.Instant;
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
     * One reason's total over a half-open instant range — the daily caps are expressed as this
     * (spec 014 C-04).
     *
     * <p>{@code [from, to)} rather than {@code between}: the closed upper bound of {@code between}
     * counts an entry written exactly at midnight in both days.
     *
     * <p>Covered by {@code ix_coin_ledger_entries_wallet_created_at (wallet_id, created_at DESC)} —
     * {@code reason_type} filters the handful of rows one member wrote in one day.
     */
    @Query("""
            select coalesce(sum(e.amount), 0) from CoinLedgerEntry e
             where e.walletId = :walletId and e.reasonType = :reasonType
               and e.createdAt >= :from and e.createdAt < :to
            """)
    long sumAmountByWalletAndReasonBetween(@Param("walletId") Long walletId,
                                           @Param("reasonType") String reasonType,
                                           @Param("from") Instant from, @Param("to") Instant to);

    /** Counts slot spins from their existing bet or payout ledger facts; no parallel spin table exists. */
    @Query("""
            select count(e) from CoinLedgerEntry e join Wallet w on w.id = e.walletId
            where w.userId = :userId and e.reasonType = :reasonType and e.referenceType = :referenceType
              and e.createdAt >= :from and e.createdAt < :to
            """)
    long countSlotSpinsBetween(@Param("userId") Long userId, @Param("reasonType") String reasonType,
                               @Param("referenceType") String referenceType,
                               @Param("from") Instant from, @Param("to") Instant to);

    /**
     * Latest slot outcomes from the ledger facts that already settle every spin.
     *
     * <p>Each returned row is one {@code SLOT_BET}; {@code true} means its shared spin reference
     * has a {@code SLOT_PAYOUT}, and {@code false} means the spin lost. The caller reads only a
     * small suffix while holding the member wallet lock, so no separate pity counter can drift from
     * the economic record (spec 021 FR-011).
     */
    @Query(value = """
            select exists (
                select 1 from coin_ledger_entries payout
                 where payout.wallet_id = bet.wallet_id
                   and payout.reason_type = :payoutReason
                   and payout.reference_type = :referenceType
                   and payout.reference_id = bet.reference_id
            )
              from coin_ledger_entries bet
              join wallets w on w.id = bet.wallet_id
             where w.user_id = :userId
               and bet.reason_type = :betReason
               and bet.reference_type = :referenceType
             order by bet.created_at desc, bet.id desc
             limit :limit
            """, nativeQuery = true)
    List<Boolean> findRecentSlotWinFlags(@Param("userId") Long userId,
                                         @Param("betReason") String betReason,
                                         @Param("payoutReason") String payoutReason,
                                         @Param("referenceType") String referenceType,
                                         @Param("limit") int limit);

    /**
     * Per-wallet ledger totals for reconciliation (spec 003 FR-014). Wallets with no entries are
     * absent from the result, so the caller must treat a missing row as a ledger sum of 0 rather
     * than skipping the wallet — a wallet with a non-zero balance and no entries is exactly the
     * kind of mismatch this check exists to find.
     */
    @Query("select e.walletId, sum(e.amount) from CoinLedgerEntry e group by e.walletId")
    List<Object[]> sumAmountGroupedByWalletId();

    /**
     * 한 부스와 닿는 코인 흐름 (spec 015 FR-005, S15P21A604-501).
     *
     * <p><b>부스로 코인이 들어오는 경로는 없다.</b> 원장 사유를 전수로 보면 부스와 닿는 것은 둘뿐이고
     * 둘 다 수익이 아니다 — 임대료는 소유자가 <i>내는</i> 돈이고, 설문 보상은 차감되는 지갑 없이
     * <i>발행</i>된다. 그래서 "수익" 대신 이 둘을 그대로 돌려준다: 이 부스가 코인 경제에서 무엇을
     * 쓰고 무엇을 뿌렸는가.
     *
     * <p>특히 설문 보상은 응답 수 옆에 놓이면 바로 읽힌다 — 코인 155개를 뿌려 응답 31개를 받았다.
     * 보상 구조가 실제로 먹히는지가 그 두 숫자에 있다(GitLab #94 의 검증 지표).
     *
     * <p>네이티브인 이유는 {@code reference_id} 가 {@code VARCHAR} 이고 임대·설문 어느 쪽과도 연관
     * 매핑이 없기 때문이다. 원장이 자기 참조를 해석하는 질의라 이 자리에 둔다.
     *
     * @return {@code [임대료 합(양수), 설문 보상 발행 합]}
     */
    @Query(value = """
            select
              coalesce(sum(-e.amount) filter (
                  where e.reason_type = :leaseReason and e.reference_type = :leaseRefType), 0),
              coalesce(sum(e.amount) filter (
                  where e.reason_type = :surveyReason and e.reference_type = :surveyRefType), 0)
            from coin_ledger_entries e
            where e.created_at >= :from and e.created_at < :to
              and ((e.reason_type = :leaseReason and e.reference_type = :leaseRefType
                    and e.reference_id in (select cast(l.id as varchar)
                                           from booth_leases l where l.booth_id = :boothId))
                or (e.reason_type = :surveyReason and e.reference_type = :surveyRefType
                    and e.reference_id in (select cast(s.id as varchar)
                                           from surveys s where s.booth_id = :boothId)))
            """, nativeQuery = true)
    Object[] sumBoothCoinFlow(@Param("boothId") Long boothId,
                              @Param("from") Instant from, @Param("to") Instant to,
                              @Param("leaseReason") String leaseReason,
                              @Param("leaseRefType") String leaseRefType,
                              @Param("surveyReason") String surveyReason,
                              @Param("surveyRefType") String surveyRefType);
}
