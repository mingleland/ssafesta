package com.example.ssafesta.wallet;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    Optional<Wallet> findByUserId(Long userId);

    /**
     * Locks the wallet row for the rest of the transaction ({@code SELECT ... FOR UPDATE}).
     *
     * <p>Every balance change goes through this so that concurrent spends on one wallet are
     * serialized instead of both reading the same "sufficient" balance (spec 003 FR-008, SC-003).
     * It also serializes duplicate requests, which is what lets the idempotency lookup in
     * {@link WalletService} see an already-committed entry rather than racing it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.userId = :userId")
    Optional<Wallet> findByUserIdForUpdate(@Param("userId") Long userId);
}
