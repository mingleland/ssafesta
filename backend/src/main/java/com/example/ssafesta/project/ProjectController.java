package com.example.ssafesta.project;

import com.example.ssafesta.common.MemberPrincipal;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
 * Project exhibition — owner-side write and read-back (spec 009, contracts/project-api.md).
 *
 * <p>Additive: three new endpoints, no existing consumer changes. {@code docs/08} §5 and §18 are
 * updated in the same commit (헌법 24조).
 *
 * <p>The visitor-facing read — published gate, like counts — is S15P21A604-177 and deliberately
 * absent here. What this controller exposes is the editor's own view of their own values.
 */
@RestController
@RequestMapping("/api/v1")
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

    @PostMapping("/booths/{boothId}/projects")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    public ProjectService.ProjectView create(@AuthenticationPrincipal Jwt jwt,
                                             @PathVariable Long boothId,
                                             @RequestBody(required = false)
                                             ProjectService.ProjectCommand command) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        return projects.create(boothId, userId, command);
    }

    /** 0개 또는 1개 배열. 없는 것은 오류가 아니다 — 아직 안 만들었을 뿐이다. */
    @GetMapping("/booths/{boothId}/projects")
    @SecurityRequirement(name = "bearerAuth")
    public ProjectService.ProjectListView list(@AuthenticationPrincipal Jwt jwt,
                                               @PathVariable Long boothId) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        return new ProjectService.ProjectListView(projects.findByBooth(boothId, userId));
    }

    @PatchMapping("/projects/{projectId}")
    @SecurityRequirement(name = "bearerAuth")
    public ProjectService.ProjectView update(@AuthenticationPrincipal Jwt jwt,
                                             @PathVariable Long projectId,
                                             @RequestBody(required = false)
                                             ProjectService.ProjectCommand command) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        return projects.update(projectId, userId, command);
    }
}
