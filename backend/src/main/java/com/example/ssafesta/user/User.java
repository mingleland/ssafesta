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

    static final String MEMBER = "MEMBER";
    static final String ADMIN = "ADMIN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_type", nullable = false, length = 20)
    private String accountType = MEMBER;

    /**
     * The one account nobody may act on (S15P21A604-743).
     *
     * <p>Deliberately <b>not</b> a third {@code accountType} value: {@link #promoteToAdmin()} and
     * {@link #demoteToMember()} overwrite that column wholesale, so a master kept there would
     * vanish on a single demotion. Here it sits in a column those two never touch, and there is no
     * API that sets it — a migration is the only way in.
     */
    @Column(name = "is_master", nullable = false)
    private boolean master;

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
    public String getAccountType() { return accountType; }
    public Instant getCreatedAt() { return createdAt; }

    /** Whether this account may use the admin API at all. A master is an admin too. */
    public boolean isAdmin() { return ADMIN.equals(accountType); }

    /** The protected account. Every admin action that names a target has to ask this first. */
    public boolean isMaster() { return master; }

    /**
     * Grants admin rights. Leaves {@link #master} alone — that flag has no API.
     *
     * @return {@code false} when the account already had them, so the caller can answer the
     *         duplicate request without a second audit row
     */
    public boolean promoteToAdmin() {
        if (isAdmin()) {
            return false;
        }
        this.accountType = ADMIN;
        this.updatedAt = Instant.now();
        return true;
    }

    /** @return {@code false} when the account was not an admin to begin with */
    public boolean demoteToMember() {
        if (!isAdmin()) {
            return false;
        }
        this.accountType = MEMBER;
        this.updatedAt = Instant.now();
        return true;
    }

    /** The stored appearance encoding, or {@code null} for a user who has never saved one. */
    public String getAvatarCode() { return avatarCode; }

    public void changeNickname(String nickname) {
        this.nickname = nickname;
        this.updatedAt = Instant.now();
    }

    /**
     * Stores the appearance encoding <em>verbatim</em>.
     *
     * <p>No trim, no case change, no default substitution. The string is opaque to the server —
     * spec 013 hands appearance interpretation to the client and asks Spring for length and
     * charset only, so anything this method "fixed" would be a silent divergence from what the
     * client sent. That is the shape T-24 took: a value quietly rewritten on the way in, and a
     * user left wondering why the button did nothing. Callers validate first
     * ({@link AvatarCodePolicy}) and reject loudly.
     */
    public void changeAvatarCode(String avatarCode) {
        this.avatarCode = avatarCode;
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
