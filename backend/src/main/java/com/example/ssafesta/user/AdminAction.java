package com.example.ssafesta.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One thing an administrator did (S15P21A604-743).
 *
 * <p>Promotion and demotion live here too. Account status and coin movements each keep their own
 * history, so leaving privilege changes unrecorded would make the one question nobody can answer
 * later — "who made this person an administrator?" — the largest hole in the audit trail.
 *
 * <p><b>No foreign keys.</b> {@code AccountDeletionService} ends with {@code DELETE FROM users};
 * a reference from here would make withdrawal fail for anyone who was ever promoted, even after
 * being demoted. The coin ledger keeps its actor the same way — an audit row states what was true
 * at a moment, and is not a child of whatever still exists today.
 */
@Entity
@Table(name = "admin_actions")
public class AdminAction {

    /** The migration's own rows. Member ids start at 1, so this cannot collide with a person. */
    public static final long SYSTEM_ACTOR = 0L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private Long actorUserId;

    @Column(nullable = false, length = 40, updatable = false)
    private String action;

    @Column(name = "target_type", nullable = false, length = 20, updatable = false)
    private String targetType;

    @Column(name = "target_id", updatable = false)
    private Long targetId;

    @Column(columnDefinition = "text", updatable = false)
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected AdminAction() {
    }

    public AdminAction(Long actorUserId, String action, String targetType, Long targetId, String detail) {
        this.actorUserId = actorUserId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.detail = detail;
    }

    public Long getId() { return id; }
    public Long getActorUserId() { return actorUserId; }
    public String getAction() { return action; }
    public String getTargetType() { return targetType; }
    public Long getTargetId() { return targetId; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
