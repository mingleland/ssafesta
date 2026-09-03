package com.example.ssafesta.ai;

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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI staff configuration — the owner-facing half of spec 007 (docs/08 §6).
 *
 * <p>Additive: five new endpoints, no existing consumer changes. {@code DELETE} was not in the
 * canonical spec; it came from the Jira completion criteria and was confirmed 2026-08-30, so it is
 * written into spec 007 C-14 alongside the reference rules it needs.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "AI Agent")
public class AiAgentController {

    /** Guests own nothing (헌법 12조), and saying so at the door beats failing later. */
    private static final String MEMBER_ONLY = "회원 계정만 AI 직원을 편집할 수 있습니다.";

    private final AiAgentService agents;

    public AiAgentController(AiAgentService agents) {
        this.agents = agents;
    }

    @Operation(summary = "AI 직원 만들기 — 부스당 1명",
            description = """
                    부스의 AI 직원을 만든다. **한 부스에 1명뿐**이며, 이미 있으면 `409 AGENT_LIMIT_EXCEEDED` 다 —
                    바꾸려면 만들지 말고 `PATCH /api/v1/agents/{agentId}` 로 수정한다.

                    소유자와 같은 부스 스태프가 만들 수 있고, 임대가 유효한 부스여야 한다.

                    **필수는 `name`·`role`·`systemPrompt` 세 개다.** 나머지는 기본값이 있다.

                    | 필드 | 값 | 기본 |
                    |---|---|---|
                    | `name` | 최대 100자 | 필수 |
                    | `role` | `PROJECT_DOCENT`·`GUIDE` | 필수 |
                    | `systemPrompt` | 답변 지침 원문. **글자 그대로 저장되고 그대로 돌아온다** | 필수 |
                    | `tone` | `FRIENDLY`·`PROFESSIONAL`·`ENTHUSIASTIC` | `FRIENDLY` |
                    | `responseLength` | `SHORT`·`MEDIUM`·`LONG` | `MEDIUM` |
                    | `servicePrice` | 상담 1회 코인 가격(0 이상) | `0` |
                    | `handoffEnabled` | 사람 상담 연결 허용 | `false` |
                    | `forbiddenTopics` | 금지 주제 문자열 배열 | 빈 배열 |
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성된 AI 직원 전체"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — 필수 누락이나 허용값 위반. `errors[0].field` 가 문제 필드다"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY`(게스트) 또는 `BOOTH_FORBIDDEN`(내 부스가 아니다)"),
            @ApiResponse(responseCode = "404", description = "`BOOTH_NOT_FOUND` — 그런 부스가 없다"),
            @ApiResponse(responseCode = "409", description = "`AGENT_LIMIT_EXCEEDED`(이미 1명 있다) 또는 `BOOTH_LEASE_EXPIRED`(임대 만료)")})
    @PostMapping("/booths/{boothId}/agents")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    public AiAgentService.AgentView create(@AuthenticationPrincipal Jwt jwt,
                                           @Parameter(description = "내 부스 식별자", example = "7")
                                           @PathVariable Long boothId,
                                           @RequestBody(required = false)
                                           AiAgentService.AgentCommand command) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        return agents.create(boothId, userId, command);
    }

    /** 0개 또는 1개 배열. 아직 안 만든 것은 오류가 아니다. */
    @Operation(summary = "부스의 AI 직원 조회 — 0개 또는 1개",
            description = """
                    부스에 설정된 AI 직원을 배열로 돌려준다. 부스당 1명이므로 길이는 **0 또는 1** 이다.

                    아직 만들지 않았으면 `404` 가 아니라 **빈 배열**이다 — 없는 것은 오류가 아니고, 스튜디오는
                    그 응답으로 "AI 직원 만들기" 버튼을 띄운다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "AI 직원 0개 또는 1개"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY`(게스트) 또는 `BOOTH_FORBIDDEN`(내 부스가 아니다)"),
            @ApiResponse(responseCode = "404", description = "`BOOTH_NOT_FOUND` — 그런 부스가 없다")})
    @GetMapping("/booths/{boothId}/agents")
    @SecurityRequirement(name = "bearerAuth")
    public AiAgentService.AgentListView list(@AuthenticationPrincipal Jwt jwt,
                                             @Parameter(description = "부스 식별자", example = "7")
                                             @PathVariable Long boothId) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        return new AiAgentService.AgentListView(agents.findByBooth(boothId, userId));
    }

    @Operation(summary = "AI 직원 단건 조회",
            description = """
                    AI 직원 하나를 식별자로 읽는다. 그 직원이 속한 부스의 편집 권한이 있어야 한다.

                    `systemPrompt` 는 저장된 원문이 **글자 그대로** 돌아온다 — 서버가 다듬지 않는다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "AI 직원 전체"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY`(게스트) 또는 `BOOTH_FORBIDDEN`(그 부스 편집 권한이 없다)"),
            @ApiResponse(responseCode = "404", description = "`AGENT_NOT_FOUND` — 그런 AI 직원이 없다")})
    @GetMapping("/agents/{agentId}")
    @SecurityRequirement(name = "bearerAuth")
    public AiAgentService.AgentView one(@AuthenticationPrincipal Jwt jwt,
                                        @Parameter(description = "AI 직원 식별자", example = "78")
                                        @PathVariable Long agentId) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        return agents.findOne(agentId, userId);
    }

    @Operation(summary = "AI 직원 수정 — 보낸 필드만 바뀐다",
            description = """
                    `PATCH` 이므로 **본문에 넣은 필드만** 바뀌고 나머지는 그대로다. 빈 본문이나 `{}` 는
                    `400` 이다 — 바꿀 것이 없는 요청은 실수로 본다.

                    보낸 값은 **전부 먼저 검증한 뒤** 적용한다. 절반만 반영된 상태로 남지 않는다.

                    `null` 을 명시하면 지우기다. 다만 `name`·`role`·`systemPrompt` 는 비울 수 없어
                    `null` 을 보내면 그 필드 이름과 함께 `400` 이 된다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정된 AI 직원 전체"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — 바꿀 내용이 없거나 허용값 위반, 또는 비울 수 없는 필드에 `null` 을 보냈다"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY`(게스트) 또는 `BOOTH_FORBIDDEN`(그 부스 편집 권한이 없다)"),
            @ApiResponse(responseCode = "404", description = "`AGENT_NOT_FOUND` — 그런 AI 직원이 없다"),
            @ApiResponse(responseCode = "409", description = "`BOOTH_LEASE_EXPIRED` — 임대가 끝난 부스다")})
    @PatchMapping("/agents/{agentId}")
    @SecurityRequirement(name = "bearerAuth")
    public AiAgentService.AgentView update(@AuthenticationPrincipal Jwt jwt,
                                           @Parameter(description = "AI 직원 식별자", example = "78")
                                           @PathVariable Long agentId,
                                           @RequestBody(required = false)
                                           AiAgentService.AgentCommand command) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        return agents.update(agentId, userId, command);
    }

    @Operation(summary = "AI 직원 삭제 — 참조 중이면 거부한다",
            description = """
                    AI 직원을 지운다. **어딘가에서 쓰고 있으면 지워지지 않는다** (`409 AGENT_DELETE_CONFLICT`) —
                    작업본 배치, 현재 공개된 배치, 그리고 그 직원을 가리키는 데이터가 검사 대상이다.
                    지우려면 먼저 배치에서 연결을 끊고 다시 공개한다.

                    지난 공개 회차에만 남아 있는 참조는 삭제를 막지 않는다 — 방문자가 보는 것은 현재 공개본뿐이다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 완료. 본문 없음"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY`(게스트) 또는 `BOOTH_FORBIDDEN`(그 부스 편집 권한이 없다)"),
            @ApiResponse(responseCode = "404", description = "`AGENT_NOT_FOUND` — 그런 AI 직원이 없다"),
            @ApiResponse(responseCode = "409", description = "`AGENT_DELETE_CONFLICT`(작업본·현재 공개본이 참조 중) 또는 `BOOTH_LEASE_EXPIRED`")})
    @DeleteMapping("/agents/{agentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = "bearerAuth")
    public void delete(@AuthenticationPrincipal Jwt jwt,
                       @Parameter(description = "AI 직원 식별자", example = "78")
                       @PathVariable Long agentId) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        agents.delete(agentId, userId);
    }
}
