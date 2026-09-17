package com.example.ssafesta.mission;

import com.example.ssafesta.booth.BoothVisitRepository;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.consultation.ConsultationRepository;
import com.example.ssafesta.minigame.MinigameSessionRepository;
import com.example.ssafesta.minigame.MinigameSessionStatus;
import com.example.ssafesta.wallet.CoinCreditCommand;
import com.example.ssafesta.wallet.CoinLedgerEntry;
import com.example.ssafesta.wallet.CoinLedgerEntryRepository;
import com.example.ssafesta.wallet.CoinReason;
import com.example.ssafesta.wallet.LedgerEntryType;
import com.example.ssafesta.wallet.LedgerResult;
import com.example.ssafesta.wallet.WalletProperties;
import com.example.ssafesta.wallet.WalletService;
import com.example.ssafesta.survey.SurveyResponseRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes and settles daily missions from recorded facts instead of maintaining a second progress table.
 */
@Service
public class DailyMissionService {

    private static final String TIMER_STOP_GAME_TYPE = "TIMER_STOP";
    private static final String SLOT_REFERENCE_TYPE = "SLOT_SPIN";

    private final ConsultationRepository consultations;
    private final SurveyResponseRepository surveyResponses;
    private final MinigameSessionRepository minigameSessions;
    private final BoothVisitRepository boothVisits;
    private final CoinLedgerEntryRepository ledger;
    private final WalletService wallets;
    private final WalletProperties walletProperties;
    private final WorldMissionProgressService worldProgress;

    public DailyMissionService(ConsultationRepository consultations, SurveyResponseRepository surveyResponses,
                               MinigameSessionRepository minigameSessions, BoothVisitRepository boothVisits,
                               CoinLedgerEntryRepository ledger, WalletService wallets,
                               WalletProperties walletProperties, WorldMissionProgressService worldProgress) {
        this.consultations = consultations;
        this.surveyResponses = surveyResponses;
        this.minigameSessions = minigameSessions;
        this.boothVisits = boothVisits;
        this.ledger = ledger;
        this.wallets = wallets;
        this.walletProperties = walletProperties;
        this.worldProgress = worldProgress;
    }

    @Transactional(readOnly = true)
    public DailyMissionsView findToday(Long userId) {
        LocalDate date = today();
        int earnedToday = wallets.grantedOnDateFor(userId, CoinReason.DAILY_MISSION, date);
        List<MissionView> missions = Arrays.stream(DailyMission.values())
                .map(mission -> viewOf(userId, mission, date))
                .toList();
        return new DailyMissionsView(date, resetAt(date), DailyMission.DAILY_CAP_COIN, earnedToday, missions);
    }

    /**
     * Serializes claim checking with the wallet row lock, so two claim requests cannot both pass
     * the running daily-cap check before either reward is recorded.
     */
    @Transactional
    public ClaimView claim(Long userId, String missionId) {
        DailyMission mission = DailyMission.require(missionId);
        LocalDate date = today();
        String key = claimKey(userId, mission, date);

        wallets.lockOwner(userId);
        if (ledger.findByIdempotencyKey(key).isPresent()) {
            throw new ApiException(ErrorCode.ALREADY_CLAIMED);
        }

        if (progressOf(userId, mission, date) < mission.goal()) {
            throw new ApiException(ErrorCode.NOT_COMPLETED);
        }
        int earnedToday = wallets.grantedOnDateFor(userId, CoinReason.DAILY_MISSION, date);
        if (earnedToday + DailyMission.REWARD_COIN > DailyMission.DAILY_CAP_COIN) {
            throw new ApiException(ErrorCode.DAILY_CAP_REACHED);
        }

        LedgerResult outcome = wallets.credit(new CoinCreditCommand(userId, LedgerEntryType.REWARD,
                DailyMission.REWARD_COIN, CoinReason.DAILY_MISSION, "DAILY_MISSION", mission.name(), key));
        CoinLedgerEntry entry = ledger.findByIdempotencyKey(key).orElseThrow();
        return new ClaimView(mission.name(), DailyMission.REWARD_COIN, outcome.balanceAfter(), entry.getCreatedAt());
    }

    private MissionView viewOf(Long userId, DailyMission mission, LocalDate date) {
        int progress = Math.min(progressOf(userId, mission, date), mission.goal());
        boolean claimed = ledger.findByIdempotencyKey(claimKey(userId, mission, date)).isPresent();
        MissionStatus status = claimed ? MissionStatus.CLAIMED
                : progress >= mission.goal() ? MissionStatus.CLAIMABLE : MissionStatus.LOCKED;
        return new MissionView(mission.name(), DailyMission.REWARD_COIN, progress, mission.goal(), status);
    }

    private int progressOf(Long userId, DailyMission mission, LocalDate date) {
        Instant from = date.atStartOfDay(zone()).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(zone()).toInstant();
        return switch (mission) {
            case AI_CONSULT -> asProgress(consultations
                    .countByVisitorUserIdAndRequestedAtGreaterThanEqualAndRequestedAtLessThan(userId, from, to));
            case SURVEY_ANSWER -> asProgress(surveyResponses
                    .countByRespondentUserIdAndSubmittedAtGreaterThanEqualAndSubmittedAtLessThan(userId, from, to));
            case STRIKER_PLAY_3 -> asProgress(minigameSessions
                    .countByUserIdAndGameTypeAndStartedAtGreaterThanEqualAndStartedAtLessThan(
                            userId, TIMER_STOP_GAME_TYPE, from, to));
            case STRIKER_SCORE -> asProgress(minigameSessions
                    .countByUserIdAndGameTypeAndStatusAndRewardCoinGreaterThanAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(
                            userId, TIMER_STOP_GAME_TYPE, MinigameSessionStatus.COMPLETED, 0, from, to));
            case SLOT_PLAY_3 -> asProgress(ledger.countSlotSpinsBetween(userId, CoinReason.SLOT_BET,
                    SLOT_REFERENCE_TYPE, from, to));
            case SLOT_WIN -> asProgress(ledger.countSlotSpinsBetween(userId, CoinReason.SLOT_PAYOUT,
                    SLOT_REFERENCE_TYPE, from, to));
            case BOOTH_VISIT_3, BOOTH_VISIT_6 -> asProgress(
                    boothVisits.countDistinctBoothsVisitedByUserBetween(userId, from, to));
            case WORLD_ENTER -> worldProgress.hasEntered(userId, date) ? 1 : 0;
        };
    }

    private static int asProgress(long count) {
        return Math.toIntExact(count);
    }

    private LocalDate today() {
        return LocalDate.now(zone());
    }

    private ZoneId zone() {
        return walletProperties.dailyGrantZone();
    }

    private OffsetDateTime resetAt(LocalDate date) {
        return date.plusDays(1).atStartOfDay(zone()).toOffsetDateTime();
    }

    public static String claimKey(Long userId, DailyMission mission, LocalDate date) {
        return CoinReason.DAILY_MISSION + ":" + userId + ":" + mission.name() + ":" + date;
    }

    public enum MissionStatus { LOCKED, CLAIMABLE, CLAIMED }

    public record MissionView(String missionId, int reward, int progress, int goal, MissionStatus status) { }

    public record DailyMissionsView(LocalDate date, OffsetDateTime resetAt, int dailyCap, int earnedToday,
                                    List<MissionView> missions) { }

    public record ClaimView(String missionId, int reward, int balanceAfter, Instant claimedAt) { }
}
