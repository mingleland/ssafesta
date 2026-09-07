package com.example.ssafesta.survey;

import com.example.ssafesta.common.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Booth surveys — editing, and what a visitor sees (spec 010, `contracts/survey-api.md`).
 *
 * <p>Editing is booth-scoped because there is one survey per booth (C-06) and the frontend Builder
 * addresses it that way. Answering and results are survey-scoped, and the id a client needs comes
 * back from {@code GET .../survey/run} — Unity sends only {@code {boothId, objectId}}, never a
 * survey id (S15P21A604-415).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Survey")
public class SurveyController {

    private static final String MEMBER_ONLY = "회원 계정만 설문을 편집할 수 있습니다.";

    private final SurveyService surveys;

    public SurveyController(SurveyService surveys) {
        this.surveys = surveys;
    }

    @Operation(summary = "내 부스 설문 조회 — 편집자용",
            description = """
                    부스의 설문을 문항·선택지까지 그대로 돌려준다. **부스당 설문은 1개**다 (C-06).

                    **임대가 만료돼도 조회된다.** 소유자가 자기 설문을 되읽는 것은 임대 종료 후에도
                    보장되는 동작이고(FR-011), 저장은 그때 막힌다.

                    아직 만들지 않았으면 `404 SURVEY_NOT_FOUND`다 — 오류 상태가 아니라
                    "설문을 만들지 않았다"는 뜻이므로 클라이언트는 빈 Builder 로 시작하면 된다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "설문 전체 — 문항·선택지·별점 범위 포함"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY`(게스트) 또는 `BOOTH_EDITOR_FORBIDDEN`(내 부스가 아니다)"),
            @ApiResponse(responseCode = "404", description = "`BOOTH_NOT_FOUND` 또는 `SURVEY_NOT_FOUND` — 아직 만들지 않았다")})
    @GetMapping("/booths/{boothId}/survey")
    @SecurityRequirement(name = "bearerAuth")
    public SurveyService.SurveyView find(@AuthenticationPrincipal Jwt jwt,
                                         @Parameter(description = "내 부스 식별자", example = "7")
                                         @PathVariable Long boothId) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        return surveys.findForEditor(boothId, userId);
    }

    @Operation(summary = "부스 설문 저장 — 없으면 만들고 있으면 갱신한다",
            description = """
                    설문을 보낸 모양 그대로 만든다. 생성·수정 구분 없이 항상 `200`이다 —
                    부스당 1개이므로 클라이언트가 "저장"만 알면 된다 (C-06).

                    **저장하면 바로 공개된다** (C-07). 별도의 게시 단계가 없으므로 저장본이 곧
                    방문자가 답할 수 있는 설문이다. 마감은 `closesAt` 이 지나면 성립하고,
                    상태를 바꾸는 API 는 없다.

                    **`description`·`rewardCoin`·`closesAt` 은 키를 보내지 않으면 기존 값을 유지한다.**
                    명시적으로 `null` 을 보내면 비운다. `title`·`questions` 는 필수다.

                    문항은 **전체 교체**다. `questionId` 를 받지 않으므로 순서 변경·삭제·추가가
                    한 번의 저장으로 표현된다.

                    **응답이 1건 이상이면 문항 구조가 잠긴다** (C-08). 유형·문구·필수 여부·선택지·별점
                    범위가 저장본과 다르면 `409 SURVEY_LOCKED`이고, 제목·설명·보상·마감만 바꾸는
                    저장은 그대로 성공한다. 답이 달린 문항은 DB 가 삭제를 막기 때문이다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장된 설문 전체"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — 필수 누락·길이 초과·유형별 부속 위반. `errors[0].field` 가 문제 필드다(`questions[2].options` 같은 경로)"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY`(게스트) 또는 `BOOTH_EDITOR_FORBIDDEN`"),
            @ApiResponse(responseCode = "404", description = "`BOOTH_NOT_FOUND`"),
            @ApiResponse(responseCode = "409", description = "`BOOTH_LEASE_EXPIRED`(임대 만료) 또는 `SURVEY_LOCKED`(응답이 있는 설문의 문항 변경)")})
    @PutMapping("/booths/{boothId}/survey")
    @SecurityRequirement(name = "bearerAuth")
    public SurveyService.SurveyView save(@AuthenticationPrincipal Jwt jwt,
                                         @Parameter(description = "내 부스 식별자", example = "7")
                                         @PathVariable Long boothId,
                                         @RequestBody(required = false)
                                         SurveyService.SurveyCommand command) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        return surveys.upsert(boothId, userId, command);
    }

    @Operation(summary = "부스 설문 열기 — 방문자용",
            description = """
                    방문자가 설문 키오스크에서 여는 것. **회원과 게스트 모두 조회한다** (C-05).

                    Unity 는 `{boothId, objectId}` 만 보내고 설문 식별자를 모르므로 부스 기준 경로다.
                    응답의 `surveyId` 가 제출·결과 조회에 쓰는 값이다.

                    방문자 경로는 부스 존재 → 유효 임대 → 게시 여부 순으로 막힌다. 순서가 계약이며
                    프로젝트 패널·부스 조회와 같다.

                    **마감된 설문도 문항을 그대로 돌려준다** — 화면이 "마감된 설문입니다"를 보여주되
                    무엇을 물었는지는 남는다. 제출이 `409 SURVEY_CLOSED` 로 막는다.

                    `rewardCoin` 이 0보다 크면 게스트는 제출할 수 없다(지갑이 없다, 헌법 12조).
                    그 사실을 제출 전에 안내할 수 있도록 값을 함께 싣는다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "`surveyId`·마감 여부·보상·문항 목록. 문항이 0개면 빈 배열이다"),
            @ApiResponse(responseCode = "404", description = "`BOOTH_NOT_FOUND` · `SURVEY_NOT_FOUND` · `LAYOUT_NOT_PUBLISHED`(게시본이 없다)"),
            @ApiResponse(responseCode = "409", description = "`BOOTH_LEASE_EXPIRED` — 임대가 끝난 부스다")})
    @GetMapping("/booths/{boothId}/survey/run")
    @SecurityRequirement(name = "bearerAuth")
    public SurveyService.RunView run(@Parameter(description = "부스 식별자", example = "7")
                                     @PathVariable Long boothId) {
        return surveys.findRun(boothId);
    }
}
