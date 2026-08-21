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

    public LayoutValidator(LayoutConfigResolver configResolver) {
        this.configResolver = configResolver;
    }

    /** The only structure the server currently understands (contracts/layout-api.md §1). */
    static final int SUPPORTED_SCHEMA_VERSION = 1;

    static final int MAX_OBJECTS = 12;

    /**
     * Booth extent: <b>6m × 6m × 6m</b>, origin at the centre of the floor (헌법 21조, 2026-08-20 확정).
     *
     * <p>The origin being central is why the horizontal limit is half the width: x and z run from
     * −3 to +3. Height is not halved — {@code y = 0} is the floor, so it runs 0 to 6.
     */
    static final BigDecimal MAX_HORIZONTAL = new BigDecimal("3");
    static final BigDecimal MAX_HEIGHT = new BigDecimal("6");

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

        if (LayoutObjectType.from(object.type()).isEmpty()) {
            result.addError("UNKNOWN_OBJECT_TYPE", objectId, "지원하지 않는 오브젝트 종류입니다: " + object.type());
        }

        checkPosition(object, objectId, result);
        checkRotation(object.rotationY(), objectId, result);
    }

    private void checkPosition(LayoutJson.LayoutObject object, String objectId,
                               LayoutValidationResult result) {
        LayoutJson.Position position = object.position();
        if (position == null || position.x() == null || position.y() == null || position.z() == null) {
            result.addError("MISSING_POSITION", objectId, "position의 x·y·z가 모두 필요합니다.");
            return;
        }
        if (outsideHorizontal(position.x()) || outsideHorizontal(position.z())) {
            result.addError("POSITION_OUT_OF_BOUNDS", objectId,
                    "부스 영역을 벗어났습니다. x·z는 ±" + MAX_HORIZONTAL + "m 이내여야 합니다.");
        }
        if (position.y().signum() < 0 || position.y().compareTo(MAX_HEIGHT) > 0) {
            result.addError("POSITION_OUT_OF_BOUNDS", objectId,
                    "y는 0 이상 " + MAX_HEIGHT + "m 이하여야 합니다. (0이 바닥)");
        }
    }

    private void checkRotation(BigDecimal rotationY, String objectId, LayoutValidationResult result) {
        if (rotationY == null) {
            result.addError("MISSING_ROTATION", objectId, "rotationY가 필요합니다.");
            return;
        }
        if (rotationY.signum() < 0 || rotationY.compareTo(FULL_TURN) >= 0) {
            result.addError("ROTATION_OUT_OF_RANGE", objectId, "rotationY는 0 이상 360 미만이어야 합니다.");
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
     *       Whether that should block publishing is C-04, still 기획·FE's to decide; when they do,
     *       this one call becomes {@code addError} and nothing else changes.
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
            if (type.requiresConfig() && object.configId() == null) {
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
