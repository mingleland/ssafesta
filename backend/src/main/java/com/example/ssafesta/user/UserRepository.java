package com.example.ssafesta.user;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
    boolean existsByNickname(String nickname);
    Optional<User> findByNickname(String nickname);

    /** The admin roster, masters included — {@code ADMIN} is the account type both of them carry. */
    List<User> findByAccountTypeOrderByIdAsc(String accountType);

    /**
     * How many accounts may still use the admin API.
     *
     * <p>Read inside the demotion transaction: it is the one check that keeps the service from
     * reaching zero admins, and the promotion endpoint that would fix that is itself behind the
     * admin gate.
     */
    long countByAccountType(String accountType);
}
