package com.example.ssafesta.game;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.AccessTokenService;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.UserRepository;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code GET /api/v1/arcade-machines/{machineId}} (S15P21A604-602, GitLab #56 안 1 · #135).
 *
 * <p>The three unplayable states are separate tests rather than one parameterised sweep because the
 * point is not that they refuse — it is that each answers <b>its own</b> reason. A single assertion
 * on {@code playable == false} would pass with the reasons swapped, and the FE maps a user-facing
 * sentence off that string.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ArcadeMachineApiIntegrationTest {

    private static final AtomicInteger MACHINE_SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private GameRepository games;
    @Autowired private ArcadeMachineBindingRepository bindings;
    @Autowired private UserRepository users;
    @Autowired private MemberSessionService sessions;
    @Autowired private AccessTokenService accessTokens;

    @Test
    void aBoundMachineResolvesToItsPublishedGame() throws Exception {
        Owner owner = publicPublishedGame("해석");
        String machineId = bind(owner.gameId());

        mockMvc.perform(get(path(machineId)))
                .andExpect(status().isOk())
                // 매 요청 판정이 계약이다. 캐시되면 방금 비공개로 바꾼 게임이 계속 열린다.
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.machineId").value(machineId))
                .andExpect(jsonPath("$.gameId").value(owner.gameId()))
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.playable").value(true))
                .andExpect(jsonPath("$.unavailableReason").value(Matchers.nullValue()))
                // 월드 고정물이라 임대·소유권 판정이 없다 — boothId 가 생기면 계약이 달라진 것이다.
                .andExpect(jsonPath("$.boothId").doesNotExist())
                .andExpect(jsonPath("$.length()").value(5));
    }

    /** 1대 = 1게임 고정 바인딩이므로 두 기계가 서로의 게임을 답하지 않는다 (2026-09-10 배치 결정). */
    @Test
    void twoMachinesResolveToTheirOwnGames() throws Exception {
        Owner first = publicPublishedGame("일번");
        Owner second = publicPublishedGame("이번");
        String firstMachine = bind(first.gameId());
        String secondMachine = bind(second.gameId());

        mockMvc.perform(get(path(firstMachine)))
                .andExpect(jsonPath("$.gameId").value(first.gameId()));
        mockMvc.perform(get(path(secondMachine)))
                .andExpect(jsonPath("$.gameId").value(second.gameId()));
    }

    /** 게시본이 있어도 PRIVATE 이면 못 연다. gameId 는 남고 버전만 숨는다. */
    @Test
    void aPrivateGameAnswersNotPublicWithoutItsVersion() throws Exception {
        Owner owner = publicPublishedGame("비공개");
        String machineId = bind(owner.gameId());
        mockMvc.perform(patch("/api/v1/games/" + owner.gameId())
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"PRIVATE\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(path(machineId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playable").value(false))
                .andExpect(jsonPath("$.unavailableReason").value("GAME_NOT_PUBLIC"))
                .andExpect(jsonPath("$.gameId").value(owner.gameId()))
                .andExpect(jsonPath("$.publishedVersion").value(Matchers.nullValue()));
    }

    /** 공개돼 있어도 게시한 적이 없으면 실행할 것이 없다 — 두 축은 독립이다. */
    @Test
    void anUnpublishedGameAnswersNotPublished() throws Exception {
        Owner owner = savedOwner("미게시");
        mockMvc.perform(makePublic(owner)).andExpect(status().isOk());
        String machineId = bind(owner.gameId());

        mockMvc.perform(get(path(machineId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playable").value(false))
                .andExpect(jsonPath("$.unavailableReason").value("GAME_NOT_PUBLISHED"))
                .andExpect(jsonPath("$.publishedVersion").value(Matchers.nullValue()));
    }

    /** 삭제가 가장 앞선 이유다 — 삭제된 게임은 비공개·미게시이기도 하지만 그렇게 답하지 않는다. */
    @Test
    void aDeletedGameAnswersDeleted() throws Exception {
        Owner owner = publicPublishedGame("삭제");
        String machineId = bind(owner.gameId());
        mockMvc.perform(delete("/api/v1/games/" + owner.gameId())
                        .header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(path(machineId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playable").value(false))
                .andExpect(jsonPath("$.unavailableReason").value("GAME_DELETED"));
    }

    /**
     * 이유의 우선순위 — 위 세 테스트가 잡지 못하는 것.
     *
     * <p>세 테스트는 조건이 하나씩만 참인 게임을 쓰므로 판정 순서를 바꿔도 전부 통과한다. 실제로
     * 겹치는 상태는 흔하다 — 비공개로 만들어 두고 한 번도 게시하지 않은 게임을 지우면 셋이 동시에
     * 참이다. 그때 사용자가 보는 문장이 "삭제된 게임" 하나여야 한다.
     */
    @Test
    void overlappingStatesAnswerTheFirstReasonInOrder() throws Exception {
        // PRIVATE(기본값) + 미게시 → 둘 다 참인데 공개 상태가 먼저다.
        Owner privateUnpublished = savedOwner("겹침비공개");
        String privateMachine = bind(privateUnpublished.gameId());
        mockMvc.perform(get(path(privateMachine)))
                .andExpect(jsonPath("$.unavailableReason").value("GAME_NOT_PUBLIC"));

        // 거기에 삭제까지 더해 셋이 참이면 삭제가 이긴다.
        mockMvc.perform(delete("/api/v1/games/" + privateUnpublished.gameId())
                        .header("Authorization", bearerFor(privateUnpublished.userId())))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(path(privateMachine)))
                .andExpect(jsonPath("$.unavailableReason").value("GAME_DELETED"));
    }

    /** 유일한 non-200. 채울 machineId 가 없어 200 으로 보낼 수 없다. */
    @Test
    void anUnknownMachineIsNotFound() throws Exception {
        mockMvc.perform(get(path("no-such-machine-" + MACHINE_SEQUENCE.incrementAndGet())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MACHINE_NOT_FOUND"));
    }

    /** 게시된 게임을 하는 것은 게스트의 몫이기도 하다 (FR-023). 토큰 없이도, 게스트 토큰으로도 열린다. */
    @Test
    void aGuestResolvesTheSameAsAnyoneElse() throws Exception {
        Owner owner = publicPublishedGame("게스트");
        String machineId = bind(owner.gameId());

        mockMvc.perform(get(path(machineId))
                        .header("Authorization", "Bearer " + accessTokens.issueGuestToken().token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playable").value(true))
                .andExpect(jsonPath("$.gameId").value(owner.gameId()));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private String bind(Long gameId) {
        String machineId = "test-arcade-" + MACHINE_SEQUENCE.incrementAndGet();
        bindings.save(new ArcadeMachineBinding(machineId, gameId));
        return machineId;
    }

    private String path(String machineId) {
        return "/api/v1/arcade-machines/" + machineId;
    }

    private Owner publicPublishedGame(String prefix) throws Exception {
        Owner owner = savedOwner(prefix);
        mockMvc.perform(post("/api/v1/games/" + owner.gameId() + "/publish")
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(GameTestSupport.publishRequest(1)))
                .andExpect(status().isOk());
        mockMvc.perform(makePublic(owner)).andExpect(status().isOk());
        return owner;
    }

    private MockHttpServletRequestBuilder makePublic(Owner owner) {
        return patch("/api/v1/games/" + owner.gameId())
                .header("Authorization", bearerFor(owner.userId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"visibility\":\"PUBLIC\"}");
    }

    private Owner savedOwner(String prefix) throws Exception {
        Long userId = GameTestSupport.createMember(users, prefix);
        Long gameId = games.save(new Game(userId, prefix + " 오락기게임")).getId();
        Owner owner = new Owner(userId, gameId);
        mockMvc.perform(put("/api/v1/games/" + gameId + "/draft")
                        .header("Authorization", bearerFor(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(GameTestSupport.saveRequest(0, GameTestSupport.validProjectFor(gameId))))
                .andExpect(status().isOk());
        return owner;
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }

    private record Owner(Long userId, Long gameId) { }
}
