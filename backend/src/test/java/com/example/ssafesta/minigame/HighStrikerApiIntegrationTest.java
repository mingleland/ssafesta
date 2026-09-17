package com.example.ssafesta.minigame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Covers the Unity world's durable high-striker reporting endpoint and its replay guard. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class HighStrikerApiIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private MinigameSessionRepository plays;

    @Test
    void recordsTheApprovedScoreOnceAndRejectsAnImmediateReplay() throws Exception {
        Long userId = member();
        String bearer = "Bearer " + sessions.issue(userId).accessToken();

        mockMvc.perform(post("/api/v1/minigames/high-striker/plays")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"machineId\":\"plaza-high-striker-01\",\"score\":400}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.score").value(400))
                .andExpect(jsonPath("$.playsToday").value(1))
                .andExpect(jsonPath("$.bestScoreToday").value(400));

        assertEquals(1, plays.countByUserIdAndGameTypeAndStartedAtGreaterThanEqualAndStartedAtLessThan(
                userId, HighStrikerService.GAME_TYPE,
                java.time.Instant.now().minus(java.time.Duration.ofMinutes(1)), java.time.Instant.now().plusSeconds(1)));

        mockMvc.perform(post("/api/v1/minigames/high-striker/plays")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"machineId\":\"plaza-high-striker-01\",\"score\":999}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("HIGH_STRIKER_TOO_FAST"));
    }

    @Test
    void distinguishesAnUnknownWorldMachineFromAPathThatIsMissing() throws Exception {
        String bearer = "Bearer " + sessions.issue(member()).accessToken();

        mockMvc.perform(post("/api/v1/minigames/high-striker/plays")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"machineId\":\"not-in-the-plaza\",\"score\":400}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HIGH_STRIKER_NOT_FOUND"));
    }

    private Long member() {
        Long userId = users.save(new User("스트라이커" + SEQUENCE.incrementAndGet())).getId();
        wallets.openWallet(userId);
        return userId;
    }
}
