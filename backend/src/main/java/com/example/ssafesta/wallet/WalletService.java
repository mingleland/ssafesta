package com.example.ssafesta.wallet;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only path that changes coin balances (spec 003 FR-010, 헌법 20조).
 *
 * <p>Every movement runs as {@code lock wallet -> check idempotency key -> insert ledger row ->
 * update balance} inside one transaction. Methods use the default {@code REQUIRED} propagation so
 * a caller such as booth lease (spec 004) can make its own write and this charge atomic.
 *
 * <p><b>Why the wallet is locked before the idempotency lookup.</b> The lock serializes concurrent
 * requests for the same wallet, so a duplicate that arrives while the first is still in flight
 * waits, then reads the committed entry and returns it. Checking the key first would let both
 * requests pass the check and race to insert. The {@code UNIQUE} constraint on
 * {@code idempotency_key} remains as the backstop: if it ever fires, the ordering here was
 * bypassed, and the resulting failure is meant to be loud rather than recovered from.
 *
 * <p>This ordering assumes {@code READ COMMITTED} (PostgreSQL's default), where each statement
 * takes a fresh snapshot and therefore sees the committed entry after the lock is granted.
 */
@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;
    private static final int MAX_REFERENCE_ID_LENGTH = 100;
    private static final int MAX_REFERENCE_TYPE_LENGTH = 40;

    private final WalletRepository wallets;
    private final CoinLedgerEntryRepository ledger;
    private final WalletProperties properties;

    public WalletService(WalletRepository wallets, CoinLedgerEntryRepository ledger, WalletProperties properties) {
        this.wallets = wallets;
        this.ledger = ledger;
        this.properties = properties;
    }

    /**
     * Creates the member's wallet and records the signup grant (spec 003 FR-002).
     *
     * <p>Called from inside the member-creation transaction so that a member never exists without
     * a wallet. Never called for guests (spec 003 FR-003b, 헌법 12조).
     */
    @Transactional
    public Wallet openWallet(Long userId) {
        Optional<Wallet> existing = wallets.findByUserId(userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        Wallet wallet = wallets.saveAndFlush(new Wallet(userId));
        if (properties.initialGrant() > 0) {
            credit(new CoinCreditCommand(userId, LedgerEntryType.CHARGE, properties.initialGrant(),
                    CoinReason.INITIAL_GRANT, null, null, initialGrantKey(userId)));
        }
        return wallet;
    }

    /** Idempotency key for the signup grant — one per member, forever. */
    public static String initialGrantKey(Long userId) {
        return CoinReason.INITIAL_GRANT + ":" + userId;
    }

    @Transactional(readOnly = true)
    public Wallet requireWallet(Long userId) {
        return wallets.findByUserId(userId).orElseThrow(() -> missingWallet(userId));
    }

    /**
     * A member without a wallet is a broken state, not an expected 404: the wallet is created in
     * the member-creation transaction. Report it, and leave a trace to investigate with.
     *
     * <p>The log lives here rather than at the callers because there are three throw sites and, for
     * a while, only the one behind {@code GET /wallets/me} logged anything — booth lease and
     * catalog purchase hit the same state and left nothing behind but a 500 (S15P21A604-402).
     */
    private WalletNotFoundException missingWallet(Long userId) {
        log.error("회원에게 지갑이 없습니다 — 가입 트랜잭션을 확인해야 합니다. userId={}", userId);
        return new WalletNotFoundException();
    }

    @Transactional(readOnly = true)
    public int balanceOf(Long userId) {
        return requireWallet(userId).getBalance();
    }

    /**
     * Takes this member's wallet row lock for the caller's transaction, changing nothing.
     *
     * <p>For callers that must serialize one member's concurrent requests <b>before</b> reading
     * state they are about to write. A check-then-act without it lets parallel requests all pass
     * the same check — spec 004 lost the one-lease-per-member rule exactly that way (T-110).
     *
     * <p>Joins the caller's transaction (REQUIRED), so the lock is held until that transaction
     * ends. Every wallet mutation already goes through the same row lock, so this adds no new
     * lock ordering: it only moves the moment the lock is taken earlier.
     */
    @Transactional
    public void lockOwner(Long userId) {
        wallets.findByUserIdForUpdate(userId).orElseThrow(() -> missingWallet(userId));
    }

    /** Grants coins — {@code CHARGE}, {@code REWARD} or {@code REFUND}. */
    @Transactional
    public LedgerResult credit(CoinCreditCommand command) {
        if (command.entryType() == null) {
            throw new IllegalArgumentException("원장 유형이 필요합니다.");
        }
        return applyEntry(command.userId(), command.entryType(), command.entryType().signedAmount(command.amount()),
                command.reasonType(), command.referenceType(), command.referenceId(), command.idempotencyKey());
    }

    /**
     * Spends coins (spec 003 FR-008, FR-009). Rejects with {@link InsufficientCoinException}
     * without changing the balance when the wallet cannot afford it.
     */
    @Transactional
    public LedgerResult spend(CoinSpendCommand command) {
        return applyEntry(command.userId(), LedgerEntryType.SPEND,
                LedgerEntryType.SPEND.signedAmount(command.amount()), command.reasonType(),
                command.referenceType(), command.referenceId(), command.idempotencyKey());
    }

    /**
     * Manual administrator correction (spec 003 FR-013). Not exposed over HTTP — the ADMIN
     * authorization model is undecided (spec 003 plan U-01, 헌법 30조).
     */
    @Transactional
    public LedgerResult adjustByAdmin(CoinAdminAdjustCommand command) {
        if (command.signedAmount() == 0) {
            throw new IllegalArgumentException("관리자 조정 금액은 0일 수 없습니다.");
        }
        if (command.actorUserId() == null) {
            throw new IllegalArgumentException("관리자 조정에는 행위자 식별자가 필요합니다.");
        }
        LedgerEntryType entryType = LedgerEntryType.ofSignedAmount(command.signedAmount());
        return applyEntry(command.userId(), entryType, command.signedAmount(), CoinReason.ADMIN_ADJUSTMENT,
                CoinReason.ADMIN_ACTOR_REFERENCE_TYPE, String.valueOf(command.actorUserId()),
                command.idempotencyKey());
    }

    /** Transaction history, newest first (spec 003 FR-012). */
    @Transactional(readOnly = true)
    public Page<CoinLedgerEntryView> history(Long userId, Pageable pageable) {
        Wallet wallet = requireWallet(userId);
        return ledger.findPageByWalletId(wallet.getId(), pageable).map(CoinLedgerEntryView::of);
    }

    /** Ledger total for one wallet — the value the stored balance must equal (invariant I-1). */
    @Transactional(readOnly = true)
    public long ledgerSumOf(Long walletId) {
        return ledger.sumAmountByWalletId(walletId);
    }

    /**
     * Coins this member has already been granted <b>today</b> under one reason, where "today" is the
     * grant time zone's calendar day. The basis for every daily cap (spec 014 C-04).
     *
     * <p>Lives here rather than in the feature that needs it so that neither the wallet id nor the
     * day boundary leaves this package: {@code app.wallet.daily-grant-zone} is the one definition of
     * which day a coin policy belongs to, and a second copy is how the daily grant and a feature cap
     * end up on different calendars.
     *
     * <p><b>Call this while holding the member's wallet lock</b> ({@link #lockOwner}). Read outside
     * it, two concurrent submissions at 48/50 both see 48, both grant, and the day totals 58 — the
     * same check-then-act this class exists to prevent.
     */
    @Transactional(readOnly = true)
    public int grantedTodayFor(Long userId, String reasonType) {
        return grantedOnDateFor(userId, reasonType, LocalDate.now(properties.dailyGrantZone()));
    }

    /**
     * {@link #grantedTodayFor} for an explicit date. Exists so the day boundary can be exercised in
     * tests without waiting for midnight, the same reason
     * {@code DailyCoinGrantService.hasGrantedOn} takes one.
     */
    @Transactional(readOnly = true)
    public int grantedOnDateFor(Long userId, String reasonType, LocalDate date) {
        CoinReason.validate(reasonType);
        Wallet wallet = requireWallet(userId);
        ZoneId zone = properties.dailyGrantZone();
        // atStartOfDay(zone) rather than atTime(0, 0).atZone(zone): it resolves a DST gap instead of
        // producing an instant for a wall clock that never happened. Korea has none today; the cost
        // of writing the version that stays right is zero.
        Instant from = date.atStartOfDay(zone).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();
        return Math.toIntExact(
                ledger.sumAmountByWalletAndReasonBetween(wallet.getId(), reasonType, from, to));
    }

    private LedgerResult applyEntry(Long userId, LedgerEntryType entryType, int signedAmount, String reasonType,
                                    String referenceType, String referenceId, String idempotencyKey) {
        if (userId == null) {
            throw new IllegalArgumentException("지갑 소유자 식별자가 필요합니다.");
        }
        validateIdempotencyKey(idempotencyKey);
        validateReference(referenceType, referenceId);
        CoinReason.validate(reasonType);

        Wallet wallet = wallets.findByUserIdForUpdate(userId)
                .orElseThrow(() -> missingWallet(userId));

        Optional<CoinLedgerEntry> recorded = ledger.findByIdempotencyKey(idempotencyKey);
        if (recorded.isPresent()) {
            return LedgerResult.alreadyApplied(recorded.get());
        }

        if (signedAmount < 0 && !wallet.canAfford(-signedAmount)) {
            throw new InsufficientCoinException(-signedAmount, wallet.getBalance());
        }

        wallet.apply(signedAmount);
        CoinLedgerEntry entry = ledger.save(new CoinLedgerEntry(wallet.getId(), entryType, signedAmount,
                wallet.getBalance(), reasonType, referenceType, referenceId, idempotencyKey));
        return LedgerResult.applied(entry);
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("멱등성 키는 비어 있을 수 없습니다.");
        }
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            // Truncating here would make two different actions share a key and silently merge.
            throw new IllegalArgumentException("멱등성 키는 " + MAX_IDEMPOTENCY_KEY_LENGTH + "자를 넘을 수 없습니다: "
                    + idempotencyKey.length() + "자");
        }
    }

    private void validateReference(String referenceType, String referenceId) {
        if (referenceType != null && referenceType.length() > MAX_REFERENCE_TYPE_LENGTH) {
            throw new IllegalArgumentException("referenceType은 " + MAX_REFERENCE_TYPE_LENGTH + "자를 넘을 수 없습니다.");
        }
        if (referenceId != null && referenceId.length() > MAX_REFERENCE_ID_LENGTH) {
            throw new IllegalArgumentException("referenceId는 " + MAX_REFERENCE_ID_LENGTH + "자를 넘을 수 없습니다.");
        }
    }
}
