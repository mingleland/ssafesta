package com.example.ssafesta.user;

import java.time.Instant;

/** One immutable row returned by the administrator's account status history endpoint. */
public record AccountStatusHistoryView(
        Long userId,
        AccountStatus previousStatus,
        AccountStatus currentStatus,
        String reason,
        Long actorUserId,
        Instant createdAt) {
}
