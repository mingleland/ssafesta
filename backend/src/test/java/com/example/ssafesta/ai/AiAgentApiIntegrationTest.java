package com.example.ssafesta.ai;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.expireLease;
import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static com.example.ssafesta.booth.BoothTestSupport.releaseAllSlots;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.AccessTokenService;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.booth.BoothStaff;
import com.example.ssafesta.booth.BoothStaffRepository;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI 직원 등록·조회·수정·삭제 (spec 007 US1 · FR-001~FR-003 · C-12·C-13·C-14·C-15).
 *
 * <p>T022 — 구현보다 먼저 쓰고 실패를 확인한 뒤 구현한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AiAgentApiIntegrationTest {

    private static final String MINIMAL = """
            {"name": "도슨트", "role": "PROJECT_DOCENT", "systemPrompt": "문서를 근거로 답한다."}""";

    @Autowired private MockMvc mockMvc;
    @Autowired private BoothRepository booths;
    @Autowired private BoothStaffRepository staffs;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private AccessTokenService accessTokens;
    @Autowired private AiAgentRepository agentRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;

    @BeforeEach
    void freeSlots() {
        releaseAllSlots(jdbc);
    }

    // ── 등록 ────────────────────────────────────────────────────────────────

    /**
     * 안 보낸 설정은 <b>서버가 기본값을 채운다</b> (C-12) — {@code null} 로 두지 않는다.
     *
     * <p>{@code forbiddenTopics} 만 예외로 빈 배열이다. 조건부로 키가 사라지면 클라이언트가
     * {@code undefined} 와 {@code []} 를 둘 다 다뤄야 한다.
     */
    @Test
    void registeringWithTheMinimumFillsTheDefaults() throws Exception {
        Owner owner = leasedOwner("등록");

        mockMvc.perform(create(owner, MINIMAL))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.boothId").value(owner.boothId()))
                .andExpect(jsonPath("$.name").value("도슨트"))
                .andExpect(jsonPath("$.role").value("PROJECT_DOCENT"))
                .andExpect(jsonPath("$.tone").value("FRIENDLY"))
                .andExpect(jsonPath("$.responseLength").value("MEDIUM"))
                .andExpect(jsonPath("$.servicePrice").value(0))
                .andExpect(jsonPath("$.handoffEnabled").value(false))
                .andExpect(jsonPath("$.forbiddenTopics").isArray())
                .andExpect(jsonPath("$.forbiddenTopics.length()").value(0));
    }

    /** 부스당 1명 (C-13). 덮어쓰지 않고 거절한다 — 수정은 PATCH 다. */
    @Test
    void aSecondAgentOnTheSameBoothIsRefused() throws Exception {
        Owner owner = leasedOwner("중복");
        mockMvc.perform(create(owner, MINIMAL)).andExpect(status().isCreated());

        mockMvc.perform(create(owner, MINIMAL))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AGENT_LIMIT_EXCEEDED"));
    }

    /** 아직 직원이 없는 부스는 오류가 아니라 빈 배열이다. */
    @Test
    void aBoothWithoutAnAgentAnswersWithAnEmptyList() throws Exception {
        Owner owner = leasedOwner("빈목록");

        mockMvc.perform(list(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agents").isArray())
                .andExpect(jsonPath("$.agents.length()").value(0));
    }

    // ── 화이트리스트 (C-12) ─────────────────────────────────────────────────

    /**
     * 세 필드는 자유 문자열이 아니다. <b>어느 칸이 왜 틀렸는지</b>가 전달돼야 한다.
     *
     * <p>enum 으로 받으면 역직렬화가 먼저 터져 필드별 문장을 못 준다 — 그래서 String + Set 검증이다
     * (T-24).
     */
    @ParameterizedTest
    @CsvSource({"role,MANAGER", "tone,ANGRY", "responseLength,HUGE"})
    void aValueOutsideTheWhitelistIsRefusedWithItsOwnField(String field, String bad) throws Exception {
        Owner owner = leasedOwner("허용" + field.charAt(0));

        mockMvc.perform(create(owner, """
                        {"name": "x", "role": "PROJECT_DOCENT", "systemPrompt": "p", "%s": "%s"}"""
                        .formatted(field, bad)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].rule").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value(field));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "name|{\"role\":\"PROJECT_DOCENT\",\"systemPrompt\":\"p\"}",
        "name|{\"name\":\"   \",\"role\":\"PROJECT_DOCENT\",\"systemPrompt\":\"p\"}",
        "systemPrompt|{\"name\":\"x\",\"role\":\"PROJECT_DOCENT\",\"systemPrompt\":\"  \"}",
        "servicePrice|{\"name\":\"x\",\"role\":\"PROJECT_DOCENT\",\"systemPrompt\":\"p\",\"servicePrice\":-1}",
        "forbiddenTopics|{\"name\":\"x\",\"role\":\"PROJECT_DOCENT\",\"systemPrompt\":\"p\",\"forbiddenTopics\":[\"\"]}",
    })
    void anUnusableFieldIsRefusedWithThatField(String field, String body) throws Exception {
        Owner owner = leasedOwner("필드" + field.charAt(0) + body.length() % 10);

        mockMvc.perform(create(owner, body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value(field));
    }

    // ── 왕복 (생성→수정→삭제→재등록) ───────────────────────────────────────

    @Test
    void theOwnerCreatesEditsDeletesAndCreatesAgain() throws Exception {
        Owner owner = leasedOwner("왕복");
        long agentId = createAgent(owner, """
                {"name": "도슨트", "role": "PROJECT_DOCENT", "systemPrompt": "원본 지시문",
                 "tone": "PROFESSIONAL", "servicePrice": 20,
                 "forbiddenTopics": ["PERSONAL_INFORMATION"]}""");

        // 키 누락은 유지 — tone 을 안 보냈다고 기본값으로 되돌아가지 않는다
        mockMvc.perform(edit(owner, agentId, """
                        {"name": "새 이름"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("새 이름"))
                .andExpect(jsonPath("$.tone").value("PROFESSIONAL"))
                .andExpect(jsonPath("$.servicePrice").value(20))
                .andExpect(jsonPath("$.systemPrompt").value("원본 지시문"))
                .andExpect(jsonPath("$.forbiddenTopics[0]").value("PERSONAL_INFORMATION"));

        mockMvc.perform(remove(owner, agentId)).andExpect(status().isNoContent());
        mockMvc.perform(edit(owner, agentId, """
                        {"name": "지워진 것"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AGENT_NOT_FOUND"));

        // 재등록된다 — 하드 삭제이므로 유니크 인덱스가 막지 않는다
        mockMvc.perform(create(owner, MINIMAL)).andExpect(status().isCreated());
    }

    /** {@code forbiddenTopics} 만 비울 수 있다. NOT NULL 컬럼에 명시적 null 은 삭제가 아니라 오류다. */
    @Test
    void patchDistinguishesClearableFromNotNullFields() throws Exception {
        Owner owner = leasedOwner("세갈래");
        long agentId = createAgent(owner, """
                {"name": "x", "role": "PROJECT_DOCENT", "systemPrompt": "p",
                 "forbiddenTopics": ["A"]}""");

        mockMvc.perform(edit(owner, agentId, """
                        {"forbiddenTopics": null}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.forbiddenTopics").isArray())
                .andExpect(jsonPath("$.forbiddenTopics.length()").value(0));

        mockMvc.perform(edit(owner, agentId, "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /**
     * NOT NULL 필드에 명시적 {@code null} 은 <b>전부</b> 400 이고, 오류가 그 필드를 지목한다.
     *
     * <p>한 필드만 보면 검증을 안 거치는 필드가 있어도 초록이다 — {@code handoffEnabled} 가
     * 실제로 그랬다. 유일하게 {@code validated*} 를 안 타서 {@code null} 이 조용히 {@code false}
     * 로 저장됐고, 켜 두었던 상담원 연결이 PATCH 한 번에 꺼졌다. 필드를 늘리면 여기에도 넣는다.
     */
    @ParameterizedTest
    @CsvSource({
            "name, \"이름\"",
            "role, \"PROJECT_DOCENT\"",
            "tone, \"FRIENDLY\"",
            "systemPrompt, \"프롬프트\"",
            "responseLength, \"MEDIUM\"",
            "servicePrice, 100",
            "handoffEnabled, true",
    })
    void anExplicitNullOnANotNullFieldNamesThatField(String field, String liveValue)
            throws Exception {
        Owner owner = leasedOwner("널" + field.charAt(0));
        long agentId = createAgent(owner, MINIMAL);
        // 먼저 값을 세워 둔다 — 기본값과 같으면 "조용히 덮어썼다"가 "안 바뀌었다"와 구분되지 않는다.
        mockMvc.perform(edit(owner, agentId, "{\"%s\": %s}".formatted(field, liveValue)))
                .andExpect(status().isOk());
        String before = updatedAtOf(agentId);

        mockMvc.perform(edit(owner, agentId, "{\"%s\": null}".formatted(field)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value(field));

        mockMvc.perform(single(owner, agentId)).andExpect(status().isOk())
                .andExpect(jsonPath("$." + field).value(jsonValue(liveValue)));
        assertEquals(before, updatedAtOf(agentId), "거절된 PATCH 가 updated_at 을 흔들었다");
    }

    /** 등록에서도 같다 — 명시적 {@code null} 은 기본값 요청이 아니다. */
    @Test
    void anExplicitNullOnRegistrationIsRefusedRatherThanDefaulted() throws Exception {
        Owner owner = leasedOwner("등록널");

        mockMvc.perform(create(owner, """
                        {"name": "도슨트", "role": "PROJECT_DOCENT", "systemPrompt": "p",
                         "handoffEnabled": null}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("handoffEnabled"));
    }

    /**
     * 같은 값을 다시 보내는 것은 <b>변경이 아니다</b>.
     *
     * <p>{@code forbiddenTopics} 가 이 단언의 핵심이다 — 응답에서는 빈 배열과 null 이 똑같이
     * {@code []} 인데 저장 표현이 갈리면 "안 바뀌었는데 바뀐 것"으로 판정된다.
     */
    @Test
    void aPatchThatChangesNothingLeavesUpdatedAtAlone() throws Exception {
        Owner owner = leasedOwner("시각");
        long agentId = createAgent(owner, MINIMAL);
        String before = updatedAtOf(agentId);

        mockMvc.perform(edit(owner, agentId, """
                        {"name": "도슨트", "forbiddenTopics": []}"""))
                .andExpect(status().isOk());
        assertEquals(before, updatedAtOf(agentId), "같은 값 PATCH 가 updated_at 을 흔들었다");

        mockMvc.perform(edit(owner, agentId, """
                        {"name": "정말 바뀐 이름"}"""))
                .andExpect(status().isOk());
        assertNotEquals(before, updatedAtOf(agentId), "실제 변경인데 updated_at 이 그대로다");
    }

    // ── 삭제 거부 (C-14) ────────────────────────────────────────────────────

    /** Draft 가 가리키면 거부한다 — 기존 Draft 데이터 보호. */
    @Test
    void anAgentUsedByADraftLayoutCannotBeDeleted() throws Exception {
        Owner owner = leasedOwner("초안");
        long agentId = createAgent(owner, MINIMAL);
        saveDraftReferencing(owner, agentId);

        mockMvc.perform(remove(owner, agentId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AGENT_DELETE_CONFLICT"));
    }

    /**
     * <b>현재</b> 공개된 배치가 가리키면 거부한다 — 이게 강한 불변식이다 (A-3).
     *
     * <p>Draft 에서 지워도 공개본은 그대로이므로 여전히 막힌다.
     */
    @Test
    void anAgentUsedByTheCurrentPublishedLayoutCannotBeDeleted() throws Exception {
        Owner owner = leasedOwner("공개");
        long agentId = createAgent(owner, MINIMAL);
        saveDraftReferencing(owner, agentId);
        publish(owner);
        saveDraftWithoutAgent(owner);

        mockMvc.perform(remove(owner, agentId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AGENT_DELETE_CONFLICT"));
    }

    /**
     * 과거 공개 버전만 가리키면 <b>삭제된다</b>.
     *
     * <p>판정을 {@code MAX(version)} 으로 하면 방문자에게 보이지 않는 옛 버전까지 삭제를 막는다.
     * 포인터({@code booths.published_layout_version})로 봐야 이 케이스가 통과한다.
     */
    @Test
    void anAgentOnlyInAnOlderPublishedVersionCanBeDeleted() throws Exception {
        Owner owner = leasedOwner("과거");
        long agentId = createAgent(owner, MINIMAL);
        saveDraftReferencing(owner, agentId);
        publish(owner);                 // v1 — 참조 있음
        saveDraftWithoutAgent(owner);
        publish(owner);                 // v2 — 참조 없음. 포인터는 이제 v2

        mockMvc.perform(remove(owner, agentId)).andExpect(status().isNoContent());
    }

    @ParameterizedTest
    @CsvSource({"ai_documents", "consultations"})
    void anAgentWithRowsPointingAtItCannotBeDeleted(String table) throws Exception {
        Owner owner = leasedOwner("참조" + table.charAt(0));
        long agentId = createAgent(owner, MINIMAL);
        seedReference(table, owner, agentId);

        mockMvc.perform(remove(owner, agentId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AGENT_DELETE_CONFLICT"));
    }

    /**
     * 사전 검사를 지나친 뒤 터진 외래키도 <b>409</b> 로 나오는지 (C-14).
     *
     * <p>사전 검사와 DELETE 사이에 문서가 생기는 경쟁이 있고, 그때 남는 방어는 외래키 하나다.
     * 그 경쟁의 인터리빙을 재현할 훅을 프로덕션에 파는 대신, 경쟁이 성립하려면 반드시 참이어야 할
     * 것을 실제 DB 로 확인한다 — Postgres 가 주는 제약 이름이 {@code AiAgentService} 가 아는
     * 이름과 같은지. 번역 규칙 자체는 {@code AiAgentDeleteTranslationTest} 가 덮는다.
     *
     * <p>이 둘이 갈리면 경쟁에서 진 요청은 409 대신 500 을 받는다. 이름은 V1 의 인라인
     * {@code REFERENCES} 가 정하므로 마이그레이션이 바뀌면 여기서 깨진다 — 그게 이 테스트의 몫이다.
     */
    @Test
    void theRealForeignKeyNameIsTheOneTheServiceTranslates() throws Exception {
        Owner owner = leasedOwner("FK");
        long agentId = createAgent(owner, MINIMAL);
        seedReference("ai_documents", owner, agentId);

        DataIntegrityViolationException violation = assertThrows(
                DataIntegrityViolationException.class,
                () -> agentRepository.deleteById(agentId));

        assertInstanceOf(AiAgentDeleteConflictException.class,
                AiAgentService.translateDeleteViolation(violation),
                "실제 위반이 AGENT_DELETE_CONFLICT 로 번역돼야 한다");
    }

    // ── 권한 (C-15 · FR-003) ────────────────────────────────────────────────

    /**
     * 같은 부스 스태프는 <b>네 가지를 전부</b> 할 수 있다 (C-15).
     *
     * <p>타 부스 403 만 보면 스태프에게 권한이 주어졌는지는 검증되지 않는다 — 아무에게도 권한이
     * 없어도 그 단언은 통과한다.
     */
    @Test
    void aStaffOfTheSameBoothMayDoEverything() throws Exception {
        Owner owner = leasedOwner("스태프");
        Long staffId = createMemberWithWallet(users, wallets, "스탭");
        staffs.save(new BoothStaff(owner.boothId(), staffId, "CONTENT_EDITOR"));
        Owner staff = new Owner(staffId, owner.boothId());

        long agentId = createAgent(staff, MINIMAL);
        mockMvc.perform(list(staff)).andExpect(status().isOk())
                .andExpect(jsonPath("$.agents.length()").value(1));
        mockMvc.perform(edit(staff, agentId, """
                {"name": "스태프가 고침"}""")).andExpect(status().isOk());
        mockMvc.perform(remove(staff, agentId)).andExpect(status().isNoContent());
    }

    @Test
    void anotherBoothsEditorIsRefusedEverywhere() throws Exception {
        Owner mine = leasedOwner("내부스");
        long agentId = createAgent(mine, MINIMAL);
        Owner stranger = leasedOwner("남부스");

        mockMvc.perform(list(stranger.withBooth(mine.boothId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BOOTH_EDITOR_FORBIDDEN"));
        mockMvc.perform(edit(stranger, agentId, """
                {"name": "가로채기"}""")).andExpect(status().isForbidden());
        mockMvc.perform(remove(stranger, agentId)).andExpect(status().isForbidden());
    }

    /** 만료 부스는 쓸 수 없지만 읽을 수는 있다 — 보존을 확인할 방법이 있어야 한다. */
    @Test
    void anExpiredBoothCanStillBeReadButNotEdited() throws Exception {
        Owner owner = leasedOwner("만료");
        long agentId = createAgent(owner, MINIMAL);
        expireLease(jdbc, owner.boothId());

        mockMvc.perform(edit(owner, agentId, """
                        {"name": "수정 시도"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOTH_LEASE_EXPIRED"));
        mockMvc.perform(remove(owner, agentId)).andExpect(status().isConflict());
        mockMvc.perform(list(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agents[0].name").value("도슨트"));
    }

    @Test
    void guestsAreRefusedAtTheDoor() throws Exception {
        Owner owner = leasedOwner("게스트");
        long agentId = createAgent(owner, MINIMAL);
        String guest = "Bearer " + accessTokens.issueGuestToken().token();

        mockMvc.perform(post("/api/v1/booths/{id}/agents", owner.boothId())
                        .header("Authorization", guest)
                        .contentType(MediaType.APPLICATION_JSON).content(MINIMAL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_ONLY"));
        mockMvc.perform(patch("/api/v1/agents/{id}", agentId)
                        .header("Authorization", guest)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name": "게스트"}"""))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/agents/{id}", agentId).header("Authorization", guest))
                .andExpect(status().isForbidden());
    }

    /** 저장한 바이트가 그대로 돌아온다 — 지시문은 사용자가 쓴 프롬프트다. */
    @Test
    void theSystemPromptRoundTripsVerbatim() throws Exception {
        Owner owner = leasedOwner("무손실");
        String prompt = "  줄바꿈\n과 공백을   보존한다. \"따옴표\"도.  ";

        long agentId = createAgent(owner, """
                {"name": "x", "role": "GUIDE", "systemPrompt": %s}"""
                .formatted(jsonMapper.writeValueAsString(prompt)));

        mockMvc.perform(single(owner, agentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.systemPrompt").value(prompt))
                .andExpect(jsonPath("$.description").doesNotHaveJsonPath());
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private long createAgent(Owner owner, String body) throws Exception {
        String json = mockMvc.perform(create(owner, body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return jsonMapper.readTree(json).get("agentId").asLong();
    }

    /** {@code @CsvSource} 는 전부 문자열로 준다 — 응답 단언은 실제 JSON 타입이어야 맞는다. */
    private static Object jsonValue(String literal) {
        if ("true".equals(literal) || "false".equals(literal)) {
            return Boolean.valueOf(literal);
        }
        if (Character.isDigit(literal.charAt(0))) {
            return Integer.valueOf(literal);
        }
        return literal.replace("\"", "");
    }

    private String updatedAtOf(long agentId) {
        return jdbc.queryForObject(
                "SELECT updated_at::text FROM ai_agents WHERE id = ?", String.class, agentId);
    }

    private void seedReference(String table, Owner owner, long agentId) {
        if ("ai_documents".equals(table)) {
            jdbc.update("""
                    INSERT INTO ai_documents (booth_id, agent_id, original_filename, content_type,
                                              size_bytes, s3_key, processing_status, uploaded_by_user_id)
                    VALUES (?, ?, 'a.pdf', 'application/pdf', 1, ?, 'QUEUED', ?)
                    """, owner.boothId(), agentId, "k/" + agentId, owner.userId());
        } else {
            jdbc.update("""
                    INSERT INTO consultations (booth_id, visitor_user_id, agent_id, status)
                    VALUES (?, ?, ?, 'REQUESTED')
                    """, owner.boothId(), owner.userId(), agentId);
        }
    }

    private void saveDraftReferencing(Owner owner, long agentId) throws Exception {
        putDraft(owner, """
                [{"objectId":"ai-1","type":"AI_AGENT","position":{"x":0.0,"y":0.0,"z":0.0},
                  "rotationY":0.0,"configId":%d}]""".formatted(agentId));
    }

    private void saveDraftWithoutAgent(Owner owner) throws Exception {
        putDraft(owner, """
                [{"objectId":"deco-1","type":"DECORATION","position":{"x":0.0,"y":0.0,"z":0.0},
                  "rotationY":0.0}]""");
    }

    private void putDraft(Owner owner, String objectsJson) throws Exception {
        long revision = currentRevision(owner.boothId());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/booths/{id}/layouts/draft", owner.boothId())
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":%d,"schemaVersion":1,"template":"PROJECT_EXHIBITION","objects":%s}"""
                                .formatted(revision, objectsJson)))
                .andExpect(status().isOk());
    }

    private long currentRevision(Long boothId) {
        Long revision = jdbc.queryForObject(
                "SELECT COALESCE(MAX(revision), 0) FROM booth_layout_drafts WHERE booth_id = ?",
                Long.class, boothId);
        return revision == null ? 0L : revision;
    }

    private void publish(Owner owner) throws Exception {
        mockMvc.perform(post("/api/v1/booths/{id}/layouts/publish", owner.boothId())
                        .header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().is2xxSuccessful());
    }

    private RequestBuilder create(Owner owner, String body) {
        return post("/api/v1/booths/{id}/agents", owner.boothId())
                .header("Authorization", bearerFor(owner.userId()))
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private RequestBuilder list(Owner owner) {
        return get("/api/v1/booths/{id}/agents", owner.boothId())
                .header("Authorization", bearerFor(owner.userId()));
    }

    private RequestBuilder single(Owner owner, long agentId) {
        return get("/api/v1/agents/{id}", agentId)
                .header("Authorization", bearerFor(owner.userId()));
    }

    private RequestBuilder edit(Owner owner, long agentId, String body) {
        return patch("/api/v1/agents/{id}", agentId)
                .header("Authorization", bearerFor(owner.userId()))
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private RequestBuilder remove(Owner owner, long agentId) {
        return delete("/api/v1/agents/{id}", agentId)
                .header("Authorization", bearerFor(owner.userId()));
    }

    private Owner leasedOwner(String prefix) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(userId, prefix + " 부스")).getId();
        grantLease(jdbc, boothId, userId);
        return new Owner(userId, boothId);
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }

    private record Owner(Long userId, Long boothId) {
        Owner withBooth(Long other) {
            return new Owner(userId, other);
        }
    }
}
