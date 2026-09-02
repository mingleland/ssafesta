package com.example.ssafesta.project;

import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothEditorGuard;
import com.example.ssafesta.booth.BoothExpiredException;
import com.example.ssafesta.booth.BoothLeaseRepository;
import com.example.ssafesta.booth.BoothNotFoundException;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.booth.LayoutNotPublishedException;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ConstraintViolations;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.common.HttpUrlValidator;
import com.example.ssafesta.common.PresenceField;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registering and editing what a booth exhibits (spec 009 FR-001~FR-004, contracts/project-api.md).
 *
 * <p>The same shape as {@code BoothHomepageService} and {@code BoothFacadeService}: editor guard,
 * valid lease, validate, write. Draft/Publish is not involved — a project is saved the moment it is
 * sent, and whether visitors <i>see</i> it is a separate question that belongs to
 * S15P21A604-177.
 *
 * <p>Reading is deliberately ungated here: the owner of an unpublished booth still has to load their
 * own values to fill the edit form, exactly as {@code GET /booths/mine} does for 016.
 */
@Service
public class ProjectService {

    private static final int MAX_NAME = 100;

    /** V14 가 만든 유니크 인덱스. 이 이름의 위반만 409 다 (아래 {@code translate}). */
    private static final String PROJECT_BOOTH_INDEX = "ux_projects_booth";

    private final ProjectRepository projects;
    private final BoothEditorGuard editorGuard;
    private final BoothLeaseRepository leases;
    private final BoothRepository booths;

    public ProjectService(ProjectRepository projects, BoothEditorGuard editorGuard,
                          BoothLeaseRepository leases, BoothRepository booths) {
        this.projects = projects;
        this.editorGuard = editorGuard;
        this.leases = leases;
        this.booths = booths;
    }

    // ── 등록 ────────────────────────────────────────────────────────────────

    @Transactional
    public ProjectView create(Long boothId, Long userId, ProjectCommand command) {
        editorGuard.requireEditor(boothId, userId);
        requireValidLease(boothId);

        String name = validatedName(command);
        validateUrls(command);

        // 사전 검사와 제약 번역을 둘 다 둔다. 사전 검사는 흔한 중복에 제대로 된 문장을 주고,
        // 번역은 경쟁 조건에서만 나오는 위반을 같은 응답으로 만든다 (research R-02).
        if (projects.findByBoothId(boothId).isPresent()) {
            throw new ProjectAlreadyExistsException(boothId);
        }

        Instant now = Instant.now();
        Project project = new Project(boothId, name, command.description.value(),
                command.thumbnailUrl.value(), command.videoUrl.value(), command.deployUrl.value(),
                command.gitUrl.value(), command.portfolioUrl.value(), now);
        try {
            // id 가 IDENTITY 라 save() 만으로도 INSERT 가 지금 나가고 위반도 여기서 잡힌다.
            // 그래도 saveAndFlush 로 못박아 두는 것은, 생성 전략을 SEQUENCE 로 바꾸는 순간
            // INSERT 가 커밋 시점으로 밀려 이 catch 가 조용히 무력해지기 때문이다 — 그때
            // 깨지는 것은 이 줄이 아니라 사용자가 받는 500 이다.
            project = projects.saveAndFlush(project);
        } catch (DataIntegrityViolationException exception) {
            throw translate(exception, boothId);
        }
        return ProjectView.of(project);
    }

    // ── 수정 ────────────────────────────────────────────────────────────────

    @Transactional
    public ProjectView update(Long projectId, Long userId, ProjectCommand command) {
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));

        // 권한은 프로젝트가 아니라 그것이 붙은 부스에 딸린다 — 이 한 줄이 타 부스 프로젝트
        // 수정을 막는다.
        editorGuard.requireEditor(project.getBoothId(), userId);
        requireValidLease(project.getBoothId());

        if (command == null || !command.hasAnyKey()) {
            // 빈 본문을 조용한 no-op 으로 통과시키면 "저장했다"는 200 을 받고 아무 일도 안 일어난다.
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "수정할 내용이 없습니다.");
        }

        if (command.name.isPresent()) {
            validatedName(command);
        }
        validateUrls(command);

        boolean changed = apply(command.name, project.getName(), project::changeName);
        changed |= apply(command.description, project.getDescription(), project::changeDescription);
        changed |= apply(command.thumbnailUrl, project.getThumbnailUrl(), project::changeThumbnailUrl);
        changed |= apply(command.videoUrl, project.getVideoUrl(), project::changeVideoUrl);
        changed |= apply(command.deployUrl, project.getDeployUrl(), project::changeDeployUrl);
        changed |= apply(command.gitUrl, project.getGitUrl(), project::changeGitUrl);
        changed |= apply(command.portfolioUrl, project.getPortfolioUrl(), project::changePortfolioUrl);
        if (changed) {
            project.touch(Instant.now());
        }
        return ProjectView.of(project);
    }

    // ── 조회 (편집자) ───────────────────────────────────────────────────────

    /**
     * No published gate, and no lease check either.
     *
     * <p>FR-008 keeps the data alive after a lease expires; if the owner could not read it back,
     * "preserved" would be unobservable. Editing stays blocked — that is the part an expired booth
     * has no business doing.
     *
     * <p>Returns a list of zero or one. A booth holds one project (C-01), but the shape stays a list
     * so that {@code docs/08} §5 does not change if the limit ever rises.
     */
    @Transactional(readOnly = true)
    public List<ProjectView> findByBooth(Long boothId, Long userId) {
        editorGuard.requireEditor(boothId, userId);
        return projects.findByBoothId(boothId).map(ProjectView::of).map(List::of).orElseGet(List::of);
    }

    // ── 조회 (방문자) ───────────────────────────────────────────────────────

    /**
     * What a visitor sees on a public booth (계약 §6, FR-005·SC-002).
     *
     * <p>Three gates in this order, and the order is the contract: a booth that does not exist, one
     * whose lease has run out, and one that has never been published each get their own answer. The
     * order is copied from {@code BoothQueryService.findPublicBooth} so the same booth cannot report
     * different reasons depending on which endpoint asked.
     *
     * <p><b>미게시는 404 이고 빈 배열이 아니다.</b> "이 부스는 방문자에게 열려 있지 않다"와 "부스는
     * 열렸고 전시가 아직 없다"는 다른 사실이라, 하나로 뭉치면 클라이언트가 둘을 구분할 수단을
     * 잃는다. 빈 배열은 계속 후자 하나만 뜻한다 (§3 과 같은 규칙).
     *
     * <p>쿼리는 회원 방문자 기준 5개다 (부스·임대·프로젝트·좋아요 수·본인 좋아요). 부스당
     * 프로젝트가 1개라 N+1 이 되지 않으므로 그대로 둔다 — 합칠 곳은 뒤의 둘이고,
     * {@code COUNT(*) FILTER (WHERE user_id = ...)} 한 줄로 4개가 되지만 반환이
     * {@code Object[]} 가 돼 호출부에 캐스팅이 생긴다. <b>부스 진입 지연이 실제로 문제가 되면</b>
     * 그때 합친다.
     *
     * @param viewerUserId the member behind the request, or {@code null} for a guest — a guest is a
     *        normal caller here, not a refusal (헌법 12조는 소유를 금지하는 것이고 열람이 아니다)
     */
    @Transactional(readOnly = true)
    public VisitorProjectListView findPublishedByBooth(Long boothId, Long viewerUserId) {
        requireVisitorVisible(boothId);
        return new VisitorProjectListView(projects.findByBoothId(boothId)
                .map(project -> VisitorProjectView.of(project, projects.countLikes(project.getId()),
                        likedBy(project.getId(), viewerUserId)))
                .map(List::of).orElseGet(List::of));
    }

    /** 게스트는 좋아요 행을 가질 수 없으므로 묻지 않는다 — 계약 §6 이 정한 {@code false} 다. */
    private boolean likedBy(Long projectId, Long viewerUserId) {
        return viewerUserId != null && projects.isLikedBy(projectId, viewerUserId);
    }

    /**
     * 방문자에게 이 부스가 열려 있는가 — 세 게이트이고 <b>순서가 계약이다</b> (§6). 없는 부스,
     * 임대가 끝난 부스, 한 번도 게시되지 않은 부스가 각각 자기 답을 받는다. 순서는
     * {@code BoothQueryService.findPublicBooth} 에서 그대로 가져왔다.
     *
     * <p>읽기(§6)와 좋아요(§8)가 <b>같은 함수를 부른다.</b> 나누면 같은 부스가 무엇을 물었는지에
     * 따라 다른 사유를 답할 수 있고, 그때 틀린 쪽이 어느 쪽인지 응답만 보고는 알 수 없다.
     */
    private void requireVisitorVisible(Long boothId) {
        Booth booth = booths.findById(boothId).orElseThrow(() -> new BoothNotFoundException(boothId));
        requireValidLease(boothId);
        // 게시 게이트는 배치의 게시 여부다 — 프로젝트에 별도의 게시 상태를 만들지 않는다.
        // 프로젝트 패널이 게시된 배치 안의 오브젝트라서, 방문자가 그것을 누를 수 있는 순간과
        // 이 술어가 정확히 겹친다. Booth.isPublished 가 016 홈페이지와 공유하는 그 술어다.
        if (!booth.isPublished()) {
            throw new LayoutNotPublishedException();
        }
    }

    // ── 좋아요 (계약 §8) ────────────────────────────────────────────────────

    /**
     * 누르기. <b>멱등이다</b> — 이미 누른 회원이 다시 불러도 좋아요는 하나이고 응답도 같다.
     *
     * <p>토글 하나로 만들지 않은 이유가 여기다. 더블탭과 네트워크 재시도가 같은 요청을 두 번
     * 보내는데, 토글이면 두 번째가 방금 누른 것을 취소한다 — 사용자는 누른 적 없는 취소를 본다.
     *
     * <p>{@code likedByMe} 를 다시 묻지 않는다. 이 함수가 정상 반환했다는 것이 곧 행이 있다는
     * 뜻이다 (§8).
     */
    @Transactional
    public LikeView like(Long projectId, Long userId) {
        requireLikeableProject(projectId);
        projects.insertLike(projectId, userId);
        return new LikeView(projects.countLikes(projectId), true);
    }

    /** 취소. 누른 적 없는 회원이 불러도 오류가 아니다 — 결과가 같으므로 답도 같다. */
    @Transactional
    public LikeView unlike(Long projectId, Long userId) {
        requireLikeableProject(projectId);
        projects.deleteLike(projectId, userId);
        return new LikeView(projects.countLikes(projectId), false);
    }

    /**
     * 프로젝트 먼저, 그 다음 부스 게이트. {@code update} 와 같은 순서다 — 프로젝트가 없으면
     * 어느 부스를 물어야 할지도 모르기 때문이고, 그래서 이 순서에는 선택의 여지가 없다.
     *
     * <p>편집자 가드는 걸지 않는다. 좋아요는 방문자의 행위이므로 <b>남의 부스에서 누르는 것이
     * 정상 경로다</b> — 여기에 {@code requireEditor} 를 넣으면 기능이 자기 부스 전용이 된다.
     */
    private void requireLikeableProject(Long projectId) {
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
        requireVisitorVisible(project.getBoothId());
    }

    // ── 검증 ────────────────────────────────────────────────────────────────

    /**
     * 만료 부스에서 막는 것은 <b>쓰기와 방문자 읽기 둘</b>이고, 예외는 편집자 읽기(§3) 하나다.
     *
     * <p>이 함수를 지우거나 조건을 느슨하게 만들면 만료 부스의 전시가 방문자에게 계속 보이고,
     * "방문자는 여기서 만료를 안다"(004 FR-019)가 조용히 깨진다 — 정상 경로는 전부 초록인 채로
     * {@code expiredBoothIsConflictEvenWhenItWasPublished} 하나만 빨개진다.
     *
     * <p>편집자 읽기가 예외인 이유는 {@code findByBooth} 에 적어 두었다 (FR-008 — 보존은 소유자가
     * 읽을 수 있어야 관측된다).
     */
    private void requireValidLease(Long boothId) {
        // facade·homepage 와 같은 결이다: 만료된 부스는 아무에게도 보이지 않으므로 편집은
        // 보이지 않는 것을 고치는 일이 된다 (spec 004 만료 계약).
        leases.findValidByBoothId(boothId, Instant.now())
                .orElseThrow(() -> new BoothExpiredException(boothId));
    }

    /**
     * 통과하면 값을 돌려주고, 아니면 던진다. <b>{@code null} 을 돌려주는 경로가 없다</b> — 있으면
     * 호출자가 그것을 {@code changeName(null)} 로 흘려 NOT NULL 컬럼을 깨뜨릴 수 있고, 그건
     * 검증기가 만든 구멍이 된다.
     *
     * <p>키가 없는 경우는 호출자가 먼저 갈라야 한다. {@code POST} 는 필수라 여기 오면 오류이고,
     * {@code PATCH} 는 "안 건드림"이라 애초에 이 함수를 부르지 않는다.
     */
    private String validatedName(ProjectCommand command) {
        String name = command == null || !command.name.isPresent() ? null : command.name.value();
        if (name == null || name.isBlank()) {
            // NOT NULL 컬럼이라 명시적 null 도 삭제가 아니라 오류다.
            throw rejectField("name", "프로젝트 이름을 입력해 주세요.");
        }
        if (name.length() > MAX_NAME) {
            throw rejectField("name", "프로젝트 이름이 너무 깁니다. (최대 " + MAX_NAME + "자)");
        }
        return name;
    }

    /**
     * 보낸 필드만, 그리고 <b>값이 실제로 달라졌을 때만</b> 적용한다.
     *
     * @return 이 필드가 행을 바꿨는지 — {@code updated_at} 을 흔들지 말지를 호출자가 이것으로 정한다
     */
    private static boolean apply(PresenceField<String> field, String current, Consumer<String> setter) {
        if (!field.isPresent() || Objects.equals(current, field.value())) {
            return false;
        }
        setter.accept(field.value());
        return true;
    }

    /**
     * 다섯 필드가 같은 규칙을 쓰되 각자의 이름으로 거부당한다 — "주소 형식이 올바르지 않습니다"만
     * 다섯 번 나오면 어느 칸을 고쳐야 할지 알 수 없다.
     *
     * <p>보낸 필드만 본다. 안 보낸 필드를 검사하면 {@code PATCH} 가 남의 값에 손대는 셈이 된다.
     */
    private void validateUrls(ProjectCommand command) {
        validateUrl(command.thumbnailUrl, "thumbnailUrl", "대표 이미지");
        validateUrl(command.videoUrl, "videoUrl", "영상");
        validateUrl(command.deployUrl, "deployUrl", "배포");
        validateUrl(command.gitUrl, "gitUrl", "저장소");
        validateUrl(command.portfolioUrl, "portfolioUrl", "포트폴리오");
    }

    private void validateUrl(PresenceField<String> field, String jsonField, String displayName) {
        if (field.isPresent()) {
            HttpUrlValidator.validate(field.value(), jsonField, displayName);
        }
    }

    /**
     * 이 INSERT 가 깨뜨릴 수 있는 제약은 하나가 아니다.
     *
     * <p>{@code ux_projects_booth} 는 경쟁에서 진 것이고 409 가 맞다. 그러나 {@code booth_id} 의
     * 외래키도 같은 예외 타입으로 온다 — 등록하는 사이에 부스가 사라진 경우다. 그것까지
     * "이미 프로젝트가 있습니다"로 답하면 사용자는 있지도 않은 프로젝트를 찾으러 간다.
     *
     * <p>그래서 <b>제약 이름을 보고</b>, 모르는 위반은 삼키지 않고 그대로 올린다
     * ({@code GlobalExceptionHandler} 가 500 으로 낸다 — 서버가 설명하지 못하는 사건이라
     * 그게 정직하다). {@code BoothLeaseService.translateRace} 와 같은 판별 방식이다.
     */
    static RuntimeException translate(DataIntegrityViolationException exception, Long boothId) {
        return ConstraintViolations.isViolationOf(exception, PROJECT_BOOTH_INDEX)
                ? new ProjectAlreadyExistsException(boothId)
                : exception;
    }

    private ApiException rejectField(String jsonField, String message) {
        return ApiException.fieldInvalid(jsonField, message);
    }

    // ── 요청·응답 ───────────────────────────────────────────────────────────

    /** Not a {@code record} — see {@link Field}. */
    public static final class ProjectCommand {

        private final PresenceField<String> name = new PresenceField<>();
        private final PresenceField<String> description = new PresenceField<>();
        private final PresenceField<String> thumbnailUrl = new PresenceField<>();
        private final PresenceField<String> videoUrl = new PresenceField<>();
        private final PresenceField<String> deployUrl = new PresenceField<>();
        private final PresenceField<String> gitUrl = new PresenceField<>();
        private final PresenceField<String> portfolioUrl = new PresenceField<>();

        @JsonProperty("name")
        void setName(String value) { name.set(value); }

        @JsonProperty("description")
        void setDescription(String value) { description.set(value); }

        @JsonProperty("thumbnailUrl")
        void setThumbnailUrl(String value) { thumbnailUrl.set(value); }

        @JsonProperty("videoUrl")
        void setVideoUrl(String value) { videoUrl.set(value); }

        @JsonProperty("deployUrl")
        void setDeployUrl(String value) { deployUrl.set(value); }

        @JsonProperty("gitUrl")
        void setGitUrl(String value) { gitUrl.set(value); }

        @JsonProperty("portfolioUrl")
        void setPortfolioUrl(String value) { portfolioUrl.set(value); }

        boolean hasAnyKey() {
            return name.isPresent() || description.isPresent() || thumbnailUrl.isPresent() || videoUrl.isPresent()
                    || deployUrl.isPresent() || gitUrl.isPresent() || portfolioUrl.isPresent();
        }
    }

    /**
     * A {@code record}, unlike the request — every key is always present and {@code null} means
     * "no value". A client reading {@code thumbnailUrl} should get {@code null}, never
     * {@code undefined} (C-03, the same rule {@code avatarCode} follows).
     */
    public record ProjectView(Long projectId, String name, String description, String thumbnailUrl,
                              String videoUrl, String deployUrl, String gitUrl,
                              String portfolioUrl) {

        static ProjectView of(Project project) {
            return new ProjectView(project.getId(), project.getName(), project.getDescription(),
                    project.getThumbnailUrl(), project.getVideoUrl(), project.getDeployUrl(),
                    project.getGitUrl(), project.getPortfolioUrl());
        }
    }

    /** {@code { "projects": [...] }} — 0개 또는 1개 (C-01 파생 ⑵). */
    public record ProjectListView(List<ProjectView> projects) {

        public ProjectListView {
            projects = List.copyOf(Objects.requireNonNull(projects));
        }
    }

    /**
     * {@link ProjectView} 의 여덟 필드 + 좋아요 둘 (계약 §6).
     *
     * <p>필드를 더하지 않고 {@code ProjectView} 를 쓰지 않는 이유: 그러면 편집자 응답(§3)의 모양이
     * 같이 바뀐다. 반대로 이름·타입은 §3 과 똑같이 두었다 — 클라이언트가 파서를 하나로 쓴다.
     *
     * <p>{@code likeCount} 는 {@code null} 이 되지 않는다. {@code -135} 가 붙기 전에는 항상 {@code 0}
     * 이지만 키는 지금부터 있다 — 나중에 키를 더하면 소비자가 그 시점에 파서를 또 고쳐야 한다.
     */
    public record VisitorProjectView(Long projectId, String name, String description, String thumbnailUrl,
                                     String videoUrl, String deployUrl, String gitUrl, String portfolioUrl,
                                     long likeCount, boolean likedByMe) {

        static VisitorProjectView of(Project project, long likeCount, boolean likedByMe) {
            return new VisitorProjectView(project.getId(), project.getName(), project.getDescription(),
                    project.getThumbnailUrl(), project.getVideoUrl(), project.getDeployUrl(),
                    project.getGitUrl(), project.getPortfolioUrl(), likeCount, likedByMe);
        }
    }

    /** {@code { "projects": [...] }} — §3 과 같은 모양이다. 부스당 1개라도 배열을 유지한다 (C-01). */
    public record VisitorProjectListView(List<VisitorProjectView> projects) {

        public VisitorProjectListView {
            projects = List.copyOf(Objects.requireNonNull(projects));
        }
    }

    /**
     * 좋아요 응답 (계약 §8) — {@link VisitorProjectView} 의 좋아요 두 필드와 같은 이름·같은
     * 타입이다. 클라이언트가 방문자 조회에 쓰던 파서를 그대로 쓴다.
     *
     * <p>프로젝트 전체를 돌려주지 않는다. 좋아요는 나머지 여덟 필드를 바꾸지 않으므로 같이 보내면
     * 소비자가 "이 응답으로 화면을 다시 그려야 하나"를 매번 판단해야 한다.
     */
    public record LikeView(long likeCount, boolean likedByMe) {
    }
}
