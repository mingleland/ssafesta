package com.example.ssafesta.survey;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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
    private static final String MEMBER_ROLE = "MEMBER";
    private static final String GUEST_ROLE = "GUEST";

    private final SurveyService surveys;
    private final SurveyResponseService submissions;

    public SurveyController(SurveyService surveys, SurveyResponseService submissions) {
        this.surveys = surveys;
        this.submissions = submissions;
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

    @Operation(summary = "설문 응답 제출 — 1인 1응답",
            description = """
                    방문자가 답을 제출한다. **회원과 게스트 모두 제출할 수 있다** (C-05).

                    **1인 1응답**이다. 회원은 계정 기준, 게스트는 접속 토큰 주체 기준이며 재제출은
                    `409 SURVEY_ALREADY_RESPONDED`다. 게스트 토큰은 발급마다 주체가 새로 나오므로
                    게스트의 중복 방지는 그 세션 안에서만 성립한다 — 계정 없는 사람을 그 이상
                    식별할 방법이 없다.

                    **보상이 있는 설문(`rewardCoin > 0`)은 게스트가 제출할 수 없다** —
                    `403 MEMBER_ONLY`. 게스트에게는 지갑이 없어 지급이 불가능하고(헌법 12조),
                    답을 받은 뒤 빈손으로 돌려보내지 않기 위해 시작 전에 막는다. 방문자 조회
                    응답의 `rewardCoin`으로 화면이 미리 안내할 수 있다.

                    보상은 응답 저장과 **같은 트랜잭션**에서 원장에 기록된다 (헌법 20조).
                    멱등 기준이 `설문 + 회원`이므로 어떤 경로로 두 번 들어와도 지급은 한 번이다.

                    답은 문항별로 하나씩 싣고 **유형에 맞는 키 하나만** 채운다 —
                    선택형은 `selectedOptionIds`, 별점은 `rating`, 텍스트 3유형은 `text`다.
                    다른 키가 실리면 `400`이다. 선택 문항을 답하지 않으려면 배열에서 빼거나
                    빈 값으로 보낸다(빈 배열·공백 문자열은 "답하지 않음"이다).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "`responseId`와 실제 지급된 `rewardedCoin`. 보상이 없으면 `0`이며 키는 항상 있다"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — 이 설문의 문항이 아니거나, 유형에 맞지 않는 키, 남의 선택지, 별점 범위 밖, 길이 초과, 필수 문항 미응답. `errors[0].field`가 문제 자리다"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY` — 보상이 있는 설문에 게스트가 제출했다"),
            @ApiResponse(responseCode = "404", description = "`SURVEY_NOT_FOUND` · `BOOTH_NOT_FOUND` · `LAYOUT_NOT_PUBLISHED`"),
            @ApiResponse(responseCode = "409", description = "`SURVEY_CLOSED`(마감) · `SURVEY_ALREADY_RESPONDED`(재제출) · `BOOTH_LEASE_EXPIRED`")})
    @PostMapping("/surveys/{surveyId}/responses")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    public SurveyResponseService.SubmitResult submit(@AuthenticationPrincipal Jwt jwt,
                                                     @Parameter(description = "설문 식별자 — 방문자 조회 응답의 `surveyId`", example = "12")
                                                     @PathVariable Long surveyId,
                                                     @RequestBody(required = false)
                                                     SurveyResponseService.SubmitCommand command) {
        return submissions.submit(surveyId, respondentOf(jwt), command);
    }

    /**
     * Who is answering — read from the token, never from the request (헌법 16조).
     *
     * <p>Same shape as {@code WorldSessionService.identityOf}: a member becomes their id, a guest
     * becomes their token subject, and a token carrying neither role is one this server did not
     * issue.
     */
    private SurveyResponseService.Respondent respondentOf(Jwt jwt) {
        if (jwt == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        String role = jwt.getClaimAsString("role");
        if (MEMBER_ROLE.equals(role)) {
            return SurveyResponseService.Respondent.member(MemberPrincipal.requireMemberId(jwt));
        }
        if (GUEST_ROLE.equals(role)) {
            return SurveyResponseService.Respondent.guest(jwt.getSubject());
        }
        throw new ApiException(ErrorCode.UNAUTHORIZED);
    }
}
