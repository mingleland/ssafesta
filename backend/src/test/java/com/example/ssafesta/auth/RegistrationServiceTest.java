package com.example.ssafesta.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ssafesta.user.NicknamePolicy;
import com.example.ssafesta.user.OAuthIdentity;
import com.example.ssafesta.user.OAuthIdentityRepository;
import com.example.ssafesta.user.OAuthProvider;
import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.util.Optional;
import org.junit.jupiter.api.Test;

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
}
