package com.example.ssafesta.user;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.common.MemberPrincipal;
import com.example.ssafesta.inventory.InventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "User")
public class MyAccountController {
    private final AccountLifecycleService lifecycle;
    private final UserRepository users;
    private final OAuthIdentityRepository identities;
    private final NicknamePolicy nicknamePolicy;
    private final AvatarCodePolicy avatarCodePolicy;
    private final InventoryService inventory;

    public MyAccountController(AccountLifecycleService lifecycle, UserRepository users, OAuthIdentityRepository identities,
                               NicknamePolicy nicknamePolicy, AvatarCodePolicy avatarCodePolicy,
                               InventoryService inventory) {
        this.lifecycle = lifecycle;
        this.users = users;
        this.identities = identities;
        this.nicknamePolicy = nicknamePolicy;
        this.avatarCodePolicy = avatarCodePolicy;
        this.inventory = inventory;
    }

    @Operation(summary = "내 계정 조회",
            description = """
                    로그인한 회원의 닉네임·상태·연결된 소셜 제공자·아바타 외형을 돌려준다. 회원 전용이다.

                    **여기에 없는 것** — 코인 잔액은 `GET /api/v1/wallets/me`, 부스는 `GET /api/v1/booths/mine` 이 소유한다.
                    한 화면에 함께 보이더라도 응답은 합치지 않는다.

                    `avatarCode` 는 아직 저장한 적이 없으면 `null` 이다. **서버가 기본 프리셋을 만들어 넣지 않는다** —
                    폴백은 클라이언트 몫이고(spec 013 FR-010), 서버가 지어낸 값은 다음 조회에서 사용자의 선택과 구별되지 않는다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "`USER_NOT_FOUND` — 토큰은 유효하지만 그 회원의 행이 없다(탈퇴 등). 다시 로그인해야 한다"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY` — 게스트 토큰이다")})
    @GetMapping
    @Transactional(readOnly = true)
    public MyAccountResponse me(@AuthenticationPrincipal Jwt jwt) {
        User user = activeMember(jwt);
        return accountOf(user);
    }

    @Operation(summary = "닉네임 변경",
            description = """
                    닉네임만 바꾸고 나머지는 그대로 둔다. 응답은 `GET /api/v1/users/me` 와 같은 모양이라
                    변경 후 다시 조회할 필요가 없다.

                    같은 값으로 보내면 중복 검사를 건너뛰고 성공한다 — 자기 닉네임이 자기와 충돌하지 않는다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "변경된 계정 정보"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — 길이·문자 규칙 위반. `errors[0].field` 가 `nickname` 이다"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY` — 게스트 토큰이다"),
            @ApiResponse(responseCode = "409", description = "`NICKNAME_DUPLICATED` — 다른 회원이 쓰는 닉네임이다")})
    @PatchMapping
    @Transactional
    public MyAccountResponse changeNickname(@AuthenticationPrincipal Jwt jwt, @RequestBody NicknameChangeRequest request) {
        User user = activeMember(jwt);
        nicknamePolicy.validate(request.nickname());
        if (!user.getNickname().equals(request.nickname()) && users.existsByNickname(request.nickname())) {
            throw new ApiException(ErrorCode.NICKNAME_DUPLICATED);
        }
        user.changeNickname(request.nickname());
        return accountOf(user);
    }

    /**
     * Stores the avatar appearance encoding (spec 013a FR-013, #24 contract).
     *
     * <p>{@code PUT} rather than {@code PATCH}: the body is the whole value, not a delta. The
     * response echoes what was stored so the client can confirm the server changed nothing — the
     * string is opaque here and {@link AvatarCodePolicy} only checks length and charset.
     *
     * <p>Guests are refused outright (헌법 12조). The contract has Unity skip the call for them,
     * but a client-side decision is not a control (헌법 16조).
     */
    @Operation(summary = "아바타 외형 저장 — 값 전체를 덮어쓴다",
            description = """
                    Unity 가 만든 외형 인코딩 문자열을 그대로 저장한다. `PATCH` 가 아니라 `PUT` 인 이유는
                    본문이 부분 수정이 아니라 **값 전체**이기 때문이다.

                    **서버는 이 문자열을 해석하지 않는다.** 길이(3800자)와 인쇄 가능 ASCII 만 보고, 착용 품목 검사를 위해
                    `fa|` 형식의 슬롯만 읽는다. trim·대소문자 변환·기본값 치환을 하지 않으므로 저장한 값이 **그대로** 돌아온다 —
                    응답으로 그 사실을 확인할 수 있다.

                    **미보유 품목은 거부된다.** 상점에서 사지 않은 파츠를 착용한 코드는 `409 AVATAR_ITEM_NOT_OWNED` 이고,
                    `errors[]` 에 어떤 품목이 미보유인지 `objectId` 로 하나씩 들어온다.

                    게스트는 `403` 이다 — 외형을 서버에 영속 저장하지 않는다(헌법 12조). 클라이언트가 호출을 건너뛰는 것과
                    별개로 서버가 막는다(헌법 16조).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장 성공. 저장한 값을 그대로 echo 한다"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — 빈 값·3800자 초과·허용 문자셋 위반. 세 경우가 서로 다른 문장을 받는다"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY` — 게스트 토큰이다"),
            @ApiResponse(responseCode = "409", description = "`AVATAR_ITEM_NOT_OWNED` — 보유하지 않은 파츠를 착용했다. `errors[].objectId` 가 미보유 품목이다")})
    @PutMapping("/avatar")
    @Transactional
    public AvatarResponse changeAvatar(@AuthenticationPrincipal Jwt jwt, @RequestBody AvatarChangeRequest request) {
        User user = activeMember(jwt);
        avatarCodePolicy.validate(request.avatarCode());
        inventory.requireOwned(user.getId(), AvatarWornItems.parse(request.avatarCode()));
        user.changeAvatarCode(request.avatarCode());
        return new AvatarResponse(user.getAvatarCode());
    }

    /**
     * One place builds this response.
     *
     * <p>It was assembled inline in two handlers before, which is how a field lands in one and not
     * the other: adding {@code avatarCode} to {@code me()} alone would have dropped it from the
     * nickname response with nothing failing to say so.
     */
    private MyAccountResponse accountOf(User user) {
        List<String> providers = identities.findAllByUser_Id(user.getId()).stream()
                .map(identity -> identity.getProvider().name()).toList();
        return new MyAccountResponse(user.getId(), user.getNickname(), user.getStatus().name(), providers,
                user.getAvatarCode());
    }

    @Operation(summary = "회원 탈퇴 — 즉시 삭제되고 되돌릴 수 없다",
            description = """
                    ⚠️ **계정과 연결 데이터를 즉시 삭제한다.** 보관 기간이나 복구 경로가 없다.

                    실수로 지우는 일을 막기 위해 본문의 `confirmed` 를 **`true` 로 명시**해야 한다. `false` 이거나 빠지면
                    `400 WITHDRAWAL_NOT_CONFIRMED` 이고 계정은 그대로다 — 확인 화면을 서버가 강제하는 장치다.

                    시험할 때는 폐기 가능한 소셜 계정으로만 한다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 완료. 본문이 없고 기존 토큰은 더 이상 쓸 수 없다"),
            @ApiResponse(responseCode = "400", description = "`WITHDRAWAL_NOT_CONFIRMED` — `confirmed` 가 `true` 가 아니다. 아무것도 삭제되지 않았다"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY` — 게스트 토큰이다")})
    @DeleteMapping
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal Jwt jwt, @RequestBody WithdrawalRequest request) {
        activeMember(jwt);
        if (!request.confirmed()) {
            throw new ApiException(ErrorCode.WITHDRAWAL_NOT_CONFIRMED);
        }
        lifecycle.withdraw(memberId(jwt));
        return ResponseEntity.noContent().build();
    }

    private Long memberId(Jwt jwt) {
        return MemberPrincipal.requireMemberId(jwt);
    }

    private User activeMember(Jwt jwt) {
        return users.findById(memberId(jwt))
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
    }

    @Schema(description = "탈퇴 확인. 화면에서 안내를 읽었다는 뜻이며 서버가 이 값을 강제한다")
    public record WithdrawalRequest(
            @Schema(description = "`true` 여야 삭제가 진행된다. `false`·누락이면 400 이고 계정은 유지된다",
                    example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
            boolean confirmed) { }

    public record NicknameChangeRequest(
            @Schema(description = "새 닉네임. 다른 회원이 쓰는 값이면 409 다", example = "FESTA_USER",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String nickname) { }

    public record AvatarChangeRequest(
            @Schema(description = """
                    Unity 가 만든 외형 인코딩. 최대 3800자, 인쇄 가능 ASCII(0x20–0x7E)만 허용한다.
                    상한은 Unity `AvatarAppearance.MaxEncodedLength` 가 소유한 값이라 낮추지 않는다 —
                    모듈러 인코딩(`fa|…`)은 파츠 이름이 그대로 들어가 길다 (헌법 23조·T-24).""",
                    maxLength = 3800, example = "fa|3=SK_Hair_Long_01|c=FF8800",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String avatarCode) { }

    @Schema(description = "저장된 외형. 보낸 값과 **글자 그대로** 같아야 한다")
    public record AvatarResponse(
            @Schema(description = "저장된 외형 인코딩", example = "fa|3=SK_Hair_Long_01|c=FF8800")
            String avatarCode) { }

    /**
     * @param avatarCode the stored appearance encoding, {@code null} for a user who has never saved
     *                   one. Null and not a preset: picking a default is the client's job (FR-010),
     *                   and a server-invented one would be indistinguishable from a real choice on
     *                   the next read. The key stays present — unlike {@code ApiErrorDetail}, where
     *                   an absent field means "this rule has no field", here the field always
     *                   exists and is merely empty.
     */
    public record MyAccountResponse(
            @Schema(description = "회원 식별자", example = "12") Long userId,
            @Schema(description = "표시 이름", example = "FESTA_USER") String nickname,
            @Schema(description = "계정 상태", example = "ACTIVE") String status,
            @Schema(description = "연결된 소셜 제공자 목록. 한 계정에 여러 개가 붙을 수 있다", example = "[\"GOOGLE\"]")
            List<String> providers,
            @Schema(description = "저장된 아바타 외형 인코딩. 한 번도 저장하지 않았으면 `null` 이다 — 키는 항상 있다",
                    nullable = true, example = "fa|3=SK_Hair_Long_01|c=FF8800")
            String avatarCode) { }
}
