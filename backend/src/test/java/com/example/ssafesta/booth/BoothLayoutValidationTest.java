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

    private final LayoutValidator validator = new LayoutValidator(
            Mockito.mock(LayoutConfigResolver.class), new LayoutPassageChecker());

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

    /** 셸 유효 높이는 실측 2.72다 — 옛 대칭 가정(6)으로 저장된 높이는 이제 거부된다 (#19 ②). */
    @Test
    void aPositionAboveTheShellHeight() {
        assertRule("POSITION_OUT_OF_BOUNDS", document("""
                [{"objectId":"a","type":"DECORATION","position":{"x":0,"y":2.73,"z":0},"rotationY":0}]
                """));
    }

    /** 앵커 점은 안인데 실물이 벽을 넘는 배치 — 점 검사만으로는 잡히지 않던 것 (#19 ③). */
    @Test
    void anExtentPastTheWall() {
        // RECRUITMENT_BOARD는 로컬 x ±1.50이라 x=1.6이면 실물이 3.1까지 나간다.
        assertRule("AREA_OUT_OF_BOUNDS", document("""
                [{"objectId":"a","type":"RECRUITMENT_BOARD","position":{"x":1.6,"y":0,"z":0},"rotationY":0}]
                """));
    }

    /** 같은 위치라도 회전이 실물을 벽 밖으로 돌릴 수 있다 — 90° 스왑이 아닌 코너 회전 검증. */
    @Test
    void rotationMovesTheExtent() {
        // VIDEO_SCREEN(로컬 x −1.50~+1.20, z ±0.15)을 90° 돌리면 z 실물이 [z−1.2, z+1.5]가 된다.
        assertNoErrors(document("""
                [{"objectId":"a","type":"VIDEO_SCREEN","position":{"x":0,"y":0,"z":1.6},"rotationY":0}]
                """));
        assertRule("AREA_OUT_OF_BOUNDS", document("""
                [{"objectId":"a","type":"VIDEO_SCREEN","position":{"x":0,"y":0,"z":1.6},"rotationY":90}]
                """));
    }

    /** 최고 파츠(2.72)는 바닥에서만 성립한다 — 셸 높이와 정확히 같아서다 (#19 ② 교차 검증). */
    @Test
    void theTallestPartsFitOnlyOnTheFloor() {
        assertNoErrors(document("""
                [{"objectId":"a","type":"PROJECT_PANEL","position":{"x":0,"y":0,"z":0},"rotationY":0}]
                """));
        assertRule("AREA_OUT_OF_BOUNDS", document("""
                [{"objectId":"a","type":"PROJECT_PANEL","position":{"x":0,"y":0.01,"z":0},"rotationY":0}]
                """));
    }

    /** 실물이 벽 세 면에 정확히 닿는 배치는 허용 — 경계선상은 안이다. */
    @Test
    void anExtentTouchingTheWallsIsAllowed() {
        assertNoErrors(document("""
                [{"objectId":"a","type":"DECORATION","position":{"x":2.7,"y":1.11,"z":-2.7},"rotationY":0}]
                """));
    }

    /** 회전 후의 딱 맞는 배치도 부동소수점 잡음으로 거부되면 안 된다 (EXTENT_EPS의 존재 이유). */
    @Test
    void aRotatedExtentTouchingTheWallIsAllowed() {
        // 90°에서 z 실물은 [1.5−1.2, 1.5+1.5] = [0.3, 3.0], x 실물은 ±0.15 — 전부 경계선상 이내.
        assertNoErrors(document("""
                [{"objectId":"a","type":"VIDEO_SCREEN","position":{"x":2.85,"y":0,"z":1.5},"rotationY":90}]
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
                {"schemaVersion":2,"template":"PROJECT_EXHIBITION","objects":[]}
                """);
    }

    @Test
    void anUnknownTemplate() {
        assertRule("UNKNOWN_TEMPLATE", """
                {"schemaVersion":1,"template":"SPACE_STATION","objects":[]}
                """);
    }

    /** DEFAULT는 #19 ④에서 제거됐다 — 셸이 1종이라 "DEFAULT는 어느 셸인가"에 답이 없다. */
    @Test
    void theRetiredDefaultTemplateIsRefused() {
        assertRule("UNKNOWN_TEMPLATE", """
                {"schemaVersion":1,"template":"DEFAULT","objects":[]}
                """);
    }

    @Test
    void missingObjectsArray() {
        assertRule("MISSING_OBJECTS", """
                {"schemaVersion":1,"template":"PROJECT_EXHIBITION"}
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
                {"schemaVersion":1,"template":"PROJECT_EXHIBITION","objects":%s}
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
