package com.example.ssafesta.common;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 두 endpoint 가 같은 이름의 타입을 쓰면 한쪽 문서가 다른 쪽 본문이 된다 (S15P21A604-614, GitLab #172).
 *
 * <p>springdoc 은 스키마를 <b>단순 이름</b>으로 등록한다. 서로 다른 두 클래스가 같은 단순 이름을
 * 가지면 나중 것이 앞의 것을 덮고, 덮인 쪽 endpoint 의 요청·응답 본문이 남의 필드로 문서화된다.
 * 런타임 바인딩은 각자의 Java 타입이라 멀쩡하므로 <b>테스트도 서버도 아무 말을 하지 않는다</b> —
 * 실제로 그 상태로 develop 에 있었고, FE 가 Swagger 를 읽다 발견했다.
 *
 * <p>그래서 문서가 아니라 <b>원인</b>을 본다. 생성된 문서만 보면 덮어쓴 결과가 내부적으로는
 * 일관돼 있어 어긋난 것을 알 수 없다.
 *
 * <p>고치는 방법은 {@code @Schema(name = "...")} 로 이름을 갈라 주는 것이다. 클래스 이름을 바꾸는
 * 것도 되지만, 도메인 안에서는 {@code SubmitCommand} 가 옳은 이름이라 wire 이름만 가른다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSchemaNameTest {

    private static final String OUR_PACKAGE = "com.example.ssafesta";

    /** Actuator 도 같은 타입의 bean 을 하나 등록한다 — 이름으로 지목해 우리 컨트롤러 쪽을 잡는다. */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired private MockMvc mockMvc;
    @Autowired private JsonMapper json;

    /**
     * API 표면에 나오는 두 타입이 같은 wire 이름을 갖지 않는다.
     *
     * <p>요청 본문·응답 타입에서 출발해 record 부품을 따라 내려간다 — 덮이는 것은 최상위 타입만이
     * 아니고, 중첩된 view 도 같은 방식으로 등록되기 때문이다.
     */
    @Test
    void noTwoApiTypesShareASchemaName() {
        Map<String, Set<Class<?>>> byWireName = new TreeMap<>();
        for (Class<?> exposed : exposedTypes()) {
            collect(exposed, byWireName, new HashSet<>());
        }

        List<String> collisions = byWireName.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> entry.getKey() + " ← " + entry.getValue().stream()
                        .map(Class::getName).sorted().toList())
                .toList();

        assertTrue(collisions.isEmpty(),
                "같은 이름으로 등록되는 타입이 있습니다. 나중 것이 앞의 것을 덮어 한쪽 endpoint 의"
                        + " 본문이 남의 필드로 문서화됩니다 — @Schema(name = \"…\") 로 갈라 주세요:\n  "
                        + String.join("\n  ", collisions));
    }

    /**
     * 생성된 문서가 실제로 각자의 본문을 싣는다 — #172 가 본 증상 그대로.
     *
     * <p>위 테스트는 이름이 겹치지 않는다는 것까지만 말한다. springdoc 이
     * {@code @Schema(name = …)} 을 실제로 반영하지 않는다면 이름은 유일한데 문서는 그대로
     * 틀린 상태가 되므로, 고친 수단 자체를 여기서 확인한다.
     */
    @Test
    void theDocumentGivesEachSubmitEndpointItsOwnBody() throws Exception {
        JsonNode document = json.readTree(mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        Set<String> minigame = requestBodyPropertiesOf(document,
                "/api/v1/minigames/timer-stop/sessions/{sessionId}/result");
        Set<String> survey = requestBodyPropertiesOf(document, "/api/v1/surveys/{surveyId}/responses");

        assertTrue(minigame.contains("stoppedSeconds"),
                "미니게임 결과 제출은 stoppedSeconds 를 받는다. 실제 문서: " + minigame);
        assertTrue(!minigame.contains("answers"),
                "설문 본문이 미니게임 문서에 실렸다 — 스키마 이름이 덮인 상태다: " + minigame);
        assertTrue(survey.contains("answers"),
                "설문 제출은 answers 를 받는다. 실제 문서: " + survey);
        assertTrue(!survey.contains("stoppedSeconds"),
                "미니게임 본문이 설문 문서에 실렸다: " + survey);
    }

    /** 그 경로 POST 요청 본문 스키마의 property 이름들. {@code $ref} 를 한 번 따라간다. */
    private Set<String> requestBodyPropertiesOf(JsonNode document, String path) {
        JsonNode schema = document.path("paths").path(path).path("post").path("requestBody")
                .path("content").path("application/json").path("schema");
        String ref = schema.path("$ref").asString("");
        JsonNode resolved = ref.isBlank()
                ? schema
                : document.path("components").path("schemas").path(ref.substring(ref.lastIndexOf('/') + 1));
        Set<String> names = new LinkedHashSet<>();
        resolved.path("properties").propertyNames().forEach(names::add);
        return names;
    }

    /** 컨트롤러가 실제로 주고받는 타입 — 요청 본문과 반환값. */
    private Set<Class<?>> exposedTypes() {
        Set<Class<?>> types = new LinkedHashSet<>();
        for (HandlerMethod handler : handlerMapping.getHandlerMethods().values()) {
            Method method = handler.getMethod();
            addUnwrapped(method.getGenericReturnType(), types);
            for (MethodParameter parameter : handler.getMethodParameters()) {
                if (parameter.hasParameterAnnotation(RequestBody.class)) {
                    addUnwrapped(parameter.getGenericParameterType(), types);
                }
            }
        }
        return types;
    }

    /**
     * {@code ResponseEntity<X>}·{@code List<X>} 같은 껍데기를 벗긴다.
     *
     * <p>껍데기 자체는 springdoc 이 스키마로 만들지 않으므로 충돌원이 아니다. 안에 든 것이 문서에
     * 실리는 타입이다.
     */
    private void addUnwrapped(Type type, Set<Class<?>> into) {
        if (type instanceof ParameterizedType parameterized) {
            Class<?> raw = (Class<?>) parameterized.getRawType();
            if (isOurs(raw) && !Collection.class.isAssignableFrom(raw)) {
                into.add(raw);
            }
            for (Type argument : parameterized.getActualTypeArguments()) {
                addUnwrapped(argument, into);
            }
            return;
        }
        if (type instanceof Class<?> raw && isOurs(raw)) {
            into.add(raw);
        }
    }

    /** record 부품을 따라 내려가며 wire 이름을 모은다. */
    private void collect(Class<?> type, Map<String, Set<Class<?>>> byWireName, Set<Class<?>> seen) {
        if (!isOurs(type) || !seen.add(type)) {
            return;
        }
        byWireName.computeIfAbsent(wireNameOf(type), key -> new LinkedHashSet<>()).add(type);
        if (!type.isRecord()) {
            return;
        }
        for (RecordComponent component : type.getRecordComponents()) {
            List<Class<?>> nested = new ArrayList<>();
            Set<Class<?>> unwrapped = new LinkedHashSet<>();
            addUnwrapped(component.getGenericType(), unwrapped);
            nested.addAll(unwrapped);
            for (Class<?> child : nested) {
                collect(child, byWireName, seen);
            }
        }
    }

    /** {@code @Schema(name = "…")} 이 있으면 그것이 문서에 실리는 이름이고, 없으면 단순 이름이다. */
    private static String wireNameOf(Class<?> type) {
        Schema annotation = type.getAnnotation(Schema.class);
        if (annotation != null && !annotation.name().isBlank()) {
            return annotation.name();
        }
        return type.getSimpleName();
    }

    private static boolean isOurs(Class<?> type) {
        return type.getPackageName().startsWith(OUR_PACKAGE);
    }
}
