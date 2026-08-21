package com.example.ssafesta.wallet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Detects wallets whose stored balance disagrees with their ledger total (spec 003 FR-014,
 * SC-001).
 *
 * <p>Detection only. A mismatch means something already went wrong, and quietly rewriting the
 * balance would hide both the discrepancy and its cause — so the run records what it found and
 * leaves the data untouched (spec 003 C-05).
 *
 * <p>Not exposed over HTTP: the ADMIN authorization model is still undecided (plan U-01,
 * 헌법 30조), and an unauthenticated operations endpoint would be worse than none.
 */
@Service
public class CoinReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(CoinReconciliationService.class);

    private final WalletRepository wallets;
    private final CoinLedgerEntryRepository ledger;
    private final CoinReconciliationRunRepository runs;
    private final ObjectMapper objectMapper;

    public CoinReconciliationService(WalletRepository wallets, CoinLedgerEntryRepository ledger,
                                     CoinReconciliationRunRepository runs, ObjectMapper objectMapper) {
        this.wallets = wallets;
        this.ledger = ledger;
        this.runs = runs;
        this.objectMapper = objectMapper;
    }

    /**
     * Compares every wallet's balance against its ledger total and records the outcome.
     *
     * @return the persisted run; {@code mismatchedWalletCount > 0} is an SC-001 violation
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CoinReconciliationRun run() {
        CoinReconciliationRun current = runs.save(new CoinReconciliationRun());
        try {
            Map<Long, Long> ledgerSums = ledgerSumsByWalletId();
            List<Mismatch> mismatches = new ArrayList<>();
            List<Wallet> allWallets = wallets.findAll();

            for (Wallet wallet : allWallets) {
                long ledgerSum = ledgerSums.getOrDefault(wallet.getId(), 0L);
                if (ledgerSum != wallet.getBalance()) {
                    mismatches.add(new Mismatch(wallet.getId(), wallet.getUserId(), wallet.getBalance(),
                            ledgerSum, wallet.getBalance() - ledgerSum));
                }
            }

            current.complete(allWallets.size(), mismatches.size(), serialize(mismatches));
            if (mismatches.isEmpty()) {
                log.info("코인 정합성 점검 완료 — 지갑 {}건, 불일치 없음", allWallets.size());
            } else {
                log.error("코인 정합성 불일치 — 지갑 {}건 중 {}건 불일치. 자동 보정하지 않습니다. {}",
                        allWallets.size(), mismatches.size(), mismatches);
            }
            return current;
        } catch (RuntimeException exception) {
            log.error("코인 정합성 점검 실패", exception);
            current.fail(exception.toString());
            return current;
        }
    }

    private Map<Long, Long> ledgerSumsByWalletId() {
        Map<Long, Long> sums = new HashMap<>();
        for (Object[] row : ledger.sumAmountGroupedByWalletId()) {
            sums.put((Long) row[0], ((Number) row[1]).longValue());
        }
        return sums;
    }

    private String serialize(List<Mismatch> mismatches) {
        if (mismatches.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(mismatches);
        } catch (JacksonException exception) {
            // The count still records that a mismatch exists; only the detail is lost.
            log.error("정합성 불일치 상세를 직렬화하지 못했습니다.", exception);
            return null;
        }
    }

    /** One wallet whose balance and ledger total disagree. */
    public record Mismatch(Long walletId, Long userId, int balance, long ledgerSum, long difference) {
    }
}
