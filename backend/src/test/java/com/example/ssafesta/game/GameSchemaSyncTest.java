package com.example.ssafesta.game;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * S15P21A604-818 — 리뷰 발견(!993 note_2813554): {@code game-project-v1.schema.json}이 backend
 * 배포본과 {@code specs/019-game-studio/contracts/} 원본 두 벌로 손으로 맞춰지고 있어, 한쪽만
 * 고치고 다른 쪽을 잊는 드리프트가 재발할 수 있다(744가 실제로 그 드리프트를 발견하고 고친
 * 사례다). 두 파일이 byte 단위로 같은지만 확인해 이 부류를 영구히 막는다.
 *
 * <p>경로는 Maven 모듈 작업 디렉터리({@code backend/}) 기준 상대 경로다 — 두 파일 모두 이
 * 모듈의 소스 트리 밖(전자는 리소스, 후자는 형제 디렉터리)에 있어 classpath로는 후자에 닿지
 * 않는다.
 */
class GameSchemaSyncTest {

    private static final Path BACKEND_COPY =
            Path.of("src", "main", "resources", "game", "game-project-v1.schema.json");
    private static final Path SPECS_COPY =
            Path.of("..", "specs", "019-game-studio", "contracts", "game-project-v1.schema.json");

    @Test
    void backendAndSpecsSchemaCopiesStayByteIdentical() throws IOException {
        byte[] backend = Files.readAllBytes(BACKEND_COPY);
        byte[] specs = Files.readAllBytes(SPECS_COPY);

        assertArrayEquals(backend, specs,
                () -> BACKEND_COPY + "와 " + SPECS_COPY + "가 달라졌다 — 한쪽만 고치고"
                        + " 다른 쪽을 잊은 드리프트일 가능성이 높다. 두 파일을 다시 동기화하라.");
    }
}
