package com.example.ssafesta.survey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The survey a booth collects answers with (spec 010 FR-001, FR-003).
 *
 * <p>The table has existed since V1 with every column this needs; what was missing was any read or
 * write path. V22 adds the one thing V1 left out — {@code ux_surveys_booth}, which makes "one survey
 * per booth" (C-06) an invariant rather than an intention.
 *
 * <p><b>Saving publishes.</b> {@code status} is written as {@code OPEN} and never read: the frontend
 * Builder has no publish button and no close button (C-07), so a saved survey is one visitors can
 * answer. Closing is {@code endsAt} passing, judged on read rather than stored — storing it would
 * need a scheduler to flip the flag, and there is none. {@code BoothLease} decides expiry the same
 * way.
 *
 * <p>{@code createdByUserId} is always the <b>booth owner</b>, never the staff member who happened
 * to press save. Nothing reads the column, and holding a staff id in it is what made withdrawal
 * delete someone else's surveys — see {@code AccountDeletionService} (data-model invariant I-5).
 *
 * <p>The booth is a plain id rather than a {@code @ManyToOne}, matching {@code Project} and
 * {@code BoothLease}: nothing here walks into the booth, and a lazy association would invite it.
 */
@Entity
@Table(name = "surveys")
public class Survey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Fixed at creation — a survey moving between booths would move responses across owners. */
    @Column(name = "booth_id", nullable = false, updatable = false)
    private Long boothId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    /** 0 means no reward. Guests are refused above 0 — they have no wallet (헌법 12조). */
    @Column(name = "reward_coin", nullable = false)
    private int rewardCoin;

    /**
     * Always {@code OPEN}. Kept because V1 declares it {@code NOT NULL}; deliberately not a state
     * machine, which is why V22 leaves it without a CHECK.
     */
    @Column(nullable = false, length = 20)
    private String status;

    /** {@code null} = no deadline. Past = closed; nothing flips a flag. */
    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "created_by_user_id", nullable = false, updatable = false)
    private Long createdByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Maintained here, not by the database. V1 gives it {@code DEFAULT CURRENT_TIMESTAMP}, but a
     * default fires only on INSERT and there is no update trigger — left alone the column would sit
     * at the creation time forever and look maintained (same reasoning as {@code Project}).
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Survey() {
    }

    public Survey(Long boothId, Long ownerUserId, String title, String description,
                  int rewardCoin, Instant endsAt, Instant now) {
        this.boothId = boothId;
        this.createdByUserId = ownerUserId;
        this.title = title;
        this.description = description;
        this.rewardCoin = rewardCoin;
        this.endsAt = endsAt;
        this.status = "OPEN";
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Metadata only. Questions live in their own rows and are replaced separately. */
    public void update(String title, String description, int rewardCoin, Instant endsAt, Instant now) {
        this.title = title;
        this.description = description;
        this.rewardCoin = rewardCoin;
        this.endsAt = endsAt;
        this.updatedAt = now;
    }

    /** Read-time judgement, never stored (see class javadoc). */
    public boolean isClosedAt(Instant moment) {
        return endsAt != null && !endsAt.isAfter(moment);
    }

    public boolean hasReward() {
        return rewardCoin > 0;
    }

    public Long getId() {
        return id;
    }

    public Long getBoothId() {
        return boothId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public int getRewardCoin() {
        return rewardCoin;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public Long getCreatedByUserId() {
        return createdByUserId;
    }
}
