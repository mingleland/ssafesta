package com.example.ssafesta.user;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Administrative account suspension, reinstatement, and history endpoints. */
@RestController
@RequestMapping("/api/v1/admin/users")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin")
public class AdminUserController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminGuard guard;
    private final AdminUserOperationService operations;

    public AdminUserController(AdminGuard guard, AdminUserOperationService operations) {
        this.guard = guard;
        this.operations = operations;
    }

    @Operation(summary = "계정 정지", description = "관리자만 회원 계정을 정지한다. 이미 정지 상태면 상태 이력과 감사를 추가하지 않는다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "정지 완료(이미 정지 상태면 no-op)"),
            @ApiResponse(responseCode = "400", description = "사유가 비어 있거나 500자를 초과함"),
            @ApiResponse(responseCode = "403", description = "관리자 아님 또는 MASTER_PROTECTED"),
            @ApiResponse(responseCode = "409", description = "ADMIN_LAST_ONE")})
    @PostMapping("/{userId}/suspend")
    public ResponseEntity<Void> suspend(@AuthenticationPrincipal Jwt jwt,
                                        @PathVariable Long userId,
                                        @Valid @RequestBody SuspendRequest request) {
        Long actor = guard.requireAdmin(jwt);
        operations.suspend(userId, actor, request.reason());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "계정 정지 해제", description = "관리자만 회원 계정의 정지를 해제한다. 이미 활성 상태면 no-op으로 성공한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "해제 완료(이미 활성 상태면 no-op)"),
            @ApiResponse(responseCode = "403", description = "관리자 아님 또는 MASTER_PROTECTED")})
    @PostMapping("/{userId}/unsuspend")
    public ResponseEntity<Void> unsuspend(@AuthenticationPrincipal Jwt jwt,
                                          @PathVariable Long userId) {
        Long actor = guard.requireAdmin(jwt);
        operations.unsuspend(userId, actor);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "계정 상태 이력 조회", description = "관리자만 대상 계정의 상태 변경 이력을 최신순 페이지로 조회한다.")
    @GetMapping("/{userId}/status-history")
    public AdminAccountHistoryPage history(@AuthenticationPrincipal Jwt jwt,
                                           @PathVariable Long userId,
                                           @Parameter(description = "0부터 시작하는 페이지 번호")
                                           @RequestParam(defaultValue = "0") int page,
                                           @Parameter(description = "페이지 크기(1~100)")
                                           @RequestParam(defaultValue = "20") int size) {
        guard.requireAdmin(jwt);
        if (page < 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "page는 0 이상이어야 합니다.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "size는 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다.");
        }
        Page<AccountStatusHistoryView> result = operations.history(userId, PageRequest.of(page, size));
        return new AdminAccountHistoryPage(result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    public record SuspendRequest(@NotBlank(message = "정지 사유를 입력해야 합니다.")
                                 @Size(max = 500, message = "정지 사유는 500자 이하여야 합니다.")
                                 String reason) { }

    public record AdminAccountHistoryPage(java.util.List<AccountStatusHistoryView> content,
                                          int page, int size, long totalElements, int totalPages) { }
}
