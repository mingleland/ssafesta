package com.example.ssafesta.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The administrator roster (S15P21A604-743).
 *
 * <p>Every route here is behind {@link AdminGuard} — administrators are the only ones who can make
 * another administrator. There is no self-service path in and no endpoint that sets the master
 * flag: the first administrator arrives through a migration, and the master through the same one.
 */
@RestController
@RequestMapping("/api/v1/admin/admins")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin")
public class AdminController {

    private final AdminGuard guard;
    private final AdminAccountService admins;

    public AdminController(AdminGuard guard, AdminAccountService admins) {
        this.guard = guard;
        this.admins = admins;
    }

    @Operation(summary = "관리자 목록",
            description = """
                    관리자 권한을 가진 계정 전부. `master` 가 `true` 인 행은 **보호된 계정**이라
                    강등·정지·탈퇴·코인 조정의 대상이 될 수 없다 — 콘솔은 그 행의 조치 버튼을 잠근다.

                    마스터도 관리자다. 목록에서 빠지지 않는다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY`(게스트) 또는 `FORBIDDEN`(관리자가 아니다)")})
    @GetMapping
    public List<AdminAccountService.AdminView> list(@AuthenticationPrincipal Jwt jwt) {
        guard.requireAdmin(jwt);
        return admins.list();
    }

    @Operation(summary = "관리자 승격",
            description = """
                    회원 하나에게 관리자 권한을 준다. **관리자만 관리자를 만들 수 있다.**

                    `note` 는 감사 기록에 그대로 남는 사유다. 남기지 않아도 승격은 되지만, 한 달 뒤
                    이 행을 읽는 사람에게 남는 것은 그 한 줄뿐이다.

                    정지된 계정은 승격할 수 없다 — 지금은 세션조차 없으므로 아무 권한도 주지 못하고,
                    나중에 무관한 이유로 정지가 풀리는 순간 관리자 권한이 함께 살아난다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "승격된 계정"),
            @ApiResponse(responseCode = "403", description = "`FORBIDDEN` — 호출자가 관리자가 아니거나, 대상이 정지 계정이다"),
            @ApiResponse(responseCode = "404", description = "`ADMIN_TARGET_NOT_FOUND` — 그런 회원이 없다"),
            @ApiResponse(responseCode = "409", description = "`ADMIN_ALREADY` — 이미 관리자다")})
    @PostMapping("/{userId}")
    public AdminAccountService.AdminView promote(@AuthenticationPrincipal Jwt jwt,
                                                 @PathVariable Long userId,
                                                 @RequestBody(required = false) GrantRequest request) {
        Long actorUserId = guard.requireAdmin(jwt);
        return admins.promote(userId, actorUserId, request == null ? null : request.note());
    }

    @Operation(summary = "관리자 강등",
            description = """
                    관리자 권한을 거둔다. 계정 자체는 그대로 남고 회원으로 계속 쓴다.

                    **다음 요청부터 즉시 막힌다** — 권한은 토큰이 아니라 매 요청 DB 조회로 판정하므로
                    토큰 만료를 기다리지 않는다.

                    **마지막 관리자는 강등할 수 없다**(`ADMIN_LAST_ONE`). 승격 API 자체가 관리자
                    전용이라 관리자가 0명이 되면 API 로는 되돌릴 수 없고 마이그레이션이 필요하다.

                    이미 관리자가 아닌 회원을 강등하면 아무 일도 없이 `204` 다. 요청이 바라는 상태가
                    이미 참이기 때문이다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "강등 완료(또는 이미 관리자가 아니었다)"),
            @ApiResponse(responseCode = "403", description = "`FORBIDDEN`(호출자가 관리자가 아니다) 또는 `MASTER_PROTECTED`(마스터 계정이다)"),
            @ApiResponse(responseCode = "404", description = "`ADMIN_TARGET_NOT_FOUND` — 그런 회원이 없다"),
            @ApiResponse(responseCode = "409", description = "`ADMIN_LAST_ONE` — 남은 관리자가 이 한 명이다")})
    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> demote(@AuthenticationPrincipal Jwt jwt,
                                       @PathVariable Long userId,
                                       @Parameter(description = "감사 기록에 남길 사유")
                                       @RequestParam(required = false) String note) {
        Long actorUserId = guard.requireAdmin(jwt);
        admins.demote(userId, actorUserId, note);
        return ResponseEntity.noContent().build();
    }

    /** @param note 감사 기록에 남길 사유. 본문 자체를 생략할 수 있다 */
    public record GrantRequest(String note) {
    }
}
