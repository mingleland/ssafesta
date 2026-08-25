package com.example.ssafesta.auth;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.ssafesta.TestcontainersConfiguration;
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
}
