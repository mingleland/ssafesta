package com.example.ssafesta.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.user.NicknamePolicy;
import com.example.ssafesta.user.OAuthIdentity;
import com.example.ssafesta.user.OAuthIdentityRepository;
import com.example.ssafesta.user.OAuthProvider;
import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.sql.SQLException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

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
     * The losing side of a signup race carries a code, so the client is told to retry rather than
     * that the server broke.
     *
     * <p>The race itself cannot be staged here — it lives between the duplicate check and the insert,
     * inside the database. What is worth pinning is the translation: a unique violation must come out
     * as a 409 the client can act on. Before the #113 sweep this exception carried no code at all and
     * became a 500.
     */
    @Test
    void aUniqueViolationDuringSignupCarriesItsCode() {
        RegistrationConflictException thrown = assertThrows(RegistrationConflictException.class,
                () -> signupFailingWith(violation("23505", "duplicate key value violates unique constraint")));

        assertEquals(ErrorCode.REGISTRATION_CONFLICT, thrown.errorCode());
        assertEquals(HttpStatus.CONFLICT, thrown.errorCode().status());
    }

    /**
     * Everything that is not a unique violation stays a fault, loudly.
     *
     * <p>Catching {@code DataIntegrityViolationException} wholesale would answer 409 for a foreign
     * key, a NOT NULL or a CHECK failing — all of them bugs in our own code. The caller would be told
     * to retry something that can never succeed, and the fault would sit in a status nobody
     * investigates instead of in an error log (T-24). Raised in review of !56.
     */
    @Test
    void anIntegrityFaultDuringSignupIsNotDisguisedAsARace() {
        DataIntegrityViolationException notARace = violation("23503", "violates foreign key constraint");

        DataIntegrityViolationException thrown = assertThrows(DataIntegrityViolationException.class,
                () -> signupFailingWith(notARace));

        assertSame(notARace, thrown, "경합이 아닌 무결성 오류는 그대로 올라가 500으로 크게 남아야 합니다.");
    }

    /** A violation shaped like the driver's: the SQLState rides on a nested {@link SQLException}. */
    private DataIntegrityViolationException violation(String sqlState, String message) {
        return new DataIntegrityViolationException(message, new SQLException(message, sqlState));
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
