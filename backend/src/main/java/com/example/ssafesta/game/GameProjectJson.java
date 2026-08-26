package com.example.ssafesta.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * Reads and measures a GameProject document without interpreting its meaning.
 *
 * <p>Parsing is separated from validation on purpose: a body that is not JSON at all, and a JSON
 * document that breaks a rule, are different events and the contract gives them different codes
 * ({@code MALFORMED_PROJECT} versus a named rule).
 *
 * <p>Nothing here rewrites the document. FR-034 forbids coordinate clamping, unknown-field dropping
 * and reference substitution, so the bytes the client sent are the bytes that get stored — which is
 * also what makes the round trip lossless.
 */
final class GameProjectJson {

    /** Server-supported envelope versions. MINOR differences are accepted; a different MAJOR is not. */
    static final Set<String> SUPPORTED_SCHEMA_VERSIONS = Set.of("1.0.0", "1.1.0");

    /** FR-050. Draft and Publish share this cap (contracts §Draft 저장). */
    static final int MAX_PROJECT_BYTES = 2_000_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GameProjectJson() {
    }

    /**
     * @throws GameValidationFailedException the body is not a JSON object
     */
    static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            throw GameValidationFailedException.of("게임 데이터를 읽을 수 없습니다.",
                    "MALFORMED_PROJECT", "본문이 비어 있습니다.");
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(body);
        } catch (Exception cause) {
            // The parser's own message can quote the document and name internals; neither belongs in
            // a response (ErrorCode's contract). The location is enough for the editor.
            throw GameValidationFailedException.of("게임 데이터를 읽을 수 없습니다.",
                    "MALFORMED_PROJECT", "JSON 형식이 올바르지 않습니다.");
        }
        if (!node.isObject()) {
            throw GameValidationFailedException.of("게임 데이터를 읽을 수 없습니다.",
                    "MALFORMED_PROJECT", "GameProject 객체가 아닙니다.");
        }
        return node;
    }

    /**
     * The document's serialised size in <b>UTF-8 bytes</b>.
     *
     * <p>Not character count: the editor measures with {@code TextEncoder(JSON.stringify(project))}
     * and Korean titles are three bytes each, so counting characters would let a project through on
     * the server that the editor already refused — or the reverse (contracts §상한).
     */
    static int utf8Size(String body) {
        return body.getBytes(StandardCharsets.UTF_8).length;
    }

    static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception cause) {
            throw new IllegalStateException("GameProject 직렬화 실패", cause);
        }
    }

    /** {@code null} when absent or not textual — the caller decides which rule that breaks. */
    static String textAt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    /** {@code null} when absent or not an integral number. */
    static Long longAt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isIntegralNumber() ? value.asLong() : null;
    }

    static List<JsonNode> arrayAt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        List<JsonNode> out = new java.util.ArrayList<>(value.size());
        value.forEach(out::add);
        return out;
    }
}
