package com.example.ssafesta.booth;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Checks a layout before it is stored or published (spec 005 FR-007, data-model §3).
 *
 * <p>Nothing here trusts the request: the type list, the object cap and the bounds are the server's
 * (헌법 16·22조). The validator also never <b>repairs</b> anything — no clamping a coordinate into
 * range, no dropping an unknown type. A silent repair is how T-24 happened: the client believed it
 * had saved one thing while the server stored another.
 */
@Component
public class LayoutValidator {

    private final LayoutConfigResolver configResolver;
    private final LayoutPassageChecker passageChecker;

    public LayoutValidator(LayoutConfigResolver configResolver, LayoutPassageChecker passageChecker) {
        this.configResolver = configResolver;
        this.passageChecker = passageChecker;
    }

    /** The only structure the server currently understands (contracts/layout-api.md §1). */
    static final int SUPPORTED_SCHEMA_VERSION = 1;

    static final int MAX_OBJECTS = 12;

    /**
     * Booth extent: <b>6m × 6m × 2.72m</b>, origin at the centre of the floor (헌법 21조).
     *
     * <p>The origin being central is why the horizontal limit is half the width: x and z run from
     * −3 to +3. Height is not halved — {@code y = 0} is the floor. 높이는 대칭 가정의 6이었다가
     * 셸 프리팹 실측(벽 패널 상단 y = 2.725)으로 <b>2.72</b>가 됐다 (#19 ②, 2026-08-21 확정).
     * 최고 파츠 두 종(PROJECT_PANEL·RECRUITMENT_BOARD)이 정확히 2.72라 이 둘은 y = 0에서만
     * 놓일 수 있다 — 의도된 결과다.
     */
    static final BigDecimal MAX_HORIZONTAL = new BigDecimal("3");
    static final BigDecimal MAX_HEIGHT = new BigDecimal("2.72");

    private static final double HALF_WIDTH = MAX_HORIZONTAL.doubleValue();
    private static final double HEIGHT = MAX_HEIGHT.doubleValue();

    /**
     * Float-noise slack for the rotated-extent comparison only. cos(90°)가 정확히 0이 아니라서
     * (6.1e-17), 벽에 꼭 맞춘 배치가 1e-16만큼 "벗어났다"고 거부되는 것을 막는다. 실제 위반은
     * 래스터 해상도(0.05 m)보다 9자리 작은 이 값으로는 통과할 수 없다.
     */
    private static final double EXTENT_EPS = 1e-9;

    private static final BigDecimal FULL_TURN = new BigDecimal("360");
    private static final Pattern OBJECT_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /**
     * Saving a draft: structure and limits only.
     *
     * <p>Completeness rules stay out — an object being edited may not be linked to anything yet, and
     * refusing to save half-finished work would make the editor unusable. The cap, though, is
     * enforced here as well as on publish: checking it only on publish lets a broken editor grow
     * {@code layout_json} unbounded and the user finds out much later (research R-05).
     */
    public LayoutValidationResult validateForDraft(LayoutJson.LayoutDocument document) {
        LayoutValidationResult result = new LayoutValidationResult();
        checkStructure(document, result);
        return result;
    }

    /**
     * Publishing: everything the draft check does, plus ownership of linked content and the
     * completeness warnings.
     *
     * <p>Ownership is checked here and not on save because a half-built booth legitimately points
     * at content that does not exist yet — refusing to save that would make the editor unusable.
     * Publishing is the moment it has to be true.
     */
    public LayoutValidationResult validateForPublish(LayoutJson.LayoutDocument document, Long boothId) {
        LayoutValidationResult result = new LayoutValidationResult();
        checkStructure(document, result);
        checkContentLinks(document, boothId, result);
        if (!result.hasErrors()) {
            // 통행 판정(#19 ⑤)은 좌표가 전부 유효할 때만 성립한다. error가 있으면 공개 자체가
            // 거부되므로 여기서 계산해 봐야 실릴 응답이 없다.
            passageChecker.check(document.objects(), result);
        }
        return result;
    }

    private void checkStructure(LayoutJson.LayoutDocument document, LayoutValidationResult result) {
        if (document.schemaVersion() == null || document.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            // Storing a future version would read back as something the server cannot interpret.
            result.addError("UNSUPPORTED_SCHEMA_VERSION",
                    "지원하지 않는 schemaVersion입니다. 현재: " + SUPPORTED_SCHEMA_VERSION);
        }
        if (LayoutTemplate.from(document.template()).isEmpty()) {
            result.addError("UNKNOWN_TEMPLATE", "지원하지 않는 템플릿입니다: " + document.template());
        }
        if (document.objects() == null) {
            result.addError("MISSING_OBJECTS", "objects 배열이 없습니다. 빈 배치는 []로 보냅니다.");
            return;
        }
        if (document.objects().size() > MAX_OBJECTS) {
            result.addError("OBJECT_LIMIT",
                    "오브젝트는 " + MAX_OBJECTS + "개까지입니다. 현재 " + document.objects().size() + "개");
        }

        Set<String> seenIds = new HashSet<>();
        for (LayoutJson.LayoutObject object : document.objects()) {
            checkObject(object, seenIds, result);
        }
    }

    private void checkObject(LayoutJson.LayoutObject object, Set<String> seenIds,
                             LayoutValidationResult result) {
        String objectId = object.objectId();
        if (objectId == null || !OBJECT_ID.matcher(objectId).matches()) {
            result.addError("INVALID_OBJECT_ID", objectId,
                    "objectId는 1~64자의 영문·숫자·하이픈·밑줄이어야 합니다.");
        } else if (!seenIds.add(objectId)) {
            // Duplicates make it undecidable which one Unity should keep.
            result.addError("DUPLICATE_OBJECT_ID", objectId, "objectId가 중복됩니다.");
        }

        LayoutObjectType type = LayoutObjectType.from(object.type()).orElse(null);
        if (type == null) {
            result.addError("UNKNOWN_OBJECT_TYPE", objectId, "지원하지 않는 오브젝트 종류입니다: " + object.type());
        }

        boolean positionOk = checkPosition(object, objectId, result);
        boolean rotationOk = checkRotation(object.rotationY(), objectId, result);
        if (type != null && positionOk && rotationOk) {
            checkExtent(type, object, objectId, result);
        }
    }

    private boolean checkPosition(LayoutJson.LayoutObject object, String objectId,
                                  LayoutValidationResult result) {
        LayoutJson.Position position = object.position();
        if (position == null || position.x() == null || position.y() == null || position.z() == null) {
            result.addError("MISSING_POSITION", objectId, "position의 x·y·z가 모두 필요합니다.");
            return false;
        }
        if (outsideHorizontal(position.x()) || outsideHorizontal(position.z())) {
            result.addError("POSITION_OUT_OF_BOUNDS", objectId,
                    "부스 영역을 벗어났습니다. x·z는 ±" + MAX_HORIZONTAL + "m 이내여야 합니다.");
        }
        if (position.y().signum() < 0 || position.y().compareTo(MAX_HEIGHT) > 0) {
            result.addError("POSITION_OUT_OF_BOUNDS", objectId,
                    "y는 0 이상 " + MAX_HEIGHT + "m 이하여야 합니다. (0이 바닥)");
        }
        return true;
    }

    private boolean checkRotation(BigDecimal rotationY, String objectId, LayoutValidationResult result) {
        if (rotationY == null) {
            result.addError("MISSING_ROTATION", objectId, "rotationY가 필요합니다.");
            return false;
        }
        if (rotationY.signum() < 0 || rotationY.compareTo(FULL_TURN) >= 0) {
            result.addError("ROTATION_OUT_OF_RANGE", objectId, "rotationY는 0 이상 360 미만이어야 합니다.");
            return false;
        }
        return true;
    }

    /**
     * 영역 이탈 — 앵커 점이 아니라 <b>오브젝트 실물</b>이 부스 안에 있는가 (#19 ③, 2026-08-21 확정).
     *
     * <p>점 검사만으로는 앵커는 안에 있고 실물 절반이 옆 슬롯에 걸치는 배치를 통과시킨다.
     * 실물은 타입별 실측 AABB({@link LayoutObjectType#localBounds()})를 원점 기준으로 회전한 뒤
     * 다시 AABB로 잡아 판정한다. 남의 슬롯을 침범하는 객관적 결함이라 warning이 아니라 error다 —
     * 통행 고립(소유자의 선택일 수 있는 것)과 강제력을 가른 #19 ⑤ 합의.
     */
    private void checkExtent(LayoutObjectType type, LayoutJson.LayoutObject object, String objectId,
                             LayoutValidationResult result) {
        LayoutJson.Position position = object.position();
        LayoutGeometry.WorldAabb box = LayoutGeometry.worldAabb(type.localBounds(),
                position.x().doubleValue(), position.y().doubleValue(), position.z().doubleValue(),
                object.rotationY().doubleValue());
        boolean outHorizontal = box.minX() < -HALF_WIDTH - EXTENT_EPS
                || box.maxX() > HALF_WIDTH + EXTENT_EPS
                || box.minZ() < -HALF_WIDTH - EXTENT_EPS
                || box.maxZ() > HALF_WIDTH + EXTENT_EPS;
        boolean outVertical = box.minY() < -EXTENT_EPS || box.maxY() > HEIGHT + EXTENT_EPS;
        if (outHorizontal || outVertical) {
            result.addError("AREA_OUT_OF_BOUNDS", objectId,
                    "오브젝트 실물(회전 반영)이 부스 영역을 벗어났습니다. x·z는 ±" + MAX_HORIZONTAL
                            + "m, 높이는 " + MAX_HEIGHT + "m 이내여야 합니다.");
        }
    }

    /**
     * Content links: who owns them, and whether they are there at all.
     *
     * <p>Three outcomes, deliberately different:
     *
     * <ul>
     *   <li><b>error</b> — the reference belongs to another booth. Client claims are not trusted
     *       (헌법 16·17조).
     *   <li><b>warning</b> {@code CONFIG_NOT_LINKED} — a functional object points at nothing.
     *       C-04 settled this as warn-and-allow (2026-08-21, #45); if it is ever reopened, these
     *       calls become {@code addError} and nothing else changes.
     *       <p>{@code LAPTOP} asks a different question for the same warning: since C-01 fixed the
     *       homepage URL onto {@code booths.homepage_url}, a laptop never carries a {@code configId}
     *       at all, and judging it by one would flag every correctly configured booth forever. The
     *       code and the envelope stay put; only the predicate moves to "does this booth have a URL"
     *       (spec 016 contracts/homepage-api.md §3-1).
     *       <p>{@code requiresConfig} is deliberately left {@code true} for it —
     *       {@link LayoutPassageChecker} reads the same flag to decide which objects need a viewing
     *       band, and clearing it would drop laptops out of that check entirely (research R-10).
     *   <li><b>warning</b> {@code CONFIG_UNVERIFIED} — the kind of content cannot be checked yet
     *       because the spec that owns it does not exist. Said out loud so "no error" is not
     *       mistaken for "verified".
     * </ul>
     */
    private void checkContentLinks(LayoutJson.LayoutDocument document, Long boothId,
                                   LayoutValidationResult result) {
        if (document.objects() == null) {
            return;
        }
        for (LayoutJson.LayoutObject object : document.objects()) {
            LayoutObjectType type = LayoutObjectType.from(object.type()).orElse(null);
            if (type == null) {
                continue; // Already reported as UNKNOWN_OBJECT_TYPE.
            }
            if (type == LayoutObjectType.LAPTOP) {
                if (!configResolver.boothHomepageRegistered(boothId)) {
                    result.addWarning("CONFIG_NOT_LINKED", object.objectId(),
                            "홈페이지 주소가 등록되지 않았습니다.");
                }
                // Falls through to the configId chain on purpose: a LAPTOP should not carry one, and
                // if it does, CONFIG_UNVERIFIED is how FE hears about it (계약 §3-1 통보 1).
            } else if (type.requiresConfig() && object.configId() == null) {
                result.addWarning("CONFIG_NOT_LINKED", object.objectId(),
                        type.name() + "에 연결된 콘텐츠가 없습니다.");
                continue;
            }
            if (object.configId() == null) {
                continue;
            }
            if (!configResolver.belongsToBooth(type, object.configId(), boothId)) {
                result.addError("CONFIG_NOT_OWNED", object.objectId(),
                        "이 부스의 콘텐츠가 아닙니다. configId=" + object.configId());
            } else if (!configResolver.isVerifiable(type)) {
                result.addWarning("CONFIG_UNVERIFIED", object.objectId(),
                        type.name() + "의 연결 대상은 아직 서버가 확인할 수 없습니다.");
            }
        }
    }

    private boolean outsideHorizontal(BigDecimal value) {
        return value.abs().compareTo(MAX_HORIZONTAL) > 0;
    }
}
