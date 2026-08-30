package com.example.ssafesta.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * What a booth exhibits (spec 009 FR-001~FR-003).
 *
 * <p>The table has existed since V1 with every column this needs; what was missing was any read or
 * write path. V14 adds the one thing V1 left out — the unique index that makes "one project per
 * booth" (C-01) an invariant rather than an intention.
 *
 * <p><b>Links are three columns, not a list.</b> The spec's Key Entities used to describe a
 * {@code Project Link} entity with a type and a display name; that was removed on 2026-08-28 (C-05)
 * because no screen ever asked for it. If one does, a {@code project_links} table is a cheaper
 * migration than unwinding a table nobody reads.
 *
 * <p>The booth is held as a plain id rather than a {@code @ManyToOne}, matching {@code BoothLease}:
 * nothing here needs to walk into the booth, and a lazy association would only invite it.
 */
@Entity
@Table(name = "projects")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Fixed at creation. Moving a project between booths would move exhibition content across
     * owners, which C-04 (content follows the original owner) forbids.
     */
    @Column(name = "booth_id", nullable = false, updatable = false)
    private Long boothId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "thumbnail_url", length = 2048)
    private String thumbnailUrl;

    @Column(name = "video_url", length = 2048)
    private String videoUrl;

    @Column(name = "deploy_url", length = 2048)
    private String deployUrl;

    @Column(name = "git_url", length = 2048)
    private String gitUrl;

    @Column(name = "portfolio_url", length = 2048)
    private String portfolioUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Maintained here, not by the database.
     *
     * <p>V1 gives both timestamp columns {@code DEFAULT CURRENT_TIMESTAMP}, but a default only fires
     * on INSERT and there is no update trigger. Left to the schema alone this column would sit at
     * the creation time forever, which is worse than having no column at all — it would look
     * maintained.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Project() {
    }

    public Project(Long boothId, String name, String description, String thumbnailUrl,
                   String videoUrl, String deployUrl, String gitUrl, String portfolioUrl,
                   Instant now) {
        this.boothId = boothId;
        this.name = name;
        this.description = description;
        this.thumbnailUrl = thumbnailUrl;
        this.videoUrl = videoUrl;
        this.deployUrl = deployUrl;
        this.gitUrl = gitUrl;
        this.portfolioUrl = portfolioUrl;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() { return id; }

    public Long getBoothId() { return boothId; }

    public String getName() { return name; }

    public String getDescription() { return description; }

    public String getThumbnailUrl() { return thumbnailUrl; }

    public String getVideoUrl() { return videoUrl; }

    public String getDeployUrl() { return deployUrl; }

    public String getGitUrl() { return gitUrl; }

    public String getPortfolioUrl() { return portfolioUrl; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    // ── 수정 ────────────────────────────────────────────────────────────────
    // 한 필드씩 갈아끼운다. PATCH 는 "보낸 것만" 바꾸므로 (C-06) 전체를 받는 setter 를 두면
    // 호출자가 안 바뀐 필드까지 실어 보내야 하고, 그 순간 누락 = 삭제가 되어 계약이 뒤집힌다.

    public void changeName(String name) { this.name = name; }

    public void changeDescription(String description) { this.description = description; }

    public void changeThumbnailUrl(String thumbnailUrl) { this.thumbnailUrl = thumbnailUrl; }

    public void changeVideoUrl(String videoUrl) { this.videoUrl = videoUrl; }

    public void changeDeployUrl(String deployUrl) { this.deployUrl = deployUrl; }

    public void changeGitUrl(String gitUrl) { this.gitUrl = gitUrl; }

    public void changePortfolioUrl(String portfolioUrl) { this.portfolioUrl = portfolioUrl; }

    /** 값이 실제로 바뀐 요청에서만 부른다 — 아무것도 안 바뀐 PATCH 가 시각을 흔들지 않게. */
    public void touch(Instant now) { this.updatedAt = now; }
}
