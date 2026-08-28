package com.example.ssafesta.game;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One immutable snapshot of a published game.
 *
 * <p>Every column is {@code updatable = false}: a version that could change would mean a player
 * mid-session and a player starting now are running different games under the same number
 * (contracts §Publish — "기존 Published Version은 update하지 않는다"). Re-publishing appends a new
 * row and moves the pointer instead.
 *
 * <p>History survives ordinary deletion. It is removed only by member withdrawal, so "restore" and
 * "play an older version" stay possible later — history that was not kept cannot be offered.
 */
@Entity
@Table(name = "game_published_versions")
public class GamePublishedVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "game_id", nullable = false, updatable = false)
    private Long gameId;

    /** The game's publish count, unique per game. Never reset. */
    @Column(name = "version_no", nullable = false, updatable = false)
    private int versionNo;

    @Column(name = "schema_version", nullable = false, updatable = false, length = 20)
    private String schemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "project_json", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String projectJson;

    @Column(name = "published_by_user_id", nullable = false, updatable = false)
    private Long publishedByUserId;

    @Column(name = "published_at", nullable = false, updatable = false)
    private Instant publishedAt = Instant.now();

    protected GamePublishedVersion() {
    }

    public GamePublishedVersion(Long gameId, int versionNo, String schemaVersion,
                                String projectJson, Long publishedByUserId) {
        this.gameId = gameId;
        this.versionNo = versionNo;
        this.schemaVersion = schemaVersion;
        this.projectJson = projectJson;
        this.publishedByUserId = publishedByUserId;
        this.publishedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getGameId() { return gameId; }
    public int getVersionNo() { return versionNo; }
    public String getSchemaVersion() { return schemaVersion; }
    public String getProjectJson() { return projectJson; }
    public Long getPublishedByUserId() { return publishedByUserId; }
    public Instant getPublishedAt() { return publishedAt; }
}
