package com.example.ssafesta.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_type", nullable = false, length = 20)
    private String accountType = "MEMBER";

    @Column(nullable = false, unique = true, length = 30)
    private String nickname;

    @Column(name = "avatar_code", columnDefinition = "text")
    private String avatarCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();


    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "suspension_reason")
    private String suspensionReason;

    protected User() {
    }

    public User(String nickname) {
        this.nickname = nickname;
    }

    public Long getId() { return id; }
    public String getNickname() { return nickname; }
    public AccountStatus getStatus() { return status; }

    public void changeNickname(String nickname) {
        this.nickname = nickname;
        this.updatedAt = Instant.now();
    }

    public void suspend(String reason) {
        this.status = AccountStatus.SUSPENDED;
        this.suspendedAt = Instant.now();
        this.suspensionReason = reason;
        this.updatedAt = Instant.now();
    }

    public void unsuspend() {
        this.status = AccountStatus.ACTIVE;
        this.suspendedAt = null;
        this.suspensionReason = null;
        this.updatedAt = Instant.now();
    }
}
