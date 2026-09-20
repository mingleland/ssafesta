package com.example.ssafesta.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.user.UserRepository;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * A database failure <b>after</b> the version row is appended and <b>before</b> the pointer moves.
 *
 * <p>This is the only ordering that can leave the two halves disagreeing, and it is the one the
 * validation-failure test cannot reach — that one refuses before writing anything. Here the insert
 * has already been flushed, so if the transaction boundary were wrong the game would end up with an
 * orphan version and a stale pointer, and the previous Published would still be the one served while
 * a newer row existed underneath it.
 *
 * <p>Injected at {@code games.save} because that is the pointer step in
 * {@link GamePublishService#publish} — the line right after {@code published.saveAndFlush}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class GamePublishRollbackIntegrationTest {

    @MockitoSpyBean private GameRepository games;

    @Autowired private GameDraftService draftService;
    @Autowired private GamePublishService publishService;
    @Autowired private GamePublishedVersionRepository published;
    @Autowired private UserRepository users;

    @Test
    void aFailureAtThePointerStepRollsBackTheAppendedVersion() {
        Long userId = GameTestSupport.createMember(users, "롤백주입");
        Long gameId = games.save(new Game(userId, "롤백 게임")).getId();
        draftService.save(gameId, userId, 0, project(gameId));
        publishService.publish(gameId, userId, 1);
        // A real v1 is live before the failure, so "이전 Published가 그대로" is something to check
        // rather than an empty state that would pass trivially.
        assertEquals(1, published.highestVersionNo(gameId));

        // Second publish: bump the draft so its revision is 2, then break the pointer step.
        draftService.save(gameId, userId, 1, project(gameId));
        doThrow(new DataAccessResourceFailureException("주입된 DB 실패"))
                .when(games).save(any(Game.class));

        assertThrows(DataAccessResourceFailureException.class,
                () -> publishService.publish(gameId, userId, 2));

        reset(games);
        assertEquals(1, published.highestVersionNo(gameId),
                "포인터가 실패했으면 append 된 version 도 남아서는 안 됩니다.");
        assertEquals(1, games.findById(gameId).orElseThrow().getPublishedVersion(),
                "이전 Published 가 그대로 서비스돼야 합니다.");
    }

    private String project(Long gameId) {
        ObjectNode project = GameTestSupport.validProjectFor(gameId);
        return GameTestSupport.write(project);
    }
}
