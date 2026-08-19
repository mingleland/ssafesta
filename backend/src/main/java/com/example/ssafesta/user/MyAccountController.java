package com.example.ssafesta.user;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.server.ResponseStatusException;

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
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다.");
        }
        user.changeNickname(request.nickname());
        return new MyAccountResponse(user.getId(), user.getNickname(), user.getStatus().name(),
                identities.findAllByUser_Id(user.getId()).stream().map(identity -> identity.getProvider().name()).toList());
    }

    @DeleteMapping
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal Jwt jwt, @RequestBody WithdrawalRequest request) {
        activeMember(jwt);
        if (!request.confirmed()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "탈퇴 내용을 확인한 뒤 확정해야 합니다.");
        }
        lifecycle.withdraw(memberId(jwt));
        return ResponseEntity.noContent().build();
    }

    private Long memberId(Jwt jwt) {
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 회원 토큰입니다.");
        }
    }

    private User activeMember(Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Access Token이 필요합니다.");
        }
        if (!"MEMBER".equals(jwt.getClaimAsString("role"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "회원 계정만 이용할 수 있습니다.");
        }
        return users.findById(memberId(jwt)).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "존재하지 않는 회원입니다."));
    }

    public record WithdrawalRequest(boolean confirmed) { }
    public record NicknameChangeRequest(String nickname) { }
    public record MyAccountResponse(Long userId, String nickname, String status, List<String> providers) { }
}
