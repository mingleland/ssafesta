package com.example.ssafesta.booth;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reading and writing layout documents (research R-04).
 *
 * <p><b>Every number stays a {@link BigDecimal} from end to end.</b> That single rule is what makes
 * SC-004 ("React 미리보기와 Unity 월드의 배치가 일치한다") hold: {@code jsonb} keeps numbers as
 * {@code numeric} and loses nothing, so the only place precision could die is in our own code — and
 * it would die silently, as a coordinate that is slightly wrong rather than an error anyone sees.
 *
 * <p>Byte-for-byte preservation of the request is <b>not</b> offered, and was never available:
 * {@code jsonb} normalises key order and whitespace as it stores. "Lossless" here means the same
 * keys with the same values, which is the part any consumer can observe.
 */
public final class LayoutJson {

    /**
     * Unknown fields are rejected rather than dropped.
     *
     * <p>Silently discarding them is how a version mismatch becomes invisible: the editor would
     * believe it saved something the server threw away (the shape of T-24). A new field is a
     * 3파트 contract change and should fail loudly until every side has it (헌법 24조).
     */
    private static final JsonMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final String raw;
    private final LayoutDocument document;

    private LayoutJson(String raw, LayoutDocument document) {
        this.raw = raw;
        this.document = document;
    }

    /**
     * @throws LayoutParseException when the text is not a layout document — the caller turns this
     *         into a validation error rather than letting it escape as a 500
     */
    public static LayoutJson parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new LayoutParseException("배치 내용이 비어 있습니다.");
        }
        try {
            LayoutDocument document = STRICT.readValue(raw, LayoutDocument.class);
            return new LayoutJson(raw, document);
        } catch (JacksonException exception) {
            throw new LayoutParseException(readableReason(exception));
        }
    }

    /**
     * Reads a save request: the layout document plus the revision the editor believes it has.
     *
     * <p>Parsed here rather than bound by the controller because Spring Boot's ObjectMapper has
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} <b>off</b> — an unknown field would vanish on the way in
     * and the editor would never learn its save was partly ignored.
     */
    public static SaveRequest parseSaveRequest(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new LayoutParseException("요청 본문이 비어 있습니다.");
        }
        try {
            return STRICT.readValue(raw, SaveRequest.class);
        } catch (JacksonException exception) {
            throw new LayoutParseException(readableReason(exception));
        }
    }

    /**
     * Renders a document for storage.
     *
     * <p>Numbers survive this because they never leave {@link BigDecimal} — Jackson writes the
     * decimal it was given. Byte-for-byte preservation of the request is not on offer and never
     * was: {@code jsonb} normalises key order and whitespace on the way into the column. What must
     * survive is the <b>value</b>, and that is what this guarantees (research R-04).
     */
    public static String write(LayoutDocument document) {
        return STRICT.writeValueAsString(document);
    }

    public String raw() {
        return raw;
    }

    public LayoutDocument document() {
        return document;
    }

    /**
     * Jackson's own message carries class names and byte offsets. Neither belongs in a response
     * body ({@code GlobalExceptionHandler} keeps internals out), so only the location survives.
     */
    private static String readableReason(JacksonException exception) {
        String message = exception.getOriginalMessage();
        return message == null || message.isBlank()
                ? "배치 JSON을 읽을 수 없습니다."
                : "배치 JSON을 읽을 수 없습니다: " + message;
    }

    /** A draft save: the document plus the revision the client read (contracts/layout-api.md §3). */
    public record SaveRequest(Long expectedRevision, Integer schemaVersion, String template,
                              List<LayoutObject> objects) {

        public LayoutDocument document() {
            return new LayoutDocument(schemaVersion, template, objects);
        }
    }

    /** The whole document. Field names are the 3파트 contract (contracts/layout-api.md §1). */
    public record LayoutDocument(Integer schemaVersion, String template, List<LayoutObject> objects) { }

    /**
     * One placed object.
     *
     * <p>Coordinates are {@link BigDecimal} on purpose — see the class comment. {@code configId} and
     * {@code assetCode} are optional: a decoration has no content to point at, and an object being
     * edited may not be linked yet.
     *
     * <p>Absent optionals are omitted rather than written as {@code null}: Unity binds
     * {@code configId} to an {@code int} through {@code JsonUtility}, and a literal null there is
     * worse than a missing key.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LayoutObject(String objectId, String type, String assetCode,
                               Position position, BigDecimal rotationY, Integer configId) { }

    /** Metres, origin at the centre of the booth floor, {@code y = 0} is the ground (헌법 21조). */
    public record Position(BigDecimal x, BigDecimal y, BigDecimal z) { }
}
