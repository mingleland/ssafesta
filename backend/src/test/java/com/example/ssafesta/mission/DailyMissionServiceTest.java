package com.example.ssafesta.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssafesta.booth.BoothVisitRepository;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.minigame.MinigameSessionRepository;
import com.example.ssafesta.survey.SurveyResponseRepository;
import com.example.ssafesta.wallet.CoinLedgerEntry;
import com.example.ssafesta.wallet.CoinLedgerEntryRepository;
import com.example.ssafesta.wallet.CoinCreditCommand;
import com.example.ssafesta.wallet.LedgerResult;
import com.example.ssafesta.wallet.WalletProperties;
import com.example.ssafesta.wallet.WalletService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

/** Maps every fixed daily mission to its one authoritative fact source without a progress table. */
class DailyMissionServiceTest {

    @Test
    void allNineMissionFactsBecomeClaimable() {
        SurveyResponseRepository surveys = mock(SurveyResponseRepository.class);
        MinigameSessionRepository minigames = mock(MinigameSessionRepository.class);
        BoothVisitRepository visits = mock(BoothVisitRepository.class);
        CoinLedgerEntryRepository ledger = mock(CoinLedgerEntryRepository.class);
        WalletService wallets = mock(WalletService.class);
        DailyMissionMarkerService markers = mock(DailyMissionMarkerService.class);
        DailyMissionService service = service(surveys, minigames, visits, ledger, wallets, markers);

        when(wallets.grantedOnDateFor(anyLong(), anyString(), any())).thenReturn(0);
        when(ledger.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(markers.has(eq(DailyMission.AI_CONSULT), anyLong(), any())).thenReturn(true);
        when(surveys.countByRespondentUserIdAndSubmittedAtGreaterThanEqualAndSubmittedAtLessThan(
                anyLong(), any(), any())).thenReturn(1L);
        when(minigames.countByUserIdAndGameTypeAndStartedAtGreaterThanEqualAndStartedAtLessThan(
                anyLong(), anyString(), any(), any())).thenReturn(3L);
        when(minigames.countByUserIdAndGameTypeAndStatusAndResultValueGreaterThanEqualAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(
                anyLong(), anyString(), any(), any(BigDecimal.class), any(), any())).thenReturn(1L);
        when(ledger.countSlotSpinsBetween(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(3L, 1L);
        when(visits.countDistinctBoothsVisitedByUserBetween(anyLong(), any(), any())).thenReturn(6L);
        when(markers.has(eq(DailyMission.WORLD_ENTER), anyLong(), any())).thenReturn(true);

        DailyMissionService.DailyMissionsView result = service.findToday(42L);

        assertEquals(135, result.dailyCap());
        assertEquals(9, result.missions().size());
        result.missions().forEach(mission -> {
            assertEquals(15, mission.reward());
            assertEquals(mission.goal(), mission.progress());
            assertEquals(DailyMissionService.MissionStatus.CLAIMABLE, mission.status());
        });
    }

    @Test
    void claimUsesTheDailyMissionLedgerKeyAndRejectsASecondClaim() {
        SurveyResponseRepository surveys = mock(SurveyResponseRepository.class);
        MinigameSessionRepository minigames = mock(MinigameSessionRepository.class);
        BoothVisitRepository visits = mock(BoothVisitRepository.class);
        CoinLedgerEntryRepository ledger = mock(CoinLedgerEntryRepository.class);
        WalletService wallets = mock(WalletService.class);
        DailyMissionMarkerService markers = mock(DailyMissionMarkerService.class);
        DailyMissionService service = service(surveys, minigames, visits, ledger, wallets, markers);
        CoinLedgerEntry entry = mock(CoinLedgerEntry.class);

        when(markers.has(eq(DailyMission.AI_CONSULT), anyLong(), any())).thenReturn(true);
        when(wallets.grantedOnDateFor(anyLong(), anyString(), any())).thenReturn(0);
        when(ledger.findByIdempotencyKey(anyString())).thenReturn(Optional.empty(), Optional.of(entry));
        when(wallets.credit(any())).thenReturn(new LedgerResult(99L, 215, false));
        when(entry.getCreatedAt()).thenReturn(Instant.parse("2026-09-17T08:00:00Z"));

        DailyMissionService.ClaimView claim = service.claim(42L, "AI_CONSULT");

        assertEquals("AI_CONSULT", claim.missionId());
        assertEquals(15, claim.reward());
        assertEquals(215, claim.balanceAfter());
        ArgumentCaptor<CoinCreditCommand> credit = ArgumentCaptor.forClass(CoinCreditCommand.class);
        verify(wallets).credit(credit.capture());
        String today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        assertEquals("DAILY_MISSION:42:AI_CONSULT:" + today, credit.getValue().idempotencyKey());

        when(ledger.findByIdempotencyKey(anyString())).thenReturn(Optional.of(entry));
        ApiException error = assertThrows(ApiException.class, () -> service.claim(42L, "AI_CONSULT"));
        assertEquals(ErrorCode.ALREADY_CLAIMED, error.errorCode());
    }

    @Test
    void claimRejectsTheNinthRewardWhenTheDailyCapIsAlreadyReached() {
        SurveyResponseRepository surveys = mock(SurveyResponseRepository.class);
        MinigameSessionRepository minigames = mock(MinigameSessionRepository.class);
        BoothVisitRepository visits = mock(BoothVisitRepository.class);
        CoinLedgerEntryRepository ledger = mock(CoinLedgerEntryRepository.class);
        WalletService wallets = mock(WalletService.class);
        DailyMissionMarkerService markers = mock(DailyMissionMarkerService.class);
        DailyMissionService service = service(surveys, minigames, visits, ledger, wallets, markers);

        when(markers.has(eq(DailyMission.AI_CONSULT), anyLong(), any())).thenReturn(true);
        when(ledger.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(wallets.grantedOnDateFor(anyLong(), anyString(), any())).thenReturn(135);

        ApiException error = assertThrows(ApiException.class, () -> service.claim(42L, "AI_CONSULT"));

        assertEquals(ErrorCode.DAILY_CAP_REACHED, error.errorCode());
    }

    private DailyMissionService service(SurveyResponseRepository surveys,
                                        MinigameSessionRepository minigames, BoothVisitRepository visits,
                                        CoinLedgerEntryRepository ledger, WalletService wallets,
                                        DailyMissionMarkerService markers) {
        return new DailyMissionService(surveys, minigames, visits, ledger, wallets,
                new WalletProperties(200, 50, ZoneId.of("Asia/Seoul")), markers);
    }
}
