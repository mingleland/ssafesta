package com.example.ssafesta.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.ssafesta.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MemberSessionServiceIntegrationTest {

    @Autowired
    private MemberSessionService sessions;

    @Test
    void reusedRotatedRefreshTokenRevokesTheActiveSession() {
        MemberSessionService.MemberSession first = sessions.issue(991_234L);
        MemberSessionService.MemberSession second = sessions.refresh(first.refreshToken());

        assertThrows(InvalidRefreshTokenException.class, () -> sessions.refresh(first.refreshToken()));
        assertThrows(InvalidRefreshTokenException.class, () -> sessions.refresh(second.refreshToken()));
    }

    /**
     * 같은 refresh 토큰으로 동시에 들어온 두 요청 중 하나만 발급받는다 (S15P21A604-485).
     *
     * <p>읽기와 삭제가 갈라져 있으면 둘 다 세션을 읽고 검사를 통과해 각각 발급한다 — 위 테스트가
     * 잡는 순차 재사용과 달리, 재사용 감지가 켜지지도 않고 지나간다. 소비가 원자적이면 키를 집은
     * 쪽만 살아남고 나머지는 없는 토큰을 본다.
     */
    @Test
    void twoSimultaneousRefreshesWithOneTokenIssueOnce() throws Exception {
        MemberSessionService.MemberSession issued = sessions.issue(991_235L);
        String token = issued.refreshToken();

        List<Callable<MemberSessionService.MemberSession>> attempts = new ArrayList<>();
        attempts.add(() -> sessions.refresh(token));
        attempts.add(() -> sessions.refresh(token));

        int succeeded = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (Future<MemberSessionService.MemberSession> attempt : pool.invokeAll(attempts)) {
                try {
                    attempt.get();
                    succeeded++;
                } catch (Exception refused) {
                    // 진 쪽은 GETDEL 이 null 을 준다 — 이 분기가 곧 재사용 판정이다.
                }
            }
        }

        assertEquals(1, succeeded, "같은 토큰으로 두 세션이 발급되면 재사용 감지가 우회된다");
    }
}
