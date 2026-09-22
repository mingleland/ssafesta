package com.example.ssafesta.internal.ai;

import static org.hamcrest.Matchers.contains;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * FastAPI's AI_CONSULT report, end to end (spec 022 FR-003a, S15P21A604-955).
 *
 * <p>The mission is asserted by {@code missionId}, never by array index: the response order follows
 * the {@code DailyMission} enum, so an index would keep passing while checking a different mission
 * the day someone inserts a constant.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AiMissionMarkerApiIntegrationTest {

    private static final String PATH = "/internal/ai/mission/ai-consult";
    /** Must match {@code src/test/resources/application-local.properties}. */
    private static final String SERVICE_TOKEN = "test-ai-to-spring";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;

    @Test
    @DisplayName("대화 통보가 AI_CONSULT 를 수령 가능하게 만들고, 같은 날 재통보는 진행도를 늘리지 않는다")
    void aReportedConversationMakesTheMissionClaimableAndIsIdempotent() throws Exception {
        Long userId = newMember();
        String bearer = "Bearer " + sessions.issue(userId).accessToken();

        mark(userId).andExpect(status().isNoContent());
        mark(userId).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/missions/daily").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.missions[?(@.missionId=='AI_CONSULT')].progress").value(contains(1)))
                .andExpect(jsonPath("$.missions[?(@.missionId=='AI_CONSULT')].goal").value(contains(1)))
                .andExpect(jsonPath("$.missions[?(@.missionId=='AI_CONSULT')].status").value(contains("CLAIMABLE")));

        mockMvc.perform(post("/api/v1/missions/daily/AI_CONSULT/claims").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.missionId").value("AI_CONSULT"))
                .andExpect(jsonPath("$.reward").value(15));

        mockMvc.perform(post("/api/v1/missions/daily/AI_CONSULT/claims").header("Authorization", bearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_CLAIMED"));
    }

    @Test
    @DisplayName("통보가 없으면 진행도는 0 이고 수령은 거절된다")
    void withoutAReportTheMissionStaysLocked() throws Exception {
        Long userId = newMember();
        String bearer = "Bearer " + sessions.issue(userId).accessToken();

        mockMvc.perform(get("/api/v1/missions/daily").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.missions[?(@.missionId=='AI_CONSULT')].progress").value(contains(0)))
                .andExpect(jsonPath("$.missions[?(@.missionId=='AI_CONSULT')].status").value(contains("LOCKED")));

        mockMvc.perform(post("/api/v1/missions/daily/AI_CONSULT/claims").header("Authorization", bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOT_COMPLETED"));
    }

    @Test
    @DisplayName("본문이 계약을 벗어나면 400")
    void aBodyOutsideTheContractIsRejected() throws Exception {
        postBody(null).andExpect(status().isBadRequest());
        postBody("{}").andExpect(status().isBadRequest());
        postBody("{\"userId\":null}").andExpect(status().isBadRequest());
        postBody("{\"userId\":0}").andExpect(status().isBadRequest());
        postBody("{\"userId\":-3}").andExpect(status().isBadRequest());
        postBody("{\"userId\":7,\"boothId\":1}").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("토큰이 없거나 틀리면 401 — 사용자 토큰도 이 경로를 열지 못한다")
    void onlyTheAiServiceTokenOpensThePath() throws Exception {
        String body = "{\"userId\":1}";

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(post(PATH).header("Authorization", "Bearer " + SERVICE_TOKEN + "x")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        // 반대 방향(Spring→AI) 토큰은 이 방향을 열지 않는다.
        mockMvc.perform(post(PATH).header("Authorization", "Bearer test-spring-to-ai")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        Long userId = newMember();
        mockMvc.perform(post(PATH).header("Authorization", "Bearer " + sessions.issue(userId).accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions mark(Long userId) throws Exception {
        return postBody("{\"userId\":" + userId + "}");
    }

    private org.springframework.test.web.servlet.ResultActions postBody(String body) throws Exception {
        var request = post(PATH)
                .header("Authorization", "Bearer " + SERVICE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON);
        return mockMvc.perform(body == null ? request : request.content(body));
    }

    private Long newMember() {
        Long userId = users.save(new User("마커v" + SEQUENCE.incrementAndGet())).getId();
        wallets.openWallet(userId);
        return userId;
    }
}
