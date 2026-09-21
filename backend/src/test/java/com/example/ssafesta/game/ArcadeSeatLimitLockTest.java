package com.example.ssafesta.game;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.CapturingStatementInspector;
import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.user.UserRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 1인 한도는 <b>주인 행을 잠근 뒤에</b> 세야 한다 (S15P21A604-942).
 *
 * <p>세고 나서 꽂는 사이에 같은 사람의 다른 요청이 같은 수를 읽으면 둘 다 통과해 세 대가 된다.
 * 자리마다 하나를 보장하는 {@code machine_id} 기본키는 <b>사람마다 몇 대인지 모른다</b> — 서로
 * 다른 캐비닛이라 충돌하지 않기 때문이다.
 *
 * <p><b>그 경합은 테스트로 재현할 수 없다.</b> 두 트랜잭션이 그 창에 겹쳐야 하는데 그 타이밍을
 * 강제할 seam 이 없다. 실제로 동시 요청 테스트는 버그가 있는 코드에서도 통과했다. 그래서
 * {@link ArcadeMachineSingleQueryTest} 와 같은 방식을 쓴다 — 결과가 아니라 <b>수단</b>을 고정한다.
 * 잠금이 나가면 두 번째 트랜잭션은 첫 번째가 커밋할 때까지 세지 못한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties =
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.example.ssafesta.CapturingStatementInspector")
class ArcadeSeatLimitLockTest {

    @Autowired private ArcadeSeatService seats;
    @Autowired private GameRepository games;
    @Autowired private UserRepository users;
    @Autowired private TransactionTemplate transactions;

    @Test
    void claimingLocksTheOwnerRowBeforeCountingSeats() {
        Long userId = GameTestSupport.createMember(users, "한도잠금");
        Game game = games.save(new Game(userId, "한도잠금 게임"));
        game.changeVisibility(GameVisibility.PUBLIC);
        games.save(game);

        CapturingStatementInspector.clear();

        transactions.executeWithoutResult(status ->
                seats.claimOnPublish(game, userId, "arcade-20"));

        // PostgreSQL 방언에서 PESSIMISTIC_WRITE 는 `for update` 가 아니라 `for no key update` 로
        // 나간다. 둘 다 같은 행에 대해 서로를 막으므로 직렬화에는 차이가 없다 — 문구만 다르다.
        List<String> onUsers = CapturingStatementInspector.matching("from users");
        assertTrue(onUsers.stream().anyMatch(sql -> sql.contains("for no key update")
                        || sql.contains("for update")),
                "주인 행을 잠그지 않고 한도를 셌습니다 — 같은 사람의 동시 요청이 한도를 넘길 수 "
                        + "있습니다. users 를 읽은 문장: " + onUsers);
    }
}
