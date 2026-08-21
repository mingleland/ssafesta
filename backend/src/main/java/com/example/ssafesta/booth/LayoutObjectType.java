package com.example.ssafesta.booth;

import java.util.Optional;

/**
 * The object kinds a booth layout may contain (spec 005 §공통 계약, 3파트 canonical 문자열).
 *
 * <p>This list is a <b>server-side whitelist</b>, which is what keeps 헌법 4조 true: adding a kind
 * is a data change here, not a Unity rebuild. Unity separately ignores kinds it does not know
 * (SC-005), so the two sides can move at different speeds.
 *
 * <p>The POC-era spellings {@code SURVEY} and {@code CONSULT_DESK} are deliberately absent. Unity
 * still <i>reads</i> them for frozen v0.0.1 data, but nothing new may be stored under them —
 * otherwise the old names never die.
 *
 * <p>Each kind carries its <b>local AABB</b> — Unity 프리팹 실측값, rotationY=0 기준, 원점은 바닥
 * (min.y = 0), 단위는 미터 (#19 ③, 2026-08-21 확정). min/max를 그대로 든 이유: x·z가 파츠별로
 * 비대칭이라 size만 주고 중앙 원점을 가정하면 회전 계산이 틀린다. 타입당 프리팹이 2개 이상이
 * 되는 날에는 가장 큰 프리팹 기준의 포락으로 갱신한다(#19 합의 — 서버가 보수적인 쪽).
 */
public enum LayoutObjectType {

    AI_AGENT(true, new LocalBounds(-0.31, 0, -0.16, 0.31, 1.15, 0.16)),
    VIDEO_SCREEN(true, new LocalBounds(-1.50, 0, -0.15, 1.20, 2.10, 0.15)),
    PROJECT_PANEL(true, new LocalBounds(-0.78, 0, -0.18, 0.77, 2.72, 0.18)),
    SURVEY_KIOSK(true, new LocalBounds(-0.31, 0, -0.16, 0.31, 0.93, 0.16)),
    RECRUITMENT_BOARD(true, new LocalBounds(-1.50, 0, -0.18, 1.50, 2.72, 0.18)),
    CONSULTATION_DESK(true, new LocalBounds(-0.93, 0, -1.00, 0.93, 0.92, 0.16)),
    LAPTOP(true, new LocalBounds(-0.40, 0, -0.40, 0.40, 0.94, 0.40)),
    LIKE_VOTE(true, new LocalBounds(-0.31, 0, -0.16, 0.31, 1.23, 0.16)),
    FURNITURE(false, new LocalBounds(-0.61, 0, -0.86, 0.89, 0.75, 0.86)),
    DECORATION(false, new LocalBounds(-0.30, 0, -0.30, 0.30, 1.61, 0.30));

    private final boolean requiresConfig;
    private final LocalBounds localBounds;

    LayoutObjectType(boolean requiresConfig, LocalBounds localBounds) {
        this.requiresConfig = requiresConfig;
        this.localBounds = localBounds;
    }

    /**
     * Whether the object is meant to point at a piece of content.
     *
     * <p>Drives the {@code CONFIG_NOT_LINKED} warning only — whether an unlinked object blocks
     * publishing is C-04 and still undecided (research R-05). Also marks the kinds that get a
     * viewing-band passage check on publish (#19 ⑤) — 장식(FURNITURE·DECORATION)에는 관람할
     * 정면이 없다.
     */
    public boolean requiresConfig() {
        return requiresConfig;
    }

    public LocalBounds localBounds() {
        return localBounds;
    }

    public static Optional<LayoutObjectType> from(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        for (LayoutObjectType type : values()) {
            if (type.name().equals(raw)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /**
     * rotationY=0 기준의 프리팹 로컬 AABB. 정면은 {@code +z}다 — 관람 띠(#19 ⑤)가 이 면 앞에
     * 깔린다.
     */
    public record LocalBounds(double minX, double minY, double minZ,
                              double maxX, double maxY, double maxZ) { }
}
