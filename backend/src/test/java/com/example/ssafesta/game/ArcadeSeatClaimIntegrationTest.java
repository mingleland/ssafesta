package com.example.ssafesta.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.UserRepository;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 게시하면서 오락실 자리를 잡는다 (S15P21A604-942, GitLab #256).
 *
 * <p>자리 배정은 독립 엔드포인트가 아니라 게시 트랜잭션의 일부다. 그래서 검증의 중심은 "잡힌다"
 * 가 아니라 <b>거절이 게시까지 되돌리는가</b>, 그리고 <b>내리면 비는가</b> 다 — 그 둘이 깨지면
 * 프라임 자리에 아무도 못 켜는 캐비닛이 남는다.
 *
 * <p>테스트마다 다른 캐비닛을 쓴다. 같은 컨텍스트에서 순차 실행되고 자리는 롤백되지 않으므로,
 * 같은 id 를 나눠 쓰면 앞 테스트가 남긴 자리 때문에 뒤가 깨진다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ArcadeSeatClaimIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private GameRepository games;
    @Autowired private ArcadeMachineBindingRepository bindings;
    @Autowired private UserRepository users;
    @Autowired private MemberSessionService sessions;

    @Test
    void publishingWithAMachineIdClaimsThatCabinet() throws Exception {
        Owner owner = draftedGame("자리잡기");

        publish(owner, "arcade-01").andExpect(status().isOk())
                .andExpect(jsonPath("$.arcadeMachineId").value("arcade-01"));

        ArcadeMachineBinding seat = bindings.findById("arcade-01").orElseThrow();
        assertEquals(owner.gameId(), seat.getGameId());
        // 게임이 아니라 잡은 사람이 남아야 한다 — 한도는 사람 단위다.
        assertEquals(owner.userId(), seat.getOwnerUserId());
    }

    @Test
    void publishingWithoutAMachineIdClaimsNothing() throws Exception {
        Owner owner = draftedGame("자리안잡기");

        publish(owner, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.arcadeMachineId").doesNotExist());

        assertTrue(bindings.findByGameIdAndOwnerUserIdNotNull(owner.gameId()).isEmpty());
    }

    @Test
    void aMachineIdTheSceneDoesNotHaveIsNotFound() throws Exception {
        Owner owner = draftedGame("없는자리");

        publish(owner, "arcade-99").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MACHINE_NOT_FOUND"));

        // 자리가 거절되면 게시도 없던 일이어야 한다. 이것이 둘을 한 트랜잭션에 둔 이유다.
        assertNull(games.findById(owner.gameId()).orElseThrow().getPublishedVersion(),
                "자리 거절이 게시를 되돌리지 않았습니다");
    }

    @Test
    void anEmptyMachineIdIsARequestErrorRatherThanNoChoice() throws Exception {
        Owner owner = draftedGame("빈자리값");

        // 없는 것과 같이 취급하면 "자리를 고른 줄 알았는데 안 걸렸다" 가 조용히 성립하고,
        // 사용자는 게시 성공만 보고 프라임 자리를 놓친다.
        mockMvc.perform(post("/api/v1/games/" + owner.gameId() + "/publish")
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"machineId\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertNull(games.findById(owner.gameId()).orElseThrow().getPublishedVersion());
    }

    @Test
    void someoneElsesCabinetIsRefused() throws Exception {
        Owner first = draftedGame("선점자");
        publish(first, "arcade-02").andExpect(status().isOk());
        Owner second = draftedGame("후발자");

        publish(second, "arcade-02").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ARCADE_MACHINE_TAKEN"));

        assertEquals(first.gameId(), bindings.findById("arcade-02").orElseThrow().getGameId());
    }

    @Test
    void republishingToTheSameCabinetKeepsIt() throws Exception {
        Owner owner = draftedGame("재게시");
        publish(owner, "arcade-03").andExpect(status().isOk());

        // 회차를 올릴 때마다 자리를 새로 잡아야 한다면 그 사이에 남이 채 갈 수 있다.
        publish(owner, "arcade-03").andExpect(status().isOk())
                .andExpect(jsonPath("$.arcadeMachineId").value("arcade-03"));

        assertEquals(1, bindings.findByGameIdAndOwnerUserIdNotNull(owner.gameId()).size());
    }

    @Test
    void aSeatedGameCannotHopToAnotherCabinet() throws Exception {
        Owner owner = draftedGame("이사시도");
        publish(owner, "arcade-04").andExpect(status().isOk());

        publish(owner, "arcade-05").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ARCADE_ALREADY_SEATED"));

        assertTrue(bindings.findById("arcade-05").isEmpty());
    }

    @Test
    void aThirdCabinetIsRefusedForTheSamePerson() throws Exception {
        Owner first = draftedGame("두대주인");
        publish(first, "arcade-06").andExpect(status().isOk());
        Owner second = sameOwnerGame(first);
        publish(second, "arcade-07").andExpect(status().isOk());
        Owner third = sameOwnerGame(first);

        publish(third, "arcade-08").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ARCADE_SEAT_LIMIT"));

        assertTrue(bindings.findById("arcade-08").isEmpty());
    }

    @Test
    void anOperatorFixtureDoesNotCountAgainstAnyone() throws Exception {
        Owner owner = draftedGame("운영자고정물");
        // 주인이 없는 행 — 광장 오락기처럼 운영자가 걸어 둔 고정물이다.
        bindings.save(new ArcadeMachineBinding("plaza-arcade-seatcount", owner.gameId()));
        Owner mine = sameOwnerGame(owner);

        publish(mine, "arcade-09").andExpect(status().isOk());

        assertEquals(1, bindings.countByOwnerUserId(owner.userId()),
                "운영자 고정물이 한도 셈에 들어갔습니다");
    }

    @Test
    void aPrivateGameCannotTakeACabinet() throws Exception {
        Owner owner = draftedGame("비공개점유");
        // 공개 설정과 게시는 별개의 축이다 — 비공개인 채로도 게시가 된다. 그 상태로 자리를 잡으면
        // 방문자는 못 켜는 캐비닛을 보고, 내렸을 때 비우는 규칙과도 어긋난다.
        makePrivate(owner);

        publish(owner, "arcade-13").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GAME_NOT_PUBLIC"));

        assertTrue(bindings.findById("arcade-13").isEmpty());
    }

    @Test
    void theSecondAndThirdCabinetCannotBeTakenAtOnce() throws Exception {
        Owner first = draftedGame("동시한도");
        publish(first, "arcade-14").andExpect(status().isOk());
        Owner second = sameOwnerGame(first);
        Owner third = sameOwnerGame(first);

        // 한도 검사는 세고 나서 꽂는다. 두 요청이 같은 수를 보면 둘 다 통과해 세 대가 된다 —
        // 기본키는 자리마다 하나를 보장할 뿐 사람마다 몇 대인지는 모른다.
        List<MockHttpServletResponse> responses = inParallel(
                () -> publishRaw(second, "arcade-15"),
                () -> publishRaw(third, "arcade-16"));

        assertTrue(bindings.countByOwnerUserId(first.userId()) <= 2,
                "한도를 넘겨 자리를 잡았습니다: " + bindings.countByOwnerUserId(first.userId())
                        + "대, 응답 " + statuses(responses));
    }

    @Test
    void goingPrivateReleasesTheCabinet() throws Exception {
        Owner owner = draftedGame("비공개전환");
        publish(owner, "arcade-10").andExpect(status().isOk());

        makePrivate(owner);

        assertTrue(bindings.findById("arcade-10").isEmpty(), "내렸는데 자리가 남아 있습니다");
    }

    @Test
    void deletingTheGameReleasesTheCabinet() throws Exception {
        Owner owner = draftedGame("삭제");
        publish(owner, "arcade-11").andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/games/" + owner.gameId())
                        .header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isNoContent());

        // 휴지통에 있는 게임의 자리를 남겨 두면 목록에서는 빈 자리로 보이는데 잡으면 이미 점유다.
        assertTrue(bindings.findById("arcade-11").isEmpty());
    }

    @Test
    void twoPeopleChoosingTheSameCabinetLeaveOneWinner() throws Exception {
        Owner first = draftedGame("동시가");
        Owner second = draftedGame("동시나");

        List<MockHttpServletResponse> responses = inParallel(
                () -> publishRaw(first, "arcade-12"),
                () -> publishRaw(second, "arcade-12"));

        long ok = responses.stream().filter(r -> r.getStatus() == 200).count();
        long conflict = responses.stream().filter(r -> r.getStatus() == 409).count();
        // 빈 행에는 잠글 것이 없어 두 트랜잭션이 모두 "비었다" 를 본다. 한 명만 넣는 것은 기본키다.
        assertEquals(1, ok, "둘 다 자리를 잡았거나 둘 다 실패했습니다: " + statuses(responses));
        assertEquals(1, conflict, "경합 패자가 409 가 아닙니다: " + statuses(responses));
        assertTrue(bindings.findById("arcade-12").isPresent());
    }

    private List<MockHttpServletResponse> inParallel(Callable<MockHttpServletResponse> one,
                                                     Callable<MockHttpServletResponse> two)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<MockHttpServletResponse>> futures = pool.invokeAll(List.of(one, two));
            return List.of(futures.get(0).get(), futures.get(1).get());
        } finally {
            pool.shutdown();
        }
    }

    private String statuses(List<MockHttpServletResponse> responses) {
        return responses.stream().map(response -> String.valueOf(response.getStatus())).toList()
                .toString();
    }

    private MockHttpServletResponse publishRaw(Owner owner, String machineId) throws Exception {
        return mockMvc.perform(publishRequest(owner, machineId)).andReturn().getResponse();
    }

    private ResultActions publish(Owner owner, String machineId) throws Exception {
        return mockMvc.perform(publishRequest(owner, machineId));
    }

    private MockHttpServletRequestBuilder publishRequest(Owner owner, String machineId) {
        String body = machineId == null
                ? GameTestSupport.publishRequest(1)
                : GameTestSupport.publishRequest(1, machineId);
        return post("/api/v1/games/" + owner.gameId() + "/publish")
                .header("Authorization", bearerFor(owner.userId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    /** 게시 직전까지 간 게임 — 작업본이 있고 아직 공개 회차가 없다. */
    private Owner draftedGame(String prefix) throws Exception {
        Long userId = GameTestSupport.createMember(users, prefix);
        return draftFor(userId, prefix);
    }

    /** 같은 사람의 다른 게임 — 1인 한도를 세려면 사람이 같아야 한다. */
    private Owner sameOwnerGame(Owner owner) throws Exception {
        return draftFor(owner.userId(), "추가");
    }

    private Owner draftFor(Long userId, String prefix) throws Exception {
        Long gameId = games.save(new Game(userId, prefix + " 오락실게임")).getId();
        mockMvc.perform(put("/api/v1/games/" + gameId + "/draft")
                        .header("Authorization", bearerFor(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(GameTestSupport.saveRequest(0, GameTestSupport.validProjectFor(gameId))))
                .andExpect(status().isOk());
        Owner owner = new Owner(userId, gameId);
        // 자리를 잡으려면 공개여야 한다. 기본값은 PRIVATE 이라 여기서 올려 둔다 — 비공개인 채로
        // 잡히는지는 aPrivateGameCannotTakeACabinet 이 따로 본다.
        changeVisibility(owner, "PUBLIC");
        return owner;
    }

    private void makePrivate(Owner owner) throws Exception {
        changeVisibility(owner, "PRIVATE");
    }

    private void changeVisibility(Owner owner, String visibility) throws Exception {
        mockMvc.perform(patch("/api/v1/games/" + owner.gameId())
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"" + visibility + "\"}"))
                .andExpect(status().isOk());
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }

    private record Owner(Long userId, Long gameId) { }
}
