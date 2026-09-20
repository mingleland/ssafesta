package com.example.ssafesta.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ssafesta.user.NicknamePolicy;
import com.example.ssafesta.user.OAuthIdentity;
import com.example.ssafesta.user.OAuthIdentityRepository;
import com.example.ssafesta.user.OAuthProvider;
import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.sql.SQLException;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class RegistrationServiceTest {

    @Test
    void returnsExistingMemberWithoutCreatingDuplicate() {
        UserRepository users = mock(UserRepository.class);
        OAuthIdentityRepository identities = mock(OAuthIdentityRepository.class);
        User user = new User("기존사용자");
        OAuthIdentity identity = new OAuthIdentity(user, OAuthProvider.GOOGLE, "subject");
        when(identities.findByProviderAndProviderSubject(OAuthProvider.GOOGLE, "subject")).thenReturn(Optional.of(identity));

        WalletService wallets = mock(WalletService.class);
        RegistrationService service = new RegistrationService(users, identities, new NicknamePolicy(), wallets);

        assertFalse(service.complete(OAuthProvider.GOOGLE, "subject", "다른닉네임").newlyRegistered());
    }

    /**
     * A refusal we cannot name is a fault, loudly.
     *
     * <p>The service decides between race and fault by the constraint PostgreSQL named. When there
     * is no name — a refusal that did not come through Hibernate, or one it could not attribute —
     * there is nothing to excuse, and guessing would mean answering 409 for a foreign key or a CHECK:
     * a bug of ours, told to the caller as something to retry, in a status nobody investigates.
     *
     * <p>The race mappings are covered against real violations in
     * {@code RegistrationConstraintIntegrationTest} — a hand-built exception could only assert that
     * the code matches strings it was handed, not that those strings are what the database says.
     */
    @Test
    void anUnattributableIntegrityFailureIsNotDisguisedAsARace() {
        DataIntegrityViolationException notARace = new DataIntegrityViolationException("violates foreign key");

        DataIntegrityViolationException thrown = assertThrows(DataIntegrityViolationException.class,
                () -> signupFailingWith(notARace));

        assertSame(notARace, thrown, "이름 없는 무결성 오류는 그대로 올라가 500 으로 크게 남아야 합니다.");
    }

    /** A name we do not translate is a fault too — same arm, different input class. */
    @Test
    void aUniqueViolationTheServiceCannotNameIsNotDisguisedAsARace() {
        DataIntegrityViolationException unknown = new DataIntegrityViolationException("dup",
                new ConstraintViolationException("dup", new SQLException("dup", "23505"),
                        "oauth_identities_user_id_provider_key"));

        assertSame(unknown, assertThrows(DataIntegrityViolationException.class, () -> signupFailingWith(unknown)),
                "서비스가 번역하지 않는 제약도 그대로 올라가야 합니다.");
    }

    private void signupFailingWith(DataIntegrityViolationException failure) {
        UserRepository users = mock(UserRepository.class);
        OAuthIdentityRepository identities = mock(OAuthIdentityRepository.class);
        when(identities.findByProviderAndProviderSubject(OAuthProvider.GOOGLE, "subject")).thenReturn(Optional.empty());
        when(users.existsByNickname("경합닉네임")).thenReturn(false);
        when(users.save(any(User.class))).thenThrow(failure);

        new RegistrationService(users, identities, new NicknamePolicy(), mock(WalletService.class))
                .complete(OAuthProvider.GOOGLE, "subject", "경합닉네임");
    }
}
