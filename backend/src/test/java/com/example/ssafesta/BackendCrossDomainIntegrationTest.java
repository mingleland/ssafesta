package com.example.ssafesta;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.publishLayout;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static com.example.ssafesta.booth.BoothTestSupport.releaseAllSlots;
import static com.example.ssafesta.wallet.WalletTestSupport.assertBalanceMatchesLedger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.ai.FakeDocumentProcessingClient;
import com.example.ssafesta.auth.AccessTokenService;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.booth.BoothLeaseRepository;
import com.example.ssafesta.booth.BoothLeaseService;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.eventshop.EventPrize;
import com.example.ssafesta.eventshop.EventPrizeRepository;
import com.example.ssafesta.eventshop.EventPurchaseRepository;
import com.example.ssafesta.inventory.InventoryService;
import com.example.ssafesta.minigame.MinigameProperties;
import com.example.ssafesta.minigame.SlotMachineProperties;
import com.example.ssafesta.minigame.TimerStopService;
import com.example.ssafesta.mission.DailyMission;
import com.example.ssafesta.storage.FakeObjectStorage;
import com.example.ssafesta.survey.SurveyResponseService;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.CoinAdminAdjustCommand;
import com.example.ssafesta.wallet.CoinCreditCommand;
import com.example.ssafesta.wallet.CoinReason;
import com.example.ssafesta.wallet.CoinSpendCommand;
import com.example.ssafesta.wallet.DailyCoinGrantService;
import com.example.ssafesta.wallet.LedgerEntryType;
import com.example.ssafesta.wallet.WalletService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BackendCrossDomainIntegrationTest {

    /** {@code application.yml} 의 {@code app.wallet.initial-grant}. */
    private static final int SIGNUP_GRANT = 200;
    /** {@code app.wallet.daily-grant} — 그날 첫 인증 요청에 붙는다. */
    private static final int DAILY_GRANT = 50;
    /** {@code app.lease.price-coin}. */
    private static final int LEASE_PRICE = 50;
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

    // ── 코인 경제 (S15P21A604-941) ──────────────────────────────────────────

    /**
     * 캡 세 갈래가 한 지갑에서 같은 날 돌 때 서로를 잠식하지 않는다.
     *
     * <p>일일 캡은 세 군데에 따로 있고 <b>각자 자기 사유 코드만</b> 센다 — 타이밍스톱은
     * {@code MINIGAME_REWARD}, 미션은 {@code DAILY_MISSION}, 슬롯은 캡이 없는 것이 확정값이다
     * (#205, RTP 0.68 싱크). 셋 다 자기 도메인 테스트에서 초록인데, 캡 산식이 사유 필터를 잃으면
     * — {@code grantedOnDateFor} 의 인자 하나다 — 미니게임 수입이 미션을 막고 그 세 테스트는
     * 전부 그대로 통과한다. 여기서만 드러난다.
     *
     * <p><b>캡 포화는 사유 코드를 직접 적립해서 만든다.</b> 캡의 정의가 "오늘 그 사유로 지급된
     * 합계" 이므로 적립이 곧 캡 상태다. 실제 플레이를 캡까지 반복하면 타이밍스톱 한 판이 2~4초라
     * (yml {@code target-min/max-seconds}) 열 판에 30초가 든다. 포화는 상태이고, 검증 대상은
     * 그 상태에서 <b>다른 도메인의 실제 행위</b>가 통과하는지다.
     */
    @Nested
    @DisplayName("코인 경제 — 세 캡이 한 지갑에서 같은 날 돈다")
    class EconomyCycle {

        /** {@code application.yml} 의 {@code app.minigame.slot-machine.machine-ids} 첫 항목. */
        private static final String SLOT_MACHINE = "plaza-slot-01";
        /** 받는 자 정보는 모든 구매에 필수다 (GitLab #239). */
        private static final String RECIPIENT =
                ",\"campus\":\"구미\",\"teamName\":\"A604\",\"recipientName\":\"황덕\"";
        private static final String SPIN = "/api/v1/minigames/slot-machines/{id}/spins";

        @Autowired private TimerStopService timerStop;
        @Autowired private MinigameProperties minigameProperties;
        @Autowired private SlotMachineProperties slotProperties;
        @Autowired private EventPrizeRepository prizes;
        @Autowired private EventPurchaseRepository purchases;
        @Autowired private DailyCoinGrantService dailyGrants;

        /**
         * 미니게임 캡이 가득 차 있어도 미션 보상은 그대로 나온다.
         *
         * <p>{@code earnedToday} 가 0 이라는 단언이 핵심이다 — 미니게임으로 받은 코인이 미션
         * 화면의 오늘 수령액에 섞이면 사용자는 받은 적 없는 보상을 받은 것으로 보게 되고, 그
         * 다음 claim 이 캡에 걸린다.
         */
        @Test
        @DisplayName("미니게임 캡이 가득 차도 미션 claim 은 통과한다")
        void aSaturatedMinigameCapDoesNotBlockTheMissionClaim() throws Exception {
            Long userId = member("캡교차미션");
            String bearer = bearerFor(userId);
            seed(userId, CoinReason.MINIGAME_REWARD, minigameProperties.dailyCapCoins());
            int balanceBefore = wallets.balanceOf(userId);

            mockMvc.perform(post("/api/v1/world-sessions").header("Authorization", bearer))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/api/v1/missions/daily").header("Authorization", bearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.dailyCap").value(DailyMission.DAILY_CAP_COIN))
                    .andExpect(jsonPath("$.earnedToday").value(0));

            mockMvc.perform(claim(bearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reward").value(DailyMission.REWARD_COIN));

            assertEquals(1, ledgerCount(userId, CoinReason.DAILY_MISSION),
                    "미션 원장은 한 건이어야 합니다");
            mockMvc.perform(claim(bearer))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ALREADY_CLAIMED"));
            assertEquals(1, ledgerCount(userId, CoinReason.DAILY_MISSION),
                    "거부된 재claim 이 원장을 늘리면 안 됩니다");

            assertEquals(balanceBefore + DailyMission.REWARD_COIN, wallets.balanceOf(userId));
            assertBalanceMatchesLedger(wallets, userId);
        }

        /**
         * 미션 캡이 가득 차 있어도 타이밍스톱 보상은 그대로 나온다.
         *
         * <p>반대 방향은 같은 사람·같은 날로 만들 수 없다 — 미션은 하루에 미션당 한 번씩만
         * claim 되므로 한 명이 두 캡을 차례로 포화시킬 수 없고, 시계를 넘기면 시간 의존이
         * 생긴다. 그래서 사용자를 나눈다.
         *
         * <p><b>이 테스트만 실제로 기다린다.</b> 보상을 받으려면 신고한 정지 시각이 서버 경과
         * 시각과 허용 오차 안에서 일치해야 하고({@code elapsed-tolerance-seconds}), 목표가
         * 2~4초라 목표 시각에 맞춰 멈추려면 그만큼 실제로 흘러야 한다. 비동기 상태를 폴링하는
         * 잠이 아니라 게임 규칙 자체가 요구하는 대기이고, 판은 한 번만 돈다.
         */
        @Test
        @DisplayName("미션 캡이 가득 차도 타이밍스톱 보상은 지급된다")
        void aSaturatedMissionCapDoesNotBlockTheMinigameReward() throws Exception {
            Long userId = member("캡교차미니");
            String bearer = bearerFor(userId);
            seed(userId, CoinReason.DAILY_MISSION, DailyMission.DAILY_CAP_COIN);

            // 진행도를 먼저 채운다 — 그래야 거부 사유가 NOT_COMPLETED 가 아니라 캡이다.
            mockMvc.perform(post("/api/v1/world-sessions").header("Authorization", bearer))
                    .andExpect(status().isOk());
            mockMvc.perform(claim(bearer))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DAILY_CAP_REACHED"));

            int balanceBefore = wallets.balanceOf(userId);
            TimerStopService.SessionIssued issued = timerStop.issue(userId);
            BigDecimal target = issued.targetSeconds();
            Thread.sleep(target.multiply(new BigDecimal("1000")).longValue());

            TimerStopService.SubmitResult result = timerStop.submit(userId, issued.sessionId(),
                    new TimerStopService.SubmitCommand(target));

            assertTrue(result.accepted(), "미션 캡은 미니게임 판정에 관여하지 않습니다");
            assertTrue(result.rewardedCoins() > 0,
                    "미션 캡이 미니게임 지급을 막았습니다 — 두 캡이 같은 합계를 세고 있습니다");
            assertEquals(minigameProperties.dailyCapCoins() - result.rewardedCoins(),
                    result.dailyRemainingCoins(),
                    "미니게임 잔여 한도가 미션 수령액만큼 깎였습니다");
            assertEquals(1, ledgerCount(userId, CoinReason.MINIGAME_REWARD));
            assertEquals(balanceBefore + result.rewardedCoins(), wallets.balanceOf(userId));
            assertBalanceMatchesLedger(wallets, userId);
        }

        /**
         * 슬롯은 두 캡 어느 쪽에도 잡히지 않고, 자기 사유 두 개로만 잔액을 움직인다.
         *
         * <p>슬롯이 캡 밖인 것은 확정값이다 ({@code SlotMachineService} 클래스 주석, #205) —
         * 지급 쪽에 캡을 씌우면 베팅만 나가고 돌아오는 것이 없어 이 기계가 사람을 마르게 하는
         * 유일한 경로가 된다. 그 확정이 지켜지는지는 <b>다른 두 캡의 잔여량이 그대로인지</b>로만
         * 드러난다.
         */
        @Test
        @DisplayName("슬롯은 두 캡 밖에서 자기 사유 두 개로만 잔액을 움직인다")
        void slotSpinsStayOutsideBothCapsAndUseOnlyTheirOwnTwoReasons() throws Exception {
            Long userId = member("슬롯캡밖");
            String bearer = bearerFor(userId);
            topUp(userId, 1_000);
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
            assertEquals(0, wallets.grantedOnDateFor(userId, CoinReason.MINIGAME_REWARD, today));
            assertEquals(0, wallets.grantedOnDateFor(userId, CoinReason.DAILY_MISSION, today));

            int bet = slotProperties.betCoins();
            int rounds = 20;
            int balanceBefore = wallets.balanceOf(userId);
            int paidOut = 0;
            int wins = 0;
            for (int round = 0; round < rounds; round++) {
                int payout = spinOnce(bearer, bet);
                paidOut += payout;
                if (payout > 0) {
                    wins++;
                }
            }

            assertEquals(0, wallets.grantedOnDateFor(userId, CoinReason.MINIGAME_REWARD, today),
                    "슬롯 지급이 미니게임 한도를 잠식했습니다 — 사유 코드가 섞였습니다");
            assertEquals(0, wallets.grantedOnDateFor(userId, CoinReason.DAILY_MISSION, today),
                    "슬롯 지급이 미션 한도를 잠식했습니다");
            assertEquals(balanceBefore + paidOut - bet * rounds, wallets.balanceOf(userId),
                    "슬롯 기인 잔액 변화는 지급 합계에서 베팅 합계를 뺀 값이어야 합니다");
            assertEquals(rounds, ledgerCount(userId, CoinReason.SLOT_BET),
                    "베팅은 판마다 한 건입니다");
            assertEquals(wins, ledgerCount(userId, CoinReason.SLOT_PAYOUT),
                    "지급 원장은 실제로 딴 판의 수와 같아야 합니다 — 0 코인 지급 행이 남으면 어긋납니다");
            assertBalanceMatchesLedger(wallets, userId);
        }

        /**
         * 한 사람의 하루가 사유 코드 일곱 개를 지나고, 원장이 그 산술과 맞는다.
         *
         * <p>잔액만 맞춰 보면 두 사유가 서로를 상쇄해도 통과한다 — 그래서 사유별 건수와
         * <b>등장한 사유의 집합</b>까지 본다. 집합을 보는 이유는 반대쪽이다: 아무도 부르지 않은
         * 사유가 끼어드는 것(중복 지급, 잘못된 보상 경로)은 건수표가 예상한 사유만 세는 한
         * 영원히 안 보인다.
         */
        @Test
        @DisplayName("하루를 한 바퀴 돌면 사유별 건수와 산술이 모두 맞는다")
        void oneDayThroughEveryReasonLeavesALedgerThatAddsUp() throws Exception {
            Long userId = member("경제일주");
            String bearer = bearerFor(userId);
            Long admin = administrator("경제일주운영");

            mockMvc.perform(get("/api/v1/wallets/me").header("Authorization", bearer))
                    .andExpect(status().isOk());
            int opening = wallets.balanceOf(userId);

            mockMvc.perform(post("/api/v1/world-sessions").header("Authorization", bearer))
                    .andExpect(status().isOk());
            mockMvc.perform(claim(bearer)).andExpect(status().isOk());

            int bet = slotProperties.betCoins();
            int spins = 5;
            int paidOut = 0;
            int wins = 0;
            for (int round = 0; round < spins; round++) {
                int payout = spinOnce(bearer, bet);
                paidOut += payout;
                if (payout > 0) {
                    wins++;
                }
            }

            EventPrize prize = prizes.saveAndFlush(new EventPrize("일주경품", 20, 3));
            long purchaseId = buy(bearer, userId, prize.getId());
            assertEquals(2, prizes.findById(prize.getId()).orElseThrow().getStock());

            mockMvc.perform(post("/api/v1/admin/event-shop/purchases/{id}/fulfillment", purchaseId)
                            .header("Authorization", bearerFor(admin))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"CANCELLED\",\"note\":\"일주 검증\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.fulfillment").value("CANCELLED"));
            assertEquals(3, prizes.findById(prize.getId()).orElseThrow().getStock(),
                    "취소는 재고를 되돌려야 합니다");

            assertEquals(1, ledgerCount(userId, CoinReason.INITIAL_GRANT));
            assertEquals(1, ledgerCount(userId, CoinReason.DAILY_GRANT));
            assertEquals(1, ledgerCount(userId, CoinReason.DAILY_MISSION));
            assertEquals(spins, ledgerCount(userId, CoinReason.SLOT_BET));
            assertEquals(wins, ledgerCount(userId, CoinReason.SLOT_PAYOUT));
            assertEquals(1, ledgerCount(userId, CoinReason.PRIZE_PURCHASE));
            assertEquals(1, ledgerCount(userId, CoinReason.PRIZE_REFUND),
                    "환불은 한 건입니다 — 구매당 한 번뿐인 멱등키가 그것을 지킵니다");

            Set<String> expected = new HashSet<>(List.of(CoinReason.INITIAL_GRANT,
                    CoinReason.DAILY_GRANT, CoinReason.DAILY_MISSION, CoinReason.SLOT_BET,
                    CoinReason.PRIZE_PURCHASE, CoinReason.PRIZE_REFUND));
            if (wins > 0) {
                expected.add(CoinReason.SLOT_PAYOUT);
            }
            assertEquals(expected, new HashSet<>(reasonsUsedBy(userId)),
                    "아무도 부르지 않은 사유가 원장에 들어왔거나, 불렀어야 할 사유가 빠졌습니다");

            // 산술: 미션 보상이 들어오고, 슬롯이 오간 만큼 움직이고, 구매와 환불이 상쇄된다.
            assertEquals(opening + DailyMission.REWARD_COIN + paidOut - bet * spins,
                    wallets.balanceOf(userId),
                    "구매와 환불이 상쇄되지 않았거나 슬롯 산술이 어긋났습니다");
            assertBalanceMatchesLedger(wallets, userId);
        }

        // ── 단계 ────────────────────────────────────────────────────────────

        /**
         * 그날 첫 인증 요청이 일일 지급을 붙인다 ({@code DailyCoinGrantInterceptor}) — 지급을
         * 먼저 떨어뜨리지 않으면 시작 잔액이 "몇 번째 호출에서 읽었는가" 에 따라 달라지고,
         * 이 아크의 산술은 전부 그 값에서 출발한다.
         */
        private Long member(String prefix) {
            Long userId = createMemberWithWallet(users, wallets, prefix);
            dailyGrants.grantIfDue(userId);
            return userId;
        }

        /**
         * 캡을 그 사유로 직접 적립해 채운다. 캡의 정의가 "오늘 그 사유로 지급된 합계" 라서
         * 이 적립이 곧 캡 상태다 ({@code MinigameRewardIntegrationTest} 가 쓰는 것과 같은 수).
         */
        private void seed(Long userId, String reason, int coins) {
            wallets.credit(new CoinCreditCommand(userId, LedgerEntryType.REWARD, coins, reason,
                    "TEST_SEED", String.valueOf(userId), reason + ":seed:" + UUID.randomUUID()));
        }

        private void topUp(Long userId, int coins) {
            wallets.adjustByAdmin(new CoinAdminAdjustCommand(userId, coins, "경제 아크 충전", userId,
                    "TEST_ECONOMY_TOPUP:" + UUID.randomUUID()));
        }

        private Long administrator(String prefix) {
            Long userId = createMemberWithWallet(users, wallets, prefix);
            jdbc.update("UPDATE users SET account_type='ADMIN' WHERE id=?", userId);
            return userId;
        }

        /** 목표가 1 이라 월드 입장 한 번으로 claim 가능해지는 미션이다. */
        private RequestBuilder claim(String bearer) {
            return post("/api/v1/missions/daily/{id}/claims", DailyMission.WORLD_ENTER.name())
                    .header("Authorization", bearer);
        }

        /** @return 이 판의 지급액. 잔액과 원장은 호출자가 합계로 본다 */
        private int spinOnce(String bearer, int bet) throws Exception {
            String body = mockMvc.perform(post(SPIN, SLOT_MACHINE)
                            .header("Authorization", bearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"bet\":" + bet + "}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            return read(body).get("payout").asInt();
        }

        private long buy(String bearer, Long buyerUserId, Long prizeId) throws Exception {
            mockMvc.perform(post("/api/v1/event-shop/purchases")
                            .header("Authorization", bearer)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"prizeId\":" + prizeId + ",\"quantity\":1" + RECIPIENT + "}"))
                    .andExpect(status().isCreated());
            return purchases.findAll().stream()
                    .filter(purchase -> purchase.getPrizeId().equals(prizeId)
                            && purchase.getBuyerUserId().equals(buyerUserId))
                    .findFirst().orElseThrow().getId();
        }

        private List<String> reasonsUsedBy(Long userId) {
            return jdbc.queryForList("""
                    SELECT DISTINCT e.reason_type FROM coin_ledger_entries e
                      JOIN wallets w ON w.id = e.wallet_id
                     WHERE w.user_id = ?
                    """, String.class, userId);
        }
    }

    // ── 임대 결제 (S15P21A604-941) ──────────────────────────────────────────

    /**
     * 지갑과 부스가 만나는 한 줄 — 임대료.
     *
     * <p>임대는 코인을 쓰는 유일한 부스 경로이고, 그 원장 행은 {@code BOOTH_LEASE} 참조로
     * 특정 임대를 가리킨다. 지갑 테스트는 차감이 맞는지 보고 부스 테스트는 임대가 생겼는지
     * 보는데, <b>그 둘이 같은 건을 가리키는지</b>는 참조 컬럼에만 있다. 참조가 어긋나면 부스
     * 대시보드가 남의 임대료를 자기 지출로 세고, 두 도메인 테스트는 그대로 초록이다.
     *
     * <p><b>멱등키는 클라이언트가 주지 않는다.</b> 서버가 leaseId 로 만든다
     * ({@code BoothLeaseService.charge}). {@code (user, slot)} 으로 잡으면 같은 사람이 같은
     * 자리를 나중에 다시 빌릴 때 두 번째 과금이 조용히 건너뛰어져 공짜 부스가 된다 — 그래서
     * 재시도 계약은 "살아 있는 임대에는 추가 과금 없음" 과 "종료 뒤 재임대는 다시 과금" 두 줄이다.
     *
     * <p><b>범위 밖.</b> 차감과 임대 저장 사이의 원자성은 여기서 보지 않는다. 실패 주입점이 없어
     * 테스트 전용 훅을 억지로 만들게 되므로, 트랜잭션 경계와 DB 제약을 확인한 뒤 따로 둔다.
     */
    @Nested
    @DisplayName("임대 결제 — 원장 한 줄이 임대 하나를 가리킨다")
    class LeasePayment {

        @Autowired private BoothLeaseService leaseService;
        @Autowired private BoothLeaseRepository leases;
        @Autowired private BoothRepository booths;
        @Autowired private DailyCoinGrantService dailyGrants;

        /** 임대가 끝나는 세 가지 — 원인만 다르고 원장에 대한 요구는 같다. */
        enum Termination { EXPIRY, RETURN, ADMIN_RELEASE }

        @Test
        @DisplayName("유료 임대는 그 임대를 가리키는 결제 원장 한 줄을 남긴다")
        void aPaidLeaseWritesExactlyOnePaymentBoundToItsLeaseId() throws Exception {
            Long userId = member("임대결제");
            String bearer = bearerFor(userId);
            int opening = wallets.balanceOf(userId);
            long slotId = firstAvailableRentalSlot();

            mockMvc.perform(lease(bearer, slotId))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.chargedCoin").value(LEASE_PRICE))
                    .andExpect(jsonPath("$.balanceAfter").value(opening - LEASE_PRICE));

            long leaseId = activeLeaseId(userId);
            assertEquals(1, ledgerCount(userId, CoinReason.LEASE_PAYMENT));
            assertEquals(1, countOf("""
                    SELECT count(*) FROM coin_ledger_entries e JOIN wallets w ON w.id = e.wallet_id
                     WHERE w.user_id = ? AND e.reason_type = ? AND e.reference_type = ?
                       AND e.reference_id = ? AND e.amount = ?
                    """, userId, CoinReason.LEASE_PAYMENT, CoinReason.LEASE_REFERENCE_TYPE,
                    String.valueOf(leaseId), -LEASE_PRICE),
                    "결제 원장이 방금 생긴 임대를 가리키지 않습니다 — 참조가 어긋나면 부스 지출 집계가 "
                            + "남의 임대료를 셉니다");
            assertEquals(opening - LEASE_PRICE, wallets.balanceOf(userId));
            assertBalanceMatchesLedger(wallets, userId);
        }

        /**
         * 살아 있는 임대에는 다시 과금하지 않고, 종료 뒤 같은 자리를 다시 빌리면 다시 과금한다.
         *
         * <p>두 줄을 한 테스트에 두는 이유는 이것이 <b>하나의</b> 계약이기 때문이다. 멱등키를
         * leaseId 가 아니라 {@code (user, slot)} 으로 잡으면 앞 줄은 그대로 통과하고 뒤 줄만
         * 깨진다 — 따로 두면 그 실패가 "재임대 테스트가 빨갛다" 로만 보이고 원인이 멱등키라는
         * 것은 드러나지 않는다.
         */
        @Test
        @DisplayName("살아 있는 임대는 재과금 없고, 종료 뒤 재임대는 다시 과금한다")
        void aRetryChargesNothingMoreButALaterLeaseOfTheSameSlotChargesAgain() throws Exception {
            Long userId = member("임대재시도");
            String bearer = bearerFor(userId);
            long slotId = firstAvailableRentalSlot();
            String first = mockMvc.perform(lease(bearer, slotId))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            long boothId = read(first).get("boothId").asLong();
            long firstLeaseId = activeLeaseId(userId);
            int afterFirst = wallets.balanceOf(userId);

            mockMvc.perform(lease(bearer, slotId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.boothId").value(boothId));

            assertEquals(1, ledgerCount(userId, CoinReason.LEASE_PAYMENT),
                    "살아 있는 임대에 재요청은 원장을 늘리면 안 됩니다");
            assertEquals(afterFirst, wallets.balanceOf(userId));

            mockMvc.perform(delete("/api/v1/booth-slots/{id}/leases/mine", slotId)
                            .header("Authorization", bearer))
                    .andExpect(status().isNoContent());
            mockMvc.perform(lease(bearer, slotId)).andExpect(status().isCreated());

            long secondLeaseId = activeLeaseId(userId);
            assertNotEquals(firstLeaseId, secondLeaseId);
            assertEquals(2, ledgerCount(userId, CoinReason.LEASE_PAYMENT),
                    "종료 뒤 재임대는 다시 과금되어야 합니다 — 멱등키가 leaseId 가 아니라 자리로 "
                            + "잡히면 두 번째가 조용히 공짜가 됩니다");
            assertEquals(1, countOf("""
                    SELECT count(*) FROM coin_ledger_entries e JOIN wallets w ON w.id = e.wallet_id
                     WHERE w.user_id = ? AND e.reason_type = ? AND e.reference_id = ?
                    """, userId, CoinReason.LEASE_PAYMENT, String.valueOf(secondLeaseId)));
            assertEquals(afterFirst - LEASE_PRICE, wallets.balanceOf(userId));
            assertBalanceMatchesLedger(wallets, userId);
        }

        @Test
        @DisplayName("잔액이 모자라면 임대도 원장도 남지 않고 자리는 비어 있다")
        void anUnaffordableLeaseLeavesNoLeaseNoLedgerAndTheSlotFree() throws Exception {
            Long userId = member("임대잔액부족");
            String bearer = bearerFor(userId);
            drainToZero(userId);
            long slotId = firstAvailableRentalSlot();
            int ledgerBefore = ledgerCount(userId, CoinReason.LEASE_PAYMENT);

            mockMvc.perform(lease(bearer, slotId))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INSUFFICIENT_COIN"));

            assertEquals(ledgerBefore, ledgerCount(userId, CoinReason.LEASE_PAYMENT));
            assertEquals(0, wallets.balanceOf(userId));
            assertTrue(leases.findValidBySlotId(slotId, Instant.now()).isEmpty(),
                    "거부된 임대가 자리를 잡고 있으면 안 됩니다");
            mockMvc.perform(get("/api/v1/booth-slots"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.slotId == " + slotId + ")].status").value("AVAILABLE"));
            assertBalanceMatchesLedger(wallets, userId);
        }

        /**
         * 관리자 임대는 0 코인 원장이 아니라 <b>원장 행 자체를 남기지 않는다</b>
         * ({@code BoothLeaseService} — 일어나지 않은 거래가 지갑 내역에 보이면 안 된다).
         */
        @Test
        @DisplayName("관리자 무상 임대는 원장 행을 아예 만들지 않는다")
        void anAdministratorsFreeLeaseWritesNoLedgerRowAtAll() throws Exception {
            Long adminId = member("무상임대운영");
            jdbc.update("UPDATE users SET account_type='ADMIN' WHERE id=?", adminId);
            int before = wallets.balanceOf(adminId);

            BoothLeaseService.LeaseOutcome outcome =
                    leaseService.lease(adminId, firstAvailableRentalSlot(), 1);

            assertEquals(0, outcome.lease().getChargedCoin());
            assertEquals(before, wallets.balanceOf(adminId));
            assertEquals(0, ledgerCount(adminId, CoinReason.LEASE_PAYMENT),
                    "0 코인 결제 행도 남기면 안 됩니다");
            assertBalanceMatchesLedger(wallets, adminId);
        }

        /**
         * 지갑이 기록한 그 결제가 부스 대시보드의 지출로 그대로 보인다.
         *
         * <p>집계는 사유와 참조 타입 <b>쌍</b>으로 걸린다 ({@code BoothDashboardService}). 한쪽만
         * 맞고 다른 쪽이 어긋나면 합계는 0 이 되는데, 빈 부스도 0 이라 대시보드 테스트만으로는
         * 구분되지 않는다 — 실제로 지불한 금액과 맞춰 보는 이 줄에서만 드러난다.
         */
        @Test
        @DisplayName("대시보드의 임대 지출이 지갑이 기록한 그 결제다")
        void theDashboardLeaseCostIsTheSamePaymentSeenFromTheBooth() throws Exception {
            Long userId = member("임대대시보드");
            String bearer = bearerFor(userId);
            String body = mockMvc.perform(lease(bearer, firstAvailableRentalSlot()))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            long boothId = read(body).get("boothId").asLong();

            mockMvc.perform(get("/api/v1/booths/{id}/dashboard/summary", boothId)
                            .header("Authorization", bearer)
                            .param("from", "2000-01-01T00:00:00Z")
                            .param("to", "2100-01-01T00:00:00Z"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.leaseCostCoin").value(LEASE_PRICE))
                    .andExpect(jsonPath("$.surveyRewardCoin").value(0));
        }

        /**
         * 임대가 어떻게 끝나든 결제 기록은 그대로 남고 환불은 없다 (FR-021).
         *
         * <p>세 가지를 한 메서드로 도는 이유는 원인만 다르고 요구가 같기 때문이다. 따로 쓰면 같은
         * 단언을 세 벌 유지하게 되고, 환불 정책이 바뀔 때 한 벌만 고쳐진다.
         *
         * <p>환불 없음은 <b>참조 기준</b>으로 단언한다 — {@code LEASE_REFUND} 사유 코드는 존재하지
         * 않으므로 사유로 세면 새 사유를 달고 들어오는 환불을 통째로 놓친다.
         */
        @ParameterizedTest(name = "{0}")
        @EnumSource(Termination.class)
        @DisplayName("임대가 어떻게 끝나도 결제는 남고 환불은 없다")
        void everyWayALeaseEndsLeavesThePaymentInPlaceAndRefundsNothing(Termination cause)
                throws Exception {
            Long userId = member("임대종료" + cause.ordinal());
            String bearer = bearerFor(userId);
            long slotId = firstAvailableRentalSlot();
            mockMvc.perform(lease(bearer, slotId)).andExpect(status().isCreated());
            long leaseId = activeLeaseId(userId);
            int afterLease = wallets.balanceOf(userId);

            terminate(cause, userId, bearer, slotId, leaseId);
            // 한 번 더 쓸어도 아무것도 움직이지 않는다 — 종료가 두 번 계산되면 여기서 드러난다.
            leaseService.expireStaleLeases();

            assertTrue(leases.findValidBySlotId(slotId, Instant.now()).isEmpty(),
                    "종료했으면 자리가 비어야 합니다");
            assertEquals(1, ledgerCount(userId, CoinReason.LEASE_PAYMENT),
                    "종료가 결제 기록을 지우거나 늘리면 안 됩니다");
            assertEquals(0, countOf("""
                    SELECT count(*) FROM coin_ledger_entries e JOIN wallets w ON w.id = e.wallet_id
                     WHERE w.user_id = ? AND e.reference_type = ? AND e.amount > 0
                    """, userId, CoinReason.LEASE_REFERENCE_TYPE),
                    "임대 종료는 환불하지 않습니다 (FR-021)");
            assertEquals(afterLease, wallets.balanceOf(userId));
            assertBalanceMatchesLedger(wallets, userId);
        }

        // ── 단계 ────────────────────────────────────────────────────────────

        private void terminate(Termination cause, Long userId, String bearer, long slotId,
                               long leaseId) throws Exception {
            switch (cause) {
                case EXPIRY -> {
                    jdbc.update("UPDATE booth_leases SET starts_at = now() - interval '25 hours', "
                            + "ends_at = now() - interval '1 hour' WHERE id = ?", leaseId);
                    leaseService.expireStaleLeases();
                }
                case RETURN -> mockMvc.perform(delete("/api/v1/booth-slots/{id}/leases/mine", slotId)
                                .header("Authorization", bearer))
                        .andExpect(status().isNoContent());
                case ADMIN_RELEASE -> {
                    Long adminId = member("회수운영" + userId);
                    jdbc.update("UPDATE users SET account_type='ADMIN' WHERE id=?", adminId);
                    leaseService.releaseByAdmin(adminId, slotId, "횡단 검증");
                }
            }
        }

        /** 일일 지급을 먼저 떨어뜨린다 — {@code EconomyCycle.member} 와 같은 이유다. */
        private Long member(String prefix) {
            Long userId = createMemberWithWallet(users, wallets, prefix);
            dailyGrants.grantIfDue(userId);
            return userId;
        }

        private long activeLeaseId(Long userId) {
            return leases.findValidByLesseeUserId(userId, Instant.now()).orElseThrow().getId();
        }

        /** 원장을 통해 비운다 — 직접 UPDATE 하면 잔액과 원장이 어긋난 채로 테스트가 시작된다. */
        private void drainToZero(Long userId) {
            wallets.spend(new CoinSpendCommand(userId, wallets.balanceOf(userId),
                    CoinReason.ADMIN_ADJUSTMENT, null, null, "TEST_LEASE_DRAIN:" + userId));
        }
    }
}
