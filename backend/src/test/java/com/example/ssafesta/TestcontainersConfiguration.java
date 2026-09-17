package com.example.ssafesta;

import com.example.ssafesta.ai.FakeDocumentProcessingClientConfiguration;
import com.example.ssafesta.storage.FakeObjectStorageConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 모든 통합 테스트가 항상 쓰는 컨테이너 + Fake 어댑터. Fake 는 실제 R2·FastAPI 를 부르지 않는
 * 안전장치라 선택적으로 켤 이유가 없다 — 항상 켜두면 그만큼 {@code @Import} 조합이 갈리지 않아
 * Spring 컨텍스트 캐시 분기도 줄어든다 (GitLab #221 §4-2).
 */
@TestConfiguration(proxyBeanMethods = false)
@Import({FakeObjectStorageConfiguration.class, FakeDocumentProcessingClientConfiguration.class})
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17")
                .asCompatibleSubstituteFor("postgres"));
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379);
    }

}
