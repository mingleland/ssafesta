package com.example.ssafesta.booth;

import java.math.BigDecimal;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * A layout document as it travels: the <b>request text exactly as received</b> plus a parsed view
 * used only for checking it.
 *
 * <p>The separation is the point (research R-04). What gets stored is {@link #raw}; the parsed
 * records never turn back into JSON. If the server parsed coordinates into {@code double} and
 * re-serialised them, SC-004 ("React 미리보기와 Unity 월드의 배치가 일치한다") would drift by
 * rounding and nobody would see it happen — {@code jsonb} itself stores numbers as {@code numeric}
 * and loses nothing, so the only place precision can die is in our own code.
 *
 * <p>{@code jsonb} does normalise key order and whitespace, so "lossless" here means <b>same keys,
 * same values</b> — not byte equality. Byte equality was never available.
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

    /** Rebuilds the pair for content already in the database, which was validated on the way in. */
    public static LayoutJson ofStored(String raw) {
        return parse(raw);
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

    /** The whole document. Field names are the 3파트 contract (contracts/layout-api.md §1). */
    public record LayoutDocument(Integer schemaVersion, String template, List<LayoutObject> objects) { }

    /**
     * One placed object.
     *
     * <p>Coordinates are {@link BigDecimal} on purpose — see the class comment. {@code configId} and
     * {@code assetCode} are optional: a decoration has no content to point at, and an object being
     * edited may not be linked yet.
     */
    public record LayoutObject(String objectId, String type, String assetCode,
                               Position position, BigDecimal rotationY, Integer configId) { }

    /** Metres, origin at the centre of the booth floor, {@code y = 0} is the ground (헌법 21조). */
    public record Position(BigDecimal x, BigDecimal y, BigDecimal z) { }
}
