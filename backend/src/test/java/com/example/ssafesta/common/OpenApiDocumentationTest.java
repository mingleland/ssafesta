package com.example.ssafesta.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Swagger 문서가 사람이 읽을 수 있는 상태인지 지킨다 (S15P21A604-390).
 *
 * <p>주석 없이 추가된 endpoint 는 Swagger UI 에서 메서드 이름과 필드 목록만 보이고, 인증이 필요한지·어떤
 * 오류가 나오는지가 사라진다. 그 상태는 **조용히 통과한다** — 문서는 컴파일되지 않기 때문이다. 이 테스트가
 * 그 자리를 대신한다: 새 endpoint 가 설명 없이 들어오면 여기서 깨진다.
 *
 * <p>실제 생성된 `/v3/api-docs` 를 읽는다. 주석을 세는 것이 아니라 **문서에 무엇이 실렸는지**를 보므로,
 * 주석이 있어도 springdoc 이 집어가지 못한 경우까지 잡힌다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocumentationTest {

    /** springdoc 이 붙이는 비-HTTP 키. 이 안에 있는 것만 operation 이 아니다. */
    private static final Set<String> NOT_OPERATIONS = Set.of("parameters", "servers", "summary", "description");

    private static JsonNode document;

    @BeforeAll
    static void reset() {
        document = null;
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JsonMapper json;

    /**
     * 모든 operation 이 한 줄 요약과 본문 설명을 갖는다.
     *
     * <p>둘을 함께 요구한다. `summary` 만 있으면 목록에서는 읽히지만 펼쳐도 호출 방법을 알 수 없고,
     * `description` 만 있으면 목록이 메서드 이름으로 남는다.
     */
    @Test
    void everyOperationExplainsItself() throws Exception {
        List<String> missing = new ArrayList<>();
        forEachOperation((id, operation) -> {
            if (isBlank(operation.path("summary"))) {
                missing.add(id + " — summary 없음");
            }
            if (isBlank(operation.path("description"))) {
                missing.add(id + " — description 없음");
            }
        });
        assertTrue(missing.isEmpty(),
                "@Operation(summary, description) 이 없는 endpoint 가 있습니다:\n  " + String.join("\n  ", missing));
    }

    /**
     * 성공만 적힌 문서는 절반이다 — 오류 코드로 분기하는 클라이언트가 무엇을 준비해야 할지 모른다.
     *
     * <p><b>거절할 것이 있는 endpoint 만</b> 요구한다. 인증을 요구하거나 파라미터·본문을 받는 operation 은
     * 반드시 실패 사유가 하나 이상 있어야 하고, 그 셋 다 없는 operation(토큰 없이 부르는 무인자 조회 —
     * {@code POST /auth/guest}, {@code GET /booth-slots})은 면제한다. 그런 곳에 억지로 실패를 적으면
     * 있지도 않은 분기를 클라이언트에 약속하게 된다.
     */
    @Test
    void everyRefusableOperationDocumentsAFailure() throws Exception {
        List<String> thin = new ArrayList<>();
        forEachOperation((id, operation) -> {
            boolean canRefuse = !operation.path("security").isEmpty()
                    || !operation.path("parameters").isEmpty()
                    || operation.has("requestBody");
            if (!canRefuse) {
                return;
            }
            JsonNode responses = operation.path("responses");
            boolean hasFailure = false;
            for (String status : responses.propertyNames()) {
                if (status.startsWith("4") || status.startsWith("5")) {
                    hasFailure = true;
                }
            }
            if (!hasFailure) {
                thin.add(id + " — 실패 응답이 문서화되지 않음 (현재 " + responses.size() + "개)");
            }
        });
        assertTrue(thin.isEmpty(),
                "@ApiResponse 로 실패 사유를 적어야 합니다:\n  " + String.join("\n  ", thin));
    }

    /**
     * 자물쇠가 걸린 endpoint 는 전부 {@code 401} 을 문서화한다.
     *
     * <p>컨트롤러마다 적지 않고 {@code OpenApiConfiguration} 의 customizer 가 붙인다. 그 자동화가
     * 죽으면 "토큰이 잘못되면 무엇이 오는가"가 문서에서 통째로 사라지므로 여기서 지킨다.
     */
    @Test
    void securedOperationsDocumentTheUnauthorizedCase() throws Exception {
        List<String> missing = new ArrayList<>();
        forEachOperation((id, operation) -> {
            if (!operation.path("security").isEmpty() && !operation.path("responses").has("401")) {
                missing.add(id);
            }
        });
        assertTrue(missing.isEmpty(), "인증이 필요한데 401 이 문서에 없습니다: " + missing);
    }

    /**
     * 4xx·5xx 응답 본문이 오류 봉투 스키마를 가리킨다.
     *
     * <p>{@code OpenApiConfiguration} 의 customizer 가 자동으로 붙이므로 endpoint 마다 반복하지 않는다.
     * 그 customizer 가 죽으면 오류 응답의 모양이 문서에서 사라지고, 이 테스트가 그것을 잡는다.
     */
    @Test
    void failureResponsesCarryTheErrorEnvelopeSchema() throws Exception {
        List<String> bare = new ArrayList<>();
        forEachOperation((id, operation) -> {
            JsonNode responses = operation.path("responses");
            for (String status : responses.propertyNames()) {
                if (!status.startsWith("4") && !status.startsWith("5")) {
                    continue;
                }
                JsonNode schema = responses.path(status).path("content")
                        .path("application/json").path("schema");
                if (!schema.path("$ref").asString("").endsWith("ApiErrorResponse")) {
                    bare.add(id + " " + status);
                }
            }
        });
        assertTrue(bare.isEmpty(), "오류 응답에 봉투 스키마가 없습니다: " + bare);
        assertTrue(document().path("components").path("schemas").has("ApiErrorResponse"),
                "$ref 가 가리킬 ApiErrorResponse 스키마가 등록되지 않았습니다");
    }

    /**
     * 모든 operation 이 {@code OpenApiConfiguration} 에 선언된 태그 아래에 있다.
     *
     * <p>{@code @Tag} 를 잊으면 springdoc 이 클래스 이름으로 태그를 만든다 —
     * {@code ai-agent-controller} 같은 이름이 도메인 태그들 사이에 섞이고, 설명도 없다.
     */
    @Test
    void everyOperationSitsUnderADeclaredTag() throws Exception {
        Set<String> declared = new HashSet<>();
        for (JsonNode tag : document().path("tags")) {
            declared.add(tag.path("name").asString());
        }
        List<String> stray = new ArrayList<>();
        forEachOperation((id, operation) -> {
            JsonNode tags = operation.path("tags");
            if (tags.isEmpty()) {
                stray.add(id + " — 태그 없음");
                return;
            }
            for (JsonNode tag : tags) {
                if (!declared.contains(tag.asString())) {
                    stray.add(id + " — 선언되지 않은 태그 " + tag.asString());
                }
            }
        });
        assertTrue(stray.isEmpty(),
                "@Tag 를 OpenApiConfiguration 의 목록에 맞춰 붙여야 합니다:\n  " + String.join("\n  ", stray));
    }

    /**
     * 내부 서비스 전용 경로가 공개 문서에 실리지 않는다.
     *
     * <p>{@code /v3/api-docs} 는 인증 없이 열린다. {@code AiBoothAccessController} 는 그래서
     * {@code @Hidden} 이고, 그 결정이 깨지면 서버 간 토큰 헤더의 모양까지 공개된다.
     */
    @Test
    void internalPathsStayOutOfThePublicDocument() throws Exception {
        List<String> leaked = new ArrayList<>();
        for (String path : document().path("paths").propertyNames()) {
            if (path.startsWith("/internal")) {
                leaked.add(path);
            }
        }
        assertTrue(leaked.isEmpty(), "내부 전용 경로가 공개 문서에 실렸습니다: " + leaked);
    }

    /** 문서 자체가 비어 있으면 위 검사들이 전부 공허하게 통과한다. */
    @Test
    void theDocumentCoversTheApi() throws Exception {
        int operations = countOperations();
        assertTrue(operations >= 40, "operation 이 " + operations + "개뿐입니다 — 문서 생성이 깨졌을 수 있습니다");
        assertFalse(isBlank(document().path("info").path("description")),
                "info.description 이 비어 있습니다 — 공통 규격 설명이 사라졌습니다");
    }

    private interface OperationVisitor {
        void visit(String id, JsonNode operation);
    }

    private void forEachOperation(OperationVisitor visitor) throws Exception {
        JsonNode paths = document().path("paths");
        for (String path : paths.propertyNames()) {
            JsonNode methods = paths.path(path);
            for (String method : methods.propertyNames()) {
                if (NOT_OPERATIONS.contains(method)) {
                    continue;
                }
                visitor.visit(method.toUpperCase() + " " + path, methods.path(method));
            }
        }
    }

    private int countOperations() throws Exception {
        int[] count = {0};
        forEachOperation((id, operation) -> count[0]++);
        return count[0];
    }

    private JsonNode document() throws Exception {
        if (document == null) {
            String body = mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            document = json.readTree(body);
        }
        return document;
    }

    private static boolean isBlank(JsonNode node) {
        return node.asString("").isBlank();
    }
}
