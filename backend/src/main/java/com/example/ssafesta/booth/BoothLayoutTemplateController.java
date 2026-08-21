package com.example.ssafesta.booth;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The template catalogue the editor loads before anything else (#19 ④, 2026-08-21 신설).
 *
 * <p>이 endpoint가 있는 이유: footprint(6×6×2.72)와 오브젝트 상한(12)은 FE·Unity·서버 세 곳이
 * 각자 아는 숫자였다. 한 곳에서 내려받으면 어긋날 자리가 없어진다 — 그래서 응답의 모든 값은
 * 여기 적힌 상수가 아니라 {@link LayoutValidator}의 검증 상수에서 <b>유도</b>된다. 검증이
 * 바뀌면 카탈로그도 저절로 같이 바뀐다.
 */
@RestController
@RequestMapping("/api/v1/booth-layout-templates")
public class BoothLayoutTemplateController {

    private static final BigDecimal TWO = new BigDecimal("2");

    @GetMapping
    @SecurityRequirement(name = "bearerAuth")
    public TemplateCatalog templates() {
        // 원점이 바닥 중앙이라 검증 상수는 반폭(±3)이고, 편집기가 그리는 것은 전폭(6)이다.
        BigDecimal width = LayoutValidator.MAX_HORIZONTAL.multiply(TWO).setScale(1);
        Footprint footprint = new Footprint(width, width, LayoutValidator.MAX_HEIGHT);
        List<TemplateView> templates = List.of(LayoutTemplate.values()).stream()
                // template → 셸 1:1이고 셸이 1종이라 지금은 footprint가 전 템플릿 공통이다.
                // 템플릿마다 셸이 달라지는 날 이 매핑이 템플릿별 값으로 갈라진다 (C-06).
                .map(template -> new TemplateView(template.name(), footprint,
                        LayoutValidator.MAX_OBJECTS))
                .toList();
        return new TemplateCatalog(templates);
    }

    public record TemplateCatalog(List<TemplateView> templates) { }

    public record TemplateView(String template, Footprint footprint, int maxObjects) { }

    /** 미터. width·depth는 부스 바닥 전폭, height는 셸 유효 높이(벽 패널 상단 실측)다. */
    public record Footprint(BigDecimal width, BigDecimal depth, BigDecimal height) { }
}
