package com.example.ssafesta.project;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.expireLease;
import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static com.example.ssafesta.booth.BoothTestSupport.releaseAllSlots;
import static org.hamcrest.Matchers.nullValue;
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
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/** 프로젝트 전시 등록·수정·조회 (spec 009 FR-001~FR-004·FR-008, contracts/project-api.md). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ProjectApiIntegrationTest {

    /** 계약이 정한 다섯 URL 필드. 한 곳에서 돌려 필드별 매핑이 빠지지 않게 한다. */
    private static final String[] URL_FIELDS =
            {"thumbnailUrl", "videoUrl", "deployUrl", "gitUrl", "portfolioUrl"};

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

    // ── 등록 (US1) ──────────────────────────────────────────────────────────

    /**
     * 안 보낸 URL 필드는 <b>키가 있고 값이 null</b>이다 (C-03, 불변식 I-4).
     *
     * <p>조건부로 키가 사라지면 클라이언트가 {@code undefined}와 {@code null}을 둘 다 다뤄야 한다.
     * {@code avatarCode}·{@code errors[]}가 같은 규칙을 쓴다.
     */
    @Test
    void registeringWithOnlyANameLeavesEveryUrlKeyPresentAndNull() throws Exception {
        Owner owner = leasedOwner("등록");

        var result = mockMvc.perform(create(owner, """
                        {"name": "SSAFY FESTA"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("SSAFY FESTA"))
                .andExpect(jsonPath("$.description").value(nullValue()));
        for (String field : URL_FIELDS) {
            result.andExpect(jsonPath("$." + field).value(nullValue()));
        }
        // value(nullValue()) 는 키가 없으면 실패한다 — doesNotExist() 를 쓰면 명시적 null 도
        // 통과해 버려 이 단언이 아무것도 지키지 못한다 (T-97).
    }

    /** 부스당 1개 (C-01). 두 번째 등록은 덮어쓰지 않고 거절한다 — Jira 완료 조건. */
    @Test
    void aSecondProjectOnTheSameBoothIsRefused() throws Exception {
        Owner owner = leasedOwner("중복");
        mockMvc.perform(create(owner, """
                {"name": "첫 번째"}""")).andExpect(status().isCreated());

        mockMvc.perform(create(owner, """
                        {"name": "두 번째"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_ALREADY_EXISTS"));
    }

    /**
     * 저장한 바이트가 그대로 돌아온다 (불변식 I-3).
     *
     * <p>스킴을 {@code HtTpS}로 쓴 것은 의도다. 판정은 대소문자를 무시하지만 값을 바꾸지는
     * 않으므로, 어딘가 정규화가 끼어들면 <b>이 케이스만</b> 잡아낸다.
     */
    @Test
    void storedUrlsRoundTripVerbatim() throws Exception {
        Owner owner = leasedOwner("왕복");
        // 다섯 값이 서로 달라야 한다. 하나만 넣으면 생성자에서 인자 두 개가 뒤바뀌어도
        // (thumbnailUrl <-> videoUrl) 아무 단언도 깨지지 않는다 — 7 개를 순서대로 받는
        // 생성자에서 가장 흔한 실수가 그것이다.
        mockMvc.perform(create(owner, """
                        {"name": "왕복",
                         "thumbnailUrl": "HtTpS://Thumb.Example.COM/t?q=1#f",
                         "videoUrl": "https://video.example.com/v",
                         "deployUrl": "http://deploy.example.com/d",
                         "gitUrl": "https://git.example.com/g",
                         "portfolioUrl": "https://folio.example.com/p"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.thumbnailUrl").value("HtTpS://Thumb.Example.COM/t?q=1#f"))
                .andExpect(jsonPath("$.videoUrl").value("https://video.example.com/v"))
                .andExpect(jsonPath("$.deployUrl").value("http://deploy.example.com/d"))
                .andExpect(jsonPath("$.gitUrl").value("https://git.example.com/g"))
                .andExpect(jsonPath("$.portfolioUrl").value("https://folio.example.com/p"));

        // 저장을 거쳐 다시 읽어도 같은 자리에 있어야 한다 — 위는 echo 라 매핑만 맞아도 통과한다.
        mockMvc.perform(list(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects[0].thumbnailUrl")
                        .value("HtTpS://Thumb.Example.COM/t?q=1#f"))
                .andExpect(jsonPath("$.projects[0].videoUrl").value("https://video.example.com/v"))
                .andExpect(jsonPath("$.projects[0].deployUrl").value("http://deploy.example.com/d"))
                .andExpect(jsonPath("$.projects[0].gitUrl").value("https://git.example.com/g"))
                .andExpect(jsonPath("$.projects[0].portfolioUrl")
                        .value("https://folio.example.com/p"));
    }

    /**
     * 편집자 가드는 <b>세 endpoint 전부</b>에 걸려 있다.
     *
     * <p>`PATCH` 만 검증하면 `POST`·`GET` 에서 가드를 빼먹어도 아무도 모른다 — 남의 부스에
     * 프로젝트를 만들어 넣거나 남의 저장값을 읽는 구멍이 조용히 열린다.
     */
    @Test
    void everyEndpointRefusesSomeoneElsesBooth() throws Exception {
        Owner mine = leasedOwner("내부스2");
        Owner stranger = leasedOwner("남부스2");
        String strangerToken = bearerFor(stranger.userId());

        mockMvc.perform(post("/api/v1/booths/{id}/projects", mine.boothId())
                        .header("Authorization", strangerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "남의 부스에 등록"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BOOTH_EDITOR_FORBIDDEN"));

        mockMvc.perform(get("/api/v1/booths/{id}/projects", mine.boothId())
                        .header("Authorization", strangerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BOOTH_EDITOR_FORBIDDEN"));
    }

    // ── 조회 (US1) ──────────────────────────────────────────────────────────

    /** 아직 안 만든 것은 오류가 아니다. 404 가 아니라 빈 배열이다 (C-01 파생 ⑵). */
    @Test
    void aBoothWithoutAProjectAnswersWithAnEmptyList() throws Exception {
        Owner owner = leasedOwner("빈목록");

        mockMvc.perform(list(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects").isArray())
                .andExpect(jsonPath("$.projects.length()").value(0));
    }

    // ── 수정 (US1) ──────────────────────────────────────────────────────────

    /**
     * {@code PATCH} 의 세 갈래 (C-06). 키 누락 = 유지, 명시적 {@code null} = 삭제, {@code {}} = 400.
     *
     * <p>{@code record} 로 받았다면 앞의 둘이 구분되지 않아 FE 직렬화 실수가 등록된 값을 지운다 —
     * 016 이 그 자리를 밟았다 (T-97).
     */
    @Test
    void patchDistinguishesAMissingKeyFromAnExplicitNull() throws Exception {
        Owner owner = leasedOwner("세갈래");
        Long projectId = createProject(owner, """
                {"name": "원본", "description": "설명", "videoUrl": "https://v.example.com"}""");

        // ① 키 누락 — description 은 그대로
        mockMvc.perform(patchProject(owner, projectId, """
                        {"videoUrl": "https://v2.example.com"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("설명"))
                .andExpect(jsonPath("$.videoUrl").value("https://v2.example.com"));

        // ② 명시적 null — 삭제. 키는 남는다
        mockMvc.perform(patchProject(owner, projectId, """
                        {"description": null}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value(nullValue()))
                .andExpect(jsonPath("$.videoUrl").value("https://v2.example.com"));

        // ③ 빈 본문 — 조용한 no-op 이 되지 않게 거절한다
        mockMvc.perform(patchProject(owner, projectId, "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** 다섯 필드 각각이 자기만 지워지고 나머지 넷을 건드리지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"thumbnailUrl", "videoUrl", "deployUrl", "gitUrl", "portfolioUrl"})
    void clearingOneUrlLeavesTheOtherFourAlone(String cleared) throws Exception {
        Owner owner = leasedOwner("삭" + cleared.charAt(0)); // 닉네임 VARCHAR(30) — prefix 는 짧게
        Long projectId = createProject(owner, """
                {"name": "삭제 대상", "thumbnailUrl": "https://t.example.com",
                 "videoUrl": "https://v.example.com", "deployUrl": "https://d.example.com",
                 "gitUrl": "https://g.example.com", "portfolioUrl": "https://p.example.com"}""");

        var result = mockMvc.perform(patchProject(owner, projectId, """
                        {"%s": null}""".formatted(cleared)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$." + cleared).value(nullValue()));
        for (String field : URL_FIELDS) {
            if (!field.equals(cleared)) {
                result.andExpect(jsonPath("$." + field).value(startsWithHttps()));
            }
        }
    }

    /** 권한은 프로젝트가 아니라 그것이 붙은 부스에 딸린다 — Jira 완료 조건. */
    @Test
    void anotherBoothsOwnerCannotEditThisProject() throws Exception {
        Owner mine = leasedOwner("내부스");
        Long projectId = createProject(mine, """
                {"name": "내 프로젝트"}""");
        Owner stranger = leasedOwner("남부스");

        mockMvc.perform(patch("/api/v1/projects/{id}", projectId)
                        .header("Authorization", bearerFor(stranger.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "가로채기"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BOOTH_EDITOR_FORBIDDEN"));
    }

    @Test
    void editingAProjectThatDoesNotExistSaysSo() throws Exception {
        Owner owner = leasedOwner("없는것");

        mockMvc.perform(patchProject(owner, 999_999L, """
                        {"name": "없음"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }

    // ── 만료 (FR-008) ───────────────────────────────────────────────────────

    /**
     * 만료된 부스는 <b>쓸 수 없지만 읽을 수 있다</b>.
     *
     * <p>편집 거부는 facade·homepage 와 같은 결이고, 읽기 허용은 FR-008(만료돼도 데이터 보존)의
     * 관측 가능한 형태다 — 보존해 놓고 읽을 수 없으면 보존을 확인할 방법이 없다.
     */
    @Test
    void anExpiredBoothCanStillBeReadButNotEdited() throws Exception {
        Owner owner = leasedOwner("만료");
        Long projectId = createProject(owner, """
                {"name": "보존 대상"}""");
        expireLease(jdbc, owner.boothId());

        mockMvc.perform(patchProject(owner, projectId, """
                        {"name": "수정 시도"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOTH_LEASE_EXPIRED"));

        mockMvc.perform(list(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects[0].name").value("보존 대상"));
    }

    // ── 게스트 (헌법 12조) ──────────────────────────────────────────────────

    @Test
    void guestsAreRefusedAtTheDoor() throws Exception {
        Owner owner = leasedOwner("게스트");
        Long projectId = createProject(owner, """
                {"name": "게스트가 못 건드릴 것"}""");
        String guest = "Bearer " + accessTokens.issueGuestToken().token();

        mockMvc.perform(post("/api/v1/booths/{id}/projects", owner.boothId())
                        .header("Authorization", guest)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "게스트"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_ONLY"));

        mockMvc.perform(get("/api/v1/booths/{id}/projects", owner.boothId())
                        .header("Authorization", guest))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_ONLY"));

        // 셋 다 봐야 한다. PATCH 가 빠져 있으면 남의 프로젝트를 고칠 수 있는 구멍이 나도
        // 이 테스트는 여전히 초록이다 — 게스트가 영속 자산을 못 갖는다는 헌법 12조는
        // endpoint 하나만 막아서는 성립하지 않는다.
        mockMvc.perform(patch("/api/v1/projects/{id}", projectId)
                        .header("Authorization", guest)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "게스트가 고침"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_ONLY"));
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    /**
     * 응답에서 id 를 꺼낸다.
     *
     * <p>문자열을 손으로 자르지 않는다 — 그렇게 하면 {@code projectId} 가 마지막 필드로 옮겨가는
     * 순간(뒤에 쉼표가 없다) 테스트가 파싱 오류로 죽고, 원인은 필드 순서라는 무관한 사실이 된다.
     */
    private Long createProject(Owner owner, String body) throws Exception {
        String json = mockMvc.perform(create(owner, body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return jsonMapper.readTree(json).get("projectId").asLong();
    }

    private RequestBuilder create(Owner owner, String body) {
        return post("/api/v1/booths/{id}/projects", owner.boothId())
                .header("Authorization", bearerFor(owner.userId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private RequestBuilder list(Owner owner) {
        return get("/api/v1/booths/{id}/projects", owner.boothId())
                .header("Authorization", bearerFor(owner.userId()));
    }

    private RequestBuilder patchProject(Owner owner, Long projectId, String body) {
        return patch("/api/v1/projects/{id}", projectId)
                .header("Authorization", bearerFor(owner.userId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static org.hamcrest.Matcher<String> startsWithHttps() {
        return org.hamcrest.Matchers.startsWith("https://");
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

    private record Owner(Long userId, Long boothId) { }
}
