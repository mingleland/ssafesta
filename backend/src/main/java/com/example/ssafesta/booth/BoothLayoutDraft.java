package com.example.ssafesta.booth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The work-in-progress layout of one booth (spec 005 FR-005).
 *
 * <p><b>The primary key is the booth id</b>, so "a booth has at most one draft" is a fact of the
 * schema rather than a rule the service has to remember (invariant I-1). docs/09's single-table
 * design with a {@code state} column could not say that — two DRAFT rows would be legal.
 *
 * <p>A draft is never visible to visitors. That is enforced by the read path, not by a flag here:
 * {@code GET /layouts/published} reads the published table and nothing else (I-4).
 */
@Entity
@Table(name = "booth_layout_drafts")
public class BoothLayoutDraft {

    @Id
    @Column(name = "booth_id")
    private Long boothId;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "layout_json", nullable = false, columnDefinition = "jsonb")
    private String layoutJson;

    /**
     * Bumped on every accepted save; the client must send the value it read.
     *
     * <p>This is the whole of the concurrent-editing policy (C-05, research R-03). A save that
     * quietly overwrote someone else's would be worse than a rejected one — the loser would never
     * learn what disappeared.
     */
    @Column(name = "revision", nullable = false)
    private long revision;

    @Column(name = "updated_by_user_id", nullable = false)
    private Long updatedByUserId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected BoothLayoutDraft() {
    }

    public BoothLayoutDraft(Long boothId, int schemaVersion, String layoutJson, Long updatedByUserId) {
        this.boothId = boothId;
        this.schemaVersion = schemaVersion;
        this.layoutJson = layoutJson;
        this.updatedByUserId = updatedByUserId;
        this.revision = 1L;
        this.updatedAt = Instant.now();
    }

    public Long getBoothId() { return boothId; }
    public int getSchemaVersion() { return schemaVersion; }
    public String getLayoutJson() { return layoutJson; }
    public long getRevision() { return revision; }
    public Long getUpdatedByUserId() { return updatedByUserId; }
    public Instant getUpdatedAt() { return updatedAt; }
}
