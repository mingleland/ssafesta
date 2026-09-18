package com.example.ssafesta.booth;

import com.example.ssafesta.user.AdminGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
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

/**
 * Administrator actions on a slot rather than on a booth (S15P21A604-927).
 *
 * <p>Keyed on the slot because the lease is: one slot has at most one active lease, while an
 * administrator holds several booths at once, so a booth id does not say which seat is meant.
 */
@RestController
@RequestMapping("/api/v1/admin/booth-slots")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin")
public class AdminBoothSlotController {

    private final AdminGuard admins;
    private final BoothLeaseService leases;

    public AdminBoothSlotController(AdminGuard admins, BoothLeaseService leases) {
        this.admins = admins;
        this.leases = leases;
    }

    @Operation(summary = "관리자 강제 임대 반납 — 자리를 회수한다",
            description = """
                    임차인의 동의 없이 그 자리의 임대를 끝낸다. 관리자 전용이며, 신고 대응 수단이다.

                    **`강제 비공개`와 다르다.** 비공개는 공개 포인터만 해제하므로 임대가 살아 있고
                    `GET /api/v1/booth-slots` 는 그 슬롯을 계속 `OCCUPIED` 로 보고한다. 자리를 비우는 것은 이 API 다 —
                    처리 직후 슬롯이 `AVAILABLE` 이 되고 다른 사람이 임대할 수 있다.

                    **부스 콘텐츠는 보존된다.** 가져가는 것은 자리뿐이다 — 회원의 부스와 Layout·프로젝트·설문·AI 문서는
                    임차인이 직접 반납했을 때와 똑같이 남고(spec 004 FR-010), 다시 임대하면 Draft 부터 이어서 쓴다(D08).
                    관리자 자신의 부스는 예외다 — 그 부스는 원래 반납과 함께 삭제되는 구조다(S15P21A604-905).

                    **코인은 환불되지 않는다** (spec 004 D06 을 이 경로까지 적용). 임차인의 잔액은 그대로다.

                    사유는 필수다 — `admin_actions` 에 `BOOTH_LEASE_RELEASE` 로 남는다.
                    마스터 계정이 소유한 부스는 대상이 될 수 없다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "회수 완료. 슬롯이 즉시 `AVAILABLE` 이 된다"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — 사유가 비었거나 500자를 초과함"),
            @ApiResponse(responseCode = "403", description = "`FORBIDDEN`(관리자 아님) 또는 `MASTER_PROTECTED`(마스터 소유 부스)"),
            @ApiResponse(responseCode = "404", description = "`BOOTH_SLOT_NOT_FOUND`(그런 슬롯이 없다) 또는 `ACTIVE_LEASE_NOT_FOUND`(회수할 활성 임대가 없다 — 이미 만료·반납됐다)")})
    @PostMapping("/{slotId}/lease/release")
    public ResponseEntity<Void> releaseLease(@AuthenticationPrincipal Jwt jwt,
                                             @Parameter(description = "회수할 자리. 관리자 부스 목록의 `slotId`", example = "7")
                                             @PathVariable Long slotId,
                                             @Valid @RequestBody ReleaseRequest request) {
        leases.releaseByAdmin(admins.requireAdmin(jwt), slotId, request.reason());
        return ResponseEntity.noContent().build();
    }

    public record ReleaseRequest(
            @Schema(description = "회수 사유. 감사 기록에 그대로 남는다", example = "신고 접수 — 부적절한 이미지")
            @NotBlank(message = "회수 사유를 입력해야 합니다.")
            @Size(max = 500, message = "회수 사유는 500자 이하여야 합니다.")
            String reason) { }
}
