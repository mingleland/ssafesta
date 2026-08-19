package com.example.ssafesta.auth;

import com.example.ssafesta.user.NicknamePolicy;
import com.example.ssafesta.user.OAuthIdentity;
import com.example.ssafesta.user.OAuthIdentityRepository;
import com.example.ssafesta.user.OAuthProvider;
import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {

    private final UserRepository userRepository;
    private final OAuthIdentityRepository identityRepository;
    private final NicknamePolicy nicknamePolicy;
    private final WalletService wallets;

    public RegistrationService(UserRepository userRepository, OAuthIdentityRepository identityRepository,
                               NicknamePolicy nicknamePolicy, WalletService wallets) {
        this.userRepository = userRepository;
        this.identityRepository = identityRepository;
        this.nicknamePolicy = nicknamePolicy;
        this.wallets = wallets;
    }

    @Transactional
    public RegistrationResult complete(OAuthProvider provider, String providerSubject, String nickname) {
        return identityRepository.findByProviderAndProviderSubject(provider, providerSubject)
                .map(identity -> new RegistrationResult(identity.getUser().getId(), false))
                .orElseGet(() -> createMember(provider, providerSubject, nickname));
    }

    private RegistrationResult createMember(OAuthProvider provider, String providerSubject, String nickname) {
        nicknamePolicy.validate(nickname);
        if (userRepository.existsByNickname(nickname)) {
            throw new DuplicateNicknameException();
        }
        try {
            User user = userRepository.save(new User(nickname));
            identityRepository.save(new OAuthIdentity(user, provider, providerSubject));
            // Same transaction as the member row: a member must never exist without a wallet,
            // and the signup grant must not be able to land twice (spec 003 FR-002).
            wallets.openWallet(user.getId());
            return new RegistrationResult(user.getId(), true);
        } catch (DataIntegrityViolationException exception) {
            throw new RegistrationConflictException();
        }
    }

    public record RegistrationResult(Long userId, boolean newlyRegistered) {
    }
}
