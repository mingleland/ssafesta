package com.example.ssafesta.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.CapturingStatementInspector;
import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.user.UserRepository;
import java.util.List;
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
 *
 * <p><b>재는 방법을 바꿨다 (S15P21A604-685).</b> 원래는 {@code Statistics#getPrepareStatementCount()}
 * 를 읽었는데 그 카운터는 세션이 아니라 {@code SessionFactory} <b>전역</b>이라, 같은 컨텍스트에서
 * 도는 {@code @Scheduled} 스위퍼가 측정 구간에 문장을 하나만 쏴도 숫자가 어긋났다. 실제로 spec
 * 011 의 스위퍼가 여덟 번째로 붙었을 때 이 테스트가 깨졌다 (T-154).
 *
 * <p>지금은 <b>실행된 SQL 본문을 캡처해 텍스트로 거른다.</b> {@code arcade_machine_bindings} 를
 * 읽는 문장만 세므로 다른 표를 건드리는 배경 작업은 애초에 집계에 들어오지 않는다. 같은 전역
 * 수집이지만 판정이 SQL 에 매여 있어 관계없는 문장에 흔들리지 않는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties =
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.example.ssafesta.CapturingStatementInspector")
class ArcadeMachineSingleQueryTest {

    @Autowired private ArcadeMachineResolveService resolver;
    @Autowired private ArcadeMachineBindingRepository bindings;
    @Autowired private GameRepository games;
    @Autowired private UserRepository users;

    @Test
    void resolvingAMachineIssuesExactlyOneStatement() {
        Long userId = GameTestSupport.createMember(users, "단일질의");
        Long gameId = games.save(new Game(userId, "단일질의 게임")).getId();
        String machineId = "single-query-arcade-" + gameId;
        bindings.save(new ArcadeMachineBinding(machineId, gameId));

        CapturingStatementInspector.clear();

        resolver.resolve(machineId);

        List<String> bindingReads = CapturingStatementInspector.matching("arcade_machine_bindings");
        assertEquals(1, bindingReads.size(),
                "바인딩과 게임을 한 질의로 읽어야 한다 — 둘로 나누면 탈퇴 커밋이 그 사이에 끼어든다."
                        + " 실행된 문장: " + bindingReads);
        assertTrue(bindingReads.getFirst().contains("games"),
                "게임이 같은 문장에 없다 — 따로 읽으면 두 snapshot 이 갈린다: " + bindingReads.getFirst());
    }

    @Test
    void resolvingAllMachinesIssuesExactlyOneStatement() {
        Long userId = GameTestSupport.createMember(users, "목록단일질의");
        Long gameId = games.save(new Game(userId, "목록단일질의 게임")).getId();
        bindings.save(new ArcadeMachineBinding("all-query-arcade-" + gameId, gameId));

        CapturingStatementInspector.clear();

        resolver.resolveAll();

        List<String> bindingReads = CapturingStatementInspector.matching("arcade_machine_bindings");
        assertEquals(1, bindingReads.size(),
                "목록도 바인딩과 게임을 한 질의로 읽어야 탈퇴 커밋 사이에서 찢어지지 않는다. 실행된 문장: "
                        + bindingReads);
        assertTrue(bindingReads.getFirst().contains("games"),
                "게임이 같은 목록 질의에 없다: " + bindingReads.getFirst());
    }
}
