package com.example.ssafesta.feedback;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One member-submitted feedback row (S15P21A604-953).
 *
 * <p>{@code firstFound} is an administrator's judgement, not a system-derived flag — nothing here
 * compares one submission's content to another's. Rewarding it reuses the existing manual coin
 * adjustment surface rather than a new grant path.
 */
@Entity
@Table(name = "feedback_submissions")
public class Feedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, columnDefinition = "text", updatable = false)
    private String content;

    @Column(name = "first_found", nullable = false)
    private boolean firstFound;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Feedback() {
    }

    public Feedback(Long userId, String content, Instant createdAt) {
        this.userId = userId;
        this.content = content;
        this.firstFound = false;
        this.createdAt = createdAt;
    }

    public void setFirstFound(boolean firstFound) {
        this.firstFound = firstFound;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getContent() {
        return content;
    }

    public boolean isFirstFound() {
        return firstFound;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
