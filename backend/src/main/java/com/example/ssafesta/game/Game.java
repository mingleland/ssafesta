package com.example.ssafesta.game;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One user-made game: who owns it, whether others may start it, and which version is public.
 *
 * <p>{@code publishedVersion} is a <b>pointer</b>, not a count. It can be {@code null} while
 * published history keeps growing — that is how "unpublished" is expressed (contracts
 * §Persistence Boundary). Deriving it from {@code MAX(version_no)} would resurrect a version the
 * creator deliberately took down, the same mistake spec 005 avoided for booth layouts.
 */
@Entity
@Table(name = "games")
public class Game {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private Long ownerUserId;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    private GameVisibility visibility = GameVisibility.PRIVATE;

    @Column(name = "published_version")
    private Integer publishedVersion;

    /**
     * Set by ordinary deletion; the row and its published history stay (FR-040).
     *
     * <p>Only member withdrawal removes the row, and that path deletes drafts, versions, assets and
     * display-only scores with it.
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Game() {
    }

    public Game(Long ownerUserId, String title) {
        this.ownerUserId = ownerUserId;
        this.title = title;
        this.visibility = GameVisibility.PRIVATE;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * Moves the public pointer to a version that must already be in the database.
     *
     * <p>The caller flushes the new version row first: the composite foreign key checks that
     * {@code (id, published_version)} exists, and inside one transaction the insert has to reach
     * the database before this update does.
     */
    void publishVersion(int versionNo) {
        this.publishedVersion = versionNo;
        this.updatedAt = Instant.now();
    }

    void changeVisibility(GameVisibility next) {
        this.visibility = next;
        this.updatedAt = Instant.now();
    }

    void softDelete(Instant when) {
        this.deletedAt = when;
        this.updatedAt = when;
    }

    void restore() {
        this.deletedAt = null;
        this.updatedAt = Instant.now();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isOwnedBy(Long userId) {
        return userId != null && userId.equals(ownerUserId);
    }

    public Long getId() { return id; }
    public Long getOwnerUserId() { return ownerUserId; }
    public String getTitle() { return title; }
    public GameVisibility getVisibility() { return visibility; }
    public Integer getPublishedVersion() { return publishedVersion; }
    public Instant getDeletedAt() { return deletedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
