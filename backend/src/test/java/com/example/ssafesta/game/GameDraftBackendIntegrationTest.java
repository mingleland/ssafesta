package com.example.ssafesta.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * S15P21A604-744 5단계 — {@link GameProjectLimitBoundaryTest}가 in-process로 확인한 상한들이 실제
 * HTTP·Spring Security·Jackson·DB 스택을 통과할 때도 그대로인지를 확인한다.
 *
 * <p>특히 2,000,000-byte 크기 상한은 {@code SaveRequest.read}가 클라이언트 원문이 아니라
 * {@code project} 서브트리를 Jackson으로 다시 직렬화한 문자열로 잰다({@link GameProjectJson#write}) —
 * 그 재직렬화가 실제로 그 경계에서 200/409를 가르는지는 순수 단위 테스트로는 볼 수 없고, 여기서만
 * 확인할 수 있다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class GameDraftBackendIntegrationTest {

    private static final int MAX_PROJECT_BYTES = 2_000_000;

    @Autowired private MockMvc mockMvc;
    @Autowired private GameRepository games;
    @Autowired private UserRepository users;
    @Autowired private MemberSessionService sessions;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * DECORATION 오브젝트를 채워 넣어 실측 크기를 2,000,000 byte 경계에 정확히 맞춘다 — 컷오프
     * 앞뒤 1 byte 차이로 200/409가 갈리는지를 실제 저장 경로로 확인한다.
     */
    @Test
    void projectAtExactByteCapSavesAndOneByteOverIsRejected() throws Exception {
        Owner owner = owner("바이트상한");
        ObjectNode atCap = projectPaddedToExactly(owner.gameId(), MAX_PROJECT_BYTES);
        assertEquals(MAX_PROJECT_BYTES, jacksonByteSize(atCap.get("project")));

        mockMvc.perform(put(draftPath(owner.gameId()))
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(atCap)))
                .andExpect(status().isOk());

        Owner owner2 = owner("바이트상한초과");
        ObjectNode overCap = projectPaddedToExactly(owner2.gameId(), MAX_PROJECT_BYTES + 1);
        assertEquals(MAX_PROJECT_BYTES + 1, jacksonByteSize(overCap.get("project")));

        mockMvc.perform(put(draftPath(owner2.gameId()))
                        .header("Authorization", bearerFor(owner2.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(overCap)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GAME_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].rule").value("PROJECT_SIZE_INVALID"));
    }

    /**
     * 이번 티켓에서 스키마에 새로 추가한 3개 필드가 실제 저장→조회 왕복에서 그대로 살아남는지 —
     * in-process 검증기 호출이 아니라 실제 DB round-trip으로 확인한다.
     */
    @Test
    void newlyFixedFieldsRoundTripThroughRealSaveAndLoad() throws Exception {
        Owner owner = owner("필드왕복");
        ObjectNode project = GameTestSupport.validProjectFor(owner.gameId());
        ObjectNode worldScene = (ObjectNode) project.withArray("scenes").get(0);
        ObjectNode secondObject = (ObjectNode) worldScene.path("objects").get(1);
        secondObject.put("name", "황동 열쇠 더미");
        secondObject.put("showNameInPlay", true);
        ObjectNode interactable = ((ArrayNode) secondObject.path("components")).addObject();
        interactable.put("type", "INTERACTABLE");
        interactable.put("prompt", "조사하기");
        interactable.put("range", 5);

        mockMvc.perform(put(draftPath(owner.gameId()))
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(GameTestSupport.saveRequest(0, project)))
                .andExpect(status().isOk());

        mockMvc.perform(get(draftPath(owner.gameId())).header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.project.scenes[0].objects[1].name").value("황동 열쇠 더미"))
                .andExpect(jsonPath("$.project.scenes[0].objects[1].showNameInPlay").value(true))
                // roomKey는 이미 SPRITE·PICKUP 2개를 갖고 있다 — 새로 붙인 INTERACTABLE은 index 2.
                .andExpect(jsonPath("$.project.scenes[0].objects[1].components[2].range").value(5));
    }

    /** {@code project} 서브트리만 잰다 — 저장 요청 전체({@code expectedRevision} 포함)가 아니다. */
    private int jacksonByteSize(com.fasterxml.jackson.databind.JsonNode project) throws Exception {
        return mapper.writeValueAsString(project).getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * DECORATION 오브젝트로 목표 byte 수 근처(2,000 byte 여유)까지 채운 뒤, 이미 채워 넣은
     * 오브젝트들의 {@code name} 필드(1~40자)를 하나씩 최대로 늘려가며 나머지를 정확히 맞춘다 —
     * 매번 실측(re-serialize)해서 소모한 byte를 확인하므로 오버헤드를 손으로 계산할 필요가 없다.
     * 오브젝트 하나가 대략 100 byte라 목표(2,000,000)에 닿으려면 약 19,000개가 필요한데,
     * {@code objects} 배열 상한(scene당 500)을 넘을 수 없어 scene을 새로 만들며 채운다
     * ({@code scenes} 상한 50 안에서 — fixture가 이미 3개를 쓰므로 47개 여유).
     */
    private ObjectNode projectPaddedToExactly(Long gameId, int targetBytes) throws Exception {
        ObjectNode project = GameTestSupport.validProjectFor(gameId);
        ArrayNode scenes = project.withArray("scenes");

        int sceneIndex = 0;
        int objectIndex = 0;
        ArrayNode currentObjects = newPadScene(scenes, sceneIndex);
        java.util.List<ObjectNode> padObjects = new java.util.ArrayList<>();

        // 오브젝트 하나 추가할 때마다 문서 전체를 재직렬화하면 19,000개 근처에서 O(n²)가 된다
        // (실측 120초, 리뷰 note_2813554). 목표까지 남은 여유를 "오브젝트당 최대 150 byte"로
        // 보수적으로 나눠 배치 크기를 정하면, 여유가 넉넉한 동안은 최대 500개씩 묶어 추가하고
        // 재측정 한 번으로 끝내면서도, 여유가 줄어들수록 배치가 자연히 좁아져 목표(2,000 byte
        // 여유)를 넘기지 않는다. 넉넉한 여유(2,000 byte)를 남기고 멈추는 건 기존과 같다 — 그래야
        // 다음 단계(오브젝트당 최대 39byte짜리 name 패딩)로 오버슈트 없이 정확히 맞출 수 있다.
        final int MAX_BYTES_PER_OBJECT = 150;
        while (true) {
            int headroom = targetBytes - 2_000 - jacksonByteSize(project);
            if (headroom <= 0) {
                break;
            }
            int batch = Math.max(1, Math.min(500, headroom / MAX_BYTES_PER_OBJECT));
            for (int i = 0; i < batch; i++) {
                if (currentObjects.size() >= 500) {
                    sceneIndex++;
                    if (scenes.size() >= 50) {
                        throw new IllegalStateException("scenes 상한(50)을 넘어서게 된다: " + scenes.size());
                    }
                    currentObjects = newPadScene(scenes, sceneIndex);
                }
                ObjectNode object = decoration("pad" + sceneIndex + "-obj" + objectIndex, "DECORATION");
                currentObjects.add(object);
                padObjects.add(object);
                objectIndex++;
            }
        }

        // 이미 넣어둔 패딩 오브젝트들의 name(1~40자)을 하나씩 최대로 채워가며 잔여 byte를 정확히
        // 맞춘다 — 매 단계 실측하므로 따옴표·키 이름 오버헤드를 손으로 셀 필요가 없다.
        for (ObjectNode object : padObjects) {
            int remaining = targetBytes - jacksonByteSize(project);
            if (remaining <= 0) {
                break;
            }
            object.put("name", "n");
            int afterOneChar = jacksonByteSize(project);
            int stillNeeded = targetBytes - afterOneChar;
            if (stillNeeded < 0) {
                // 1자짜리 name을 넣은 것만으로 넘쳐버렸다 — 이 오브젝트의 name은 없던 걸로 한다.
                object.remove("name");
                continue;
            }
            int extra = Math.min(stillNeeded, 39);
            object.put("name", "n" + "a".repeat(extra));
        }

        int finalDeficit = targetBytes - jacksonByteSize(project);
        if (finalDeficit != 0) {
            throw new IllegalStateException(
                    "패딩 오브젝트(" + padObjects.size() + "개)로도 정확히 못 맞췄다: 잔여=" + finalDeficit);
        }

        ObjectNode envelope = MAPPER_.createObjectNode();
        envelope.put("expectedRevision", 0);
        envelope.set("project", project);
        return envelope;
    }

    /** PLAYER_SPAWN 하나만 가진 새 TOP_DOWN scene을 만들어 {@code scenes}에 붙이고 objects 배열을 반환한다. */
    private ArrayNode newPadScene(ArrayNode scenes, int sceneIndex) {
        ObjectNode scene = MAPPER_.createObjectNode();
        String id = "pad" + sceneIndex;
        scene.put("id", id);
        scene.put("type", "TOP_DOWN");
        scene.put("name", "패딩 Scene " + sceneIndex);
        scene.put("width", 4);
        scene.put("height", 4);
        scene.putArray("tileLayers");
        scene.putArray("events");
        ArrayNode objects = scene.putArray("objects");
        objects.add(decoration(id + "-spawn", "PLAYER_SPAWN"));
        scenes.add(scene);
        return objects;
    }

    private ObjectNode decoration(String id, String preset) {
        ObjectNode object = MAPPER_.createObjectNode();
        object.put("id", id);
        object.put("preset", preset);
        ObjectNode position = object.putObject("position");
        position.put("x", 0);
        position.put("y", 0);
        object.put("visible", true);
        object.putArray("components");
        return object;
    }

    private static final ObjectMapper MAPPER_ = new ObjectMapper();

    private Owner owner(String prefix) {
        Long userId = GameTestSupport.createMember(users, prefix);
        Long gameId = games.save(new Game(userId, prefix + " 게임")).getId();
        return new Owner(userId, gameId);
    }

    private String draftPath(Long gameId) {
        return "/api/v1/games/" + gameId + "/draft";
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }

    private record Owner(Long userId, Long gameId) { }
}
