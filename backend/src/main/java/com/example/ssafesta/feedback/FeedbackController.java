package com.example.ssafesta.feedback;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Member-facing feedback submission (S15P21A604-953). */
@RestController
@RequestMapping("/api/v1/feedback")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Feedback")
public class FeedbackController {

    private static final String MEMBER_ONLY = "회원 계정만 피드백을 남길 수 있습니다.";

    private final FeedbackService feedback;

    public FeedbackController(FeedbackService feedback) {
        this.feedback = feedback;
    }

    @Operation(summary = "피드백 제출", description = "본문 하나를 그대로 접수한다. 최초 발견 판정과 보상 지급은 관리자가 따로 처리한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "접수됨"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — 본문이 비었거나 2000자 초과"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY` — 게스트는 남길 수 없다")})
    @PostMapping
    public ResponseEntity<FeedbackResponse> submit(@AuthenticationPrincipal Jwt jwt,
                                                    @RequestBody FeedbackRequest request) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        if (request == null) {
            throw ApiException.fieldInvalid("content", "필수입니다.");
        }
        Feedback saved = feedback.submit(userId, request.content());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new FeedbackResponse(saved.getId(), saved.getCreatedAt()));
    }

    public record FeedbackRequest(@Schema(description = "피드백 본문", example = "미니게임 로딩이 느려요") String content) {
    }

    public record FeedbackResponse(
            @Schema(description = "생성된 피드백 id", example = "12") Long feedbackId,
            @Schema(description = "접수 시각(UTC)", example = "2026-09-21T05:00:00Z") Instant createdAt) {
    }
}
