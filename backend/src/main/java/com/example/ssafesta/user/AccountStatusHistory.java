package com.example.ssafesta.user;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "account_status_histories")
public class AccountStatusHistory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id") private User user;
    @Enumerated(EnumType.STRING) @Column(name = "previous_status", nullable = false) private AccountStatus previousStatus;
    @Enumerated(EnumType.STRING) @Column(name = "current_status", nullable = false) private AccountStatus currentStatus;
    @Column private String reason;
    @Column(name = "actor_user_id") private Long actorUserId;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    protected AccountStatusHistory() { }
    public AccountStatusHistory(User user, AccountStatus previousStatus, AccountStatus currentStatus, String reason, Long actorUserId) {
        this.user=user; this.previousStatus=previousStatus; this.currentStatus=currentStatus; this.reason=reason; this.actorUserId=actorUserId;
    }
}
