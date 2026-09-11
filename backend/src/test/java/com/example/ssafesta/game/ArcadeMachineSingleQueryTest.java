package com.example.ssafesta.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.user.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 오락기 해석은 <b>한 질의</b>여야 한다 (S15P21A604-602).
 *
 * <p>두 번 읽으면 탈퇴 커밋을 사이에 두고 찢어진다 — 바인딩을 보고, 탈퇴가 바인딩과 게임을 함께
 * 지우고 커밋하고, 그 다음 게임을 못 찾아 "이 기계는 걸린 게임이 없다" 를 서버 결함(500)으로
 * 보고하는 경로다. 외래키는 정지 상태의 고아 행을 막지 커밋을 가로지르는 읽기를 막지 않는다.
 *
 * <p><b>그 경합은 테스트로 재현할 수 없다.</b> 두 읽기 사이에 끼어들 seam 이 코드에 없고, 외래키
 * 때문에 "바인딩만 있고 게임이 없는" 상태를 만들 수도 없다. 그래서 결과가 아니라 <b>수단</b>을
 * 고정한다 — 질의가 하나면 한 snapshot 이므로 쌍이 전부 보이거나 전부 안 보인다.
 *
 * <p>이것이 없으면 두 SELECT 로 되돌려도 나머지 테스트가 전부 통과한다. 순차 실행만 하기 때문이다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ArcadeMachineSingleQueryTest {

    @Autowired private ArcadeMachineResolveService resolver;
    @Autowired private ArcadeMachineBindingRepository bindings;
    @Autowired private GameRepository games;
    @Autowired private UserRepository users;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @Test
    void resolvingAMachineIssuesExactlyOneStatement() {
        Long userId = GameTestSupport.createMember(users, "단일질의");
        Long gameId = games.save(new Game(userId, "단일질의 게임")).getId();
        String machineId = "single-query-arcade-" + gameId;
        bindings.save(new ArcadeMachineBinding(machineId, gameId));

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        resolver.resolve(machineId);

        assertEquals(1, statistics.getPrepareStatementCount(),
                "바인딩과 게임을 한 질의로 읽어야 한다 — 둘로 나누면 탈퇴 커밋이 그 사이에 끼어든다");
    }
}
