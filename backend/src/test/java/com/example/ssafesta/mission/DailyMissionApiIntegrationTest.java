package com.example.ssafesta.mission;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.MockMvc;

/** Exercises the member-facing daily-mission contract through the real world-session fact source. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class DailyMissionApiIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;

    @Test
    void aWorldEntryBecomesClaimableOnceAndCreditsTheLedger() throws Exception {
        Long userId = newMember();
        String bearer = "Bearer " + sessions.issue(userId).accessToken();

        mockMvc.perform(post("/api/v1/world-sessions").header("Authorization", bearer))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/missions/daily").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyCap").value(135))
                .andExpect(jsonPath("$.missions[8].missionId").value("WORLD_ENTER"))
                .andExpect(jsonPath("$.missions[8].progress").value(1))
                .andExpect(jsonPath("$.missions[8].status").value("CLAIMABLE"));

        mockMvc.perform(post("/api/v1/missions/daily/WORLD_ENTER/claims").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.missionId").value("WORLD_ENTER"))
                .andExpect(jsonPath("$.reward").value(15));

        mockMvc.perform(post("/api/v1/missions/daily/WORLD_ENTER/claims").header("Authorization", bearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_CLAIMED"));
    }

    private Long newMember() {
        Long userId = users.save(new User("미션v" + SEQUENCE.incrementAndGet())).getId();
        wallets.openWallet(userId);
        return userId;
    }
}
