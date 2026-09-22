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
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP boundary for the administrator's booth surface: listing every booth on the floor, and
 * taking a booth's current publication offline without deleting its content.
 */
@RestController
@RequestMapping("/api/v1/admin/booths")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin")
public class AdminBoothPublicationController {

    private final AdminGuard admins;
    private final AdminBoothPublicationService publications;
    private final BoothQueryService queries;

    public AdminBoothPublicationController(AdminGuard admins,
                                           AdminBoothPublicationService publications,
                                           BoothQueryService queries) {
        this.admins = admins;
        this.publications = publications;
        this.queries = queries;
    }

    @Operation(summary = "부스 운영 목록", description = """
            부스를 전부 돌려준다 — **관리자 부스와 회원 부스를 함께 준다**(S15P21A604-959).
            관리자는 원래부터 회원 부스도 편집·운영할 수 있었는데(FR-023) 이 목록만 관리자 부스로
            좁혀져 있어서, 회원 부스의 `boothId` 를 얻을 경로가 없었다. **호출한 사람의 것만도 아니다**
            — 관리자 부스는 사람이 아니라 권한을 따라가므로(S15P21A604-905) 어느 관리자든 전부 보고
            전부 운영한다.

            `adminOwned` 가 둘을 가른다. 둘은 같게 움직이지 않는다 — 마스터 **개인** 부스는 관리자
            조작을 `MASTER_PROTECTED` 로 거부하고, 강제 비공개는 회원 부스의 컨텐츠를 보존하지만
            관리자 부스는 그 반납이 곧 삭제다.

            자리 순(`slotCode`)으로 정렬된다. 자리가 없는 부스 — 임대가 이미 끝난 회원 부스 — 는 맨
            뒤다. `published` 가 공개 여부이고, 그것을 내리는 것이
            `POST /api/v1/admin/booths/{boothId}/unpublish` 다 — 관리자 부스에서는 그 호출이 자리까지
            회수하므로 부스가 이 목록에서 사라진다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "부스 목록. 하나도 없으면 빈 배열이다"),
            @ApiResponse(responseCode = "403", description = "관리자 아님")})
    @GetMapping
    public List<BoothQueryService.AdminBoothView> list(@AuthenticationPrincipal Jwt jwt) {
        admins.requireAdmin(jwt);
        return queries.listAdminBooths();
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
