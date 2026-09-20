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
                [{"objectId":"a","type":"DECORATION","position":{"x":4.8,"y":0,"z":0},"rotationY":0}]
                """));
    }

    /**
     * x와 z의 경계가 다르다 — 앵커 점 판정이 축마다 자기 한계를 보는가 (S15P21A604-698).
     *
     * <p>여기서 보는 것은 <b>앵커 점</b>뿐이다. 같은 좌표라도 실물(회전 AABB)은 더 일찍 걸릴 수
     * 있어서, 경계 안쪽은 "오류가 없다"가 아니라 "이 규칙이 없다"로 단언한다 — 두 층을 섞으면
     * 어느 쪽이 통과시킨 것인지 알 수 없다.
     */
    @Test
    void theAnchorBoundIsWiderOnXThanOnZ() {
        assertNoRule("POSITION_OUT_OF_BOUNDS", decorationAt("4.7", "0"));
        assertRule("POSITION_OUT_OF_BOUNDS", decorationAt("4.8", "0"));

        assertNoRule("POSITION_OUT_OF_BOUNDS", decorationAt("0", "3.0"));
        assertRule("POSITION_OUT_OF_BOUNDS", decorationAt("0", "3.1"));

        // x가 넓어졌다고 z까지 열리면 안 된다 — 한 상수를 양축에 쓰던 시절의 회귀 방지.
        assertRule("POSITION_OUT_OF_BOUNDS", decorationAt("0", "4.7"));
    }

    /** 실물(회전 AABB) 판정도 축별 경계를 각자 본다 — DECORATION은 로컬 ±0.30이다. */
    @Test
    void theExtentBoundIsWiderOnXThanOnZ() {
        assertNoErrors(decorationAt("4.4", "0"));   // 실물이 정확히 x = 4.7에 닿는다
        assertRule("AREA_OUT_OF_BOUNDS", decorationAt("4.41", "0"));

        assertNoErrors(decorationAt("0", "2.7"));   // 실물이 정확히 z = 3.0에 닿는다
        assertRule("AREA_OUT_OF_BOUNDS", decorationAt("0", "2.71"));
    }

    @Test
    void aPositionBelowTheFloor() {
        assertRule("POSITION_OUT_OF_BOUNDS", document("""
                [{"objectId":"a","type":"DECORATION","position":{"x":0,"y":-0.1,"z":0},"rotationY":0}]
                """));
    }

    /**
     * 셸 유효 높이는 5.9다 — 방 내부 6.0에서 천장 램프(5.94~)를 뺀 실사용 상한
     * (S15P21A604-698). 옛 2.72는 방 높이가 아니라 셸 교체 이전 벽 패널 높이였다.
     */
    @Test
    void aPositionAboveTheShellHeight() {
        assertRule("POSITION_OUT_OF_BOUNDS", document("""
                [{"objectId":"a","type":"DECORATION","position":{"x":0,"y":5.91,"z":0},"rotationY":0}]
                """));
    }

    /** 앵커 점은 안인데 실물이 벽을 넘는 배치 — 점 검사만으로는 잡히지 않던 것 (#19 ③). */
    @Test
    void anExtentPastTheWall() {
        // RECRUITMENT_BOARD는 로컬 x ±1.50이라 x=3.3이면 실물이 4.8까지 나간다 (x 경계는 4.7).
        assertRule("AREA_OUT_OF_BOUNDS", document("""
                [{"objectId":"a","type":"RECRUITMENT_BOARD","position":{"x":3.3,"y":0,"z":0},"rotationY":0}]
                """));
    }

    /**
     * 같은 위치라도 회전이 실물을 벽 밖으로 돌릴 수 있다 — 90° 스왑이 아닌 코너 회전 검증.
     *
     * <p>회전 뒤에는 로컬 x가 월드 z를 채운다. 그래서 축별 경계가 갈라진 뒤에도 판정은
     * <b>월드 축</b>을 기준으로 해야 한다 — 넓어진 x 경계(4.7)가 회전을 타고 z에 적용되면
     * 부스 뒤로 1.8 m 삐져나온 배치가 통과한다 (S15P21A604-698).
     */
    @Test
    void rotationMovesTheExtent() {
        // VIDEO_SCREEN(로컬 x −1.50~+1.20, z ±0.15)을 90° 돌리면 z 실물이 [z−1.2, z+1.5]가 된다.
        assertNoErrors(document("""
                [{"objectId":"a","type":"VIDEO_SCREEN","position":{"x":0,"y":0,"z":1.6},"rotationY":0}]
                """));
        assertRule("AREA_OUT_OF_BOUNDS", document("""
                [{"objectId":"a","type":"VIDEO_SCREEN","position":{"x":0,"y":0,"z":1.6},"rotationY":90}]
                """));

        // 거울상: 회전하지 않으면 로컬 x가 그대로 월드 x라, 같은 크기가 x에서는 4.7까지 허용된다.
        assertNoErrors(document("""
                [{"objectId":"a","type":"VIDEO_SCREEN","position":{"x":3.4,"y":0,"z":0},"rotationY":0}]
                """));
    }

    /**
     * 높이 판정은 앵커가 아니라 <b>실물 상단</b>을 본다 — 셸 5.9와의 차이가 곧 올릴 수 있는 높이다.
     *
     * <p>PROJECT_PANEL은 2.72 높이라 y = 3.18에서 상단이 정확히 5.9다. 셸이 2.72였을 때는 이 파츠가
     * 바닥에서만 성립했는데, 5.9로 올라가면서 매다는 배치가 열렸다 (S15P21A604-698).
     */
    @Test
    void theCeilingIsJudgedOnTheObjectTop() {
        assertNoErrors(document("""
                [{"objectId":"a","type":"PROJECT_PANEL","position":{"x":0,"y":3.18,"z":0},"rotationY":0}]
                """));
        assertRule("AREA_OUT_OF_BOUNDS", document("""
                [{"objectId":"a","type":"PROJECT_PANEL","position":{"x":0,"y":3.19,"z":0},"rotationY":0}]
                """));
    }

    /** 실물이 벽 세 면과 천장에 정확히 닿는 배치는 허용 — 경계선상은 안이다. */
    @Test
    void anExtentTouchingTheWallsIsAllowed() {
        // DECORATION은 로컬 ±0.30·높이 1.61 — x 4.4는 4.7에, z −2.7은 −3.0에, y 4.29는 5.9에 닿는다.
        assertNoErrors(document("""
                [{"objectId":"a","type":"DECORATION","position":{"x":4.4,"y":4.29,"z":-2.7},"rotationY":0}]
                """));
    }

    /** 회전 후의 딱 맞는 배치도 부동소수점 잡음으로 거부되면 안 된다 (EXTENT_EPS의 존재 이유). */
    @Test
    void aRotatedExtentTouchingTheWallIsAllowed() {
        // 90°에서 z 실물은 [1.5−1.2, 1.5+1.5] = [0.3, 3.0], x 실물은 ±0.15 — 전부 경계선상 이내.
        assertNoErrors(document("""
                [{"objectId":"a","type":"VIDEO_SCREEN","position":{"x":4.55,"y":0,"z":1.5},"rotationY":90}]
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

    /** 바닥에 놓인 DECORATION 하나 — 로컬 ±0.30이라 경계 계산이 눈으로 따라가진다. */
    private String decorationAt(String x, String z) {
        return document("""
                [{"objectId":"a","type":"DECORATION","position":{"x":%s,"y":0,"z":%s},"rotationY":0}]
                """.formatted(x, z));
    }

    private void assertRule(String expectedRule, String json) {
        List<ApiErrorDetail> errors =
                validator.validateForDraft(LayoutJson.parse(json).document()).errors();
        assertTrue(errors.stream().anyMatch(error -> expectedRule.equals(error.rule())),
                expectedRule + "가 나와야 합니다. 실제: " + errors);
    }

    private void assertNoRule(String unexpectedRule, String json) {
        List<ApiErrorDetail> errors =
                validator.validateForDraft(LayoutJson.parse(json).document()).errors();
        assertFalse(errors.stream().anyMatch(error -> unexpectedRule.equals(error.rule())),
                unexpectedRule + "가 나오면 안 됩니다. 실제: " + errors);
    }

    private void assertNoErrors(String json) {
        assertEquals(List.of(), validator.validateForDraft(LayoutJson.parse(json).document()).errors());
    }
}
