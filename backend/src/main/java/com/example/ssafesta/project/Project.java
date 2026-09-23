package com.example.ssafesta.project;

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

    // ── AI 가 추출해 보내는 정형 정보 (S15P21A604-597) ────────────────────────
    // 사람이 입력하는 필드가 아니다. 문서 임베딩이 끝난 뒤 AI 가 뽑아 보내며, 프로젝트
    // 생성·수정 API 로는 바뀌지 않는다.

    /** 이 프로젝트가 누구를 위한 것인가. AI 가 추출하기 전에는 {@code null} 이다. */
    @Column(name = "target_audience", columnDefinition = "text")
    private String targetAudience;

    /** 무엇으로 만들었는가. AI 가 추출하기 전에는 {@code null} 이다. */
    @Column(name = "tech_stack", columnDefinition = "text")
    private String techStack;

    /** READY 문서 전체를 RAG 검색해 만든 소개. 운영자가 쓰는 {@link #description}과 분리한다. */
    @Column(name = "ai_introduction", columnDefinition = "text")
    private String aiIntroduction;

    /** 생성에 사용한 documentId/chunkId 배열. 운영 추적용이며 대화 응답에는 노출하지 않는다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "facts_sources", columnDefinition = "jsonb")
    private String factsSources;

    @Column(name = "facts_generation_version", length = 50)
    private String factsGenerationVersion;

    /** 지금 값이 어느 문서에서 나왔는가. 문서가 지워지면 {@code null} 이 되고 값은 남는다. */
    @Column(name = "facts_document_id")
    private Long factsDocumentId;

    /**
     * 지금 값을 보낸 Job.
     *
     * <p>이것이 최신성 판정의 전부다 — {@code jobId} 는 단조 증가하므로 더 작은 Job 이 보낸
     * 결과는 늦게 도착했더라도 오래된 것이다.
     */
    @Column(name = "facts_job_id")
    private Long factsJobId;

    @Column(name = "facts_updated_at")
    private Instant factsUpdatedAt;

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

    public String getTargetAudience() { return targetAudience; }

    public String getTechStack() { return techStack; }

    public String getAiIntroduction() { return aiIntroduction; }

    public String getFactsSources() { return factsSources; }

    public String getFactsGenerationVersion() { return factsGenerationVersion; }

    public Long getFactsDocumentId() { return factsDocumentId; }

    public Long getFactsJobId() { return factsJobId; }

    public Instant getFactsUpdatedAt() { return factsUpdatedAt; }

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

    // ── AI 추출 결과 (S15P21A604-597) ────────────────────────────────────────

    /**
     * 이 Job 의 결과가 지금 값보다 새로운가.
     *
     * <p>{@code jobId} 가 단조 증가한다는 사실 하나로 판정한다. 도착 순서로 판정하면 재시도·재전송이
     * 오래된 추출을 최신 값 위에 덮는다 — 그것이 이 기능의 완료 조건 중 하나다.
     *
     * <p>같은 Job 의 재전송({@code ==})도 "새롭지 않다" 로 본다. 이미 그 Job 의 값이 들어 있어
     * 다시 써도 같은 결과이므로, 쓰지 않고 조용히 성공으로 답하는 편이 싸다.
     */
    public boolean factsAreOlderThan(long jobId) {
        return factsJobId == null || factsJobId < jobId;
    }

    /** AI 가 추출한 값으로 갈아끼운다. 출처를 함께 적어야 다음 결과의 최신성을 판정할 수 있다. */
    public void applyFacts(String aiIntroduction, String targetAudience, String techStack,
                           String factsSources, String generationVersion,
                           Long documentId, long jobId, Instant now) {
        this.aiIntroduction = aiIntroduction;
        this.targetAudience = targetAudience;
        this.techStack = techStack;
        this.factsSources = factsSources;
        this.factsGenerationVersion = generationVersion;
        this.factsDocumentId = documentId;
        this.factsJobId = jobId;
        this.factsUpdatedAt = now;
    }

    /** 롤링 배포 중 구버전 AI 요청은 새 소개·근거 메타데이터를 지우지 않고 기존 두 값만 갱신한다. */
    public void applyLegacyFacts(String targetAudience, String techStack,
                                 Long documentId, long jobId, Instant now) {
        this.targetAudience = targetAudience;
        this.techStack = techStack;
        this.factsDocumentId = documentId;
        this.factsJobId = jobId;
        this.factsUpdatedAt = now;
    }
}
