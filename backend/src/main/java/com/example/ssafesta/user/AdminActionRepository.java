package com.example.ssafesta.user;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminActionRepository extends JpaRepository<AdminAction, Long> {

    /** What happened to one resource, newest first — the first question anyone asks of an audit. */
    List<AdminAction> findByTargetTypeAndTargetIdOrderByIdDesc(String targetType, Long targetId);
}
