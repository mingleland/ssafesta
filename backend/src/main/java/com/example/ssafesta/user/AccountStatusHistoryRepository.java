package com.example.ssafesta.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountStatusHistoryRepository extends JpaRepository<AccountStatusHistory, Long> {

    @Query("select new com.example.ssafesta.user.AccountStatusHistoryView(" +
            "h.user.id, h.previousStatus, h.currentStatus, h.reason, h.actorUserId, h.createdAt) " +
            "from AccountStatusHistory h where h.user.id = :userId " +
            "order by h.createdAt desc, h.id desc")
    Page<AccountStatusHistoryView> findViewsByUserId(@Param("userId") Long userId, Pageable pageable);
}
