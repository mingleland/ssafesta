package com.example.ssafesta.booth;

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
 * One published snapshot of a booth's layout (spec 005 FR-005, FR-008).
 *
 * <p><b>A snapshot, not a view of the draft.</b> Publishing copies, so editing afterwards cannot
 * change what visitors already see (invariant I-7) — which is the whole of FR-006 and SC-003.
 *
 * <p>Rows are never deleted or edited. That costs nothing and is the only reason C-07 ("rolling
 * back to an old version") can be opened later: history that was not kept cannot be offered.
 */
@Entity
@Table(name = "booth_layout_published_versions")
public class BoothLayoutPublishedVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booth_id", nullable = false, updatable = false)
    private Long boothId;

    /** The booth's publish count, unique per booth (invariant I-2). Never reset, not even on re-lease. */
    @Column(name = "version_no", nullable = false, updatable = false)
    private int versionNo;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "layout_json", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String layoutJson;

    @Column(name = "published_by_user_id", nullable = false, updatable = false)
    private Long publishedByUserId;

    @Column(name = "published_at", nullable = false, updatable = false)
    private Instant publishedAt = Instant.now();

    protected BoothLayoutPublishedVersion() {
    }

    public BoothLayoutPublishedVersion(Long boothId, int versionNo, int schemaVersion,
                                       String layoutJson, Long publishedByUserId) {
        this.boothId = boothId;
        this.versionNo = versionNo;
        this.schemaVersion = schemaVersion;
        this.layoutJson = layoutJson;
        this.publishedByUserId = publishedByUserId;
        this.publishedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getBoothId() { return boothId; }
    public int getVersionNo() { return versionNo; }
    public int getSchemaVersion() { return schemaVersion; }
    public String getLayoutJson() { return layoutJson; }
    public Long getPublishedByUserId() { return publishedByUserId; }
    public Instant getPublishedAt() { return publishedAt; }
}
