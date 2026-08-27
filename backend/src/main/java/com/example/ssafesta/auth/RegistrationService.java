package com.example.ssafesta.auth;

import com.example.ssafesta.user.NicknamePolicy;
import com.example.ssafesta.user.OAuthIdentity;
import com.example.ssafesta.user.OAuthIdentityRepository;
import com.example.ssafesta.user.OAuthProvider;
import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    /**
     * The two unique constraints a signup can actually lose to. Names are PostgreSQL's own
     * ({@code table_column_key}) and {@code RegistrationConstraintIntegrationTest} checks they still
     * exist, so a migration that renames one breaks a test rather than quietly turning a race into a
     * 500 in production.
     *
     * <p>{@code oauth_identities_user_id_provider_key} is deliberately <b>not</b> here. The identity
     * below is inserted for a user created one line earlier, so that user cannot already have an
     * identity for this provider — the constraint is unreachable on this path, and listing it would
     * mean answering 409 to something that could only be our own bug (raised in review of !56).
     */
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
     * Translates the database's refusal, and only where we can name what it refused.
     *
     * <p>Two signups colliding is recoverable and the caller is told so. A foreign key, a NOT NULL or
     * a CHECK failing is a bug in this code: retrying can never fix it, so telling the caller to
     * retry would loop them forever, and answering 409 would bury a server fault in a status nobody
     * investigates. Those become a 500 that is logged loudly (T-24).
     *
     * <p>The decision is made on the <b>constraint that fired</b>, not on the SQLState alone. Only
     * these two mean "another signup got here first", and each is checked against the live schema
     * by {@code RegistrationConstraintIntegrationTest}:
     *
     * <ul>
     *   <li>{@code users_nickname_key} — the nickname was taken between the check above and the
     *       insert. It comes back as {@link DuplicateNicknameException}, the same answer the check
     *       gives, because the caller needs a different nickname rather than a retry.
     *   <li>{@code oauth_identities_provider_provider_subject_key} — the same person's signup landed
     *       twice. Retrying works: the second attempt finds the identity and returns that member.
     * </ul>
     *
     * <p>Everything else is rethrown, <b>including a unique violation we did not expect</b>. Matching
     * on SQLState alone made every 23505 a race, so a unique index added later would silently start
     * answering 409; narrowing the catch to the two inserts made it true only by accident of
     * today's schema, and listing a constraint that cannot fire would have kept a hole open in it.
     * Being wrong in the direction of a loud 500 is the only safe way to be wrong here (!56 review).
     */
    private RuntimeException asSignupRaceOrRethrow(DataIntegrityViolationException exception) {
        String constraint = violatedConstraint(exception);
        if (constraint == null) {
            // No name means we cannot tell what was refused, so we do not get to call it a race.
            return exception;
        }
        if (NICKNAME_TAKEN.equals(constraint)) {
            log.warn("닉네임 경합 — 중복 검사 뒤 다른 가입이 같은 닉네임을 먼저 가져갔습니다.");
            return new DuplicateNicknameException();
        }
        if (IDENTITY_ALREADY_SIGNED_UP.equals(constraint)) {
            log.warn("가입 경합 — 같은 소셜 계정의 가입이 동시에 두 번 도착했습니다.");
            return new RegistrationConflictException();
        }
        return exception;
    }

    /** The constraint PostgreSQL named in its refusal, or {@code null} when it named none. */
    private static String violatedConstraint(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
        }
        return null;
    }

    public record RegistrationResult(Long userId, boolean newlyRegistered) {
    }
}
