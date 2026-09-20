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
     * Booth extent: <b>9.4m × 6m × 5.9m</b>, origin at the centre of the floor (헌법 21조).
     *
     * <p>The origin being central is why the horizontal limits are half the width: x runs from
     * −4.7 to +4.7 and z from −3 to +3. Height is not halved — {@code y = 0} is the floor.
     *
     * <p><b>x·z가 갈라진 이유</b> (S15P21A604-698, GitLab #181, 2026-09-15 확정): 실제 셸 내부는
     * x −4.804 ~ +4.926 · z −3.719 ~ +6.875 (중심 x +0.061 · z +1.578)인데 배치 가능 범위는
     * 6×6이었다 — 방의 34%만 쓰고 있었다. 대칭 {@code footprint {width, depth}} 스키마를 유지하면서
     * 넓힐 수 있는 축이 x다. <b>z는 6을 유지</b>한다: 입구 쪽 여백은 추적 카메라(기본 1.7m·최대 3.4m)가
     * 벽에 눌리지 않게 두는 의도된 공간이고, z를 더 쓰려면 방의 z 중심이 +1.578이라 비대칭 범위가 돼
     * 대칭 스키마로 표현할 수 없다. 비대칭 min/max 계약이 필요해지면 그때 위 네 값을 쓴다.
     *
     * <p>x가 ±4.8이 아니라 <b>±4.7</b>인 이유: 서쪽에서 실제로 닿는 면은 구조 벽이 아니라
     * {@code PanelGraphic} −4.804라 ±4.8은 여유가 4mm다 — {@code DEVICE_TABLET}(half-x 0.087)조차
     * 놓을 수 없으면서 편집기에는 빈자리로 보인다.
     *
     * <p>높이는 대칭 가정의 6이었다가 셸 프리팹 실측으로 2.72(벽 패널 상단 y = 2.725)가 됐고,
     * 셸 교체 뒤 <b>5.9</b>가 됐다 — 2.72는 방 높이가 아니라 <i>옛</i> 벽 패널 높이였다. 방 내부는
     * 6.0이고 천장 램프가 5.94부터라 실사용 상한 5.9를 쓴다 (6.0으로 두면 램프와 겹치는 배치가
     * 통과한다). 서버에 지지대 검사가 없어 공중에 뜬 집기가 통과하는 범위도 함께 넓어지는데,
     * 램프 트러스를 매달려면 필요한 자유도라 이번에 막지 않기로 FE와 합의했다 (#181 §5, 별건).
     */
    static final BigDecimal MAX_X = new BigDecimal("4.7");
    static final BigDecimal MAX_Z = new BigDecimal("3");
    static final BigDecimal MAX_HEIGHT = new BigDecimal("5.9");

    private static final double HALF_WIDTH = MAX_X.doubleValue();
    private static final double HALF_DEPTH = MAX_Z.doubleValue();
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
        if (outside(position.x(), MAX_X) || outside(position.z(), MAX_Z)) {
            result.addError("POSITION_OUT_OF_BOUNDS", objectId,
                    "부스 영역을 벗어났습니다. x는 ±" + MAX_X + "m, z는 ±" + MAX_Z + "m 이내여야 합니다.");
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
                || box.minZ() < -HALF_DEPTH - EXTENT_EPS
                || box.maxZ() > HALF_DEPTH + EXTENT_EPS;
        boolean outVertical = box.minY() < -EXTENT_EPS || box.maxY() > HEIGHT + EXTENT_EPS;
        if (outHorizontal || outVertical) {
            result.addError("AREA_OUT_OF_BOUNDS", objectId,
                    "오브젝트 실물(회전 반영)이 부스 영역을 벗어났습니다. x는 ±" + MAX_X + "m, z는 ±"
                            + MAX_Z + "m, 높이는 " + MAX_HEIGHT + "m 이내여야 합니다.");
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
     *       <p>{@code SURVEY_KIOSK} moved the same way (spec 010 C-06: the binding is per booth).
     *       A booth holds at most one survey and {@code GET /booths/{boothId}/survey/run} finds it
     *       by booth, so the kiosk's {@code configId} is a value nobody reads — judging the kiosk by
     *       it warned about correctly configured booths and stayed quiet about the one case that
     *       actually breaks: published kiosk, no survey, visitor gets "설문을 찾을 수 없습니다"
     *       (GitLab #181). The predicate is now "does this booth have a survey".
     *       <p>It leaves the chain outright rather than falling through like {@code LAPTOP}: with no
     *       identifier to own, neither {@code CONFIG_NOT_OWNED} nor {@code CONFIG_UNVERIFIED} is
     *       true of it any more. {@code requiresConfig} stays {@code true} for the same viewing-band
     *       reason as above.
     *       <p>{@code PROJECT_PANEL} is the third to move, on the same grounds (spec 009 C-01,
     *       GitLab #194). A booth holds at most one project ({@code ux_projects_booth}) and the
     *       visitor contract carries no id at all — {@code BOOTH_PROJECT_INTERACT} is
     *       {@code {boothId, objectId}}. The failure it closes is the kiosk's twin: panel published,
     *       booth has no project, the visitor presses F and gets an empty overlay. The predicate is
     *       "does this booth have a project".
     *       <p>Three types now take this exception, and it is still one flag. Splitting
     *       {@code requiresConfig} into a second "needs a viewing band" flag was considered and
     *       dropped: the flag has exactly two readers — the branch below and
     *       {@link LayoutPassageChecker} — and each of these types leaves the chain before reaching
     *       the branch, so the passage check keeps seeing them with nothing to split.
     *       <p>{@code VIDEO_SCREEN} went the other way in the end: the game part decided not to make
     *       video playable this festival, so it is <b>decorative</b> now (GitLab #194 ②,
     *       S15P21A604-889). It needs no split either — the flag being {@code false} is the whole
     *       answer, and it drops out of the passage check for the same reason furniture does.
     *       Decorative objects leave the chain before any {@code configId} question: one arriving
     *       from an older FE is stored, ignored, and <b>not</b> warned about.
     *   <li><b>warning</b> {@code CONFIG_UNVERIFIED} — the server does not judge this kind of
     *       content yet. Said out loud so "no error" is not mistaken for "verified". What is left
     *       here is not a missing spec any more but a missing check: each kind gets its own line in
     *       {@link LayoutConfigResolver} as its ownership question is settled.
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
            } else if (type == LayoutObjectType.SURVEY_KIOSK) {
                if (!configResolver.boothSurveyRegistered(boothId)) {
                    result.addWarning("CONFIG_NOT_LINKED", object.objectId(),
                            "이 부스에 설문이 없습니다.");
                }
                // Unlike LAPTOP this does not fall through: the kiosk's configId identifies nothing
                // (spec 010 C-06), so there is no owner to check and nothing left unverified.
                continue;
            } else if (type == LayoutObjectType.PROJECT_PANEL) {
                if (!configResolver.boothProjectRegistered(boothId)) {
                    result.addWarning("CONFIG_NOT_LINKED", object.objectId(),
                            "이 부스에 프로젝트가 없습니다.");
                }
                // Leaves the chain like SURVEY_KIOSK, and for the same reason (spec 009 C-01).
                continue;
            } else if (!type.requiresConfig()) {
                // 장식(FURNITURE·DECORATION·VIDEO_SCREEN)은 가리킬 콘텐츠가 없다. configId 가
                // 실려 와도 저장하되 무시하고 경고도 내지 않는다 (GitLab #194 ②) — 구버전 FE 가
                // 아직 보낼 수 있어 거부는 과하고, 무시하면서 CONFIG_UNVERIFIED 만 남기면 FE 는
                // 고칠 것이 없는 경고를 영구히 본다.
                continue;
            } else if (object.configId() == null) {
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

    private boolean outside(BigDecimal value, BigDecimal limit) {
        return value.abs().compareTo(limit) > 0;
    }
}
