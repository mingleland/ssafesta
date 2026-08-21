package com.example.ssafesta.user;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.common.MemberPrincipal;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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

    public MyAccountController(AccountLifecycleService lifecycle, UserRepository users, OAuthIdentityRepository identities,
                               NicknamePolicy nicknamePolicy) {
        this.lifecycle = lifecycle;
        this.users = users;
        this.identities = identities;
        this.nicknamePolicy = nicknamePolicy;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public MyAccountResponse me(@AuthenticationPrincipal Jwt jwt) {
        User user = activeMember(jwt);
        List<String> providers = identities.findAllByUser_Id(user.getId()).stream()
                .map(identity -> identity.getProvider().name()).toList();
        return new MyAccountResponse(user.getId(), user.getNickname(), user.getStatus().name(), providers);
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
        return new MyAccountResponse(user.getId(), user.getNickname(), user.getStatus().name(),
                identities.findAllByUser_Id(user.getId()).stream().map(identity -> identity.getProvider().name()).toList());
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
    public record MyAccountResponse(Long userId, String nickname, String status, List<String> providers) { }
}
