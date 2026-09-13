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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 직원 초대 (spec 011 US3 · FR-001·FR-015~FR-017,
 * {@code specs/011-staff-consultation/contracts/staff-consultation-api.md} §A).
 *
 * <p>경로가 둘로 갈린다. 부스에 매인 동작(보내기·취소)은 {@code /booths/{boothId}/...} 아래,
 * 받은 사람 자신의 동작(목록·수락)은 {@code /staff-invitations/...} 아래다 — 받는 쪽은 아직 그
 * 부스의 구성원이 아니라서 부스 경로에 매달 수 없다.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Staff Invitation")
public class StaffInvitationController {

    /** 게스트에게는 무엇이 막혔는지 말해 준다 — 일반 문구는 어느 동작이 거부됐는지를 감춘다. */
    private static final String MEMBER_ONLY = "회원 계정만 직원 초대를 주고받을 수 있습니다.";

    private final StaffInvitationService invitations;

    public StaffInvitationController(StaffInvitationService invitations) {
        this.invitations = invitations;
    }

    @Operation(summary = "직원 초대 보내기",
            description = """
                    Owner 와 `ADMIN` 직원만 보낸다. `CONTENT_EDITOR` 는 콘텐츠를 고칠 수 있지만
                    사람을 들일 수는 없다 (FR-002).

                    **대상은 닉네임으로 지정한다.** `users.nickname` 이 유일 값이고, 숫자 id 로 받으면
                    Owner 가 그 값을 알아낼 방법이 없다 — 사용자 검색 endpoint 를 열면 닉네임 훑기·id
                    수집 표면이 함께 생기므로 만들지 않았다.

                    초대는 **48시간** 유효하다 (C-07). 그 안에 수락하지 않으면 만료되며, 초대받은
                    사람의 거절 API 는 없다 (C-10) — 취소는 Owner·`ADMIN` 몫이다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "만들어진 초대와 `expiresAt`"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — 닉네임 누락, 역할 누락·어휘 밖"),
            @ApiResponse(responseCode = "403", description = "`STAFF_MANAGER_FORBIDDEN` — Owner 도 `ADMIN` 도 아니다"),
            @ApiResponse(responseCode = "404", description = "`BOOTH_NOT_FOUND` · `STAFF_INVITEE_NOT_FOUND` — 그 닉네임의 회원이 없다"),
            @ApiResponse(responseCode = "409", description = "`STAFF_ALREADY_MEMBER` — 이미 소유자거나 직원이다 · `STAFF_INVITATION_PENDING` — 대기 중 초대가 이미 있다")})
    @PostMapping("/booths/{boothId}/staff-invitations")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    public StaffInvitationService.InvitationView invite(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "내 부스 식별자", example = "7") @PathVariable Long boothId,
            @RequestBody(required = false) StaffInvitationService.InviteCommand command) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        return invitations.invite(boothId, userId, command);
    }

    @Operation(summary = "내게 온 직원 초대 목록",
            description = """
                    **대기 중이고 아직 만료되지 않은** 초대만 돌려준다 (FR-016).

                    만료 배치를 기다리지 않는다 — 화면에 떠 있는데 누르면 409 가 나는 초대를 보여
                    주지 않으려고 조회 시점에 기한을 함께 본다.
                    """)
    @ApiResponse(responseCode = "200", description = "수락 가능한 초대 목록. 없으면 빈 배열")
    @GetMapping("/staff-invitations/mine")
    @SecurityRequirement(name = "bearerAuth")
    public List<StaffInvitationService.InvitationView> mine(@AuthenticationPrincipal Jwt jwt) {
        return invitations.findMine(MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY));
    }

    @Operation(summary = "직원 초대 수락",
            description = "초대받은 본인만 수락한다. 수락 시점에 `booth_staffs` 행이 생기고 역할이 적용된다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "수락됨"),
            @ApiResponse(responseCode = "403", description = "`STAFF_INVITATION_FORBIDDEN` — 내게 온 초대가 아니다"),
            @ApiResponse(responseCode = "404", description = "`STAFF_INVITATION_NOT_FOUND`"),
            @ApiResponse(responseCode = "409", description = "`STAFF_INVITATION_NOT_PENDING` — 이미 처리됐거나 48시간이 지났다 · `STAFF_ALREADY_MEMBER`")})
    @PostMapping("/staff-invitations/{invitationId}/accept")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = "bearerAuth")
    public void accept(@AuthenticationPrincipal Jwt jwt,
                       @Parameter(description = "초대 식별자", example = "12")
                       @PathVariable Long invitationId) {
        invitations.accept(invitationId, MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY));
    }

    @Operation(summary = "대기 중 직원 초대 취소",
            description = "Owner·`ADMIN` 이 보낸 초대를 거둔다 (FR-017). 이미 수락·취소·만료된 초대는 거부된다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "취소됨"),
            @ApiResponse(responseCode = "403", description = "`STAFF_MANAGER_FORBIDDEN`"),
            @ApiResponse(responseCode = "404", description = "`BOOTH_NOT_FOUND` · `STAFF_INVITATION_NOT_FOUND` — 그 부스의 초대가 아니다"),
            @ApiResponse(responseCode = "409", description = "`STAFF_INVITATION_NOT_PENDING`")})
    @DeleteMapping("/booths/{boothId}/staff-invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = "bearerAuth")
    public void cancel(@AuthenticationPrincipal Jwt jwt,
                       @PathVariable Long boothId,
                       @PathVariable Long invitationId) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        invitations.cancel(boothId, invitationId, userId);
    }
}
