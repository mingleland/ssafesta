package com.example.ssafesta.project;

import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothAccessGuard;
import com.example.ssafesta.common.ApiErrorDetail;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.storage.ObjectStorage;
import com.example.ssafesta.storage.image.ImageBytesValidator;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 프로젝트 전시 대표 이미지의 업로드·검증·제공 (GitLab #241, spec 009 C-03 개정).
 *
 * <p>세 단계 — grant 발급, 브라우저의 {@code PUT}, {@code complete} — 는 게임 Asset 업로드(spec 019)와
 * 같은 모양이다. 새로 고른 것이 아니라 <b>이미 서 있는 것을 그대로 쓴다</b>: 같은
 * {@link ObjectStorage}, 같은 {@link ImageBytesValidator}, 같은 "올릴 때는 presigned PUT, 읽을 때는
 * 프록시" 비대칭. 그 비대칭은 취향이 아니라 저장소 계약이 버킷에 {@code PUT} 만 허용하기 때문이다 —
 * 브라우저 GET 은 CORS 에서 죽고, presigned GET 을 브라우저에 주면 그것을 만들어 낸 권한 검사보다
 * 오래 사는 URL 이 된다.
 *
 * <p><b>브라우저가 무엇을 올렸다고 말하든 믿지 않는다.</b> presigned URL 은 검사를 통과한
 * content type 과 길이를 서명에 박고, {@code complete} 는 실제로 도착한 바이트를 다시 읽어 검증한다.
 * 서명은 객체 하나를 쓸 권한이지 그 내용에 대한 진술이 아니다.
 *
 * <p>업로드는 <b>부스</b>에 매달린다. 프로젝트를 만들기 전에 로고를 올리는 흐름이 요구사항이고
 * (#241 ①), 부스 편집 권한은 {@link BoothAccessGuard} 한 곳이 답한다.
 */
@Service
public class ProjectLogoService {

    /**
     * 한 부스가 <b>아무도 가리키지 않는</b> READY 로고를 몇 개까지 둘 수 있는가.
     *
     * <p>업로드만 반복하고 저장을 누르지 않는 흐름을 여기서 자른다. 참조된 로고는 세지 않으므로
     * 정상 사용에서는 닿지 않는다 — 부스가 가리킬 수 있는 로고는 결국 하나다.
     */
    static final int MAX_UNREFERENCED_PER_BOOTH = 5;

    /** 게임 Asset 과 같은 값이다 — 브라우저가 파일을 고르고 올릴 시간. */
    static final Duration GRANT_TTL = Duration.ofMinutes(10);

    private static final int LOGO_ID_LENGTH = 26;
    private static final String ID_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int ID_ATTEMPTS = 3;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ProjectLogoRepository logos;
    private final ProjectRepository projects;
    private final BoothAccessGuard accessGuard;
    private final ObjectStorage storage;
    private final ImageBytesValidator imageValidator;
    private final ProjectLogoCleanup cleanup;

    public ProjectLogoService(ProjectLogoRepository logos, ProjectRepository projects,
                              BoothAccessGuard accessGuard, ObjectStorage storage,
                              ImageBytesValidator imageValidator, ProjectLogoCleanup cleanup) {
        this.logos = logos;
        this.projects = projects;
        this.accessGuard = accessGuard;
        this.storage = storage;
        this.imageValidator = imageValidator;
        this.cleanup = cleanup;
    }

    /**
     * {@code logoId} 와 한 번 쓰는 업로드 grant 를 발급한다.
     *
     * <p>선언된 형식·크기는 <b>거절하는 데만</b> 쓰고 통과의 근거로는 쓰지 않는다. 무엇이 통과하는지는
     * 도착한 바이트를 보고 {@link #complete} 가 정한다.
     *
     * <p>발급 직전에 이 부스의 정리 가능한 행을 한 번 훑는다. 주기 배치도 같은 일을 하지만, 방금
     * 실패한 업로드를 다시 시도하는 사람이 자기 실패분 때문에 상한에 막히는 것이 가장 흔한 경로다.
     */
    @Transactional
    public IssuedGrant start(Long boothId, Long userId, String declaredContentType, long declaredByteSize) {
        accessGuard.requireActiveEditor(boothId, userId);

        if (declaredContentType == null
                || !ImageBytesValidator.ALLOWED_CONTENT_TYPES.contains(declaredContentType)) {
            throw refuse(ErrorCode.PROJECT_LOGO_TYPE_UNSUPPORTED, "MIME_NOT_ALLOWED",
                    "PNG · JPEG · GIF · WebP 만 올릴 수 있습니다.");
        }
        if (declaredByteSize <= 0 || declaredByteSize > ImageBytesValidator.MAX_BYTES) {
            throw refuse(ErrorCode.PROJECT_LOGO_TOO_LARGE, "SIZE_EXCEEDED",
                    "이미지는 " + (ImageBytesValidator.MAX_BYTES / 1024 / 1024) + "MB 이하만 올릴 수 있습니다.");
        }

        cleanup.collectForBooth(boothId);
        if (logos.countUnreferencedReady(boothId) >= MAX_UNREFERENCED_PER_BOOTH) {
            throw refuse(ErrorCode.PROJECT_LOGO_QUOTA_EXCEEDED, "QUOTA_EXCEEDED",
                    "올렸지만 저장하지 않은 이미지가 " + MAX_UNREFERENCED_PER_BOOTH
                            + "개입니다. 하나를 프로젝트에 저장하거나 잠시 뒤 다시 시도해 주세요.");
        }

        // 한 번 읽어 행에 박는다. 업로드 시점에 다시 읽으면 그 사이에 옮겨진 fallback 을 따라가고,
        // 객체는 행이 적어 두지 않은 버킷으로 서명된다.
        ObjectStorage.WriteTarget target = storage.activeWriteTarget();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(GRANT_TTL);

        for (int attempt = 0; attempt < ID_ATTEMPTS; attempt++) {
            String logoId = randomLogoId();
            if (logos.existsByLogoId(logoId)) {
                continue;
            }
            ProjectLogo logo = logos.save(new ProjectLogo(boothId, logoId, declaredContentType,
                    declaredByteSize, target.provider(), target.bucket(), objectKey(boothId, logoId),
                    now, expiresAt));
            // grant 의 수명과 정확히 같은 서명이다 — 더 길게 서명하면 complete 가 이미 거절한 행
            // 뒤에 바이트가 도착해 아무도 가리키지 않는 객체가 남는다.
            String uploadUrl = storage.presignPut(target.provider(), target.bucket(), logo.getObjectKey(),
                    declaredContentType, declaredByteSize, null, GRANT_TTL);
            return new IssuedGrant(logoId, expiresAt, uploadUrl, declaredContentType);
        }
        throw new ApiException(ErrorCode.INTERNAL_ERROR,
                "이미지 식별자를 발급하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }

    /**
     * 올라온 바이트를 검증하고 로고를 닫는다.
     *
     * <p><b>검증 실패는 {@code FAILED} 를 돌려주고 던지지 않는다.</b> 던지면 트랜잭션이 되돌아가 실패
     * 기록이 사라지고 행은 {@code PENDING} 으로 남아 영원히 재시도된다. 응답에 {@code status} 가
     * 있는 이유가 그것이다.
     *
     * <p>성공 응답의 {@code url} 을 기존 {@code PATCH /projects/{id}} 의 {@code thumbnailUrl} 에 그대로
     * 넣으면 된다 — 새 저장 계약을 만들지 않았다.
     */
    @Transactional
    public LogoView complete(Long boothId, String logoId, Long userId) {
        accessGuard.requireActiveEditor(boothId, userId);
        ProjectLogo logo = logos.findForUpdate(logoId)
                .filter(found -> found.getBoothId().equals(boothId))
                .orElseThrow(() -> new ApiException(ErrorCode.PROJECT_LOGO_NOT_FOUND));
        if (logo.getStatus() != ProjectLogoStatus.PENDING) {
            return LogoView.of(logo);
        }

        Instant now = Instant.now();
        if (logo.isGrantExpired(now)) {
            return fail(logo, "GRANT_EXPIRED", now);
        }
        // 계약 상한으로 자른다. 선언 크기로 자르면 1KiB 라고 말하고 4GiB 를 올린 경우가 그대로
        // 통과한다 — 상한을 넘긴 객체는 한 바이트 더 길게 도착하고 검증기가 SIZE_EXCEEDED 로 거절한다.
        byte[] content = readObject(logo, ImageBytesValidator.MAX_BYTES);
        if (content == null || content.length == 0) {
            return fail(logo, "UPLOAD_MISSING", now);
        }

        ImageBytesValidator.VerifiedImage verified;
        try {
            verified = imageValidator.verify(content, logo.getDeclaredByteSize());
        } catch (ApiException refusal) {
            return fail(logo, ruleOf(refusal), now);
        }
        logo.markReady(verified, now);
        return LogoView.of(logo);
    }

    /**
     * 바이트를 내려준다 — 프록시이고 리다이렉트가 아니다 (이유는 클래스 주석).
     *
     * <p>권한이 세 갈래다.
     *
     * <ul>
     *   <li>게시된 프로젝트가 이 로고를 참조하고 임대가 유효하면 <b>누구나</b> — 방문자의
     *       {@code <img>} 가 이 경로로 들어온다.
     *   <li>그 밖의 {@code READY} 로고는 <b>그 부스의 편집자만</b>. 이 갈래가 덮는 것이 핵심이다 —
     *       {@code complete} 와 {@code PATCH} 사이, 그리고 "저장이 실패해 다시 확인" 경로에서는 로고가
     *       아직 어느 프로젝트와도 연결되어 있지 않다. 소유 근거는 행의 {@code boothId} 이고 프로젝트
     *       존재 여부와 무관하다.
     *   <li>그 밖은 404 다.
     * </ul>
     *
     * @param userId 부르는 사람, 게스트면 {@code null}
     */
    @Transactional(readOnly = true)
    public LogoContent content(Long boothId, String logoId, Long userId) {
        ProjectLogo logo = logos.findByBoothIdAndLogoId(boothId, logoId)
                .orElseThrow(() -> new ApiException(ErrorCode.PROJECT_LOGO_NOT_FOUND));
        if (logo.getStatus() != ProjectLogoStatus.READY) {
            throw new ApiException(ErrorCode.PROJECT_LOGO_NOT_FOUND);
        }
        boolean publiclyVisible = publiclyReferenced(logo);
        if (!publiclyVisible) {
            // 편집자가 아니면 여기서 끝난다. 없는 것과 같은 답을 주는 이유: 있는지 여부가 남의 부스
            // 편집 상태를 알려 주는 신호가 되면 안 된다.
            requireEditorOrNotFound(boothId, userId);
        }
        // 검증된 크기로 자른다 — 그 크기로 통과한 행이므로 길이가 다르면 승인된 그 객체가 아니다.
        byte[] content = readObject(logo, logo.getByteSize());
        if (content == null || content.length != logo.getByteSize()) {
            throw refuse(ErrorCode.PROJECT_LOGO_NOT_FOUND, "OBJECT_MISSING",
                    "이미지를 불러올 수 없습니다. 다시 올려 주세요.");
        }
        return new LogoContent(logo.getContentType(), content, publiclyVisible);
    }

    /**
     * {@code thumbnailUrl} 이 바뀐 사실을 로고 쪽에 적는다. {@code ProjectService} 가 저장 직후 부른다.
     *
     * <p>여기서 <b>지우지 않는다.</b> 되돌리는 사용자가 흔하고(A → B → 다시 A), 즉시 삭제는 그
     * 되돌리기를 깨뜨린다. 표식만 남기고 실제 삭제는 정리 배치가 유예 뒤에, 그리고 <b>그 시점의
     * 참조를 다시 확인한 뒤</b> 한다.
     */
    @Transactional
    public void referenceChanged(String previousUrl, String currentUrl) {
        ManagedProjectLogoUrl.logoIdOf(previousUrl)
                .flatMap(logos::findByLogoId)
                .ifPresent(logo -> logo.markUnreferenced(Instant.now()));
        ManagedProjectLogoUrl.logoIdOf(currentUrl)
                .flatMap(logos::findByLogoId)
                .ifPresent(ProjectLogo::markReferenced);
    }

    /** 게시된 프로젝트가 이 로고를 참조하는가 — 방문자 공개의 전제다. */
    private boolean publiclyReferenced(ProjectLogo logo) {
        Optional<Project> project = projects.findByBoothId(logo.getBoothId());
        if (project.isEmpty()
                || !ManagedProjectLogoUrl.of(logo.getBoothId(), logo.getLogoId())
                        .equals(project.get().getThumbnailUrl())) {
            return false;
        }
        try {
            // 게시 여부 + 임대 유효를 함께 본다. 방문자가 이 이미지를 볼 수 있는 순간과 정확히 같은
            // 술어다 (ProjectService.requireVisitorVisible 와 같은 게이트).
            Booth booth = accessGuard.requireVisitorVisible(logo.getBoothId());
            return booth != null;
        } catch (RuntimeException notVisible) {
            return false;
        }
    }

    private void requireEditorOrNotFound(Long boothId, Long userId) {
        if (userId == null) {
            throw new ApiException(ErrorCode.PROJECT_LOGO_NOT_FOUND);
        }
        try {
            accessGuard.requireEditor(boothId, userId);
        } catch (RuntimeException notEditor) {
            throw new ApiException(ErrorCode.PROJECT_LOGO_NOT_FOUND);
        }
    }

    /**
     * 행을 {@code FAILED} 로 적고 객체는 정리 배치에 맡긴다.
     *
     * <p>여기서 바로 {@code deleteObject} 를 부르지 않는다 — 이 코드는 실패를 기록하는 트랜잭션
     * 안이고, 그 안의 저장소 호출은 실패 기록을 되돌리거나 커밋된 행의 삭제가 조용히 일어나지 않게
     * 만든다. {@code FAILED} 는 배치가 훑는 세 갈래 중 하나다.
     */
    private LogoView fail(ProjectLogo logo, String rule, Instant now) {
        logo.markFailed(rule, now);
        return LogoView.of(logo);
    }

    private byte[] readObject(ProjectLogo logo, long maxBytes) {
        return storage.getObject(logo.getProvider(), logo.getStorageBucket(), logo.getObjectKey(), maxBytes)
                .orElse(null);
    }

    private static String ruleOf(ApiException refusal) {
        List<ApiErrorDetail> errors = refusal.errors();
        if (errors == null || errors.isEmpty() || errors.get(0).rule() == null) {
            return "VALIDATION_FAILED";
        }
        return errors.get(0).rule();
    }

    private String randomLogoId() {
        StringBuilder id = new StringBuilder(LOGO_ID_LENGTH);
        while (id.length() < LOGO_ID_LENGTH) {
            id.append(ID_ALPHABET.charAt(RANDOM.nextInt(ID_ALPHABET.length())));
        }
        return id.toString();
    }

    /**
     * 객체가 사는 자리.
     *
     * <p>부스 id 와 서버가 발급한 로고 id 만 들어간다 — 업로더가 고른 파일명을 키에 넣으면 경로·상위
     * 탐색·남의 이름이 버킷으로 따라 들어간다 (게임 Asset 과 같은 규칙).
     */
    private static String objectKey(Long boothId, String logoId) {
        return "booths/" + boothId + "/projects/logos/" + logoId;
    }

    private static ApiException refuse(ErrorCode code, String rule, String message) {
        return new ApiException(code, message, List.of(ApiErrorDetail.of(rule, message)), null);
    }

    /**
     * @param requiredContentType 브라우저의 {@code PUT} 이 반드시 보내야 하는 {@code Content-Type}.
     *     {@code uploadUrl} 서명에 박혀 있어 다른 값이면 서명이 깨진다 — 알려 주지 않으면 모든 업로드가
     *     읽을 것 없는 403 이 된다.
     */
    public record IssuedGrant(String logoId, Instant expiresAt, String uploadUrl,
                              String requiredContentType) { }

    /** @param publiclyVisible 방문자에게도 열린 로고인가 — 캐시 헤더를 이것으로 가른다. */
    public record LogoContent(String contentType, byte[] content, boolean publiclyVisible) { }

    public record LogoView(String logoId, ProjectLogoStatus status, String url, String contentType,
                           Long byteSize, Integer width, Integer height, String failureRule) {

        static LogoView of(ProjectLogo logo) {
            String url = logo.getStatus() == ProjectLogoStatus.READY
                    ? ManagedProjectLogoUrl.of(logo.getBoothId(), logo.getLogoId())
                    : null;
            return new LogoView(logo.getLogoId(), logo.getStatus(), url, logo.getContentType(),
                    logo.getByteSize(), logo.getWidth(), logo.getHeight(), logo.getFailureRule());
        }
    }
}
