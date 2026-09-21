package com.example.ssafesta;

import com.example.ssafesta.ai.FakeDocumentProcessingClientConfiguration;
import com.example.ssafesta.storage.FakeObjectStorageConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 모든 통합 테스트가 항상 쓰는 컨테이너 + Fake 어댑터. Fake 는 실제 R2·FastAPI 를 부르지 않는
 * 안전장치라 선택적으로 켤 이유가 없다 — 항상 켜두면 그만큼 {@code @Import} 조합이 갈리지 않아
 * Spring 컨텍스트 캐시 분기도 줄어든다 (GitLab #221 §4-2).
 *
 * <p><b>컨테이너는 JVM 당 하나다</b> (GitLab #261, S15P21A604-945). 예전에는 컨테이너가
 * {@code @Bean} 이었고, 그러면 <b>컨텍스트 개수만큼 컨테이너가 뜬다</b> — 애노테이션 조합이
 * 갈릴 때마다 Spring 이 새 컨텍스트를 만들고 그 안에서 postgres·redis 가 새로 기동했다. 잡
 * 하나가 pgvector 7 + redis 7 + ryuk 1 = 약 15 컨테이너를 만들었고, 러너에서 back-test 3건이
 * 겹치자 45개가 되어 EC2 가 스왑에 들어갔다(load 11.21, CPU PSI some 45.20%).
 *
 * <p>그래서 컨테이너를 static 으로 올리고 빈으로 노출하지 않는다. 빈이 아니므로 Spring 의
 * 컨테이너 생명주기 관리 대상이 아니고, 컨텍스트가 닫혀도 멈추지 않는다. 연결 정보는
 * {@link DynamicPropertyRegistrar} 로 넘긴다 — 값이 모든 컨텍스트에서 같으므로 이것 때문에
 * 캐시 키가 갈리지는 않는다.
 *
 * <p><b>JVM 사이에서는 공유하지 않는다.</b> {@code withReuse} 는 쓰지 않는다 — 그것을 켜면
 * 컨테이너가 CI 잡이 끝나도 러너에 남아 다음 잡이 남의 데이터를 물려받는다 (#261 §6 금지).
 * 지금 구조는 잡이 끝나면 Ryuk 이 라벨을 보고 회수한다.
 *
 * <p>컨테이너를 공유하면 예전에 컨텍스트가 제공하던 DB 칸막이가 사라진다. 그 자리를
 * {@link TestDataReset} 이 대신한다 — 격리는 컨테이너 재시작이 아니라 데이터 리셋으로 한다.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import({FakeObjectStorageConfiguration.class, FakeDocumentProcessingClientConfiguration.class})
public class TestcontainersConfiguration {

    static final PostgreSQLContainer POSTGRES = postgres();

    static final GenericContainer<?> REDIS = redis();

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @Bean
    DynamicPropertyRegistrar sharedContainerProperties() {
        return registry -> {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
            registry.add("spring.data.redis.host", REDIS::getHost);
            registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        };
    }

    private static PostgreSQLContainer postgres() {
        var container = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17")
                .asCompatibleSubstituteFor("postgres"))
                // 컨텍스트마다 자기 Hikari 풀(기본 10)을 쥐고 캐시에 남는데 postgres 는 이제
                // 하나다. 기본 max_connections 100 으로는 컨텍스트 열 개를 못 넘긴다 —
                // 실측에서 "FATAL: sorry, too many clients already" 로 106건이 죽었다.
                .withCommand("postgres", "-c", "max_connections=300");
        label(container);
        return container;
    }

    private static GenericContainer<?> redis() {
        var container = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
                .withExposedPorts(6379);
        label(container);
        return container;
    }

    /**
     * CI 가 잡 종료 시 자기 컨테이너만 골라 지울 수 있게 소유자 라벨을 붙인다 (ci/test 의
     * cleanup_testcontainers). 로컬에서는 두 환경변수가 없어 라벨 없이 뜬다.
     */
    private static void label(GenericContainer<?> container) {
        String owner = System.getenv("TESTCONTAINERS_JOB_OWNER");
        if (owner == null || owner.isBlank()) {
            owner = System.getenv("CI_RUN_ID");
        }
        if (owner != null && !owner.isBlank()) {
            container.withLabel("org.ssafy-festa.testcontainers.owner", owner);
        }
    }

}
