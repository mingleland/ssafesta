package com.example.ssafesta.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.user.AccountDeletionService;
import com.example.ssafesta.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Member withdrawal removes games too (FR-040, contracts §Persistence Boundary).
 *
 * <p>This exists because adding {@code games.owner_user_id REFERENCES users(id)} silently broke
 * withdrawal: the delete of {@code users} at the end of the cascade fails on the foreign key, and no
 * existing test had a withdrawing member who owned a game. The same trap is documented on the booth
 * side in V8 — "이게 없으면 회원 탈퇴가 깨진다".
 *
 * <p>It also pins the ordering. Published versions go before games, and the composite foreign key's
 * {@code ON DELETE SET NULL (published_version)} clears the pointer on the way rather than blocking
 * the delete.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class GameWithdrawalIntegrationTest {

    @Autowired private AccountDeletionService deletions;
    @Autowired private GameRepository games;
    @Autowired private GameDraftRepository drafts;
    @Autowired private GamePublishedVersionRepository published;
    @Autowired private GamePublishService publishService;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void withdrawingRemovesGamesDraftsAndPublishedVersions() {
        Long userId = GameTestSupport.createMember(users, "탈퇴");
        Long gameId = games.save(new Game(userId, "탈퇴할 게임")).getId();
        // save() rather than insertIfAbsent(): the @Modifying query needs an ambient transaction and
        // this is setup, not the path under test — the concurrent-first-save behaviour is covered by
        // GameDraftApiIntegrationTest.
        drafts.save(new GameDraft(gameId, "1.0.0",
                GameTestSupport.write(GameTestSupport.validProjectFor(gameId)), userId));
        publishService.publish(gameId, userId, 1);

        assertEquals(1, published.highestVersionNo(gameId), "발행본이 있는 상태에서 탈퇴한다");

        deletions.deleteUserGraph(userId);

        assertTrue(games.findById(gameId).isEmpty(), "Game 이 남으면 users 삭제가 FK 로 막힌다");
        assertTrue(drafts.findById(gameId).isEmpty());
        assertEquals(0, published.highestVersionNo(gameId));
        assertEquals(0, count("SELECT count(*) FROM users WHERE id = ?", userId));
    }

    /** A soft-deleted game is still a row, so withdrawal has to take it as well. */
    @Test
    void withdrawingAlsoRemovesSoftDeletedGames() {
        Long userId = GameTestSupport.createMember(users, "탈퇴삭제본");
        Game game = games.save(new Game(userId, "휴지통 게임"));
        game.softDelete(java.time.Instant.now());
        Long gameId = games.save(game).getId();

        deletions.deleteUserGraph(userId);

        assertTrue(games.findById(gameId).isEmpty());
        assertEquals(0, count("SELECT count(*) FROM users WHERE id = ?", userId));
    }

    private int count(String sql, Long argument) {
        Integer found = jdbc.queryForObject(sql, Integer.class, argument);
        return found == null ? 0 : found;
    }
}
