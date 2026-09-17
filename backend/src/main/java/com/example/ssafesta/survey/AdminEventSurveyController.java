package com.example.ssafesta.survey;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.user.AdminGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Administrator HTTP boundary for event entrants; it is not a general survey-results endpoint. */
@RestController
@RequestMapping("/api/v1/admin/event-surveys")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin")
public class AdminEventSurveyController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminGuard admins;
    private final AdminEventSurveyService events;

    public AdminEventSurveyController(AdminGuard admins, AdminEventSurveyService events) {
        this.admins = admins;
        this.events = events;
    }

    @Operation(summary = "이벤트 설문 참여자 조회", description = "이벤트 설문 참여 회원만 최신 제출순으로 조회한다. 경품 지급·추첨은 이 API 범위가 아니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "참여자 페이지"),
            @ApiResponse(responseCode = "400", description = "page 또는 size 범위 오류"),
            @ApiResponse(responseCode = "403", description = "관리자 아님"),
            @ApiResponse(responseCode = "404", description = "SURVEY_NOT_FOUND")})
    @GetMapping("/{surveyKey}/entrants")
    public EntrantPage entrants(@AuthenticationPrincipal Jwt jwt,
                                @Parameter(example = "SSAFESTA_2026") @PathVariable String surveyKey,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size) {
        if (page < 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "page는 0 이상이어야 합니다.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "size는 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다.");
        }
        Page<AdminEventSurveyService.EventEntrantView> result = events.entrants(admins.requireAdmin(jwt),
                surveyKey, PageRequest.of(page, size));
        return new EntrantPage(result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    public record EntrantPage(List<AdminEventSurveyService.EventEntrantView> content,
                              int page, int size, long totalElements, int totalPages) { }
}
