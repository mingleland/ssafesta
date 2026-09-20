package com.example.ssafesta.user;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OAuthIdentityRepository extends JpaRepository<OAuthIdentity, Long> {
    @EntityGraph(attributePaths = "user")
    Optional<OAuthIdentity> findByProviderAndProviderSubject(OAuthProvider provider, String providerSubject);
    List<OAuthIdentity> findAllByUser_Id(Long userId);

    /** Batched for the admin roster/search pages — one query instead of one per row. */
    List<OAuthIdentity> findAllByUser_IdIn(List<Long> userIds);
}
