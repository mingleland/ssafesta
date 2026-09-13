package com.example.ssafesta.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.user.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 휴지통이 가득 찼을 때의 축출이 오락기 바인딩에 걸리지 않는다 (S15P21A604-681).
 *
 * <p>{@code GameWithdrawalIntegrationTest} 와 같은 종류의 함정이다. 그쪽은 탈퇴가 {@code games}
 * 를 지울 때 자식 표를 빠뜨리면 외래키에서 막힌다는 것을 고정했고, 여기는 <b>축출</b>이 같은
 * 그래프를 지운다는 것을 고정한다. 두 경로가 같은 표를 지우는데 목록이 한 곳에만 있으면, V27 이
 * 그랬듯 새 자식 표가 한쪽에만 추가된다.
 *
 * <p>이 결함은 <b>아직 운영에서 재현되지 않는다</b> — 바인딩 행을 만드는 운영 코드가 없기
 * 때문이다. 오락기가 배치되는 순간 무장되므로 배치 전에 고정해 둔다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class GameTrashEvictionIntegrationTest {

    /** {@code app.game.deleted-limit} 기본값. 이 수만큼 찬 뒤 한 번 더 지우면 가장 오래된 것이 축출된다. */
    private static final int DELETED_LIMIT = 5;

    @Autowired private GameLifecycleService lifecycle;
    @Autowired private GameRepository games;
    @Autowired private ArcadeMachineBindingRepository bindings;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    /**
     * 오락기에 걸린 게임이 축출 대상이어도 삭제가 성공하고 바인딩이 함께 사라진다.
     *
     * <p>고치기 전에는 {@code games.delete} 가 {@code arcade_machine_bindings.game_id} 외래키에서
     * 실패하고 <b>요청 전체가 롤백</b>된다 — 여섯 번째 게임은 삭제되지 않고 사용자는 더 이상
     * 게임을 지울 수 없다.
     */
    @Test
    void evictingABoundGameSucceedsAndRemovesItsBinding() {
        Long userId = GameTestSupport.createMember(users, "축출");
        List<Long> gameIds = createGames(userId, DELETED_LIMIT + 1);
        Long oldest = gameIds.getFirst();
        Long lastCreated = gameIds.getLast();

        for (int index = 0; index < DELETED_LIMIT; index++) {
            lifecycle.softDelete(gameIds.get(index), userId);
        }
        String machineId = "eviction-arcade-" + oldest;
        bindings.save(new ArcadeMachineBinding(machineId, oldest));

        Optional<Long> evicted = lifecycle.softDelete(lastCreated, userId);

        assertEquals(Optional.of(oldest), evicted, "가장 오래된 삭제 게임이 축출된다");
        assertTrue(games.findById(oldest).isEmpty(), "축출된 게임이 남으면 축출이 아니다");
        assertEquals(0, count("SELECT count(*) FROM arcade_machine_bindings WHERE game_id = ?", oldest),
                "바인딩이 남으면 외래키가 다음 삭제를 막는다");
        assertTrue(bindings.findById(machineId).isEmpty());
        assertTrue(games.findById(lastCreated).orElseThrow().isDeleted(),
                "축출이 성공했으니 여섯 번째 게임도 휴지통에 들어가 있어야 한다");
    }

    /** 바인딩이 없을 때의 축출은 그대로다 — 고치면서 기존 경로를 건드리지 않았는지 본다. */
    @Test
    void evictingAnUnboundGameStillWorks() {
        Long userId = GameTestSupport.createMember(users, "축출무바인딩");
        List<Long> gameIds = createGames(userId, DELETED_LIMIT + 1);

        for (int index = 0; index < DELETED_LIMIT; index++) {
            lifecycle.softDelete(gameIds.get(index), userId);
        }

        Optional<Long> evicted = lifecycle.softDelete(gameIds.getLast(), userId);

        assertEquals(Optional.of(gameIds.getFirst()), evicted);
        assertTrue(games.findById(gameIds.getFirst()).isEmpty());
    }

    private List<Long> createGames(Long userId, int howMany) {
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < howMany; index++) {
            ids.add(games.save(new Game(userId, "축출 대상 " + index)).getId());
        }
        return ids;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
