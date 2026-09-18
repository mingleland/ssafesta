package com.example.ssafesta.staff;

import com.example.ssafesta.common.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 부스 직원 목록·역할 (spec 011 US3 · FR-002·FR-018,
 * {@code specs/011-staff-consultation/contracts/staff-consultation-api.md} §A).
 */
@RestController
@RequestMapping("/api/v1/booths/{boothId}/staff")
@Tag(name = "Booth Staff")
public class StaffController {

    private static final String MEMBER_ONLY = "회원 계정만 부스 직원을 관리할 수 있습니다.";

    private final StaffService staff;

    public StaffController(StaffService staff) {
        this.staff = staff;
    }

    @Operation(summary = "부스 직원 목록",
            description = """
                    부스 구성원이면 누구나 본다 — `CONSULTANT` 도 같은 부스에 누가 있는지는 알아야 한다.

                    **Owner 가 역할 `OWNER` 의 읽기 전용 행으로 함께 나온다** (FR-018). Owner 는
                    `booth_staffs` 에 저장되지 않으며 역할 변경·제거 대상이 아니다 — `readOnly: true`
                    로 표시되므로 클라이언트가 역할 문자열을 비교해 분기하지 않아도 된다.

                    `consultationStatus` 는 직원 행에만 있다. Owner 행에서는 `null` 이다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Owner 행 + 직원 목록. 직원은 합류 순이다"),
            @ApiResponse(responseCode = "403", description = "`STAFF_MANAGER_FORBIDDEN` — 이 부스의 구성원이 아니다"),
            @ApiResponse(responseCode = "404", description = "`BOOTH_NOT_FOUND`")})
    @GetMapping
    @SecurityRequirement(name = "bearerAuth")
    public List<StaffService.StaffView> list(@AuthenticationPrincipal Jwt jwt,
                                             @Parameter(description = "부스 식별자", example = "7")
                                             @PathVariable Long boothId) {
        return staff.listStaff(boothId, MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY));
    }

    @Operation(summary = "직원 역할 변경",
            description = """
                    Owner 와 `ADMIN` 만 바꾼다.

                    **Owner 는 대상이 아니다** — Owner 를 가리키면 `409 STAFF_OWNER_IMMUTABLE` 이다.
                    행이 없어서 나는 404 와 구분하는 이유는, 목록에 분명히 보이던 사람을 404 로
                    답하면 "이 부스와 무관하다" 로 읽히기 때문이다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "바뀐 직원 행"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — 역할이 어휘 밖이다"),
            @ApiResponse(responseCode = "403", description = "`STAFF_MANAGER_FORBIDDEN`"),
            @ApiResponse(responseCode = "404", description = "`BOOTH_NOT_FOUND` · `STAFF_NOT_FOUND`"),
            @ApiResponse(responseCode = "409", description = "`STAFF_OWNER_IMMUTABLE`")})
    @PatchMapping("/{userId}")
    @SecurityRequirement(name = "bearerAuth")
    public StaffService.StaffView changeRole(@AuthenticationPrincipal Jwt jwt,
                                             @PathVariable Long boothId,
                                             @PathVariable Long userId,
                                             @RequestBody(required = false)
                                             StaffService.ChangeRoleCommand command) {
        Long actorUserId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        return staff.changeRole(boothId, userId, actorUserId, command == null ? null : command.role());
    }

    @Operation(summary = "직원 제거",
            description = "Owner 와 `ADMIN` 만. Owner 는 제거 대상이 아니다 (`409 STAFF_OWNER_IMMUTABLE`).")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "제거됨"),
            @ApiResponse(responseCode = "403", description = "`STAFF_MANAGER_FORBIDDEN`"),
            @ApiResponse(responseCode = "404", description = "`BOOTH_NOT_FOUND` · `STAFF_NOT_FOUND`"),
            @ApiResponse(responseCode = "409", description = "`STAFF_OWNER_IMMUTABLE`")})
    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = "bearerAuth")
    public void remove(@AuthenticationPrincipal Jwt jwt,
                       @PathVariable Long boothId,
                       @PathVariable Long userId) {
        staff.remove(boothId, userId, MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY));
    }
}
