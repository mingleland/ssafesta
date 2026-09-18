package com.example.ssafesta.project;

import com.example.ssafesta.storage.image.ImageBytesValidator;
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
 * 업로드된 프로젝트 로고 하나의 metadata (GitLab #241, V42).
 *
 * <p>바이트는 객체 저장소에 있고 이 행은 <b>어디에 있는지</b>를 적는다. 발급 시점에 쓴
 * {@code provider}·{@code storageBucket} 이 이후 모든 읽기·삭제의 좌표다 — 지금 활성 write target
 * 이 아니다. MinIO fallback 은 새 업로드가 가는 곳을 옮기는 것이고, 옛 객체를 찾는 곳을 옮기면 안
 * 된다 (spec 007 FR-030 과 같은 불변식).
 *
 * <p>최종 참조는 {@code projects.thumbnail_url} 이 갖는다. 이 행이 참조의 정본이 아니라는 것이
 * 중요하다 — 사용자가 로고를 올린 뒤 저장을 누르지 않을 수 있고, 그 객체는 아무도 가리키지 않는
 * 상태로 남는다. {@link #markUnreferenced}·{@code unreferencedSince} 가 그 상태를 적는 자리다.
 */
@Entity
@Table(name = "project_logo_uploads")
public class ProjectLogo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booth_id", nullable = false, updatable = false)
    private Long boothId;

    @Column(name = "logo_id", nullable = false, updatable = false, length = 64)
    private String logoId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProjectLogoStatus status = ProjectLogoStatus.PENDING;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "byte_size")
    private Long byteSize;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "sha256", length = 64)
    private String sha256;

    @Column(name = "declared_content_type", nullable = false, updatable = false, length = 100)
    private String declaredContentType;

    @Column(name = "declared_byte_size", nullable = false, updatable = false)
    private Long declaredByteSize;

    // String 이고 enum 이 아니다 — provider 목록은 app.ai.storage.providers 가 정하고, 여기에 enum 을
    // 두면 그것과 맞춰야 하는 두 번째 목록이 된다 (GameAsset 과 같은 이유).
    @Column(name = "provider", nullable = false, updatable = false, length = 20)
    private String provider;

    @Column(name = "storage_bucket", nullable = false, updatable = false)
    private String storageBucket;

    @Column(name = "object_key", nullable = false, updatable = false)
    private String objectKey;

    @Column(name = "failure_rule", length = 60)
    private String failureRule;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "unreferenced_since")
    private Instant unreferencedSince;

    protected ProjectLogo() {
    }

    ProjectLogo(Long boothId, String logoId, String declaredContentType, long declaredByteSize,
                String provider, String storageBucket, String objectKey, Instant now, Instant expiresAt) {
        this.boothId = boothId;
        this.logoId = logoId;
        this.status = ProjectLogoStatus.PENDING;
        this.declaredContentType = declaredContentType;
        this.declaredByteSize = declaredByteSize;
        this.provider = provider;
        this.storageBucket = storageBucket;
        this.objectKey = objectKey;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    /** 검증을 통과했다. 실제값으로 채우고 이후 읽기는 이 값들을 쓴다. */
    void markReady(ImageBytesValidator.VerifiedImage verified, Instant now) {
        this.status = ProjectLogoStatus.READY;
        this.contentType = verified.contentType();
        this.byteSize = verified.byteSize();
        this.width = verified.width();
        this.height = verified.height();
        this.sha256 = verified.sha256();
        this.completedAt = now;
    }

    /**
     * 결정적으로 거절됐다 — 위장 MIME·초과 크기·손상된 파일.
     *
     * <p>예외를 던지지 않고 상태로 적는다. 던지면 트랜잭션이 되돌아가 실패 사실이 사라지고, 행은
     * {@code PENDING} 으로 남아 영원히 재시도된다 (게임 Asset 의 {@code complete} 와 같은 이유).
     */
    void markFailed(String rule, Instant now) {
        this.status = ProjectLogoStatus.FAILED;
        this.failureRule = rule;
        this.completedAt = now;
    }

    /** 이 로고를 가리키던 참조가 끊겼다. 삭제 후보 표식일 뿐이고 삭제의 근거는 아니다. */
    void markUnreferenced(Instant now) {
        if (this.unreferencedSince == null) {
            this.unreferencedSince = now;
        }
    }

    /** 다시 참조됐다 — 후보에서 뺀다. 되돌린 사용자를 벌주지 않는다. */
    void markReferenced() {
        this.unreferencedSince = null;
    }

    boolean isGrantExpired(Instant now) {
        return expiresAt.isBefore(now);
    }

    public Long getId() {
        return id;
    }

    public Long getBoothId() {
        return boothId;
    }

    public String getLogoId() {
        return logoId;
    }

    public ProjectLogoStatus getStatus() {
        return status;
    }

    public String getContentType() {
        return contentType;
    }

    public Long getByteSize() {
        return byteSize;
    }

    public Integer getWidth() {
        return width;
    }

    public Integer getHeight() {
        return height;
    }

    public String getFailureRule() {
        return failureRule;
    }

    public Long getDeclaredByteSize() {
        return declaredByteSize;
    }

    String getProvider() {
        return provider;
    }

    String getStorageBucket() {
        return storageBucket;
    }

    String getObjectKey() {
        return objectKey;
    }

    Instant getUnreferencedSince() {
        return unreferencedSince;
    }
}
