package com.example.ssafesta.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.common.RedisKeyspaceProperties;
import com.example.ssafesta.wallet.WalletProperties;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * The marker key's shape and its failure answer.
 *
 * <p>The world key is asserted verbatim: it is already in Redis with a day-long TTL wherever this
 * deploys, so a changed slug would quietly drop every marker written before the deploy.
 */
class DailyMissionMarkerServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 22);

    @Test
    void eachMarkerMissionKeepsItsOwnKeySegment() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.hasKey("festa:mission:world-enter:42:" + today())).thenReturn(true);
        when(redis.hasKey("festa:mission:ai-consult:42:" + today())).thenReturn(false);
        DailyMissionMarkerService markers = service(redis);

        markers.mark(DailyMission.AI_CONSULT, 42L);

        verify(values).setIfAbsent(eq("festa:mission:ai-consult:42:" + today()), eq("1"), any(Duration.class));
        assertTrue(markers.has(DailyMission.WORLD_ENTER, 42L, today()));
        assertEquals(false, markers.has(DailyMission.AI_CONSULT, 42L, today()));
    }

    @Test
    void aMissionWithoutAMarkerIsARefusalRatherThanASilentMiss() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        DailyMissionMarkerService markers = service(redis);

        assertThrows(IllegalArgumentException.class, () -> markers.has(DailyMission.SURVEY_ANSWER, 42L, DATE));
    }

    @Test
    void redisFailureSurfacesAsAServiceUnavailableRatherThanAZeroProgress() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new QueryTimeoutException("redis down"));
        when(redis.hasKey(anyString())).thenThrow(new QueryTimeoutException("redis down"));
        DailyMissionMarkerService markers = service(redis);

        ApiException onWrite = assertThrows(ApiException.class, () -> markers.mark(DailyMission.AI_CONSULT, 42L));
        ApiException onRead = assertThrows(ApiException.class,
                () -> markers.has(DailyMission.AI_CONSULT, 42L, today()));

        assertEquals(ErrorCode.MISSION_PROGRESS_UNAVAILABLE, onWrite.errorCode());
        assertEquals(ErrorCode.MISSION_PROGRESS_UNAVAILABLE, onRead.errorCode());
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Seoul"));
    }

    private DailyMissionMarkerService service(StringRedisTemplate redis) {
        return new DailyMissionMarkerService(redis, new WalletProperties(200, 50, ZoneId.of("Asia/Seoul")),
                new RedisKeyspaceProperties("festa"));
    }
}
