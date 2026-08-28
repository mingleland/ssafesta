package com.example.ssafesta.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.UserRepository;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The Draft REST contract (contracts §Draft 저장), including everything #104 ① settled.
 *
 * <p>Every case here is one that produces no exception and no failing unit test when it goes wrong —
 * a 404 where a 204 belongs, or a sentence where a decimal belongs. They only show up as a broken
 * editor, so they are pinned at the HTTP boundary.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class GameDraftApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private GameRepository games;
    @Autowired private UserRepository users;
    @Autowired private MemberSessionService sessions;

    /**
     * A game that has never been saved answers {@code 204}, not {@code 404}.
     *
     * <p>404 would make the editor open in an error state on every new game — the failure #104 ①
     * described, invisible until someone actually clicked through the flow.
     */
    @Test
    void aGameWithNoDraftAnswersNoContent() throws Exception {
        Owner owner = owner("초안없음");

        mockMvc.perform(get(draftPath(owner.gameId())).header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isNoContent());
    }

    @Test
    void theFirstSaveUsesExpectedRevisionZeroAndReturnsOne() throws Exception {
        Owner owner = owner("최초저장");

        mockMvc.perform(put(draftPath(owner.gameId()))
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(GameTestSupport.saveRequest(0, project(owner.gameId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                // The stored project carries the server's revision, not the one the client sent. The
                // editor strict-checks these two against each other on every load.
                .andExpect(jsonPath("$.project.revision").value(1))
                .andExpect(jsonPath("$.project.gameId").value(owner.gameId()));
    }

    /**
     * A first save that claims a draft exists is a conflict, not a create.
     *
     * <p>Telling a stale editor its view is wrong is better than quietly making the row it assumed —
     * which would then be overwritten by whatever it saves next.
     */
    @Test
    void aFirstSaveWithAnyOtherExpectationConflictsAtZero() throws Exception {
        Owner owner = owner("최초충돌");

        mockMvc.perform(put(draftPath(owner.gameId()))
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(GameTestSupport.saveRequest(3, project(owner.gameId()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GAME_REVISION_CONFLICT"))
                .andExpect(jsonPath("$.errors[0].rule").value("CURRENT_REVISION"))
                // A decimal string, not a sentence. The editor parses it with /^\d+$/ to open the
                // conflict-recovery screen; prose here would fall through to a generic error with
                // nothing raised anywhere.
                .andExpect(jsonPath("$.errors[0].message").value("0"));
    }

    @Test
    void aStaleRevisionConflictsAndReportsTheCurrentOne() throws Exception {
        Owner owner = owner("낡은리비전");
        saveFirst(owner);

        mockMvc.perform(put(draftPath(owner.gameId()))
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(GameTestSupport.saveRequest(0, project(owner.gameId()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].message").value("1"));
    }

    /** The path is the truth; a disagreeing body is refused rather than corrected (FR-034). */
    @Test
    void aProjectClaimingAnotherGameIsRefusedNotCorrected() throws Exception {
        Owner owner = owner("게임불일치");
        ObjectNode project = project(owner.gameId());
        project.put("gameId", owner.gameId() + 1);

        mockMvc.perform(put(draftPath(owner.gameId()))
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(GameTestSupport.saveRequest(0, project)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GAME_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].rule").value("MALFORMED_PROJECT"));
    }

    /** An unknown field is refused, which is why the endpoint takes the body as text. */
    @Test
    void anUnknownFieldIsRefusedRatherThanDropped() throws Exception {
        Owner owner = owner("미지필드");
        ObjectNode project = project(owner.gameId());
        project.put("somethingNobodyAgreedOn", true);

        mockMvc.perform(put(draftPath(owner.gameId()))
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(GameTestSupport.saveRequest(0, project)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GAME_VALIDATION_FAILED"));
    }

    @Test
    void someoneElsesGameIsForbidden() throws Exception {
        Owner owner = owner("남의게임");
        Long stranger = GameTestSupport.createMember(users, "구경꾼");

        mockMvc.perform(get(draftPath(owner.gameId())).header("Authorization", bearerFor(stranger)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GAME_FORBIDDEN"));
    }

    @Test
    void aMissingGameIsNotFound() throws Exception {
        Long userId = GameTestSupport.createMember(users, "없는게임");

        mockMvc.perform(get(draftPath(999_999_999L)).header("Authorization", bearerFor(userId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GAME_NOT_FOUND"));
    }

    /**
     * A refused save must leave the stored document exactly as it was.
     *
     * <p>The conflict response naming the current revision is not enough on its own: a server that
     * wrote half the document and then refused would answer identically. The only way to tell those
     * apart is to read the draft back.
     */
    @Test
    void aRejectedSaveLeavesTheStoredDocumentUntouched() throws Exception {
        Owner owner = owner("거부후불변");
        saveFirst(owner);
        String storedTitle = project(owner.gameId()).path("title").asText();

        ObjectNode overwrite = project(owner.gameId());
        overwrite.put("title", "덮어써지면 안 되는 제목");
        mockMvc.perform(put(draftPath(owner.gameId()))
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(GameTestSupport.saveRequest(0, overwrite)))
                .andExpect(status().isConflict());

        mockMvc.perform(get(draftPath(owner.gameId())).header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.project.title").value(storedTitle));
    }

    private void saveFirst(Owner owner) throws Exception {
        mockMvc.perform(put(draftPath(owner.gameId()))
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(GameTestSupport.saveRequest(0, project(owner.gameId()))))
                .andExpect(status().isOk());
    }

    private ObjectNode project(Long gameId) {
        return GameTestSupport.validProjectFor(gameId);
    }

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
