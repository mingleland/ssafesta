package com.example.ssafesta.game;

import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared helpers for Game Studio tests, including the fixture loader.
 *
 * <p>The fixtures are the <b>contract's own</b> files, copied into test resources rather than
 * rewritten here. quickstart requires reproducing the same manifest, and a hand-made project would
 * drift from it the first time either side changed.
 */
final class GameTestSupport {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GameTestSupport() {
    }

    static Long createMember(UserRepository users, String prefix) {
        // nickname VARCHAR(30) 예산. 태그는 헬퍼 구분용이다 — T-103, BoothTestSupport 참고.
        return users.save(new User(prefix + "g" + SEQUENCE.incrementAndGet())).getId();
    }

    /**
     * Loads a fixture, applying its mutations when it is one of the negative ones.
     *
     * <p>The invalid fixtures are {@code {base, mutations}} rather than whole documents, so that a
     * change to the valid project cannot leave them silently testing something else. Only the two
     * operations the manifest uses are supported — {@code replace} and {@code copy} — because a
     * general JSON Patch implementation would be more code than the fixtures need.
     */
    static ObjectNode loadFixture(String path) {
        ObjectNode raw = read(path);
        JsonNode base = raw.get("base");
        if (base == null) {
            return raw;
        }
        ObjectNode document = read(resolve(path, base.asText()));
        for (JsonNode mutation : raw.withArray("mutations")) {
            apply(document, mutation);
        }
        return document;
    }

    /** {@code {"expectedRevision": n, "project": {…}}} as the save endpoint takes it. */
    static String saveRequest(int expectedRevision, ObjectNode project) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("expectedRevision", expectedRevision);
        body.set("project", project);
        return write(body);
    }

    static String publishRequest(int expectedRevision) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("expectedRevision", expectedRevision);
        return write(body);
    }

    /** The valid project, re-pointed at a game that actually exists in the test database. */
    static ObjectNode validProjectFor(Long gameId) {
        ObjectNode project = loadFixture("/game/fixtures/minimal-top-down-dialogue.json");
        project.put("gameId", gameId);
        project.put("revision", 0);
        return project;
    }

    static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception cause) {
            throw new IllegalStateException(cause);
        }
    }

    private static ObjectNode read(String resource) {
        try (InputStream source = GameTestSupport.class.getResourceAsStream(resource)) {
            if (source == null) {
                throw new IllegalStateException(resource + " 가 테스트 클래스패스에 없습니다.");
            }
            return (ObjectNode) MAPPER.readTree(source);
        } catch (Exception cause) {
            throw new IllegalStateException(resource + " 를 읽을 수 없습니다.", cause);
        }
    }

    /** {@code /game/fixtures/invalid/x.json} + {@code ../minimal.json} → {@code /game/fixtures/minimal.json}. */
    private static String resolve(String from, String relative) {
        String directory = from.substring(0, from.lastIndexOf('/'));
        String rest = relative;
        while (rest.startsWith("../")) {
            directory = directory.substring(0, directory.lastIndexOf('/'));
            rest = rest.substring(3);
        }
        return directory + "/" + rest;
    }

    private static void apply(ObjectNode document, JsonNode mutation) {
        String op = mutation.get("op").asText();
        String path = mutation.get("path").asText();
        switch (op) {
            case "replace" -> replaceAt(document, path, mutation.get("value"));
            case "copy" -> replaceAt(document, path, at(document, mutation.get("from").asText()).deepCopy());
            default -> throw new IllegalStateException("지원하지 않는 fixture op: " + op);
        }
    }

    private static JsonNode at(JsonNode root, String pointer) {
        JsonNode found = root.at(pointer);
        if (found.isMissingNode()) {
            throw new IllegalStateException("fixture pointer 가 문서에 없습니다: " + pointer);
        }
        return found;
    }

    private static void replaceAt(ObjectNode document, String path, JsonNode value) {
        int cut = path.lastIndexOf('/');
        JsonNode parent = cut == 0 ? document : at(document, path.substring(0, cut));
        String last = path.substring(cut + 1);
        if ("-".equals(last)) {
            ((ArrayNode) parent).add(value);
            return;
        }
        if (parent.isArray()) {
            ((ArrayNode) parent).set(Integer.parseInt(last), value);
            return;
        }
        ((ObjectNode) parent).set(last, value);
    }
}
