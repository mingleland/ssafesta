package com.example.ssafesta.project;

import com.example.ssafesta.common.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Project exhibition — owner-side write and read-back, plus the visitor read (spec 009,
 * contracts/project-api.md).
 *
 * <p><b>Three of the four endpoints require a member; the fourth answers guests.</b>
 * {@code GET /booths/{boothId}/projects/published} (S15P21A604-177, 계약 §6) is behind the published
 * gate instead of the editor guard, and it lives on its own URL suffix so that identity never turns
 * the same path from 200 into 403. It is registered {@code permitAll} in
 * {@code SecurityConfiguration} — a change there can widen or close it without touching this file.
 *
 * <p>Additive: no existing consumer changes. {@code docs/08} §5 and §18 are updated in the same
 * commit (헌법 24조).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Project")
public class ProjectController {

    /**
     * Guests own nothing (헌법 12조), and saying so at the door beats failing later for want of a
     * booth. {@code BoothPrincipal} carries the same idea with booth wording, but it is
     * package-private to {@code booth} — this is the shared entry point it wraps.
     */
    private static final String MEMBER_ONLY = "회원 계정만 프로젝트를 편집할 수 있습니다.";

    private final ProjectService projects;

    public ProjectController(ProjectService projects) {
        this.projects = projects;
    }

    @Operation(summary = "프로젝트 카드 만들기 — 부스당 1개",
            description = """
                    부스에 전시할 프로젝트 카드를 만든다. **부스당 1개**이며 소유자와 스태프가 편집할 수 있다.

                    URL 필드(홈페이지·영상·배포 주소 등)는 모두 같은 규칙으로 검증된다 — `http`·`https` 만 허용하고,
                    사용자 정보(`@`)가 들어간 주소와 쓸 수 없는 포트를 거부하며, **저장은 보낸 문자열 그대로**다
                    (한글 도메인도 변환하지 않는다).

                    만든 직후에는 방문자에게 보이지 않는다 — 공개는 별도 상태이고, 방문자는
                    `GET /api/v1/booths/{boothId}/projects/published` 로 공개된 것만 본다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성된 프로젝트 전체"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — 필수 누락·길이 초과·URL 규칙 위반. `errors[0].field` 가 문제 필드다"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY`(게스트) 또는 `BOOTH_FORBIDDEN`(내 부스가 아니다)"),
            @ApiResponse(responseCode = "404", description = "`BOOTH_NOT_FOUND` — 그런 부스가 없다"),
            @ApiResponse(responseCode = "409", description = "이미 프로젝트가 있거나 임대가 만료된 부스다")})
    @PostMapping("/booths/{boothId}/projects")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    public ProjectService.ProjectView create(@AuthenticationPrincipal Jwt jwt,
                                             @Parameter(description = "내 부스 식별자", example = "7")
                                             @PathVariable Long boothId,
                                             @RequestBody(required = false)
                                             ProjectService.ProjectCommand command) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        return projects.create(boothId, userId, command);
    }

    /** 0개 또는 1개 배열. 없는 것은 오류가 아니다 — 아직 안 만들었을 뿐이다. */
    @Operation(summary = "내 부스의 프로젝트 조회 — 편집용, 0개 또는 1개",
            description = """
                    편집자가 자기 부스의 프로젝트를 읽는다. 공개 여부와 무관하게 **저장된 값**이 나온다 —
                    미공개 상태에서도 폼을 프리필해야 하기 때문이다.

                    아직 만들지 않았으면 `404` 가 아니라 **빈 배열**이다.

                    방문자용 조회는 `GET /api/v1/booths/{boothId}/projects/published` 로 따로 있다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "프로젝트 0개 또는 1개"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY`(게스트) 또는 `BOOTH_FORBIDDEN`(내 부스가 아니다)"),
            @ApiResponse(responseCode = "404", description = "`BOOTH_NOT_FOUND` — 그런 부스가 없다")})
    @GetMapping("/booths/{boothId}/projects")
    @SecurityRequirement(name = "bearerAuth")
    public ProjectService.ProjectListView list(@AuthenticationPrincipal Jwt jwt,
                                               @Parameter(description = "내 부스 식별자", example = "7")
                                               @PathVariable Long boothId) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        return new ProjectService.ProjectListView(projects.findByBooth(boothId, userId));
    }

    /**
     * 방문자용 조회 (계약 §6). <b>토큰이 없어도 200 이다</b> — 그래서
     * {@code @SecurityRequirement} 도 붙이지 않는다.
     *
     * <p>토큰은 {@code likedByMe} 판정에만 쓴다. {@code optionalMemberId} 는 게스트와 비회원 토큰에
     * {@code null} 을 준다 ({@code BoothSlotController.slots} 와 같은 방식).
     */
    @Operation(summary = "공개된 프로젝트 조회 — 방문자용",
            description = """
                    방문자가 부스에서 보는 프로젝트 목록이다. **토큰이 없어도 `200`** 이다 (그래서 자물쇠가 없다).

                    편집용 경로와 URL 을 나눈 이유가 있다. 같은 경로에서 신원에 따라 `200` 과 `403` 이 갈리면
                    클라이언트가 "내 것인지"를 응답 코드로 추론하게 된다 — 경로를 나누면 그런 추론이 필요 없다.

                    토큰을 보내면 `likedByMe` 판정에만 쓰인다. 게스트·비회원 토큰에서는 그 값이 채워지지 않는다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "공개된 프로젝트 목록. 공개된 것이 없으면 빈 배열이다"),
            @ApiResponse(responseCode = "404", description = "`BOOTH_NOT_FOUND` — 그런 부스가 없다"),
            @ApiResponse(responseCode = "409", description = "`BOOTH_LEASE_EXPIRED` — 임대가 끝난 부스다")})
    @GetMapping("/booths/{boothId}/projects/published")
    public ProjectService.VisitorProjectListView published(@AuthenticationPrincipal Jwt jwt,
                                                           @Parameter(description = "부스 식별자", example = "7")
                                                           @PathVariable Long boothId) {
        return projects.findPublishedByBooth(boothId, MemberPrincipal.optionalMemberId(jwt));
    }

    @Operation(summary = "프로젝트 수정 — 보낸 필드만 바뀐다",
            description = """
                    `PATCH` 이므로 본문에 넣은 필드만 바뀐다. URL 필드를 지우려면 `null` 을 **명시**한다 —
                    필드를 빼면 "그대로 두라"는 뜻이고, 그 둘을 구분하는 것이 이 API 의 계약이다.

                    공개 상태도 여기서 바꾼다. 공개하면 방문자용 조회에 즉시 나타난다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정된 프로젝트 전체"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — 바꿀 내용이 없거나 값 규칙 위반이다"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY`(게스트) 또는 `BOOTH_FORBIDDEN`(내 부스의 프로젝트가 아니다)"),
            @ApiResponse(responseCode = "404", description = "`PROJECT_NOT_FOUND` — 그런 프로젝트가 없다"),
            @ApiResponse(responseCode = "409", description = "`BOOTH_LEASE_EXPIRED` — 임대가 끝난 부스다")})
    @PatchMapping("/projects/{projectId}")
    @SecurityRequirement(name = "bearerAuth")
    public ProjectService.ProjectView update(@AuthenticationPrincipal Jwt jwt,
                                             @Parameter(description = "프로젝트 식별자", example = "301")
                                             @PathVariable Long projectId,
                                             @RequestBody(required = false)
                                             ProjectService.ProjectCommand command) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        return projects.update(projectId, userId, command);
    }
}
