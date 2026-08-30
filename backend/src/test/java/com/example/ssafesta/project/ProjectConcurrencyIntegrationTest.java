package com.example.ssafesta.project;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static com.example.ssafesta.booth.BoothTestSupport.releaseAllSlots;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 부스당 프로젝트 1개가 <b>경쟁 조건에서도</b> 지켜지는지 (C-01, 불변식 I-1).
 *
 * <p>사전 조회 검사만으로는 못 막는다 — 두 요청이 각각 "없음"을 읽고 각각 INSERT 한다. 그것을
 * 막는 것은 {@code ux_projects_booth}(V14)이고, 그 위반이 <b>500이 아니라 409</b>로 나오게
 * 하는 것은 {@code saveAndFlush} 를 감싼 번역이다. 둘 중 하나만 있으면 이 테스트가 깨진다
 * (research R-02).
 *
 * <p>반복하는 이유는 {@code BoothLeaseConcurrencyIntegrationTest} 와 같다 — 한 번 통과한 경쟁은
 * 닫혔다는 증거가 되지 못한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ProjectConcurrencyIntegrationTest {

    private static final int REPEATS = 5;

    @Autowired private ProjectService projectService;
    @Autowired private ProjectRepository projects;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;

    @BeforeEach
    void freeSlots() {
        releaseAllSlots(jdbc);
    }

    @RepeatedTest(REPEATS)
    void twoRequestsRacingToRegisterProduceExactlyOneProject() throws Exception {
        Long userId = createMemberWithWallet(users, wallets, "프젝경합");
        Long boothId = booths.save(new Booth(userId, "경합 부스")).getId();
        grantLease(jdbc, boothId, userId);

        List<Outcome> outcomes = runTogether(boothId, userId);

        long created = outcomes.stream().filter(Outcome::created).count();
        assertEquals(1, created, "정확히 하나만 만들어져야 한다");
        assertEquals(1, outcomes.size() - created, "나머지는 거절돼야 한다");
        outcomes.stream().filter(o -> !o.created()).forEach(o ->
                // 500 이 아니라 409 다. save() 만 감쌌다면 유니크 위반이 커밋 시점에 터져
                // 여기서 다른 예외가 잡힌다.
                assertInstanceOf(ProjectAlreadyExistsException.class, o.failure(),
                        "경쟁에서 진 쪽도 PROJECT_ALREADY_EXISTS 여야 한다"));
        assertEquals(1, projects.findAll().stream()
                .filter(p -> p.getBoothId().equals(boothId)).count(), "행도 하나여야 한다");
    }

    private List<Outcome> runTogether(Long boothId, Long userId) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String name = "경합 " + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        projectService.create(boothId, userId, command(name));
                        return new Outcome(true, null);
                    } catch (RuntimeException exception) {
                        return new Outcome(false, exception);
                    }
                }));
            }
            start.countDown(); // 둘을 같이 놓아야 실제로 겹친다
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                // 시한을 건다. 인자 없는 get() 은 작업이 걸리면 영원히 기다리고, 그러면 실패한
                // 테스트 하나가 스위트 전체를 세운다 — CI 에서는 무엇이 멈췄는지도 안 보인다.
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    /** presence 추적 클래스라 setter 로만 채울 수 있다 — Jackson 을 그대로 쓴다. */
    private ProjectService.ProjectCommand command(String name) {
        return jsonMapper.readValue("{\"name\": \"" + name + "\"}",
                ProjectService.ProjectCommand.class);
    }

    private record Outcome(boolean created, RuntimeException failure) { }
}
