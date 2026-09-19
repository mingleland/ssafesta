package com.example.ssafesta.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 목록 경로가 Unity 에게 설치된 오락기와 빈 자리를 알려 주는 계약을 검증한다 (S15P21A604-940).
 *
 * <p>단건 해석과 달리 삭제된 게임의 기계는 반환하지 않는다. 씬은 이 목록을 자기 고정 기계 id 와
 * 비교해 빈 자리를 계산하므로, 이미 없어진 게임의 자리까지 점유 중이라고 말하면 안 된다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ArcadeMachineListApiIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String PATH = "/api/v1/arcade-machines";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private MockMvc mockMvc;
    @Autowired private GameRepository games;
    @Autowired private ArcadeMachineBindingRepository bindings;
    @Autowired private UserRepository users;
    @Autowired private MemberSessionService sessions;

    @Test
    void anUnauthenticatedCallerReadsTheListAndIsToldNotToCacheIt() throws Exception {
        Owner owner = publicPublishedGame("목록무토큰");
        String machineId = bind("list-anon-" + SEQUENCE.incrementAndGet(), owner.gameId());

        JsonNode entries = list();

        assertNotNull(entry(entries, machineId));
    }

    @Test
    void aPrivateGameRemainsListedAsNotPublic() throws Exception {
        Owner owner = publicPublishedGame("목록비공개");
        String machineId = bind("list-private-" + SEQUENCE.incrementAndGet(), owner.gameId());
        mockMvc.perform(makePrivate(owner)).andExpect(status().isOk());

        JsonNode listed = entry(list(), machineId);

        assertNotNull(listed);
        assertEquals(machineId, listed.path("machineId").asText());
        assertEquals(owner.gameId(), listed.path("gameId").asLong());
        assertTrue(listed.path("title").isNull(),
                "비공개 게임의 제목이 무토큰 호출에 노출됩니다: " + listed);
        assertTrue(listed.path("publishedVersion").isNull());
        assertFalse(listed.path("playable").asBoolean());
        assertEquals("GAME_NOT_PUBLIC", listed.path("unavailableReason").asText());
        assertFalse(listed.has("thumbnailUrl"));
    }

    @Test
    void aNeverPublishedGameIsListedAsNotPublished() throws Exception {
        Owner owner = savedOwner("목록미게시");
        mockMvc.perform(makePublic(owner)).andExpect(status().isOk());
        String machineId = bind("list-unpublished-" + SEQUENCE.incrementAndGet(), owner.gameId());

        JsonNode listed = entry(list(), machineId);

        assertNotNull(listed);
        assertFalse(listed.path("playable").asBoolean());
        assertEquals("GAME_NOT_PUBLISHED", listed.path("unavailableReason").asText());
        assertTrue(listed.path("title").isNull());
    }

    @Test
    void aDeletedGamesMachineIsAbsentFromTheList() throws Exception {
        Owner owner = publicPublishedGame("목록삭제");
        String machineId = bind("list-deleted-" + SEQUENCE.incrementAndGet(), owner.gameId());
        mockMvc.perform(delete("/api/v1/games/" + owner.gameId())
                        .header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isNoContent());

        assertNull(entry(list(), machineId));
    }

    @Test
    void machinesAreSortedByMachineIdAscending() throws Exception {
        Owner first = publicPublishedGame("목록정렬가");
        Owner second = publicPublishedGame("목록정렬나");
        bind("list-order-z-" + SEQUENCE.incrementAndGet(), first.gameId());
        bind("list-order-a-" + SEQUENCE.incrementAndGet(), second.gameId());

        JsonNode entries = list();
        List<String> machineIds = new ArrayList<>();
        entries.forEach(entry -> machineIds.add(entry.path("machineId").asText()));

        assertEquals(machineIds.stream().sorted(Comparator.naturalOrder()).toList(), machineIds);
    }

    private JsonNode list() throws Exception {
        MvcResult result = mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")))
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode entry(JsonNode entries, String machineId) {
        for (JsonNode entry : entries) {
            if (machineId.equals(entry.path("machineId").asText())) {
                return entry;
            }
        }
        return null;
    }

    private String bind(String machineId, Long gameId) {
        bindings.save(new ArcadeMachineBinding(machineId, gameId));
        return machineId;
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

    private MockHttpServletRequestBuilder makePrivate(Owner owner) {
        return patch("/api/v1/games/" + owner.gameId())
                .header("Authorization", bearerFor(owner.userId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"visibility\":\"PRIVATE\"}");
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
