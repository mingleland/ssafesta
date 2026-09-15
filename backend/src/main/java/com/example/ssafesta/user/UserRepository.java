package com.example.ssafesta.user;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.accountType = :accountType and u.status = :status order by u.id asc")
    List<User> findByAccountTypeAndStatusForUpdate(@Param("accountType") String accountType,
                                                   @Param("status") AccountStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :userId")
    Optional<User> findByIdForUpdate(@Param("userId") Long userId);
}
