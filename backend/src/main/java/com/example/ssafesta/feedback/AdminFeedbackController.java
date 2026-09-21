package com.example.ssafesta.feedback;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.user.AdminGuard;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrator surface for feedback (S15P21A604-953).
 *
 * <p>No reward endpoint here on purpose — an administrator who marks a submission as the first
 * report grants coins through the existing wallet adjustment screen, against that submission's
 * {@code userId}. A second grant path for the same ledger would only give the two a chance to
 * disagree.
 */
@RestController
@RequestMapping("/api/v1/admin/feedback")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin")
public class AdminFeedbackController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminGuard guard;
    private final FeedbackService feedback;

    public AdminFeedbackController(AdminGuard guard, FeedbackService feedback) {
        this.guard = guard;
        this.feedback = feedback;
    }

    @Operation(summary = "피드백 목록 (최신순)", description = "최신순 한 페이지를 돌려준다. 최초 발견 판정은 목록을 읽고 손으로 고른다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "피드백 페이지"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — page·size 값 오류"),
            @ApiResponse(responseCode = "403", description = "관리자 아님")})
    @GetMapping
    public FeedbackPageResponse list(@AuthenticationPrincipal Jwt jwt,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        guard.requireAdmin(jwt);
        validatePage(page, size);
        Page<FeedbackService.AdminFeedbackView> result = feedback.listForAdmin(PageRequest.of(page, size));
        return new FeedbackPageResponse(result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @Operation(summary = "최초 발견 표시", description = "이 피드백을 최초 발견으로 표시하거나 해제한다. 보상은 지갑 조정 화면에서 따로 지급한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "갱신된 피드백"),
            @ApiResponse(responseCode = "403", description = "관리자 아님"),
            @ApiResponse(responseCode = "404", description = "`FEEDBACK_NOT_FOUND`")})
    @PatchMapping("/{feedbackId}/first-found")
    public FeedbackService.AdminFeedbackView markFirstFound(@AuthenticationPrincipal Jwt jwt,
                                                             @PathVariable Long feedbackId,
                                                             @RequestBody FirstFoundRequest request) {
        guard.requireAdmin(jwt);
        return feedback.setFirstFound(feedbackId, request != null && request.firstFound());
    }

    private static void validatePage(int page, int size) {
        if (page < 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "page는 0 이상이어야 합니다.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "size는 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다.");
        }
    }

    public record FirstFoundRequest(boolean firstFound) {
    }

    public record FeedbackPageResponse(List<FeedbackService.AdminFeedbackView> content, int page, int size,
                                       long totalElements, int totalPages) {
    }
}
