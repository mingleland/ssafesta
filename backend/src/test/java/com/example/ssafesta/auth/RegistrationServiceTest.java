package com.example.ssafesta.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
     * inside the database. What is worth pinning is the translation: a constraint violation must come
     * out as a 409 the client can act on. Before the #113 sweep this exception carried no code at all
     * and became a 500.
     */
    @Test
    void aConstraintViolationDuringSignupCarriesItsCode() {
        UserRepository users = mock(UserRepository.class);
        OAuthIdentityRepository identities = mock(OAuthIdentityRepository.class);
        when(identities.findByProviderAndProviderSubject(OAuthProvider.GOOGLE, "subject")).thenReturn(Optional.empty());
        when(users.existsByNickname("경합닉네임")).thenReturn(false);
        when(users.save(any(User.class))).thenThrow(new DataIntegrityViolationException("ux_users_nickname"));

        RegistrationService service = new RegistrationService(
                users, identities, new NicknamePolicy(), mock(WalletService.class));

        RegistrationConflictException thrown = assertThrows(RegistrationConflictException.class,
                () -> service.complete(OAuthProvider.GOOGLE, "subject", "경합닉네임"));
        assertEquals(ErrorCode.REGISTRATION_CONFLICT, thrown.errorCode());
        assertEquals(HttpStatus.CONFLICT, thrown.errorCode().status());
    }
}
