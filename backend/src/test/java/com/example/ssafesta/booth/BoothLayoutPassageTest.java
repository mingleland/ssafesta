package com.example.ssafesta.booth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.common.ApiErrorDetail;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The passage raster (#19 ⑤ — 계약 파라미터: 0.05 m 셀, 0.22 m 침식, +z에서 flood fill,
 * 관람 띠 0.7 m·도달 50% 미만이면 경고, 고립 1 ㎡ 이상이면 경고).
 *
 * <p>A plain unit test, like {@link BoothLayoutValidationTest}: the raster reads nothing from the
 * database, and the warning names are what the frontend branches on.
 */
class BoothLayoutPassageTest {

    private final LayoutValidator validator = new LayoutValidator(
            Mockito.mock(LayoutConfigResolver.class), new LayoutPassageChecker());

    /** 열린 부스: 파츠 하나가 벽 쪽에 있고 정면이 트여 있으면 통행 경고가 없어야 한다. */
    @Test
    void anOpenBoothPassesQuietly() {
        List<ApiErrorDetail> warnings = publish("""
                [{"objectId":"kiosk","type":"SURVEY_KIOSK","position":{"x":0,"y":0,"z":-1},"rotationY":0}]
                """);

        assertFalse(hasRule(warnings, "FRONT_BLOCKED"),
                "열린 배치에 막힘 경고가 나왔습니다: " + warnings);
        assertFalse(hasRule(warnings, "ISOLATED_AREA"),
                "열린 배치에 고립 경고가 나왔습니다: " + warnings);
    }

    /**
     * 게시판 네 장이 부스 전폭을 가로막는 배치: 뒤쪽 절반이 고립되고, 그 안의 키오스크 관람
     * 띠는 어디서도 닿을 수 없다. 두 경고가 모두 나와야 하고 — 공개 자체는 막지 않으므로 —
     * error는 없어야 한다.
     *
     * <p>게시판이 둘에서 넷이 된 이유: 부스 폭이 6에서 9.4가 되면서 3 m짜리 게시판 두 장으로는
     * 더 이상 벽을 만들 수 없다 (S15P21A604-698). 잠그는 것은 "가로막으면 뒤가 고립된다"는
     * <b>규칙</b>이지 그때그때의 게시판 수가 아니다.
     */
    @Test
    void aWallAcrossTheBoothStrandsTheBack() {
        LayoutValidationResult result = validate("""
                [{"objectId":"board-1","type":"RECRUITMENT_BOARD","position":{"x":-3.2,"y":0,"z":0},"rotationY":0},
                 {"objectId":"board-2","type":"RECRUITMENT_BOARD","position":{"x":-1.1,"y":0,"z":0},"rotationY":0},
                 {"objectId":"board-3","type":"RECRUITMENT_BOARD","position":{"x":1.1,"y":0,"z":0},"rotationY":0},
                 {"objectId":"board-4","type":"RECRUITMENT_BOARD","position":{"x":3.2,"y":0,"z":0},"rotationY":0},
                 {"objectId":"kiosk","type":"SURVEY_KIOSK","position":{"x":0,"y":0,"z":-1},"rotationY":0}]
                """);

        assertFalse(result.hasErrors(), "통행은 경고지 차단이 아닙니다: " + result.errors());
        assertTrue(hasRule(result.warnings(), "ISOLATED_AREA"),
                "뒤쪽 공간이 고립됐는데 경고가 없습니다: " + result.warnings());
        assertTrue(result.warnings().stream().anyMatch(w ->
                        "FRONT_BLOCKED".equals(w.rule()) && "kiosk".equals(w.objectId())),
                "키오스크 관람 띠가 막혔는데 경고가 없습니다: " + result.warnings());
    }

    /**
     * 격자가 실제로 넓어졌는가 — 옛 6 m 벽(게시판 두 장)은 이제 부스를 가로막지 못한다.
     *
     * <p>이 단언이 없으면 격자가 120×120에 묶여 있어도 위 테스트는 그대로 통과한다. 넓어진
     * 자리를 통행 판정이 <b>바닥으로 보고 있는지</b>는 여기서만 드러난다 — 격자가 ±3이면 양옆
     * 1.7 m가 존재하지 않아 뒤가 다시 고립된다 (S15P21A604-698).
     *
     * <p>{@code FRONT_BLOCKED}는 단언하지 않는다. 벽 뒤로 돌아갈 길이 열렸어도 키오스크 관람
     * 띠는 게시판 침식에 절반 이상 먹혀 여전히 경고 대상이고, 그건 격자 폭이 아니라 띠 계산이
     * 판정하는 것이라 이 테스트가 잠글 성질이 아니다.
     */
    @Test
    void theOldSixMetreWallNoLongerCrossesTheBooth() {
        List<ApiErrorDetail> warnings = publish("""
                [{"objectId":"board-l","type":"RECRUITMENT_BOARD","position":{"x":-1.5,"y":0,"z":0},"rotationY":0},
                 {"objectId":"board-r","type":"RECRUITMENT_BOARD","position":{"x":1.5,"y":0,"z":0},"rotationY":0},
                 {"objectId":"kiosk","type":"SURVEY_KIOSK","position":{"x":0,"y":0,"z":-1},"rotationY":0}]
                """);

        assertFalse(hasRule(warnings, "ISOLATED_AREA"),
                "양옆 1.7 m가 뚫려 있으므로 고립이 아닙니다: " + warnings);
    }

    /** 장식(FURNITURE·DECORATION)은 관람할 정면이 없다 — 길을 막는 쪽으로만 계산에 든다. */
    @Test
    void decorationsGetNoViewingBandWarning() {
        List<ApiErrorDetail> warnings = publish("""
                [{"objectId":"board-l","type":"RECRUITMENT_BOARD","position":{"x":-1.5,"y":0,"z":0},"rotationY":0},
                 {"objectId":"board-r","type":"RECRUITMENT_BOARD","position":{"x":1.5,"y":0,"z":0},"rotationY":0},
                 {"objectId":"deco","type":"DECORATION","position":{"x":0,"y":0,"z":-1},"rotationY":0}]
                """);

        assertFalse(warnings.stream().anyMatch(w ->
                        "FRONT_BLOCKED".equals(w.rule()) && "deco".equals(w.objectId())),
                "장식에는 막힘 경고가 붙지 않아야 합니다: " + warnings);
    }

    private List<ApiErrorDetail> publish(String objectsJson) {
        return validate(objectsJson).warnings();
    }

    private LayoutValidationResult validate(String objectsJson) {
        String json = """
                {"schemaVersion":1,"template":"PROJECT_EXHIBITION","objects":%s}
                """.formatted(objectsJson);
        return validator.validateForPublish(LayoutJson.parse(json).document(), 1L);
    }

    private boolean hasRule(List<ApiErrorDetail> warnings, String rule) {
        return warnings.stream().anyMatch(warning -> rule.equals(warning.rule()));
    }
}
