package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;

/** 부스 홈페이지 URL (spec 016 FR-001~FR-003, contracts/homepage-api.md). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BoothHomepageApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private BoothRepository booths;
    @Autowired private BoothStaffRepository staffs;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;

    @BeforeEach
    void freeSlots() {
        BoothTestSupport.releaseAllSlots(jdbc);
    }

    // ── 등록·수정·해제 (US2) ────────────────────────────────────────────────

    /**
     * Saved bytes are returned bytes (data-model §2).
     *
     * <p>The scheme is deliberately typed {@code HtTpS} — the check that accepts it is
     * case-insensitive, so a validator that normalised on the way in would still pass a plain
     * round-trip assertion and only this one catches it.
     */
    @Test
    void theOwnerRegistersAndTheValueRoundTripsVerbatim() throws Exception {
        Owner owner = leasedOwner("홈등록");
        String url = "HtTpS://My-Team.Example.COM/Path?q=1&r=2#frag";

        mockMvc.perform(homepageRequest(owner, body(url)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.homepageUrl").value(url));

        mockMvc.perform(get("/api/v1/booths/mine").header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.homepageUrl").value(url));
    }

    /** Editors are owner + staff, the same set facade and layout use (spec 005 FR-012). */
    @Test
    void registeredStaffMayRegisterToo() throws Exception {
        Owner owner = leasedOwner("스태프등록");
        Long staff = createMemberWithWallet(users, wallets, "스태프");
        staffs.save(new BoothStaff(owner.boothId(), staff, "STAFF"));

        mockMvc.perform(put("/api/v1/booths/{id}/homepage", owner.boothId())
                        .header("Authorization", bearerFor(staff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("https://staff.example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.homepageUrl").value("https://staff.example.com"));
    }

    /**
     * {@code http} passes on purpose (FR-002) — an asymmetry with the facade logo, which is https
     * only. The logo is embedded and dies silently as mixed content; a homepage is a destination and
     * the overlay can open it in a new tab (research R-04).
     */
    @Test
    void bothHttpAndHttpsPass() throws Exception {
        Owner owner = leasedOwner("스킴통과");

        for (String url : new String[] {"http://plain.example.com", "https://secure.example.com"}) {
            mockMvc.perform(homepageRequest(owner, body(url)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.homepageUrl").value(url));
        }
    }

    /**
     * The reason has to survive, not just the refusal.
     *
     * <p>None of these three has a host, so a validator that checked the host before the scheme
     * would reject them all as "malformed" and the owner would never learn that the scheme is the
     * problem. That is why the scheme rule runs first (data-model §3 #4 → #5).
     */
    @Test
    void aForbiddenSchemeIsRefusedForBeingAScheme() throws Exception {
        Owner owner = leasedOwner("스킴거부");

        for (String url : new String[] {"javascript:alert(1)", "data:text/html,hello", "ftp:notes.txt"}) {
            mockMvc.perform(homepageRequest(owner, body(url)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message")
                            .value("홈페이지 주소는 http 또는 https로 시작해야 합니다."));
        }
    }

    /** Relative, and absolute-but-hostless: both are shape problems, and say so. */
    @Test
    void aMalformedAddressIsRefusedAsMalformed() throws Exception {
        Owner owner = leasedOwner("형식거부");

        for (String url : new String[] {"/relative/path", "example.com", "http:///nohost", "ht tp://x.com"}) {
            mockMvc.perform(homepageRequest(owner, body(url)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value("홈페이지 주소 형식이 올바르지 않습니다."));
        }
    }

    /** 2048 is the V1 column width — one past it must not reach the database. */
    @Test
    void theLengthCeilingIsTwoThousandFortyEight() throws Exception {
        Owner owner = leasedOwner("길이경계");
        String prefix = "https://x.example.com/";
        String atLimit = prefix + "a".repeat(2048 - prefix.length());
        String overLimit = atLimit + "a";

        mockMvc.perform(homepageRequest(owner, body(atLimit)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.homepageUrl").value(atLimit));

        mockMvc.perform(homepageRequest(owner, body(overLimit)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("홈페이지 주소가 너무 깁니다. (최대 2048자)"));
    }

    /** Clearing is one gesture, not three. An emptied form field is a mistake, not an intent. */
    @Test
    void anEmptyStringIsNotAClear() throws Exception {
        Owner owner = leasedOwner("빈문자");
        mockMvc.perform(homepageRequest(owner, body("https://before.example.com")))
                .andExpect(status().isOk());

        mockMvc.perform(homepageRequest(owner, "{\"homepageUrl\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("홈페이지 주소를 입력해 주세요."));

        mockMvc.perform(get("/api/v1/booths/mine").header("Authorization", bearerFor(owner.userId())))
                .andExpect(jsonPath("$.homepageUrl").value("https://before.example.com"));
    }

    /**
     * The regression this endpoint exists to avoid.
     *
     * <p>A DTO that could not tell a missing key from an explicit {@code null} would read {@code {}}
     * as "clear it", so one client-side serialisation slip would silently delete a booth's page.
     * The refusal is what proves the command tracks presence.
     */
    @Test
    void aMissingFieldIsNotAClear() throws Exception {
        Owner owner = leasedOwner("필드부재");
        mockMvc.perform(homepageRequest(owner, body("https://keep.example.com")))
                .andExpect(status().isOk());

        mockMvc.perform(homepageRequest(owner, "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("homepageUrl 필드가 필요합니다."));

        mockMvc.perform(get("/api/v1/booths/mine").header("Authorization", bearerFor(owner.userId())))
                .andExpect(jsonPath("$.homepageUrl").value("https://keep.example.com"));
    }

    /** An explicit null is the one way back to "unregistered" (FR-009). */
    @Test
    void anExplicitNullClearsTheRegistration() throws Exception {
        Owner owner = leasedOwner("해제");
        mockMvc.perform(homepageRequest(owner, body("https://going.example.com")))
                .andExpect(status().isOk());

        assertPresentAndNull(mockMvc.perform(homepageRequest(owner, "{\"homepageUrl\":null}"))
                .andExpect(status().isOk()), "homepageUrl");

        assertPresentAndNull(mockMvc.perform(get("/api/v1/booths/mine")
                .header("Authorization", bearerFor(owner.userId()))), "homepageUrl");
    }

    /** #58's five-field envelope: the field name goes in {@code field}, never in {@code rule}. */
    @Test
    void theRejectionEnvelopeNamesTheField() throws Exception {
        Owner owner = leasedOwner("봉투");

        mockMvc.perform(homepageRequest(owner, body("javascript:alert(1)")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].rule").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value("homepageUrl"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("홈페이지 주소는 http 또는 https로 시작해야 합니다."));
    }

    // ── 권한·상태 ───────────────────────────────────────────────────────────

    @Test
    void anAnonymousCallerIsRefused() throws Exception {
        Owner owner = leasedOwner("미인증");

        mockMvc.perform(put("/api/v1/booths/{id}/homepage", owner.boothId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("https://anon.example.com")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aStrangerCannotRegister() throws Exception {
        Owner owner = leasedOwner("남의홈");
        Long stranger = createMemberWithWallet(users, wallets, "외부인홈");

        mockMvc.perform(put("/api/v1/booths/{id}/homepage", owner.boothId())
                        .header("Authorization", bearerFor(stranger))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("https://stranger.example.com")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BOOTH_EDITOR_FORBIDDEN"));
    }

    @Test
    void anUnknownBoothIsNotFound() throws Exception {
        Long member = createMemberWithWallet(users, wallets, "없는부스");

        mockMvc.perform(put("/api/v1/booths/{id}/homepage", 99_999_999L)
                        .header("Authorization", bearerFor(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("https://nowhere.example.com")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOTH_NOT_FOUND"));
    }

    /** Editing what nobody can see is refused, exactly as the facade is. */
    @Test
    void anExpiredBoothCannotRegister() throws Exception {
        Owner owner = leasedOwner("만료홈");
        BoothLayoutTestSupport.expireLease(jdbc, owner.boothId());

        mockMvc.perform(homepageRequest(owner, body("https://expired.example.com")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOTH_LEASE_EXPIRED"));
    }

    // ── 방문자 노출 게이트 (US1, FR-003) ────────────────────────────────────

    /**
     * "Public" means a published layout exists — the laptop only lives inside one, so the gate
     * opens exactly when a visitor could click it (research R-05).
     */
    @Test
    void anUnpublishedBoothHidesTheUrlFromVisitors() throws Exception {
        Owner owner = leasedOwner("미공개노출");
        mockMvc.perform(homepageRequest(owner, body("https://hidden.example.com")))
                .andExpect(status().isOk());

        ResultActions visitorView = mockMvc.perform(get("/api/v1/booths/{id}", owner.boothId()))
                .andExpect(status().isOk());
        assertPresentAndNull(visitorView, "publishedLayoutVersion"); // 게이트의 입력
        assertPresentAndNull(visitorView, "homepageUrl");
    }

    @Test
    void aPublishedBoothShowsTheUrlToVisitors() throws Exception {
        Owner owner = leasedOwner("공개노출");
        mockMvc.perform(homepageRequest(owner, body("https://shown.example.com")))
                .andExpect(status().isOk());
        publishLayout(owner);

        mockMvc.perform(get("/api/v1/booths/{id}", owner.boothId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.homepageUrl").value("https://shown.example.com"));
    }

    /** Unregistered reads the same as unpublished, so FE branches on one value (FR-009). */
    @Test
    void anUnregisteredPublishedBoothIsNullToo() throws Exception {
        Owner owner = leasedOwner("미등록공개");
        publishLayout(owner);

        assertPresentAndNull(mockMvc.perform(get("/api/v1/booths/{id}", owner.boothId()))
                .andExpect(status().isOk()), "homepageUrl");
    }

    /** The owner's own surface ignores the gate — otherwise the studio form cannot prefill (R-06). */
    @Test
    void theOwnersPrefillIgnoresTheGate() throws Exception {
        Owner owner = leasedOwner("프리필");
        mockMvc.perform(homepageRequest(owner, body("https://prefill.example.com")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/booths/mine").header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.homepageUrl").value("https://prefill.example.com"));
    }

    /**
     * The layout contract stays out of this (C-01).
     *
     * <p>Unity reads the published layout and must not learn the URL from it — display is the web
     * layer's (헌법 25조). Asserting on the whole body rather than one path is the point: it fails if
     * anyone ever threads the URL through {@code LayoutJson}.
     */
    @Test
    void thePublishedLayoutResponseCarriesNoUrl() throws Exception {
        Owner owner = leasedOwner("배치응답");
        mockMvc.perform(homepageRequest(owner, body("https://not-in-layout.example.com")))
                .andExpect(status().isOk());

        publishLayout(owner);

        mockMvc.perform(get("/api/v1/booths/{id}/layouts/published", owner.boothId()))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString("homepageUrl"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("not-in-layout"))));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static String body(String url) {
        return "{\"homepageUrl\":\"%s\"}".formatted(url);
    }

    /**
     * The key is there and its value is {@code null} — two assertions, deliberately.
     *
     * <p>{@code jsonPath(…).doesNotExist()} cannot express this: it is satisfied both by a missing
     * key and by an explicit {@code null}, so it would keep passing if the field ever stopped being
     * serialised. The contract says the key is always present and {@code null} carries the meaning
     * (homepage-api.md §3 — FE branches on one value), which makes the distinction the whole point.
     */
    private void assertPresentAndNull(ResultActions performed, String field) throws Exception {
        JsonNode body = jsonMapper.readTree(performed.andReturn().getResponse().getContentAsString());
        assertTrue(body.has(field),
                "%s 필드가 응답에 없습니다 — 값이 null 이어도 키는 있어야 합니다: %s".formatted(field, body));
        assertTrue(body.get(field).isNull(),
                "%s 는 null 이어야 합니다: %s".formatted(field, body.get(field)));
    }

    private RequestBuilder homepageRequest(Owner owner, String body) {
        return put("/api/v1/booths/{id}/homepage", owner.boothId())
                .header("Authorization", bearerFor(owner.userId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    /** 009 방문자 조회가 같은 게이트를 읽어서 {@code BoothLayoutTestSupport} 로 옮겼다. */
    private void publishLayout(Owner owner) throws Exception {
        BoothLayoutTestSupport.publishLayout(mockMvc, owner.boothId(), bearerFor(owner.userId()));
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
