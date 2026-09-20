package com.example.ssafesta.booth;

import java.util.Optional;

/**
 * Booth layout templates (spec 005 FR-002).
 *
 * <p>MVP는 1종이다 — 셸 프리팹이 1종이고 {@code template → 셸}이 1:1이라서다 (#19 ④, 2026-08-21
 * 확정). {@code DEFAULT}는 같은 확정으로 제거됐다: 남겨 두면 "DEFAULT로 저장된 부스는 어느
 * 셸인가"라는 답 없는 질문이 생긴다. 기존 저장분은 V11이 함께 옮겼다.
 *
 * <p>C-06 — 종수를 늘릴지, 늘리면 무엇이 다른지 — 는 여전히 기획 몫이다. 늘어나는 날 상수를
 * 추가하고 {@code GET /booth-layout-templates} 응답에 실리게 하면 끝이다; footprint가 템플릿마다
 * 달라지는 순간부터는 응답의 footprint를 템플릿별로 갈라 적는다.
 */
public enum LayoutTemplate {

    PROJECT_EXHIBITION;

    public static Optional<LayoutTemplate> from(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        for (LayoutTemplate template : values()) {
            if (template.name().equals(raw)) {
                return Optional.of(template);
            }
        }
        return Optional.empty();
    }
}
