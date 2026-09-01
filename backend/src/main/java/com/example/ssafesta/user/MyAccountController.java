package com.example.ssafesta.user;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.common.MemberPrincipal;
import com.example.ssafesta.inventory.InventoryService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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

    @GetMapping
    @Transactional(readOnly = true)
    public MyAccountResponse me(@AuthenticationPrincipal Jwt jwt) {
        User user = activeMember(jwt);
        return accountOf(user);
    }

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

    public record WithdrawalRequest(boolean confirmed) { }
    public record NicknameChangeRequest(String nickname) { }
    public record AvatarChangeRequest(String avatarCode) { }
    public record AvatarResponse(String avatarCode) { }

    /**
     * @param avatarCode the stored appearance encoding, {@code null} for a user who has never saved
     *                   one. Null and not a preset: picking a default is the client's job (FR-010),
     *                   and a server-invented one would be indistinguishable from a real choice on
     *                   the next read. The key stays present — unlike {@code ApiErrorDetail}, where
     *                   an absent field means "this rule has no field", here the field always
     *                   exists and is merely empty.
     */
    public record MyAccountResponse(Long userId, String nickname, String status, List<String> providers,
                                    String avatarCode) { }
}
