package com.example.ssafesta.game;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The editable copy of one game's project (contracts §Draft 저장).
 *
 * <p><b>The primary key is the game id</b>, so "a game has at most one draft" is a fact of the
 * schema rather than a rule the service has to remember — the same reason spec 005 keyed booth
 * layout drafts on the booth.
 *
 * <p>A draft is never reachable through the Runtime endpoint. That is enforced by the read path:
 * {@code GET /games/{id}/published} reads the published table and nothing else.
 *
 * <p>Publishing does <b>not</b> delete the draft. The creator keeps editing from where they were
 * (contracts §Publish).
 */
@Entity
@Table(name = "game_drafts")
public class GameDraft {

    @Id
    @Column(name = "game_id")
    private Long gameId;

    /** {@code "1.0.0"} or {@code "1.1.0"} — the project envelope's own value, never derived. */
    @Column(name = "schema_version", nullable = false, length = 20)
    private String schemaVersion;

    /**
     * Bumped on every accepted save; the client sends the value it read.
     *
     * <p>Starts at 1, which is why {@code expectedRevision: 0} means "there is no draft yet, create
     * it" rather than "revision zero" (contracts §Draft 저장). A save that quietly overwrote
     * someone else's would be worse than a rejected one — the loser would never learn what
     * disappeared.
     */
    @Column(name = "revision", nullable = false)
    private int revision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "project_json", nullable = false, columnDefinition = "jsonb")
    private String projectJson;

    @Column(name = "updated_by_user_id", nullable = false)
    private Long updatedByUserId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected GameDraft() {
    }

    public GameDraft(Long gameId, String schemaVersion, String projectJson, Long updatedByUserId) {
        this.gameId = gameId;
        this.schemaVersion = schemaVersion;
        this.projectJson = projectJson;
        this.updatedByUserId = updatedByUserId;
        this.revision = 1;
        this.updatedAt = Instant.now();
    }

    public Long getGameId() { return gameId; }
    public String getSchemaVersion() { return schemaVersion; }
    public int getRevision() { return revision; }
    public String getProjectJson() { return projectJson; }
    public Long getUpdatedByUserId() { return updatedByUserId; }
    public Instant getUpdatedAt() { return updatedAt; }
}
