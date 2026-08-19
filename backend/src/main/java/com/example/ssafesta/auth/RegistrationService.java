package com.example.ssafesta.auth;

import com.example.ssafesta.user.NicknamePolicy;
import com.example.ssafesta.user.OAuthIdentity;
import com.example.ssafesta.user.OAuthIdentityRepository;
import com.example.ssafesta.user.OAuthProvider;
import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {

    private final UserRepository userRepository;
    private final OAuthIdentityRepository identityRepository;
    private final NicknamePolicy nicknamePolicy;

    public RegistrationService(UserRepository userRepository, OAuthIdentityRepository identityRepository,
                               NicknamePolicy nicknamePolicy) {
        this.userRepository = userRepository;
        this.identityRepository = identityRepository;
        this.nicknamePolicy = nicknamePolicy;
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
            return new RegistrationResult(user.getId(), true);
        } catch (DataIntegrityViolationException exception) {
            throw new RegistrationConflictException();
        }
    }

    public record RegistrationResult(Long userId, boolean newlyRegistered) {
    }
}
