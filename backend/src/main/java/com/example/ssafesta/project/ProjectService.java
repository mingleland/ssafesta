package com.example.ssafesta.project;

import com.example.ssafesta.booth.BoothEditorGuard;
import com.example.ssafesta.booth.BoothExpiredException;
import com.example.ssafesta.booth.BoothLeaseRepository;
import com.example.ssafesta.common.ApiErrorDetail;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.common.HttpUrlValidator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
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

    private final ProjectRepository projects;
    private final BoothEditorGuard editorGuard;
    private final BoothLeaseRepository leases;

    public ProjectService(ProjectRepository projects, BoothEditorGuard editorGuard,
                          BoothLeaseRepository leases) {
        this.projects = projects;
        this.editorGuard = editorGuard;
        this.leases = leases;
    }

    // ── 등록 ────────────────────────────────────────────────────────────────

    @Transactional
    public ProjectView create(Long boothId, Long userId, ProjectCommand command) {
        editorGuard.requireEditor(boothId, userId);
        requireValidLease(boothId);

        String name = validatedName(command, true);
        validateUrls(command);

        // 사전 검사와 제약 번역을 둘 다 둔다. 사전 검사는 흔한 중복에 제대로 된 문장을 주고,
        // 번역은 경쟁 조건에서만 나오는 위반을 같은 응답으로 만든다 (research R-02).
        if (projects.findByBoothId(boothId).isPresent()) {
            throw new ProjectAlreadyExistsException(boothId);
        }

        Instant now = Instant.now();
        Project project = new Project(boothId, name, command.description.value,
                command.thumbnailUrl.value, command.videoUrl.value, command.deployUrl.value,
                command.gitUrl.value, command.portfolioUrl.value, now);
        try {
            // saveAndFlush 다. save 만 쓰면 INSERT 가 커밋 시점으로 밀려 유니크 위반이 이
            // try 바깥에서 터지고, 번역을 우회해 500 이 나간다 (BoothLeaseService 와 같은 이유).
            project = projects.saveAndFlush(project);
        } catch (DataIntegrityViolationException exception) {
            throw new ProjectAlreadyExistsException(boothId);
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

        if (command.name.present) {
            project.changeName(validatedName(command, false));
        }
        validateUrls(command);

        if (command.description.present) {
            project.changeDescription(command.description.value);
        }
        if (command.thumbnailUrl.present) {
            project.changeThumbnailUrl(command.thumbnailUrl.value);
        }
        if (command.videoUrl.present) {
            project.changeVideoUrl(command.videoUrl.value);
        }
        if (command.deployUrl.present) {
            project.changeDeployUrl(command.deployUrl.value);
        }
        if (command.gitUrl.present) {
            project.changeGitUrl(command.gitUrl.value);
        }
        if (command.portfolioUrl.present) {
            project.changePortfolioUrl(command.portfolioUrl.value);
        }
        project.touch(Instant.now());
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

    // ── 검증 ────────────────────────────────────────────────────────────────

    private void requireValidLease(Long boothId) {
        // facade·homepage 와 같은 결이다: 만료된 부스는 아무에게도 보이지 않으므로 편집은
        // 보이지 않는 것을 고치는 일이 된다 (spec 004 만료 계약).
        leases.findValidByBoothId(boothId, Instant.now())
                .orElseThrow(() -> new BoothExpiredException(boothId));
    }

    private String validatedName(ProjectCommand command, boolean required) {
        if (command == null || !command.name.present) {
            if (required) {
                throw rejectField("name", "프로젝트 이름을 입력해 주세요.");
            }
            return null;
        }
        String name = command.name.value;
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

    private void validateUrl(Field field, String jsonField, String displayName) {
        if (field.present) {
            HttpUrlValidator.validate(field.value, jsonField, displayName);
        }
    }

    private ApiException rejectField(String jsonField, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message,
                List.of(ApiErrorDetail.field(jsonField, message)), null);
    }

    // ── 요청·응답 ───────────────────────────────────────────────────────────

    /**
     * One field of a request, and whether the client actually sent it.
     *
     * <p>Jackson only calls a setter when the key is present, so {@code present} is the difference
     * between "leave it alone" and "clear it" — a distinction a {@code record} cannot carry, because
     * both arrive as {@code null}. 016 shipped that collapse once and {@code {}} became a silent
     * unregister (T-97).
     */
    static final class Field {
        private String value;
        private boolean present;

        void set(String value) {
            this.value = value;
            this.present = true;
        }
    }

    /** Not a {@code record} — see {@link Field}. */
    public static final class ProjectCommand {

        private final Field name = new Field();
        private final Field description = new Field();
        private final Field thumbnailUrl = new Field();
        private final Field videoUrl = new Field();
        private final Field deployUrl = new Field();
        private final Field gitUrl = new Field();
        private final Field portfolioUrl = new Field();

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
            return name.present || description.present || thumbnailUrl.present || videoUrl.present
                    || deployUrl.present || gitUrl.present || portfolioUrl.present;
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
}
