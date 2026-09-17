package com.example.ssafesta.booth;

import com.example.ssafesta.user.AdminGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP boundary for taking a booth's current publication offline without deleting its content. */
@RestController
@RequestMapping("/api/v1/admin/booths")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin")
public class AdminBoothPublicationController {

    private final AdminGuard admins;
    private final AdminBoothPublicationService publications;

    public AdminBoothPublicationController(AdminGuard admins,
                                           AdminBoothPublicationService publications) {
        this.admins = admins;
        this.publications = publications;
    }

    @Operation(summary = "관리자 부스 강제 비공개", description = "공개 포인터만 해제한다. Draft와 공개 이력은 보존하며 마스터 소유 부스는 대상이 될 수 없다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "비공개 처리 또는 이미 비공개"),
            @ApiResponse(responseCode = "400", description = "사유가 비어 있거나 500자를 초과함"),
            @ApiResponse(responseCode = "403", description = "관리자 아님 또는 MASTER_PROTECTED"),
            @ApiResponse(responseCode = "404", description = "BOOTH_NOT_FOUND")})
    @PostMapping("/{boothId}/unpublish")
    public ResponseEntity<Void> unpublish(@AuthenticationPrincipal Jwt jwt, @PathVariable Long boothId,
                                          @Valid @RequestBody UnpublishRequest request) {
        publications.unpublish(admins.requireAdmin(jwt), boothId, request.reason());
        return ResponseEntity.noContent().build();
    }

    public record UnpublishRequest(@NotBlank(message = "비공개 사유를 입력해야 합니다.")
                                   @Size(max = 500, message = "비공개 사유는 500자 이하여야 합니다.")
                                   String reason) { }
}
