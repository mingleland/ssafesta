package com.example.ssafesta.auth;

import com.example.ssafesta.user.NicknamePolicy;
import com.example.ssafesta.user.OAuthIdentity;
import com.example.ssafesta.user.OAuthIdentityRepository;
import com.example.ssafesta.user.OAuthProvider;
import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {

    /** The only two unique constraints a signup can lose to; anything else is our bug. */
    private static final String NICKNAME_TAKEN = "users_nickname_key";
    private static final String IDENTITY_ALREADY_SIGNED_UP = "oauth_identities_provider_provider_subject_key";

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
        User user;
        try {
            user = userRepository.save(new User(nickname));
            identityRepository.save(new OAuthIdentity(user, provider, providerSubject));
        } catch (DataIntegrityViolationException exception) {
            throw asSignupRaceOrRethrow(exception);
        }
        // Same transaction as the member row: a member must never exist without a wallet, and the
        // signup grant must not be able to land twice (spec 003 FR-002). Outside the catch, though —
        // this user was created a line ago, so nothing here can be someone else getting there first.
        wallets.openWallet(user.getId());
        return new RegistrationResult(user.getId(), true);
    }

    /**
     * A race is one of two named constraints; everything else is rethrown to become a loud 500.
     * Answering 409 to a fault would tell the caller to retry what can never succeed (T-24).
     */
    private RuntimeException asSignupRaceOrRethrow(DataIntegrityViolationException exception) {
        return switch (String.valueOf(violatedConstraint(exception))) {
            case NICKNAME_TAKEN -> new DuplicateNicknameException();
            case IDENTITY_ALREADY_SIGNED_UP -> new RegistrationConflictException();
            default -> exception;
        };
    }

    private static String violatedConstraint(Throwable exception) {
        return exception.getCause() instanceof ConstraintViolationException violation
                ? violation.getConstraintName() : null;
    }

    public record RegistrationResult(Long userId, boolean newlyRegistered) {
    }
}
