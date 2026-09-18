package com.example.ssafesta.minigame;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.wallet.CoinCreditCommand;
import com.example.ssafesta.wallet.CoinReason;
import com.example.ssafesta.wallet.CoinSpendCommand;
import com.example.ssafesta.wallet.CoinLedgerEntryRepository;
import com.example.ssafesta.wallet.LedgerEntryType;
import com.example.ssafesta.wallet.LedgerResult;
import com.example.ssafesta.wallet.WalletService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The plaza slot machine — the second minigame (spec 021, GitLab #205, S15P21A604-635).
 *
 * <p><b>The server owns the outcome.</b> The client sends a machine and a bet; the tier, the payout
 * and both ledger entries are the server's (헌법 16조). It is the same division the timing-stop game
 * makes, and the reason {@code MockSlotMachineClient} is labelled 체험판 on the HUD — a client-made
 * verdict must never be mistaken for this one.
 *
 * <p><b>One spin is one transaction.</b> The bet and the payout are two ledger entries and they
 * commit together, so no spin can take coins without settling and none can pay without charging.
 * {@code WalletService} takes the wallet row lock on each of them, which also serializes two spins
 * by the same member.
 *
 * <p><b>No daily cap, on purpose</b> (#205 확정값 2). RTP is 0.55, so the machine is a coin sink and
 * not a source; capping the payout side would leave a capped player's bets going out with nothing
 * coming back, which is the one way this table can actually drain someone. {@code SLOT_PAYOUT} is a
 * different reason from {@code MINIGAME_REWARD}, so {@code WalletService.grantedTodayFor} keeps the
 * timing-stop cap counting only timing-stop rewards.
 *
 * <p><b>No spin table.</b> The two ledger entries already carry the member, the amounts, the order
 * and the spin id; a row repeating them would be a second place for the same fact to be wrong. The
 * ledger is the audit trail spec 003 already guarantees.
 *
 * <p><b>What the idempotency keys do and do not do.</b> They are derived from a spin id the
 * <i>server</i> mints, so they make the two entries of one spin un-double-appliable but they cannot
 * recognise a client that resends the same HTTP request — that arrives with no key of its own and
 * is a new gamble. The confirmed contract has no client-supplied key (#205 계약), and Unity blocks a
 * second press while a spin is in flight ({@code SlotMachineSession.Spin} refuses outside
 * {@code Ready}/{@code Result}), so the exposure is a manual retry after a timeout. Recorded in
 * spec 021 rather than closed here, because closing it means changing a contract two parts signed.
 */
@Service
public class SlotMachineService {

    /** {@code coin_ledger_entries.reference_type} for both entries of one spin. */
    static final String SPIN_REFERENCE_TYPE = "SLOT_SPIN";

    /** Ten settled losses earn exactly the next spin a tier-one payout (GitLab #235). */
    static final int PITY_LOSS_STREAK = 10;

    private final WalletService wallets;
    private final CoinLedgerEntryRepository ledger;
    private final SlotMachineProperties properties;

    public SlotMachineService(WalletService wallets, CoinLedgerEntryRepository ledger,
                              SlotMachineProperties properties) {
        this.wallets = wallets;
        this.ledger = ledger;
        this.properties = properties;
    }

    @Transactional
    public SlotSpinResult spin(Long userId, String machineId, SlotSpinCommand command) {
        // Machine first: an unknown machine is a 404 whatever the bet says, and answering 400 for a
        // machine that does not exist would send the game part looking at the wrong field.
        if (!properties.knows(machineId)) {
            throw new ApiException(ErrorCode.SLOT_MACHINE_NOT_FOUND);
        }
        int bet = requiredBet(command);

        // The historical read is part of coin settlement. Take the same lock every wallet mutation
        // uses before reading it: without this, two requests can both see the same ten losses and
        // both consume one guarantee. Decide before recording this spin's bet so the current,
        // as-yet-unsettled row cannot count as an eleventh loss.
        wallets.lockOwner(userId);
        int tier = mustForceTierOne(consecutiveLosses(userId)) ? 1 : properties.rollTier();

        UUID spinId = UUID.randomUUID();
        LedgerResult charged = wallets.spend(new CoinSpendCommand(userId, bet, CoinReason.SLOT_BET,
                SPIN_REFERENCE_TYPE, spinId.toString(), betKey(spinId)));

        int payout = bet * properties.multiplierOfTier(tier);
        // 낙첨 writes no second entry. A zero-amount row would be a ledger line that moves nothing,
        // and every reader of the history would have to learn to skip it.
        int balanceAfter = payout > 0
                ? wallets.credit(new CoinCreditCommand(userId, LedgerEntryType.REWARD, payout,
                        CoinReason.SLOT_PAYOUT, SPIN_REFERENCE_TYPE, spinId.toString(),
                        payoutKey(spinId))).balanceAfter()
                : charged.balanceAfter();

        return new SlotSpinResult(spinId.toString(), bet, payout, tier, balanceAfter);
    }

    static boolean mustForceTierOne(int consecutiveLosses) {
        return consecutiveLosses >= PITY_LOSS_STREAK;
    }

    private int consecutiveLosses(Long userId) {
        int losses = 0;
        for (Boolean won : ledger.findRecentSlotWinFlags(userId, CoinReason.SLOT_BET,
                CoinReason.SLOT_PAYOUT, SPIN_REFERENCE_TYPE, PITY_LOSS_STREAK)) {
            if (Boolean.TRUE.equals(won)) {
                break;
            }
            losses++;
        }
        return losses;
    }

    /**
     * The bet is the server's number; the request only has to agree with it (헌법 16조).
     *
     * <p>A mismatch is refused rather than silently corrected to the configured value: a client
     * that believes it staked 100 and is charged 10 has been lied to, and the reel it draws from
     * {@code tier} would be settling a different wager than the one it showed.
     */
    private int requiredBet(SlotSpinCommand command) {
        int bet = properties.betCoins();
        if (command == null || command.bet() == null) {
            throw ApiException.fieldInvalid("bet", "베팅액(bet)이 필요합니다.");
        }
        if (command.bet() != bet) {
            throw ApiException.fieldInvalid("bet",
                    "베팅액은 " + bet + " 코인 고정입니다: " + command.bet());
        }
        return bet;
    }

    /** Idempotency key for the bet of one spin — stable, and never a timestamp. */
    public static String betKey(UUID spinId) {
        return CoinReason.SLOT_BET + ":" + SPIN_REFERENCE_TYPE + ":" + spinId;
    }

    /** Idempotency key for the payout of one spin. */
    public static String payoutKey(UUID spinId) {
        return CoinReason.SLOT_PAYOUT + ":" + SPIN_REFERENCE_TYPE + ":" + spinId;
    }

    /**
     * @param bet what the player stakes. Checked against the server's configured value, never
     *            trusted as the amount to charge
     */
    @Schema(name = "SlotSpinCommand")
    public record SlotSpinCommand(
            @Schema(description = "베팅 코인. 서버 설정값과 같아야 한다", example = "10") Integer bet) {
    }

    /**
     * @param sessionId    this spin's handle. It appears on both ledger entries as
     *                     {@code referenceId}, so a player's history and this response can be tied
     *                     together
     * @param bet          what was charged — the server's value, echoed back
     * @param payout       coins granted. 0 = 낙첨
     * @param tier         0 낙첨 · 1..3 winning bands, lowest multiplier first. Unity picks the reel
     *                     preset from this, so it is part of the contract and not a debug field
     * @param balanceAfter the wallet balance once the spin has settled
     */
    @Schema(name = "SlotSpinResult")
    public record SlotSpinResult(String sessionId, int bet, int payout, int tier, int balanceAfter) {
    }
}
