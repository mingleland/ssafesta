package com.example.ssafesta.survey;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.expireLease;
import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothLayoutTestSupport.publishLayout;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static com.example.ssafesta.booth.BoothTestSupport.releaseAllSlots;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.AccessTokenService;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.user.AccountDeletionService;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * 설문 편집·조회 (spec 010 FR-001~FR-004·FR-011, contracts/survey-api.md §3·§4·§5).
 *
 * <p>S15P21A604-130 · -190.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SurveyApiIntegrationTest {

    private static final String TWO_QUESTIONS = """
            {"title":"A604 설문","rewardCoin":5,
             "questions":[
               {"type":"SINGLE_CHOICE","prompt":"어떻게 알았나요?","required":true,
                "options":[{"label":"돌아다니다가"},{"label":"추천"}]},
               {"type":"RATING","prompt":"만족도","required":true,"scale":{"min":1,"max":5}}]}""";

    @Autowired private MockMvc mockMvc;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private AccessTokenService accessTokens;
    @Autowired private AccountDeletionService accountDeletion;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void freeSlots() {
        releaseAllSlots(jdbc);
    }

    // ── 저장·조회 왕복 (§3·§4) ──────────────────────────────────────────────

    @Test
    void ownerSavesThenReadsItBack() throws Exception {
        Owner owner = leasedOwner("설문저장");

        mockMvc.perform(save(owner, TWO_QUESTIONS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boothId").value(owner.boothId()))
                .andExpect(jsonPath("$.title").value("A604 설문"))
                .andExpect(jsonPath("$.rewardCoin").value(5))
                .andExpect(jsonPath("$.closed").value(false))
                .andExpect(jsonPath("$.responseCount").value(0))
                .andExpect(jsonPath("$.questions.length()").value(2))
                .andExpect(jsonPath("$.questions[0].type").value("SINGLE_CHOICE"))
                .andExpect(jsonPath("$.questions[0].order").value(0))
                .andExpect(jsonPath("$.questions[0].options.length()").value(2))
                .andExpect(jsonPath("$.questions[0].scale").doesNotExist())
                .andExpect(jsonPath("$.questions[1].type").value("RATING"))
                .andExpect(jsonPath("$.questions[1].scale.min").value(1))
                .andExpect(jsonPath("$.questions[1].scale.max").value(5))
                .andExpect(jsonPath("$.questions[1].options.length()").value(0));

        mockMvc.perform(read(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("A604 설문"))
                .andExpect(jsonPath("$.questions[0].options[0].label").value("돌아다니다가"));
    }

    @Test
    void staffMayEditLikeTheOwner() throws Exception {
        Owner owner = leasedOwner("스태프설문");
        Long staffUserId = createMemberWithWallet(users, wallets, "스태프");
        jdbc.update("INSERT INTO booth_staffs(booth_id, user_id, role) VALUES(?, ?, 'CONTENT_EDITOR')",
                owner.boothId(), staffUserId);

        mockMvc.perform(put("/api/v1/booths/{id}/survey", owner.boothId())
                        .header("Authorization", bearerFor(staffUserId))
                        .contentType(MediaType.APPLICATION_JSON).content(TWO_QUESTIONS))
                .andExpect(status().isOk());
    }

    @Test
    void anotherMembersBoothIsForbidden() throws Exception {
        Owner mine = leasedOwner("내설문");
        Long strangerId = createMemberWithWallet(users, wallets, "남");

        mockMvc.perform(put("/api/v1/booths/{id}/survey", mine.boothId())
                        .header("Authorization", bearerFor(strangerId))
                        .contentType(MediaType.APPLICATION_JSON).content(TWO_QUESTIONS))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BOOTH_EDITOR_FORBIDDEN"));

        mockMvc.perform(get("/api/v1/booths/{id}/survey", mine.boothId())
                        .header("Authorization", bearerFor(strangerId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void guestMayNotEdit() throws Exception {
        Owner owner = leasedOwner("게스트편집");

        mockMvc.perform(put("/api/v1/booths/{id}/survey", owner.boothId())
                        .header("Authorization", guestBearer())
                        .contentType(MediaType.APPLICATION_JSON).content(TWO_QUESTIONS))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_ONLY"));
    }

    @Test
    void unknownBoothIsNotFound() throws Exception {
        Long userId = createMemberWithWallet(users, wallets, "없는부스");

        mockMvc.perform(put("/api/v1/booths/{id}/survey", 999_999L)
                        .header("Authorization", bearerFor(userId))
                        .contentType(MediaType.APPLICATION_JSON).content(TWO_QUESTIONS))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOTH_NOT_FOUND"));
    }

    /** 만료는 쓰기를 막고 읽기는 남긴다 — FR-011 보존. */
    @Test
    void expiredBoothRefusesSaveButStillReads() throws Exception {
        Owner owner = leasedOwner("만료설문");
        mockMvc.perform(save(owner, TWO_QUESTIONS)).andExpect(status().isOk());
        expireLease(jdbc, owner.boothId());

        mockMvc.perform(save(owner, TWO_QUESTIONS))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOTH_LEASE_EXPIRED"));

        mockMvc.perform(read(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("A604 설문"));
    }

    @Test
    void missingSurveyIsNotFoundRatherThanEmpty() throws Exception {
        Owner owner = leasedOwner("설문없음");

        mockMvc.perform(read(owner))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SURVEY_NOT_FOUND"));
    }

    /** 교체가 UNIQUE(survey_id, display_order) 에 걸리지 않는다 — 삭제가 삽입보다 먼저 flush 된다. */
    @Test
    void savingAgainReplacesTheQuestionSetAtTheSameOrders() throws Exception {
        Owner owner = leasedOwner("재저장");
        mockMvc.perform(save(owner, TWO_QUESTIONS)).andExpect(status().isOk());

        mockMvc.perform(save(owner, """
                        {"title":"바뀐 설문",
                         "questions":[{"type":"LONG_TEXT","prompt":"자유 의견","required":false}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("바뀐 설문"))
                .andExpect(jsonPath("$.questions.length()").value(1))
                .andExpect(jsonPath("$.questions[0].type").value("LONG_TEXT"));

        // 이 부스의 설문만 센다 — 같은 클래스의 다른 테스트가 남긴 행이 DB 에 함께 있다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM survey_questions q "
                + "JOIN surveys s ON s.id = q.survey_id WHERE s.booth_id = ?",
                Long.class, owner.boothId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM survey_options o "
                + "JOIN survey_questions q ON q.id = o.question_id "
                + "JOIN surveys s ON s.id = q.survey_id WHERE s.booth_id = ?",
                Long.class, owner.boothId())).isZero();
    }

    /** FE saveDraft 는 {title, questions} 만 보낸다. 키가 없으면 보상이 유지돼야 한다 (T-97). */
    @Test
    void absentOptionalKeysKeepTheirStoredValues() throws Exception {
        Owner owner = leasedOwner("유지");
        mockMvc.perform(save(owner, TWO_QUESTIONS)).andExpect(status().isOk());

        mockMvc.perform(save(owner, """
                        {"title":"제목만 바꿈",
                         "questions":[{"type":"SINGLE_CHOICE","prompt":"어떻게 알았나요?","required":true,
                                       "options":[{"label":"돌아다니다가"},{"label":"추천"}]},
                                      {"type":"RATING","prompt":"만족도","required":true,
                                       "scale":{"min":1,"max":5}}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rewardCoin").value(5));
    }

    @Test
    void explicitNullClearsDescription() throws Exception {
        Owner owner = leasedOwner("설명비움");
        mockMvc.perform(save(owner, """
                        {"title":"t","description":"있음",
                         "questions":[{"type":"SHORT_TEXT","prompt":"q","required":false}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("있음"));

        mockMvc.perform(save(owner, """
                        {"title":"t","description":null,
                         "questions":[{"type":"SHORT_TEXT","prompt":"q","required":false}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").doesNotExist());
    }

    // ── 방문자 조회 (§5) ────────────────────────────────────────────────────

    @Test
    void visitorOpensThePublishedBoothSurvey() throws Exception {
        Owner owner = publishedOwner("방문자");
        mockMvc.perform(save(owner, TWO_QUESTIONS)).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/booths/{id}/survey/run", owner.boothId())
                        .header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.surveyId").isNumber())
                .andExpect(jsonPath("$.closed").value(false))
                .andExpect(jsonPath("$.rewardCoin").value(5))
                .andExpect(jsonPath("$.questions.length()").value(2));
    }

    @Test
    void guestOpensItToo() throws Exception {
        Owner owner = publishedOwner("게스트열기");
        mockMvc.perform(save(owner, TWO_QUESTIONS)).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/booths/{id}/survey/run", owner.boothId())
                        .header("Authorization", guestBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions.length()").value(2));
    }

    @Test
    void closedSurveyStillShowsItsQuestions() throws Exception {
        Owner owner = publishedOwner("마감");
        mockMvc.perform(save(owner, TWO_QUESTIONS)).andExpect(status().isOk());
        jdbc.update("UPDATE surveys SET ends_at = ? WHERE booth_id = ?",
                java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), owner.boothId());

        mockMvc.perform(get("/api/v1/booths/{id}/survey/run", owner.boothId())
                        .header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closed").value(true))
                .andExpect(jsonPath("$.questions.length()").value(2));
    }

    @Test
    void unpublishedBoothHidesItFromVisitors() throws Exception {
        Owner owner = leasedOwner("미게시");
        mockMvc.perform(save(owner, TWO_QUESTIONS)).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/booths/{id}/survey/run", owner.boothId())
                        .header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LAYOUT_NOT_PUBLISHED"));
    }

    @Test
    void expiredBoothIsConflictForVisitors() throws Exception {
        Owner owner = publishedOwner("방문만료");
        mockMvc.perform(save(owner, TWO_QUESTIONS)).andExpect(status().isOk());
        expireLease(jdbc, owner.boothId());

        mockMvc.perform(get("/api/v1/booths/{id}/survey/run", owner.boothId())
                        .header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOTH_LEASE_EXPIRED"));
    }

    @Test
    void visitorGetsNotFoundWhenTheBoothHasNoSurvey() throws Exception {
        Owner owner = publishedOwner("방문없음");

        mockMvc.perform(get("/api/v1/booths/{id}/survey/run", owner.boothId())
                        .header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SURVEY_NOT_FOUND"));
    }

    // ── 검증 (-190) ─────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{1} → 400 {0}")
    @CsvSource(delimiter = '|', value = {
            "title|빈 제목|{\"title\":\" \",\"questions\":[{\"type\":\"SHORT_TEXT\",\"prompt\":\"q\"}]}",
            "questions|문항 0개|{\"title\":\"t\",\"questions\":[]}",
            "questions[0].type|없는 유형|{\"title\":\"t\",\"questions\":[{\"type\":\"BOOLEAN\",\"prompt\":\"q\"}]}",
            "questions[0].prompt|빈 문구|{\"title\":\"t\",\"questions\":[{\"type\":\"SHORT_TEXT\",\"prompt\":\"  \"}]}",
            "questions[0].options|선택지 1개|{\"title\":\"t\",\"questions\":[{\"type\":\"SINGLE_CHOICE\",\"prompt\":\"q\",\"options\":[{\"label\":\"A\"}]}]}",
            "questions[0].options|빈 선택지 라벨|{\"title\":\"t\",\"questions\":[{\"type\":\"MULTIPLE_CHOICE\",\"prompt\":\"q\",\"options\":[{\"label\":\"A\"},{\"label\":\" \"}]}]}",
            "questions[0].options|텍스트 유형에 선택지|{\"title\":\"t\",\"questions\":[{\"type\":\"SHORT_TEXT\",\"prompt\":\"q\",\"options\":[{\"label\":\"A\"},{\"label\":\"B\"}]}]}",
            "questions[0].scale|별점에 범위 없음|{\"title\":\"t\",\"questions\":[{\"type\":\"RATING\",\"prompt\":\"q\"}]}",
            "questions[0].scale|별점 min>=max|{\"title\":\"t\",\"questions\":[{\"type\":\"RATING\",\"prompt\":\"q\",\"scale\":{\"min\":5,\"max\":5}}]}",
            "questions[0].scale|별점 상한 초과|{\"title\":\"t\",\"questions\":[{\"type\":\"RATING\",\"prompt\":\"q\",\"scale\":{\"min\":1,\"max\":11}}]}",
            "questions[0].scale|텍스트 유형에 범위|{\"title\":\"t\",\"questions\":[{\"type\":\"LONG_TEXT\",\"prompt\":\"q\",\"scale\":{\"min\":1,\"max\":5}}]}",
            "rewardCoin|보상 상한 초과|{\"title\":\"t\",\"rewardCoin\":11,\"questions\":[{\"type\":\"SHORT_TEXT\",\"prompt\":\"q\"}]}",
            "rewardCoin|보상 음수|{\"title\":\"t\",\"rewardCoin\":-1,\"questions\":[{\"type\":\"SHORT_TEXT\",\"prompt\":\"q\"}]}",
            "closesAt|과거 마감|{\"title\":\"t\",\"closesAt\":\"2020-01-01T00:00:00Z\",\"questions\":[{\"type\":\"SHORT_TEXT\",\"prompt\":\"q\"}]}"})
    void rejectsBadBodyWithTheOffendingField(String field, String label, String body) throws Exception {
        Owner owner = leasedOwner("검증" + Math.abs(label.hashCode() % 100_000));

        mockMvc.perform(save(owner, body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].rule").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value(field));
    }

    @Test
    void rejectsMoreQuestionsThanTheConfiguredCap() throws Exception {
        Owner owner = leasedOwner("문항상한");
        StringBuilder body = new StringBuilder("{\"title\":\"t\",\"questions\":[");
        for (int i = 0; i < 31; i++) {
            body.append(i == 0 ? "" : ",")
                    .append("{\"type\":\"SHORT_TEXT\",\"prompt\":\"q").append(i).append("\"}");
        }
        body.append("]}");

        mockMvc.perform(save(owner, body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("questions"));
    }

    @Test
    void rejectsMoreOptionsThanTheConfiguredCap() throws Exception {
        Owner owner = leasedOwner("선택지상한");
        StringBuilder options = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            options.append(i == 0 ? "" : ",").append("{\"label\":\"o").append(i).append("\"}");
        }

        mockMvc.perform(save(owner, "{\"title\":\"t\",\"questions\":[{\"type\":\"SINGLE_CHOICE\","
                        + "\"prompt\":\"q\",\"options\":[" + options + "]}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("questions[0].options"));
    }

    // ── 응답이 있는 설문 (C-08) ─────────────────────────────────────────────

    @Test
    void questionStructureLocksOnceSomeoneAnswered() throws Exception {
        Owner owner = leasedOwner("잠금");
        mockMvc.perform(save(owner, TWO_QUESTIONS)).andExpect(status().isOk());
        seedResponse(owner.boothId(), owner.userId());

        mockMvc.perform(save(owner, """
                        {"title":"A604 설문","rewardCoin":5,
                         "questions":[{"type":"LONG_TEXT","prompt":"다른 문항","required":false}]}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SURVEY_LOCKED"));
    }

    /** 전체를 잠그면 마감조차 못 건다 — 구조만 잠근다. */
    @Test
    void metadataStaysEditableAfterResponses() throws Exception {
        Owner owner = leasedOwner("메타수정");
        mockMvc.perform(save(owner, TWO_QUESTIONS)).andExpect(status().isOk());
        seedResponse(owner.boothId(), owner.userId());

        mockMvc.perform(save(owner, """
                        {"title":"제목 고침","rewardCoin":7,
                         "questions":[
                           {"type":"SINGLE_CHOICE","prompt":"어떻게 알았나요?","required":true,
                            "options":[{"label":"돌아다니다가"},{"label":"추천"}]},
                           {"type":"RATING","prompt":"만족도","required":true,"scale":{"min":1,"max":5}}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("제목 고침"))
                .andExpect(jsonPath("$.rewardCoin").value(7))
                .andExpect(jsonPath("$.responseCount").value(1));
    }

    // ── 탈퇴 (R-09) ─────────────────────────────────────────────────────────

    /**
     * 스태프가 만든 설문이 있으면 소유자 탈퇴가 surveys_booth_id_fkey 로 실패했다 —
     * 삭제 SQL 이 설문만 created_by_user_id 로 키를 잡고 있었기 때문이다.
     */
    @Test
    void ownerWithdrawalRemovesASurveyCreatedByStaff() throws Exception {
        Owner owner = leasedOwner("탈퇴");
        Long staffUserId = createMemberWithWallet(users, wallets, "탈퇴스태프");
        jdbc.update("INSERT INTO booth_staffs(booth_id, user_id, role) VALUES(?, ?, 'CONTENT_EDITOR')",
                owner.boothId(), staffUserId);
        mockMvc.perform(put("/api/v1/booths/{id}/survey", owner.boothId())
                        .header("Authorization", bearerFor(staffUserId))
                        .contentType(MediaType.APPLICATION_JSON).content(TWO_QUESTIONS))
                .andExpect(status().isOk());
        // 서비스는 소유자를 쓰지만 삭제 SQL 은 스키마가 허용하는 값에도 옳아야 한다.
        jdbc.update("UPDATE surveys SET created_by_user_id = ? WHERE booth_id = ?",
                staffUserId, owner.boothId());

        accountDeletion.deleteUserGraph(owner.userId());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM surveys WHERE booth_id = ?", Long.class, owner.boothId())).isZero();
    }

    /**
     * 작성자가 탈퇴해도 <b>남의 부스 설문은 지우지 않고</b> 소유자에게 넘긴다.
     *
     * <p>{@code surveys.created_by_user_id} 는 NOT NULL 이고 {@code users(id)} 를 NO ACTION 으로
     * 참조한다. 그러니 작성자 탈퇴는 그 행을 어떻게든 처리해야 하는데, 지우는 쪽을 고르면 부스
     * 소유자의 설문과 남들이 답한 응답이 제3자의 탈퇴로 사라진다. 소유권을 넘기는 쪽이 옳다.
     *
     * <p>지우는 쪽이 틀린 또 하나의 이유: 자식(문항·선택지·응답)은 <b>부스 소유자</b> 기준으로
     * 지워지므로, surveys 만 작성자 기준으로 지우면 {@code survey_questions_survey_id_fkey} 에
     * 걸려 <b>탈퇴 전체가 실패</b>한다.
     *
     * <p>서비스는 항상 소유자를 쓰므로(불변식 I-5) 이 상태는 지금 API 로는 만들어지지 않는다.
     * 그래도 삭제는 스키마가 허용하는 모든 값에 대해 옳아야 한다 — 그 원칙 때문에 삭제 SQL 에
     * {@code created_by_user_id} 조건이 들어가 있고, 그렇다면 그 조건도 옳아야 한다.
     */
    @Test
    void authorWithdrawalHandsTheSurveyToTheBoothOwner() throws Exception {
        Owner owner = leasedOwner("작성자탈퇴");
        Long authorUserId = createMemberWithWallet(users, wallets, "작성자");
        mockMvc.perform(save(owner, TWO_QUESTIONS)).andExpect(status().isOk());
        jdbc.update("UPDATE surveys SET created_by_user_id = ? WHERE booth_id = ?",
                authorUserId, owner.boothId());

        accountDeletion.deleteUserGraph(authorUserId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM surveys WHERE booth_id = ?",
                Long.class, owner.boothId()))
                .as("남의 부스 설문이 작성자 탈퇴로 사라지면 안 됩니다.").isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT created_by_user_id FROM surveys WHERE booth_id = ?", Long.class, owner.boothId()))
                .as("작성자 자리는 부스 소유자가 이어받아야 합니다 — NOT NULL 이고 users 를 참조합니다.")
                .isEqualTo(owner.userId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM survey_questions WHERE survey_id "
                        + "IN (SELECT id FROM surveys WHERE booth_id = ?)", Long.class, owner.boothId()))
                .as("문항도 그대로여야 합니다.").isEqualTo(2L);
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    private RequestBuilder save(Owner owner, String body) {
        return put("/api/v1/booths/{id}/survey", owner.boothId())
                .header("Authorization", bearerFor(owner.userId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private RequestBuilder read(Owner owner) {
        return get("/api/v1/booths/{id}/survey", owner.boothId())
                .header("Authorization", bearerFor(owner.userId()));
    }

    private void seedResponse(Long boothId, Long userId) {
        jdbc.update("INSERT INTO survey_responses(survey_id, respondent_user_id) "
                + "SELECT id, ? FROM surveys WHERE booth_id = ?", userId, boothId);
    }

    private Owner leasedOwner(String prefix) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(userId, prefix + " 부스")).getId();
        grantLease(jdbc, boothId, userId);
        return new Owner(userId, boothId);
    }

    private Owner publishedOwner(String prefix) throws Exception {
        Owner owner = leasedOwner(prefix);
        publishLayout(mockMvc, owner.boothId(), bearerFor(owner.userId()));
        return owner;
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }

    private String guestBearer() {
        return "Bearer " + accessTokens.issueGuestToken().token();
    }

    private record Owner(Long userId, Long boothId) { }
}
