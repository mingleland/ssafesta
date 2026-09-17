package com.example.ssafesta.minigame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.wallet.WalletService;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Guards the durable high-striker facts that daily mission 3·4 reads. */
class HighStrikerServiceTest {

    @Test
    void recordsAClampedScoreAndReturnsTodaySummary() {
        MinigameSessionRepository sessions = mock(MinigameSessionRepository.class);
        WalletService wallets = mock(WalletService.class);
        HighStrikerService service = new HighStrikerService(sessions, wallets);
        when(sessions.findFirstByUserIdAndGameTypeOrderByStartedAtDesc(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(sessions.countByUserIdAndGameTypeAndStartedAtGreaterThanEqualAndStartedAtLessThan(
                anyLong(), anyString(), any(), any())).thenReturn(3L);
        when(sessions.findTopScoreByUserIdAndGameTypeAndStartedAtBetween(
                anyLong(), anyString(), any(), any())).thenReturn(BigDecimal.valueOf(999));

        HighStrikerService.PlayRecorded result = service.record(7L,
                new HighStrikerService.PlayCommand("plaza-high-striker-01", 1_400));

        assertEquals(999, result.score());
        assertEquals(3, result.playsToday());
        assertEquals(999, result.bestScoreToday());
    }

    @Test
    void rejectsUnknownMachineBeforeCreatingAPlay() {
        HighStrikerService service = new HighStrikerService(mock(MinigameSessionRepository.class),
                mock(WalletService.class));

        ApiException error = assertThrows(ApiException.class, () -> service.record(7L,
                new HighStrikerService.PlayCommand("unknown", 400)));

        assertEquals(ErrorCode.HIGH_STRIKER_NOT_FOUND, error.errorCode());
    }
}
