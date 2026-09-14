package com.example.ssafesta;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.publishLayout;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static com.example.ssafesta.booth.BoothTestSupport.releaseAllSlots;
import static com.example.ssafesta.wallet.WalletTestSupport.assertBalanceMatchesLedger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.ai.FakeDocumentProcessingClient;
import com.example.ssafesta.ai.FakeDocumentProcessingClientConfiguration;
import com.example.ssafesta.auth.AccessTokenService;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.inventory.InventoryService;
import com.example.ssafesta.storage.FakeObjectStorage;
import com.example.ssafesta.storage.FakeObjectStorageConfiguration;
import com.example.ssafesta.survey.SurveyResponseService;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 한 회원이 가입 지급부터 답변받은 설문까지 도메인 여덟 개를 관통하는 하나의 아크
 * (S15P21A604-515).
 *
 * <p>도메인별 통합 테스트가 각자의 계약을 이미 고정하고 있으므로 여기서 새로 보는 것은
 * <b>이음매</b>다. 임대가 만든 부스 id 로 배치를 게시하고, 게시가 방문자 게이트를 열고, 구매한
 * 아이템이 아바타 저장 가드를 통과하고, 업로드한 문서가 내부 콜백을 거쳐 chunk 검색에 나온다 —
 * 각 도메인이 혼자 초록이면서 서로 어긋나 있는 상태가 이 파일에서만 드러난다.
 *
 * <p><b>범위 밖.</b> 이름을 E2E 로 올리지 않는 이유이기도 하다.
 *
 * <ul>
 *   <li><b>FastAPI 실제 처리</b> — {@link FakeDocumentProcessingClient} 가 호출을 기록만 하고,
 *       chunk 와 임베딩은 테스트가 내부 API 로 직접 넣는다. 임베딩 품질·청킹은 여기 없다.</li>
 *   <li><b>실제 오브젝트 스토리지</b> — {@link FakeObjectStorage} 다. 브라우저가 저장소로 직접
 *       PUT 하는 구간은 {@code putObject} 한 줄로 대신한다.</li>
 *   <li><b>React·Unity</b> — HTTP 계약까지만 본다. 화면과 월드 클라이언트는 이 프로세스 밖이다.
 *       {@code POST /api/v1/world-sessions} 도 발급까지이고 게임 서버 접속은 확인하지 않는다.</li>
 *   <li><b>OAuth 실 왕복</b> — 로그인은 {@link MemberSessionService#issue} 로 토큰만 발급한다.
 *       인가 코드 교환과 최초 가입은 {@code OAuthCompletionApiIntegrationTest} 소관이다.</li>
 * </ul>
 *
 * <p>컨텍스트는 {@code AiDocumentUploadIntegrationTest} 와 같은 조합을 쓴다 — 같아야 하나를 함께
 * 쓰고, 어긋나면 전체 실행에 컨텍스트가 하나 더 뜬다.
 */
@Import({TestcontainersConfiguration.class, FakeObjectStorageConfiguration.class,
        FakeDocumentProcessingClientConfiguration.class})
@SpringBootTest
@AutoConfigureMockMvc
class BackendCrossDomainIntegrationTest {

    /** {@code application.yml} 의 {@code app.wallet.initial-grant}. */
    private static final int SIGNUP_GRANT = 200;
    /** {@code app.wallet.daily-grant} — 그날 첫 인증 요청에 붙는다. */
    private static final int DAILY_GRANT = 50;
    /** {@code app.lease.price-coin}. */
    private static final int LEASE_PRICE = 100;
    private static final int SURVEY_REWARD = 5;

    /** {@code src/test/resources/application-local.properties} 가 넣는 값과 같아야 한다. */
    private static final String SERVICE_TOKEN = "test-ai-to-spring";
    private static final int EMBEDDING_DIMENSIONS = 1536;
    private static final String EMBEDDING_MODEL = "text-embedding-3-small";
    private static final String DOCUMENT_SHA =
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
    private static final long DOCUMENT_SIZE = 1024L * 1024;
    private static final String CHUNK_TEXT = "A604 팀은 SSAFY FESTA 를 만들었습니다.";

    private static final String AGENT = """
            {"name": "도슨트", "role": "PROJECT_DOCENT", "systemPrompt": "문서를 근거로 답한다."}""";

    /** 필수 문항 하나짜리 설문 — 방문자가 답을 만들 때 분기가 없다. */
    private static final String SURVEY = """
            {"title":"A604 부스 설문","rewardCoin":%d,
             "questions":[{"type":"RATING","prompt":"만족도","required":true,
                           "scale":{"min":1,"max":5}}]}""".formatted(SURVEY_REWARD);

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private AccessTokenService accessTokens;
    @Autowired private InventoryService inventory;
    @Autowired private SurveyResponseService submissions;
    @Autowired private FakeObjectStorage storage;
    @Autowired private FakeDocumentProcessingClient processing;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;

    /**
     * 자리는 열한 개뿐이고 임대는 24시간이다 — 앞에서 비우지 않으면 다른 클래스가 남긴 임대로
     * 이 파일이 자리를 못 잡는다. Fake 둘도 클래스 사이에서 공유되는 빈이라 함께 되돌린다.
     */
    @BeforeEach
    void resetSharedState() {
        releaseAllSlots(jdbc);
        storage.reset();
        processing.reset();
    }

    // ── 아크 ────────────────────────────────────────────────────────────────

    /**
     * 가입한 회원이 자리를 사고, 부스를 꾸미고, 문서를 올리고, 방문자가 그 결과를 보고 답한다.
     *
     * <p>단계를 private 헬퍼로 나누고 {@code @TestMethodOrder} 를 쓰지 않는다 — 메서드를 나누면
     * 순서가 JUnit 설정에 의존하게 되고, 앞 단계가 깨졌을 때 뒷 단계가 "실패" 가 아니라 "오류" 로
     * 무더기로 뜬다. 하나의 흐름은 하나의 테스트다.
     */
    @Test
    @DisplayName("가입 지급부터 설문 응답까지 여덟 도메인이 한 흐름으로 이어진다")
    void oneMemberRunsTheWholeBoothArcAndAVisitorAnswersIt() throws Exception {
        Long ownerId = createMemberWithWallet(users, wallets, "횡단주인");
        String owner = bearerFor(ownerId);

        int ownerStartingBalance = openingBalance(owner, ownerId);
        long slotId = firstAvailableRentalSlot();
        long boothId = leaseSlot(owner, slotId, ownerStartingBalance);
        confirmTheRetryIsNotChargedAgain(owner, slotId, boothId, ownerStartingBalance);
        confirmMyBooth(owner, boothId, slotId);

        dressTheBooth(owner, boothId);
        publishLayout(mockMvc, boothId, owner);
        registerHomepage(owner, boothId);
        long projectId = registerProject(owner, boothId);
        saveSurvey(owner, boothId);

        long agentId = createAgent(owner, boothId);
        long documentId = uploadDocument(owner, agentId, boothId);
        long jobId = completeUploadOnce(owner, documentId);

        int priceOfPurchase = buyAndWearACatalogItem(owner, ownerStartingBalance - LEASE_PRICE);
        deliverProcessingResults(jobId);

        visitAsGuest(slotId, boothId, projectId);
        Long visitorId = createMemberWithWallet(users, wallets, "횡단방문");
        String visitor = bearerFor(visitorId);
        int visitorStartingBalance = openingBalance(visitor, visitorId);
        likeTwice(visitor, projectId);
        long surveyId = answerTheSurveyOnceAndOnlyOnce(visitor, visitorId, boothId, visitorStartingBalance);

        confirmTheDocumentIsSearchable(boothId, agentId, documentId);
        confirmTheOwnerSeesTheResult(owner, surveyId);

        // 두 지갑 모두 원장과 맞고, 흐름 전체의 산술이 맞는다. I-1 만 보면 "둘 다 틀린" 경우를
        // 놓치고, 산술만 보면 원장이 비어도 통과한다 — 둘 다 봐야 한다.
        assertBalanceMatchesLedger(wallets, ownerId);
        assertBalanceMatchesLedger(wallets, visitorId);
        assertEquals(ownerStartingBalance - LEASE_PRICE - priceOfPurchase, wallets.balanceOf(ownerId),
                "소유자 최종 잔액 — 시작 잔액에서 임대료와 구매액만 빠져야 합니다");
        assertEquals(visitorStartingBalance + SURVEY_REWARD, wallets.balanceOf(visitorId),
                "방문자 최종 잔액 — 시작 잔액에 설문 보상만 더해져야 합니다");
    }

    // ── 동시성 (이 조합만 새롭다) ───────────────────────────────────────────

    /**
     * 한 지갑에 차감(구매)과 적립(설문 보상)이 동시에 들어온다.
     *
     * <p>도메인별 동시성 테스트 열한 개가 <b>같은 방향</b>의 경합만 덮는다 — 임대 경합, 레이아웃
     * 리비전, 설문 중복 제출, 지갑 동시 차감. 반대 방향이 섞이는 경우는 아무도 보고 있지 않고,
     * 잔액을 읽어 계산한 뒤 쓰는 구현이라면 여기서만 한쪽이 사라진다.
     *
     * <p>아크 메서드와 픽스처를 나눠 둔다. 아크가 쓰는 지갑에 스레드를 붙이면 실패했을 때 어느
     * 단계의 잔액이 틀린 것인지 말할 수 없다.
     */
    @Test
    @DisplayName("같은 지갑에 구매 차감과 설문 보상이 동시에 들어와도 둘 다 남는다")
    void aPurchaseAndASurveyRewardCrossOnOneWalletWithoutLosingEither() throws Exception {
        Long ownerId = createMemberWithWallet(users, wallets, "경합주인");
        String owner = bearerFor(ownerId);
        openingBalance(owner, ownerId);
        long boothId = leaseSlot(owner, firstAvailableRentalSlot(), wallets.balanceOf(ownerId));
        publishLayout(mockMvc, boothId, owner);
        saveSurvey(owner, boothId);
        Survey survey = readSurvey(owner, boothId);

        Long respondentId = createMemberWithWallet(users, wallets, "경합응답");
        long itemId = jdbc.queryForObject("""
                SELECT id FROM catalog_items
                 WHERE item_type = 'AVATAR_PART' AND is_on_sale = TRUE AND price > 0
                 ORDER BY price, id LIMIT 1
                """, Long.class);
        int price = jdbc.queryForObject("SELECT price FROM catalog_items WHERE id = ?",
                Integer.class, itemId);
        int before = wallets.balanceOf(respondentId);

        CyclicBarrier gate = new CyclicBarrier(2);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> purchase = pool.submit(waitThen(gate,
                    () -> inventory.purchase(respondentId, itemId)));
            Future<Integer> reward = pool.submit(waitThen(gate,
                    () -> submissions.submit(survey.surveyId(),
                            SurveyResponseService.Respondent.member(respondentId),
                            new SurveyResponseService.SubmitCommand(List.of(
                                    new SurveyResponseService.AnswerCommand(
                                            survey.questionId(), List.of(), 4, null))))
                            .rewardedCoin()));

            purchase.get(30, TimeUnit.SECONDS);
            assertEquals(SURVEY_REWARD, reward.get(30, TimeUnit.SECONDS),
                    "동시 진입 — 설문 제출은 보상을 그대로 지급해야 합니다");
        }

        assertEquals(before - price + SURVEY_REWARD, wallets.balanceOf(respondentId),
                "동시 진입 — 차감과 적립이 둘 다 반영돼야 합니다. 한쪽 금액만큼 어긋나면 "
                        + "나중 트랜잭션이 먼저 읽은 잔액으로 덮어쓴 것입니다");
        assertBalanceMatchesLedger(wallets, respondentId);
        assertEquals(1, ledgerCount(respondentId, "PURCHASE"), "동시 진입 — 구매 원장은 한 건이어야 합니다");
        assertEquals(1, ledgerCount(respondentId, "SURVEY_REWARD"),
                "동시 진입 — 보상 원장은 한 건이어야 합니다");
    }

    // ── 단계 ────────────────────────────────────────────────────────────────

    /**
     * 그날 첫 인증 요청이 일일 지급을 붙이므로, {@code /users/me} 가 먼저고 잔액 조회가 그 다음이다.
     * 순서를 바꾸면 이 아크의 시작 잔액이 호출 순서에 따라 달라진다.
     */
    private int openingBalance(String bearer, Long userId) throws Exception {
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                // 서버는 기본 아바타를 지어내지 않는다 — 키는 있고 값은 없다 (spec 013 FR-010).
                .andExpect(jsonPath("$.avatarCode").doesNotExist());

        String json = mockMvc.perform(get("/api/v1/wallets/me").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.balance").value(SIGNUP_GRANT + DAILY_GRANT))
                .andReturn().getResponse().getContentAsString();
        return read(json).get("balance").asInt();
    }

    /**
     * 목록이 알려 주는 자리를 고른다 — id 를 적어 두지 않는다. 1번은 이벤트 부스가 됐고(V28)
     * 그때 하드코딩한 테스트들이 깨졌다. 목록이 계약이고 번호는 계약이 아니다.
     */
    private long firstAvailableRentalSlot() throws Exception {
        String json = mockMvc.perform(get("/api/v1/booth-slots"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (JsonNode slot : read(json)) {
            if ("USER_RENTAL".equals(slot.get("type").asString())
                    && "AVAILABLE".equals(slot.get("status").asString())) {
                return slot.get("slotId").asLong();
            }
        }
        throw new AssertionError("임대 가능한 USER_RENTAL 자리가 목록에 하나도 없습니다 — "
                + "releaseAllSlots 가 앞에서 돌지 않았거나 자리 시드가 바뀌었습니다.");
    }

    /** 유료 경로. 이 아크의 핵심이라 {@code grantLease} 로 건너뛰지 않는다. */
    private long leaseSlot(String bearer, long slotId, int balanceBefore) throws Exception {
        String json = mockMvc.perform(lease(bearer, slotId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slotId").value(slotId))
                .andExpect(jsonPath("$.chargedCoin").value(LEASE_PRICE))
                .andExpect(jsonPath("$.balanceAfter").value(balanceBefore - LEASE_PRICE))
                .andReturn().getResponse().getContentAsString();
        return read(json).get("boothId").asLong();
    }

    /** 타임아웃 뒤 재시도가 이중 결제가 되지 않는다 (spec 004 FR-018). */
    private void confirmTheRetryIsNotChargedAgain(String bearer, long slotId, long boothId,
                                                  int balanceBefore) throws Exception {
        mockMvc.perform(lease(bearer, slotId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boothId").value(boothId))
                .andExpect(jsonPath("$.balanceAfter").value(balanceBefore - LEASE_PRICE));

        mockMvc.perform(get("/api/v1/wallets/me").header("Authorization", bearer))
                .andExpect(jsonPath("$.balance").value(balanceBefore - LEASE_PRICE));
    }

    /** 임대가 만든 부스를 스튜디오 첫 화면이 그대로 집어야 이후 단계의 {@code boothId} 가 같다. */
    private void confirmMyBooth(String bearer, long boothId, long slotId) throws Exception {
        mockMvc.perform(get("/api/v1/booths/mine").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boothId").value(boothId))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.lease.slotId").value(slotId))
                .andExpect(jsonPath("$.lease.chargedCoin").value(LEASE_PRICE));
    }

    private void dressTheBooth(String bearer, long boothId) throws Exception {
        mockMvc.perform(put("/api/v1/booths/{id}/facade", boothId)
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"themeCode":"SSAFY_BLUE","primaryColor":"#3B82F6",
                                 "signText":"A604 전시관"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signText").value("A604 전시관"));
    }

    private void registerHomepage(String bearer, long boothId) throws Exception {
        mockMvc.perform(put("/api/v1/booths/{id}/homepage", boothId)
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"homepageUrl\":\"https://a604.example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.homepageUrl").value("https://a604.example.com"));
    }

    /**
     * 등록하고, 게시된 부스라서 방문자 경로에 <b>바로</b> 나오는지까지 본다.
     *
     * <p>프로젝트에는 별도의 게시 스위치가 없다 — 배치 게시가 곧 전시 게시다. 그 사실을 등록
     * 직후에 확인하지 않으면 두 게이트가 갈라져도 소유자 목록만 보고 통과한다.
     */
    private long registerProject(String bearer, long boothId) throws Exception {
        String json = mockMvc.perform(post("/api/v1/booths/{id}/projects", boothId)
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"횡단 전시","gitUrl":"https://git.example.com/a604"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long projectId = read(json).get("projectId").asLong();

        mockMvc.perform(get("/api/v1/booths/{id}/projects/published", boothId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects[0].projectId").value(projectId))
                .andExpect(jsonPath("$.projects[0].name").value("횡단 전시"));
        return projectId;
    }

    private void saveSurvey(String bearer, long boothId) throws Exception {
        mockMvc.perform(put("/api/v1/booths/{id}/survey", boothId)
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SURVEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rewardCoin").value(SURVEY_REWARD))
                .andExpect(jsonPath("$.responseCount").value(0));
    }

    private Survey readSurvey(String bearer, long boothId) throws Exception {
        String json = mockMvc.perform(get("/api/v1/booths/{id}/survey", boothId)
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode view = read(json);
        return new Survey(view.get("surveyId").asLong(),
                view.get("questions").get(0).get("questionId").asLong());
    }

    private long createAgent(String bearer, long boothId) throws Exception {
        String json = mockMvc.perform(post("/api/v1/booths/{id}/agents", boothId)
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(AGENT))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return read(json).get("agentId").asLong();
    }

    /**
     * 발급받은 자리에 브라우저가 올렸다고 치고 객체를 넣는다 — 파일은 Spring 을 통과하지 않는다.
     *
     * <p>{@code objectKey} 가 부스·에이전트·문서 셋으로 이루어진다는 것을 여기서 확인한다.
     * 임대가 준 {@code boothId} 와 에이전트 생성이 준 {@code agentId} 가 실제로 같은 자리를
     * 가리키는지는 이 문자열에만 드러난다.
     */
    private long uploadDocument(String bearer, long agentId, long boothId) throws Exception {
        String json = mockMvc.perform(post("/api/v1/agents/{id}/documents/upload-url", agentId)
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileName":"a604.pdf","contentType":"application/pdf",
                                 "size":%d,"contentSha256":"%s"}""".formatted(DOCUMENT_SIZE, DOCUMENT_SHA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false))
                .andReturn().getResponse().getContentAsString();

        JsonNode grant = read(json);
        long documentId = grant.get("documentId").asLong();
        assertEquals("booths/%d/agents/%d/documents/%d/a604.pdf".formatted(boothId, agentId, documentId),
                grant.get("objectKey").asString(),
                "업로드 자리 — 임대가 준 boothId 와 에이전트가 같은 경로에 있어야 합니다");

        storage.putObject(grant.get("objectKey").asString(), DOCUMENT_SIZE);
        return documentId;
    }

    /** 완료를 두 번 불러도 Job 은 하나다 — 워커가 둘이 되면 chunk 가 두 배로 실린다. */
    private long completeUploadOnce(String bearer, long documentId) throws Exception {
        mockMvc.perform(complete(bearer, documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processingStatus").value("QUEUED"));
        mockMvc.perform(complete(bearer, documentId)).andExpect(status().isOk());

        assertEquals(1, countOf("SELECT count(*) FROM ai_document_jobs WHERE document_id = ?", documentId),
                "업로드 완료 재호출 — Job 은 문서당 하나여야 합니다");
        assertEquals(1, processing.received().size(),
                "업로드 완료 재호출 — 처리 위임은 한 번만 나가야 합니다");
        return processing.onlyRequest().jobId();
    }

    /**
     * 목록에서 살 수 있는 것을 고르고, 사고, 그것을 입는다.
     *
     * <p>마지막 한 줄이 이 단계의 이유다. 구매는 인벤토리에, 아바타 저장은 계정에 쓰는데 저장
     * 가드가 인벤토리를 읽는다 — 두 도메인이 어긋나면 방금 산 것을 입을 수 없고, 각자의 테스트는
     * 둘 다 초록이다.
     *
     * @return 차감된 금액
     */
    private int buyAndWearACatalogItem(String bearer, int balanceBefore) throws Exception {
        String json = mockMvc.perform(catalog(bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode target = null;
        for (JsonNode item : read(json).get("items")) {
            if (item.get("price").asInt() > 0 && item.get("onSale").asBoolean()
                    && !item.get("owned").asBoolean()) {
                target = item;
                break;
            }
        }
        assertNotNull(target, "카탈로그 — 살 수 있는 유료 아이템이 하나도 없습니다");
        long itemId = target.get("itemId").asLong();
        String assetKey = target.get("assetKey").asString();
        int price = target.get("price").asInt();
        assertTrue(price <= balanceBefore, "카탈로그 — 고른 아이템이 잔액보다 비쌉니다: " + price);

        mockMvc.perform(purchase(bearer, itemId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assetKey").value(assetKey))
                .andExpect(jsonPath("$.owned").value(true));
        mockMvc.perform(get("/api/v1/wallets/me").header("Authorization", bearer))
                .andExpect(jsonPath("$.balance").value(balanceBefore - price));

        mockMvc.perform(purchase(bearer, itemId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ITEM_ALREADY_OWNED"));
        mockMvc.perform(get("/api/v1/wallets/me").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(balanceBefore - price));

        String avatarCode = "fa|g=0|i=" + assetKey + ",0,0,0,0,0,0,0|p=unchanged";
        mockMvc.perform(put("/api/v1/users/me/avatar")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content(jsonMapper.writeValueAsString(java.util.Map.of("avatarCode", avatarCode))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarCode").value(avatarCode));
        return price;
    }

    /**
     * FastAPI 가 돌려주는 결과를 내부 토큰으로 직접 넣는다.
     *
     * <p>재전송을 각각 한 번씩 본다. batch 는 finalize 전에, finalize 는 그 뒤에 — 순서가 계약이다.
     * finalize 뒤의 batch 는 끝난 Job 으로 {@code JOB_GONE} 이고 멱등이 아니다.
     */
    private void deliverProcessingResults(long jobId) throws Exception {
        mockMvc.perform(chunkBatch(jobId)).andExpect(status().isNoContent());
        mockMvc.perform(chunkBatch(jobId)).andExpect(status().isNoContent());
        assertEquals(1, countOf("SELECT count(*) FROM ai_document_chunk_staging WHERE job_id = ?", jobId),
                "batch 재전송 — 같은 batch 를 다시 받아도 적재는 한 건이어야 합니다");

        mockMvc.perform(finalizeJob(jobId)).andExpect(status().isNoContent());
        mockMvc.perform(finalizeJob(jobId)).andExpect(status().isNoContent());

        assertEquals("SUCCEEDED",
                jdbc.queryForObject("SELECT status FROM ai_document_jobs WHERE id = ?", String.class, jobId),
                "finalize 재전송 — Job 은 성공 상태 그대로여야 합니다");
        assertEquals(1, countOf("""
                SELECT count(*) FROM ai_document_chunks c
                 WHERE c.document_id = (SELECT document_id FROM ai_document_jobs WHERE id = ?)
                """, jobId), "finalize 재전송 — 공개된 chunk 가 두 벌이 되면 안 됩니다");
    }

    /** 토큰 없이 돌아다니는 사람이 보는 것 — 게시가 실제로 문을 열었는지는 여기서만 드러난다. */
    private void visitAsGuest(long slotId, long boothId, long projectId) throws Exception {
        String guest = "Bearer " + accessTokens.issueGuestToken().token();

        mockMvc.perform(post("/api/v1/world-sessions").header("Authorization", guest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.worldId").value("11F"))
                .andExpect(jsonPath("$.connectionToken").isNotEmpty());

        // Unity 는 방을 앵커로 연다. 부스 키로 게시한 것이 슬롯 키로 읽혀야 월드가 빈 방을 그리지 않는다.
        mockMvc.perform(get("/api/v1/booth-slots/{id}/layouts/published", slotId)
                        .header("Authorization", guest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boothId").value(boothId))
                .andExpect(jsonPath("$.objects").isNotEmpty());

        mockMvc.perform(get("/api/v1/booths/{id}", boothId).header("Authorization", guest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facade.signText").value("A604 전시관"))
                .andExpect(jsonPath("$.homepageUrl").value("https://a604.example.com"));

        mockMvc.perform(get("/api/v1/booths/{id}/projects/published", boothId)
                        .header("Authorization", guest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects[0].projectId").value(projectId))
                .andExpect(jsonPath("$.projects[0].likedByMe").value(false));

        // 읽기는 열려 있어도 영속 자산은 못 갖는다 (헌법 12조).
        mockMvc.perform(put("/api/v1/projects/{id}/like", projectId).header("Authorization", guest))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_ONLY"));
    }

    /** 두 번을 똑같이 단정한다 — 첫 호출을 안 보면 그것이 깨져도 두 번째가 첫 성공이 된다. */
    private void likeTwice(String bearer, long projectId) throws Exception {
        for (int call = 1; call <= 2; call++) {
            mockMvc.perform(put("/api/v1/projects/{id}/like", projectId).header("Authorization", bearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.likeCount").value(1))
                    .andExpect(jsonPath("$.likedByMe").value(true));
        }
    }

    private long answerTheSurveyOnceAndOnlyOnce(String bearer, Long visitorId, long boothId,
                                                int balanceBefore) throws Exception {
        String json = mockMvc.perform(get("/api/v1/booths/{id}/survey/run", boothId)
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closed").value(false))
                .andExpect(jsonPath("$.rewardCoin").value(SURVEY_REWARD))
                .andReturn().getResponse().getContentAsString();
        JsonNode run = read(json);
        long surveyId = run.get("surveyId").asLong();
        long questionId = run.get("questions").get(0).get("questionId").asLong();
        String answers = """
                {"answers":[{"questionId":%d,"rating":4}]}""".formatted(questionId);

        mockMvc.perform(submit(bearer, surveyId, answers))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rewardedCoin").value(SURVEY_REWARD));
        assertEquals(balanceBefore + SURVEY_REWARD, wallets.balanceOf(visitorId),
                "설문 제출 — 보상만큼 잔액이 올라야 합니다");

        mockMvc.perform(submit(bearer, surveyId, answers))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SURVEY_ALREADY_RESPONDED"));
        assertEquals(balanceBefore + SURVEY_REWARD, wallets.balanceOf(visitorId),
                "설문 재제출 — 거부된 제출은 보상을 다시 주면 안 됩니다");
        assertEquals(1, ledgerCount(visitorId, "SURVEY_REWARD"),
                "설문 재제출 — 보상 원장은 한 건이어야 합니다");
        return surveyId;
    }

    /**
     * 올린 문서가 FastAPI 의 검색 진입점으로 실제로 회수된다.
     *
     * <p>업로드(007) · 결과 수신(#119 §3) · 검색(008) 이 각자 통과해도, 셋을 잇는 문서 id 와
     * scope 가 어긋나면 답변이 아무것도 인용하지 못한다 — 그 침묵은 어느 도메인 테스트도 깨지
     * 않는다.
     */
    private void confirmTheDocumentIsSearchable(long boothId, long agentId, long documentId)
            throws Exception {
        mockMvc.perform(post("/internal/ai/chunk-search")
                        .header("Authorization", "Bearer " + SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"boothId":%d,"agentId":%d,"queryEmbedding":%s,"topK":10}"""
                                .formatted(boothId, agentId, unitVector())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].documentId").value(documentId))
                .andExpect(jsonPath("$.items[0].originalFilename").value("a604.pdf"))
                .andExpect(jsonPath("$.items[0].content").value(CHUNK_TEXT));
    }

    private void confirmTheOwnerSeesTheResult(String bearer, long surveyId) throws Exception {
        mockMvc.perform(get("/api/v1/surveys/{id}/results", surveyId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalResponses").value(1))
                .andExpect(jsonPath("$.perQuestion[0].answeredCount").value(1));

        String json = mockMvc.perform(get("/api/v1/wallets/me/transactions")
                        .queryParam("size", "100")
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int leasePayments = 0;
        for (JsonNode entry : read(json).get("content")) {
            if ("LEASE_PAYMENT".equals(entry.get("reasonType").asString())) {
                leasePayments++;
                assertEquals(-LEASE_PRICE, entry.get("amount").asInt(),
                        "거래 내역 — 임대 차감은 음수 금액이어야 합니다");
            }
        }
        assertEquals(1, leasePayments,
                "거래 내역 — 재전송된 임대까지 원장에 남았다면 이중 결제입니다");
    }

    // ── 요청 ────────────────────────────────────────────────────────────────

    private RequestBuilder lease(String bearer, long slotId) {
        return post("/api/v1/booth-slots/{id}/leases", slotId).header("Authorization", bearer);
    }

    private RequestBuilder complete(String bearer, long documentId) {
        return post("/api/v1/documents/{id}/complete", documentId).header("Authorization", bearer);
    }

    private RequestBuilder catalog(String bearer) {
        return get("/api/v1/catalog/items").queryParam("type", "AVATAR_PART")
                .header("Authorization", bearer);
    }

    private RequestBuilder purchase(String bearer, long itemId) {
        return post("/api/v1/catalog/items/{id}/purchases", itemId).header("Authorization", bearer);
    }

    private RequestBuilder submit(String bearer, long surveyId, String answers) {
        return post("/api/v1/surveys/{id}/responses", surveyId)
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(answers);
    }

    private RequestBuilder chunkBatch(long jobId) {
        return post("/internal/ai/document-jobs/{id}/chunk-batches", jobId)
                .header("Authorization", "Bearer " + SERVICE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"attemptNo":0,"batchSeq":0,"chunks":[
                          {"chunkNo":0,"content":"%s","embedding":%s,"embeddingModelId":"%s",
                           "pageNumber":1,"section":null}]}"""
                        .formatted(CHUNK_TEXT, unitVector(), EMBEDDING_MODEL));
    }

    private RequestBuilder finalizeJob(long jobId) {
        return post("/internal/ai/document-jobs/{id}/finalize", jobId)
                .header("Authorization", "Bearer " + SERVICE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"attemptNo":0,"sourceHash":"%s","totalChunkCount":1,"embeddingModelId":"%s"}"""
                        .formatted(DOCUMENT_SHA, EMBEDDING_MODEL));
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    /** 첫 칸만 1 인 단위 벡터 — 적재와 질의가 같은 축이라 거리가 0 으로 결정된다. */
    private static String unitVector() {
        return "[1" + ",0".repeat(EMBEDDING_DIMENSIONS - 1) + "]";
    }

    private static <T> Callable<T> waitThen(CyclicBarrier gate, Callable<T> action) {
        return () -> {
            gate.await(30, TimeUnit.SECONDS);
            return action.call();
        };
    }

    private JsonNode read(String json) {
        return jsonMapper.readTree(json);
    }

    private int countOf(String sql, Object... arguments) {
        Integer found = jdbc.queryForObject(sql, Integer.class, arguments);
        return found == null ? 0 : found;
    }

    private int ledgerCount(Long userId, String reasonType) {
        return countOf("""
                SELECT count(*) FROM coin_ledger_entries e JOIN wallets w ON w.id = e.wallet_id
                 WHERE w.user_id = ? AND e.reason_type = ?
                """, userId, reasonType);
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }

    private record Survey(long surveyId, long questionId) { }
}
