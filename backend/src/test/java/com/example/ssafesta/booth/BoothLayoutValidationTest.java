package com.example.ssafesta.booth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.common.ApiErrorDetail;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Which rule fires for which input (data-model §3).
 *
 * <p>A plain unit test: none of these rules touch the database, and pinning them here means the
 * rule names — which the frontend branches on — cannot drift unnoticed.
 */
class BoothLayoutValidationTest {

    private final LayoutValidator validator =
            new LayoutValidator(Mockito.mock(LayoutConfigResolver.class));

    @Test
    void tooManyObjects() {
        assertRule("OBJECT_LIMIT", document(BoothLayoutTestSupport.decorations(13)));
    }

    @Test
    void twelveObjectsIsStillFine() {
        assertNoErrors(document(BoothLayoutTestSupport.decorations(12)));
    }

    @Test
    void duplicateObjectIds() {
        assertRule("DUPLICATE_OBJECT_ID", document("[%s,%s]".formatted(
                BoothLayoutTestSupport.object("same", "DECORATION"),
                BoothLayoutTestSupport.object("same", "FURNITURE"))));
    }

    @Test
    void anObjectIdWithSpaces() {
        assertRule("INVALID_OBJECT_ID", document("[%s]".formatted(
                BoothLayoutTestSupport.object("bad id", "DECORATION"))));
    }

    @Test
    void anUnknownType() {
        assertRule("UNKNOWN_OBJECT_TYPE", document("[%s]".formatted(
                BoothLayoutTestSupport.object("a", "HOLOGRAM"))));
    }

    /** The POC spellings Unity still reads are not writable — otherwise the old names never die. */
    @Test
    void legacyTypeNamesAreNotAcceptedForNewLayouts() {
        assertRule("UNKNOWN_OBJECT_TYPE", document("[%s]".formatted(
                BoothLayoutTestSupport.object("a", "CONSULT_DESK"))));
    }

    @Test
    void aPositionOutsideTheBooth() {
        assertRule("POSITION_OUT_OF_BOUNDS", document("""
                [{"objectId":"a","type":"DECORATION","position":{"x":3.1,"y":0,"z":0},"rotationY":0}]
                """));
    }

    @Test
    void aPositionBelowTheFloor() {
        assertRule("POSITION_OUT_OF_BOUNDS", document("""
                [{"objectId":"a","type":"DECORATION","position":{"x":0,"y":-0.1,"z":0},"rotationY":0}]
                """));
    }

    @Test
    void rotationAtThreeSixty() {
        // 360 and 0 are the same angle; allowing both would mean two spellings of one value.
        assertRule("ROTATION_OUT_OF_RANGE", document("""
                [{"objectId":"a","type":"DECORATION","position":{"x":0,"y":0,"z":0},"rotationY":360}]
                """));
    }

    @Test
    void aMissingPosition() {
        assertRule("MISSING_POSITION", document("""
                [{"objectId":"a","type":"DECORATION","rotationY":0}]
                """));
    }

    @Test
    void aFutureSchemaVersion() {
        assertRule("UNSUPPORTED_SCHEMA_VERSION", """
                {"schemaVersion":2,"template":"DEFAULT","objects":[]}
                """);
    }

    @Test
    void anUnknownTemplate() {
        assertRule("UNKNOWN_TEMPLATE", """
                {"schemaVersion":1,"template":"SPACE_STATION","objects":[]}
                """);
    }

    @Test
    void missingObjectsArray() {
        assertRule("MISSING_OBJECTS", """
                {"schemaVersion":1,"template":"DEFAULT"}
                """);
    }

    @Test
    void everyFindingCarriesARuleAndAMessage() {
        LayoutValidationResult result = validator.validateForDraft(
                LayoutJson.parse(document(BoothLayoutTestSupport.decorations(13))).document());

        for (ApiErrorDetail error : result.errors()) {
            assertFalse(error.rule() == null || error.rule().isBlank(), "rule이 비어 있습니다: " + error);
            assertFalse(error.message() == null || error.message().isBlank(),
                    "message가 비어 있습니다: " + error);
        }
    }

    /**
     * Every message the server writes is Korean — warnings included.
     *
     * <p>Warnings ride in <b>successful</b> responses (save and publish both return them), so they
     * are the one place server-authored prose reaches a client outside an error. Pinned here so the
     * Korean-only rule is enforced rather than merely true today.
     */
    @Test
    void warningsAreKoreanToo() {
        LayoutValidationResult result = validator.validateForPublish(LayoutJson.parse(document("""
                [{"objectId":"ai-1","type":"AI_AGENT","position":{"x":0,"y":0,"z":0},"rotationY":0},
                 {"objectId":"panel-1","type":"PROJECT_PANEL","position":{"x":1,"y":0,"z":1},"rotationY":0}]
                """)).document(), 1L);

        assertFalse(result.warnings().isEmpty(), "이 배치는 경고가 나와야 합니다.");
        for (ApiErrorDetail warning : result.warnings()) {
            assertTrue(warning.message().chars().anyMatch(c -> c >= 0xAC00 && c <= 0xD7A3),
                    "경고 메시지가 한글이 아닙니다: " + warning);
        }
    }

    private String document(String objectsJson) {
        return """
                {"schemaVersion":1,"template":"DEFAULT","objects":%s}
                """.formatted(objectsJson);
    }

    private void assertRule(String expectedRule, String json) {
        List<ApiErrorDetail> errors =
                validator.validateForDraft(LayoutJson.parse(json).document()).errors();
        assertTrue(errors.stream().anyMatch(error -> expectedRule.equals(error.rule())),
                expectedRule + "가 나와야 합니다. 실제: " + errors);
    }

    private void assertNoErrors(String json) {
        assertEquals(List.of(), validator.validateForDraft(LayoutJson.parse(json).document()).errors());
    }
}
