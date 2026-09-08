package com.example.ssafesta.survey;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.expireLease;
import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothLayoutTestSupport.publishLayout;
import static com.example.ssafesta.booth.BoothTestSupport.createMember;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static com.example.ssafesta.booth.BoothTestSupport.releaseAllSlots;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.AccessTokenService;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 설문 결과 집계와 주관식 페이지 (spec 010 FR-006~FR-009, contracts/survey-api.md §7·§8).
 *
 * <p>S15P21A604-132 · -193 · -191.
 *
 * <p><b>응답은 전부 API 로 넣는다.</b> SC-001 이 주장하는 것은 "집계 수치가 <i>실제 응답 데이터</i>와
 * 100% 일치"이므로, 행을 손으로 INSERT 하면 검증하는 대상이 제출 경로가 아니라 내가 상상한 제출
 * 경로가 된다. 응답자는 게스트다 — 토큰마다 주체가 새로 나오므로 회원 20명을 만들 이유가 없고,
 * 보상 없는 설문에는 게스트가 제출할 수 있다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SurveyResultApiIntegrationTest {

    /**
     * 집계 대조용 설문. 필수 2개(Q0 객관식·Q2 별점)와 선택 3개(Q1 복수선택·Q3 장문·Q4 단답)다 —
     * 선택 문항이 있어야 {@code answeredCount < totalResponses} 가 성립한다.
     */
    private static final String AGGREGATE_SURVEY = """
            {"title":"집계 대조","rewardCoin":0,
             "questions":[
               {"type":"SINGLE_CHOICE","prompt":"어떻게 왔나","required":true,
                "options":[{"label":"월드"},{"label":"추천"}]},
               {"type":"MULTIPLE_CHOICE","prompt":"관심","required":false,
                "options":[{"label":"게임"},{"label":"전시"},{"label":"굿즈"}]},
               {"type":"RATING","prompt":"만족도","required":true,"scale":{"min":1,"max":5}},
               {"type":"LONG_TEXT","prompt":"의견","required":false},
               {"type":"SHORT_TEXT","prompt":"한마디","required":false}]}""";

    /** 주관식 페이지용. 텍스트 문항 하나만 필수라 전체 개수가 곧 응답 수다. */
    private static final String TEXT_SURVEY = """
            {"title":"주관식 페이지","rewardCoin":0,
             "questions":[{"type":"LONG_TEXT","prompt":"의견","required":true}]}""";

    private static final int RESPONSES = 20;
    private static final int TEXT_RESPONSES = 55;

    @Autowired private MockMvc mockMvc;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private AccessTokenService accessTokens;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;

    @BeforeEach
    void freeSlots() {
        releaseAllSlots(jdbc);
    }

    // ── 집계 (계약 §7) ──────────────────────────────────────────────────────

    /**
     * 20응답을 넣고 손으로 센 값과 맞춰 본다 (-132 완료조건, SC-001).
     *
     * <p>기대값은 아래 상수들이고, 그 근거는 {@link #answerOf} 가 만드는 분포다.
     * <ul>
     *   <li>Q0 객관식 필수 — 앞 11명 "월드", 뒤 9명 "추천". 전원 응답 20</li>
     *   <li>Q1 복수선택 선택 — 앞 15명만 응답. 게임 10 · 전시 15 · 굿즈 5, <b>합 30 &gt; 15</b></li>
     *   <li>Q2 별점 필수 — 2점 1명 · 3점 2명 · 4점 9명 · 5점 8명. <b>1점은 0명</b>, 평균 84/20 = 4.2</li>
     *   <li>Q3 장문 선택 — 앞 12명, Q4 단답 선택 — 앞 7명</li>
     * </ul>
     */
    @Test
    void theAggregateOfTwentyResponsesMatchesTheHandCount() throws Exception {
        Fixture fixture = aggregateFixture("수기대조");
        submitTwenty(fixture);

        mockMvc.perform(results(fixture)).andExpect(status().isOk())
                .andExpect(jsonPath("$.surveyId").value(fixture.surveyId))
                .andExpect(jsonPath("$.totalResponses").value(20))
                .andExpect(jsonPath("$.firstRespondedAt").isNotEmpty())
                .andExpect(jsonPath("$.lastRespondedAt").isNotEmpty())
                .andExpect(jsonPath("$.perQuestion.length()").value(5))
                // 문항 문구는 유형과 무관하게 전부 실린다 (S15P21A604-546) — 화면이 번호로 부르지 않는다
                .andExpect(jsonPath("$.perQuestion[0].prompt").value("어떻게 왔나"))
                .andExpect(jsonPath("$.perQuestion[1].prompt").value("관심"))
                .andExpect(jsonPath("$.perQuestion[2].prompt").value("만족도"))
                .andExpect(jsonPath("$.perQuestion[3].prompt").value("의견"))
                .andExpect(jsonPath("$.perQuestion[4].prompt").value("한마디"))
                // Q0 객관식 — 11 / 9
                .andExpect(jsonPath("$.perQuestion[0].type").value("SINGLE_CHOICE"))
                .andExpect(jsonPath("$.perQuestion[0].answeredCount").value(20))
                .andExpect(jsonPath("$.perQuestion[0].counts[0].label").value("월드"))
                .andExpect(jsonPath("$.perQuestion[0].counts[0].count").value(11))
                .andExpect(jsonPath("$.perQuestion[0].counts[1].label").value("추천"))
                .andExpect(jsonPath("$.perQuestion[0].counts[1].count").value(9))
                .andExpect(jsonPath("$.perQuestion[0].average").doesNotExist())
                .andExpect(jsonPath("$.perQuestion[0].distribution").isEmpty())
                // Q1 복수선택 — 15명이 30표
                .andExpect(jsonPath("$.perQuestion[1].answeredCount").value(15))
                .andExpect(jsonPath("$.perQuestion[1].counts[0].count").value(10))
                .andExpect(jsonPath("$.perQuestion[1].counts[1].count").value(15))
                .andExpect(jsonPath("$.perQuestion[1].counts[2].count").value(5))
                // Q2 별점 — 평균과 분포
                .andExpect(jsonPath("$.perQuestion[2].answeredCount").value(20))
                .andExpect(jsonPath("$.perQuestion[2].average").value(4.2))
                .andExpect(jsonPath("$.perQuestion[2].counts").isEmpty())
                .andExpect(jsonPath("$.perQuestion[2].distribution.length()").value(5))
                .andExpect(jsonPath("$.perQuestion[2].distribution[0].value").value(1))
                .andExpect(jsonPath("$.perQuestion[2].distribution[0].count").value(0))
                .andExpect(jsonPath("$.perQuestion[2].distribution[1].count").value(1))
                .andExpect(jsonPath("$.perQuestion[2].distribution[2].count").value(2))
                .andExpect(jsonPath("$.perQuestion[2].distribution[3].count").value(9))
                .andExpect(jsonPath("$.perQuestion[2].distribution[4].count").value(8))
                // Q3·Q4 텍스트 — answeredCount 만 채워 들어간다
                .andExpect(jsonPath("$.perQuestion[3].type").value("LONG_TEXT"))
                .andExpect(jsonPath("$.perQuestion[3].answeredCount").value(12))
                .andExpect(jsonPath("$.perQuestion[3].counts").isEmpty())
                .andExpect(jsonPath("$.perQuestion[3].average").doesNotExist())
                .andExpect(jsonPath("$.perQuestion[4].answeredCount").value(7))
                // 주관식 첫 페이지 — 장문 12 + 단답 7
                .andExpect(jsonPath("$.textAnswers.totalElements").value(19))
                .andExpect(jsonPath("$.textAnswers.page").value(0))
                .andExpect(jsonPath("$.textAnswers.size").value(20))
                .andExpect(jsonPath("$.textAnswers.totalPages").value(1))
                .andExpect(jsonPath("$.textAnswers.content.length()").value(19));
    }

    /**
     * 응답 0건에서도 모든 문항이 실리고 나눗셈이 없다 (FR-012 · SC-002).
     *
     * <p>문항 목록이 아니라 집계 행을 기준으로 조립하면 이 응답은 {@code perQuestion: []} 이 되고,
     * 화면은 "문항이 없는 설문"과 "아직 아무도 답하지 않은 설문"을 구별할 수 없다.
     */
    @Test
    void aSurveyWithNoResponsesReportsEveryQuestionAtZero() throws Exception {
        Fixture fixture = aggregateFixture("응답없음");

        mockMvc.perform(results(fixture)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalResponses").value(0))
                .andExpect(jsonPath("$.firstRespondedAt").doesNotExist())
                .andExpect(jsonPath("$.lastRespondedAt").doesNotExist())
                .andExpect(jsonPath("$.perQuestion.length()").value(5))
                .andExpect(jsonPath("$.perQuestion[0].prompt").value("어떻게 왔나"))
                .andExpect(jsonPath("$.perQuestion[0].answeredCount").value(0))
                // 아무도 고르지 않은 선택지도 0 으로 실린다 — 없는 선택지와 구별돼야 한다
                .andExpect(jsonPath("$.perQuestion[0].counts.length()").value(2))
                .andExpect(jsonPath("$.perQuestion[0].counts[0].count").value(0))
                .andExpect(jsonPath("$.perQuestion[0].counts[1].count").value(0))
                .andExpect(jsonPath("$.perQuestion[1].counts.length()").value(3))
                // 평균은 null 이다. 0.0 은 실제로 준 점수처럼 그려진다
                .andExpect(jsonPath("$.perQuestion[2].average").doesNotExist())
                .andExpect(jsonPath("$.perQuestion[2].distribution.length()").value(5))
                .andExpect(jsonPath("$.perQuestion[2].distribution[4].count").value(0))
                .andExpect(jsonPath("$.textAnswers.content").isEmpty())
                .andExpect(jsonPath("$.textAnswers.totalElements").value(0));
    }

    /** 응답 본문 어디에도 응답자를 가리키는 것이 없다 (FR-009 · SC-003). */
    @Test
    void theResultsBodyNamesNoRespondent() throws Exception {
        Fixture fixture = aggregateFixture("무기명");
        submitTwenty(fixture);

        String body = mockMvc.perform(results(fixture)).andReturn()
                .getResponse().getContentAsString();

        assertThat(body.toLowerCase())
                .as("집계 응답에 응답자 식별 필드가 실려서는 안 됩니다 (SC-003).")
                .doesNotContain("respondent")
                .doesNotContain("userid")
                .doesNotContain("nickname")
                .doesNotContain("guest");
        // responseId 는 남는다 — 같은 사람의 답을 묶는 열쇠이고 사람의 이름이 아니다
        assertThat(body).contains("responseId");
    }

    // ── 주관식 페이지 (계약 §8) ─────────────────────────────────────────────

    /**
     * 55건을 20씩 세 페이지로 훑어 중복·누락이 0인지 (-193).
     *
     * <p>세 페이지의 {@code responseId} 를 모두 모아 개수와 원소를 본다. 정렬이 흔들리면 같은 답이
     * 두 페이지에 나오거나 어느 페이지에도 안 나오고, 둘 다 이 단정에서 걸린다.
     */
    @Test
    void fiftyFiveTextAnswersPageWithoutDuplicateOrGap() throws Exception {
        Fixture fixture = textFixture("페이지순회");
        List<Long> submitted = submitText(fixture, TEXT_RESPONSES, 0);

        Set<Long> seen = new LinkedHashSet<>();
        int[] sizes = new int[3];
        for (int page = 0; page < 3; page++) {
            JsonNode body = readJson(textAnswers(fixture, "?page=" + page + "&size=20"));
            assertThat(body.get("totalElements").asLong()).isEqualTo(55);
            assertThat(body.get("totalPages").asInt()).isEqualTo(3);
            assertThat(body.get("page").asInt()).isEqualTo(page);
            sizes[page] = body.get("content").size();
            for (JsonNode item : body.get("content")) {
                assertThat(seen.add(item.get("responseId").asLong()))
                        .as("page %d 에 이미 본 답이 다시 나왔습니다.", page).isTrue();
            }
        }

        assertThat(sizes).containsExactly(20, 20, 15);
        assertThat(seen).as("55건이 빠짐없이 나와야 합니다.")
                .containsExactlyElementsOf(submitted);
    }

    /**
     * 페이지를 넘기는 중에 제출이 들어와도 이미 본 페이지가 밀리지 않는다 (C-09).
     *
     * <p>{@code id ASC} 고정이 하는 일이 이것이다 — 새 답은 항상 뒤에 붙는다. 최신순이면 첫 페이지가
     * 한 칸 밀려 20번째 답이 두 번째 페이지 머리로 내려가고, 그 사이 페이지를 넘긴 사람은 그것을 두 번
     * 읽는다.
     */
    @Test
    void aSubmissionDuringPagingDoesNotShiftPagesAlreadyRead() throws Exception {
        Fixture fixture = textFixture("순회중제출");
        List<Long> before = submitText(fixture, 25, 0);

        JsonNode first = readJson(textAnswers(fixture, "?page=0&size=20"));
        List<Long> firstPage = idsOf(first);

        submitText(fixture, 1, 100);

        JsonNode reread = readJson(textAnswers(fixture, "?page=0&size=20"));
        assertThat(idsOf(reread)).as("첫 페이지는 그대로여야 합니다.").isEqualTo(firstPage);

        JsonNode second = readJson(textAnswers(fixture, "?page=1&size=20"));
        assertThat(second.get("totalElements").asLong()).isEqualTo(26);
        assertThat(idsOf(second)).as("두 번째 페이지는 남은 5건 + 새 1건입니다.").hasSize(6)
                .doesNotContainAnyElementsOf(firstPage)
                .startsWith(before.get(20), before.get(21));
    }

    /** {@code questionId} 를 주면 그 문항 답만 나온다. */
    @Test
    void theQuestionIdFilterNarrowsToOneQuestion() throws Exception {
        Fixture fixture = aggregateFixture("문항필터");
        submitTwenty(fixture);

        assertThat(readJson(textAnswers(fixture, "?size=100")).get("totalElements").asLong())
                .as("필터가 없으면 텍스트 3유형 전체입니다.").isEqualTo(19);

        JsonNode longText = readJson(textAnswers(fixture,
                "?questionId=" + fixture.question(3) + "&size=100"));
        assertThat(longText.get("totalElements").asLong()).isEqualTo(12);
        for (JsonNode item : longText.get("content")) {
            assertThat(item.get("questionId").asLong()).isEqualTo(fixture.question(3));
            assertThat(item.get("text").asString()).startsWith("의견");
        }

        assertThat(readJson(textAnswers(fixture,
                "?questionId=" + fixture.question(4) + "&size=100"))
                .get("totalElements").asLong()).isEqualTo(7);
    }

    /** 별점 문항으로 걸러도 400 이 아니다 — 텍스트 답이 없으니 빈 페이지가 맞는 답이다. */
    @Test
    void filteringByANonTextQuestionReturnsAnEmptyPage() throws Exception {
        Fixture fixture = aggregateFixture("별점필터");
        submitTwenty(fixture);

        mockMvc.perform(textAnswers(fixture, "?questionId=" + fixture.question(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    // ── 검증·권한 (계약 §8, SC-003·SC-004) ──────────────────────────────────

    @ParameterizedTest(name = "[{index}] {0} → 400 {1}")
    @CsvSource({
            "?page=-1,page",
            "?size=0,size",
            "?size=101,size",
    })
    void pageAndSizeOutOfRangeAreRejected(String query, String field) throws Exception {
        Fixture fixture = textFixture("페이지검증" + field + query.length());

        mockMvc.perform(textAnswers(fixture, query))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.errors[0].rule").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value(field));
    }

    /**
     * 아무도 안 볼 만큼 큰 page 도 500 이 아니라 빈 페이지다.
     *
     * <p>{@code page * size} 를 int 로 곱하면 넘쳐서 음수가 되고, PostgreSQL 은 음수 OFFSET 을
     * 거부한다 — 사용자에게는 500 이다. 검증이 page 상한을 두지 않으므로 곱셈 쪽이 넘치지 않아야
     * 한다.
     */
    @Test
    void aHugePageNumberIsAnEmptyPageNotAnError() throws Exception {
        Fixture fixture = textFixture("큰페이지");
        submitText(fixture, 1, 0);

        mockMvc.perform(textAnswers(fixture, "?page=21474837&size=100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    /** 남의 설문 문항으로 걸러도 빈 페이지가 아니라 400 이다 — 조용히 비면 "아무도 안 썼다"로 읽힌다. */
    @Test
    void aQuestionFromAnotherSurveyIsRejected() throws Exception {
        Fixture mine = textFixture("남의문항내것");
        Fixture other = aggregateFixture("남의문항남것");

        mockMvc.perform(textAnswers(mine, "?questionId=" + other.question(3)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("questionId"));
    }

    @Test
    void anotherMembersSurveyResultsAreForbidden() throws Exception {
        Fixture fixture = aggregateFixture("남의결과");
        String stranger = bearerFor(createMember(users, "결과침입자"));

        mockMvc.perform(get("/api/v1/surveys/{id}/results", fixture.surveyId)
                        .header("Authorization", stranger))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BOOTH_EDITOR_FORBIDDEN"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        mockMvc.perform(get("/api/v1/surveys/{id}/text-answers", fixture.surveyId)
                        .header("Authorization", stranger))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BOOTH_EDITOR_FORBIDDEN"));
    }

    @Test
    void aGuestCannotReadResults() throws Exception {
        Fixture fixture = aggregateFixture("게스트결과");
        String guest = "Bearer " + accessTokens.issueGuestToken().token();

        mockMvc.perform(get("/api/v1/surveys/{id}/results", fixture.surveyId)
                        .header("Authorization", guest))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_ONLY"));

        mockMvc.perform(get("/api/v1/surveys/{id}/text-answers", fixture.surveyId)
                        .header("Authorization", guest))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_ONLY"));
    }

    /** 임대가 끝나도 결과는 읽힌다 (FR-011) — 축제가 끝난 뒤에 보는 것이 이 화면의 주 용도다. */
    @Test
    void resultsSurviveAnExpiredLease() throws Exception {
        Fixture fixture = aggregateFixture("임대만료결과");
        submitTwenty(fixture);
        expireLease(jdbc, fixture.boothId);

        mockMvc.perform(results(fixture)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalResponses").value(20));
        mockMvc.perform(textAnswers(fixture, "")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(19));
    }

    @Test
    void anUnknownSurveyIsNotFound() throws Exception {
        String bearer = bearerFor(createMember(users, "없는설문결과"));

        mockMvc.perform(get("/api/v1/surveys/{id}/results", 9_999_999L)
                        .header("Authorization", bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SURVEY_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    // ── 제출 ────────────────────────────────────────────────────────────────

    /** 20응답의 분포를 만든다. 기대값의 근거는 이 메서드 하나이고 클래스 javadoc 에 적혀 있다. */
    private void submitTwenty(Fixture fixture) throws Exception {
        for (int index = 0; index < RESPONSES; index++) {
            mockMvc.perform(post("/api/v1/surveys/{id}/responses", fixture.surveyId)
                            .header("Authorization", guestBearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(answerOf(fixture, index)))
                    .andExpect(status().isCreated());
        }
    }

    private String answerOf(Fixture fixture, int index) {
        List<String> answers = new ArrayList<>();
        answers.add("{\"questionId\":%d,\"selectedOptionIds\":[%d]}"
                .formatted(fixture.question(0), fixture.option(0, index < 11 ? 0 : 1)));
        if (index < 15) {
            String picks = switch (index % 3) {
                case 0 -> "%d,%d".formatted(fixture.option(1, 0), fixture.option(1, 1));
                case 1 -> String.valueOf(fixture.option(1, 1));
                default -> "%d,%d,%d".formatted(fixture.option(1, 0), fixture.option(1, 1),
                        fixture.option(1, 2));
            };
            answers.add("{\"questionId\":%d,\"selectedOptionIds\":[%s]}"
                    .formatted(fixture.question(1), picks));
        }
        int rating = index == 0 ? 2 : index <= 2 ? 3 : index <= 11 ? 4 : 5;
        answers.add("{\"questionId\":%d,\"rating\":%d}".formatted(fixture.question(2), rating));
        if (index < 12) {
            answers.add("{\"questionId\":%d,\"text\":\"의견 %d\"}"
                    .formatted(fixture.question(3), index));
        }
        if (index < 7) {
            answers.add("{\"questionId\":%d,\"text\":\"한마디 %d\"}"
                    .formatted(fixture.question(4), index));
        }
        return "{\"answers\":[" + String.join(",", answers) + "]}";
    }

    /** 텍스트 설문에 {@code count} 건을 넣고 만들어진 {@code responseId} 를 제출 순서대로 준다. */
    private List<Long> submitText(Fixture fixture, int count, int offset) throws Exception {
        List<Long> ids = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String body = mockMvc.perform(post("/api/v1/surveys/{id}/responses", fixture.surveyId)
                            .header("Authorization", guestBearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"answers\":[{\"questionId\":%d,\"text\":\"의견 %d\"}]}"
                                    .formatted(fixture.question(0), offset + index)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            ids.add(jsonMapper.readTree(body).get("responseId").asLong());
        }
        return ids;
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    private Fixture aggregateFixture(String prefix) throws Exception {
        return fixture(prefix, AGGREGATE_SURVEY);
    }

    private Fixture textFixture(String prefix) throws Exception {
        return fixture(prefix, TEXT_SURVEY);
    }

    private Fixture fixture(String prefix, String body) throws Exception {
        Long ownerId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(ownerId, prefix + " 부스")).getId();
        grantLease(jdbc, boothId, ownerId);
        String bearer = bearerFor(ownerId);
        publishLayout(mockMvc, boothId, bearer);
        mockMvc.perform(put("/api/v1/booths/{id}/survey", boothId)
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        JsonNode view = readJson(get("/api/v1/booths/{id}/survey", boothId)
                .header("Authorization", bearer));
        List<Long> questionIds = new ArrayList<>();
        List<List<Long>> optionIds = new ArrayList<>();
        for (JsonNode question : view.get("questions")) {
            questionIds.add(question.get("questionId").asLong());
            List<Long> perQuestion = new ArrayList<>();
            for (JsonNode option : question.get("options")) {
                perQuestion.add(option.get("optionId").asLong());
            }
            optionIds.add(perQuestion);
        }
        return new Fixture(view.get("surveyId").asLong(), boothId, bearer, questionIds, optionIds);
    }

    private org.springframework.test.web.servlet.RequestBuilder results(Fixture fixture) {
        return get("/api/v1/surveys/{id}/results", fixture.surveyId)
                .header("Authorization", fixture.bearer);
    }

    private org.springframework.test.web.servlet.RequestBuilder textAnswers(Fixture fixture,
                                                                            String query) {
        return get("/api/v1/surveys/" + fixture.surveyId + "/text-answers" + query)
                .header("Authorization", fixture.bearer);
    }

    private JsonNode readJson(org.springframework.test.web.servlet.RequestBuilder request)
            throws Exception {
        return jsonMapper.readTree(mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private List<Long> idsOf(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : page.get("content")) {
            ids.add(item.get("responseId").asLong());
        }
        return ids;
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }

    private String guestBearer() {
        return "Bearer " + accessTokens.issueGuestToken().token();
    }

    private record Fixture(Long surveyId, Long boothId, String bearer, List<Long> questionIds,
                           List<List<Long>> optionIds) {

        long question(int index) {
            return questionIds.get(index);
        }

        long option(int question, int option) {
            return optionIds.get(question).get(option);
        }
    }
}
