package com.example.ssafesta.booth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Booth Layout")
public class BoothLayoutTemplateController {

    private static final BigDecimal TWO = new BigDecimal("2");

    @Operation(summary = "배치 템플릿 카탈로그 — 편집기가 가장 먼저 읽는 값",
            description = """
                    부스 내부 편집기를 열기 전에 **바닥 크기와 오브젝트 상한**을 받아 간다.

                    이 endpoint 가 있는 이유는 숫자의 출처를 하나로 만들기 위해서다. footprint 와 오브젝트 상한을
                    FE·Unity·서버가 각자 상수로 들고 있으면 언젠가 어긋난다 — 응답의 모든 값은 서버의 **검증 상수에서
                    유도**되므로 검증이 바뀌면 카탈로그도 저절로 같이 바뀐다 (#19 ④).

                    편집기는 이 값으로 캔버스를 그리고 "12개까지" 같은 안내를 띄운다. 하드코딩하지 않는다.

                    `footprint` 는 미터이고 **전폭**이다. 서버의 검증 상수는 원점이 바닥 중앙이라 반폭(±3)이지만,
                    편집기가 그리는 것은 전폭(6)이므로 여기서 두 배로 환산해 내려준다.
                    """)
    @ApiResponse(responseCode = "200", description = "템플릿 목록. 현재는 셸이 1종이라 footprint 가 전 템플릿 공통이다")
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

    public record TemplateCatalog(
            @Schema(description = "선택할 수 있는 템플릿 목록") List<TemplateView> templates) { }

    public record TemplateView(
            @Schema(description = "템플릿 코드. 배치 저장 시 그대로 보낸다", example = "DEFAULT") String template,
            Footprint footprint,
            @Schema(description = "이 템플릿에 놓을 수 있는 오브젝트 최대 개수. 초과하면 저장이 `OBJECT_LIMIT` 로 거부된다",
                    example = "12") int maxObjects) { }

    /** 미터. width·depth는 부스 바닥 전폭, height는 셸 유효 높이(벽 패널 상단 실측)다. */
    @Schema(description = "부스 내부의 실제 크기(미터). 원점은 바닥 중앙이므로 좌표는 ±(width/2) 범위다")
    public record Footprint(
            @Schema(description = "바닥 전폭(미터)", example = "6.0") BigDecimal width,
            @Schema(description = "바닥 전길이(미터)", example = "6.0") BigDecimal depth,
            @Schema(description = "셸 유효 높이(미터). 벽 패널 상단 실측값이다", example = "2.72") BigDecimal height) { }
}
