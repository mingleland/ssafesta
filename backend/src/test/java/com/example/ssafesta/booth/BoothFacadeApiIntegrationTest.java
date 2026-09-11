package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Booth exterior (spec 005 FR-018, contracts/layout-api.md §6·§7). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BoothFacadeApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private BoothRepository booths;
    @Autowired private BoothSlotRepository slots;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void freeSlots() {
        BoothTestSupport.releaseAllSlots(jdbc);
    }

    @Test
    void theOwnerChangesTheFacadeAndVisitorsSeeIt() throws Exception {
        Owner owner = leasedOwner("외관수정");

        mockMvc.perform(facadeRequest(owner, """
                        {"themeCode":"SSAFY_BLUE","primaryColor":"#3B82F6","signText":"AI 프로젝트 전시관"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.themeCode").value("SSAFY_BLUE"));

        mockMvc.perform(get("/api/v1/booths/{id}", owner.boothId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facade.primaryColor").value("#3B82F6"))
                .andExpect(jsonPath("$.facade.signText").value("AI 프로젝트 전시관"))
                .andExpect(jsonPath("$.facade.logoUrl").doesNotExist());
    }

    @Test
    void aMalformedColourIsRefused() throws Exception {
        Owner owner = leasedOwner("색형식");

        mockMvc.perform(facadeRequest(owner, """
                        {"themeCode":"DEFAULT","primaryColor":"파랑"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /**
     * Lowercase in, uppercase out — the one place BE alters a value it was given
     * (contracts/layout-api.md §6).
     *
     * <p>Without it the same colour is stored two ways and a client comparing what it sent against
     * what came back concludes the save did not take.
     */
    @Test
    void aLowercaseColourIsStoredUppercase() throws Exception {
        Owner owner = leasedOwner("소문자색");

        mockMvc.perform(facadeRequest(owner, """
                        {"themeCode":"DEFAULT","primaryColor":"#3b82f6"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primaryColor").value("#3B82F6"));

        mockMvc.perform(get("/api/v1/booths/{id}", owner.boothId()))
                .andExpect(jsonPath("$.facade.primaryColor").value("#3B82F6"));
    }

    /** Well-formed hex, but not one of the twelve — a different mistake from "파랑", and told apart. */
    @Test
    void aColourOutsideThePaletteIsRefused() throws Exception {
        Owner owner = leasedOwner("팔레트밖");

        mockMvc.perform(facadeRequest(owner, """
                        {"themeCode":"DEFAULT","primaryColor":"#123456"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("팔레트에 없는 색입니다."));
    }

    /** The palette constrains which colour may be chosen, not whether one must be. */
    @Test
    void noColourIsStillAllowed() throws Exception {
        Owner owner = leasedOwner("색없음");

        mockMvc.perform(facadeRequest(owner, """
                        {"themeCode":"WARM","primaryColor":null}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.themeCode").value("WARM"))
                .andExpect(jsonPath("$.primaryColor").doesNotExist());
    }

    /**
     * All twelve, not a sample.
     *
     * <p>A palette is a table of literals and the failure mode is a single mistyped digit, which no
     * sampled test would catch — and the FE swatch that hits it would look broken for that one
     * colour only.
     */
    @Test
    void everyPaletteColourIsAccepted() throws Exception {
        Owner owner = leasedOwner("전체팔레트");

        for (Map.Entry<String, String> colour : FacadePalette.hexByCode().entrySet()) {
            mockMvc.perform(facadeRequest(owner, """
                            {"themeCode":"DEFAULT","primaryColor":"%s"}
                            """.formatted(colour.getValue())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.primaryColor").value(colour.getValue()));
        }

        assertEquals(12, FacadePalette.hexByCode().size(), "팔레트는 12색입니다.");
    }

    /** An http logo would be blocked as mixed content and simply not appear. */
    @Test
    void anInsecureLogoUrlIsRefused() throws Exception {
        Owner owner = leasedOwner("로고");

        mockMvc.perform(facadeRequest(owner, """
                        {"themeCode":"DEFAULT","logoUrl":"http://example.com/logo.png"}
                        """))
                .andExpect(status().isBadRequest());
    }

    /**
     * The eight characters {@code "https://"} — a scheme and nothing behind it.
     *
     * <p>The facade's own {@code startsWith("https://")} accepted this and stored it, while every
     * other URL field in the product refused it. That gap is why the rule moved into
     * {@link com.example.ssafesta.common.HttpUrlValidator}.
     */
    @Test
    void aLogoUrlWithoutAHostIsRejected() throws Exception {
        Owner owner = leasedOwner("호스트없는로고");

        mockMvc.perform(facadeRequest(owner, """
                        {"themeCode":"DEFAULT","logoUrl":"https://"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("logoUrl"));
    }

    @Test
    void anUnknownThemeIsRefused() throws Exception {
        Owner owner = leasedOwner("테마");

        mockMvc.perform(facadeRequest(owner, """
                        {"themeCode":"SPACE"}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aStrangerCannotChangeIt() throws Exception {
        Owner owner = leasedOwner("남의외관");
        Long stranger = createMemberWithWallet(users, wallets, "외부인");

        mockMvc.perform(put("/api/v1/booths/{id}/facade", owner.boothId())
                        .header("Authorization", bearerFor(stranger))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"themeCode\":\"MONO\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BOOTH_EDITOR_FORBIDDEN"));
    }

    @Test
    void anExpiredBoothCannotChangeIt() throws Exception {
        Owner owner = leasedOwner("만료외관");
        BoothLayoutTestSupport.expireLease(jdbc, owner.boothId());

        mockMvc.perform(facadeRequest(owner, "{\"themeCode\":\"MONO\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOTH_LEASE_EXPIRED"));
    }

    /**
     * 슬롯 목록이 같은 facade 를 싣는다 (S15P21A604-622, GitLab #171).
     *
     * <p>Unity 는 축제장에 들어서며 간판 12개를 한 번에 그린다. 이 필드가 없으면 목록 1회 +
     * 부스당 상세 1회로 최대 13요청이다.
     *
     * <p>부스 상세와 <b>같은 값</b>인지까지 보는 이유: 두 경로가 각자 조립하면 한쪽만 갱신되는
     * 날이 온다. 빈 슬롯이 {@code null} 인 것도 함께 본다 — 거기에 기본값이 들어가면 임대되지
     * 않은 자리에 간판이 선다.
     */
    @Test
    void theSlotListCarriesTheSameFacadeAndLeavesFreeSlotsNull() throws Exception {
        Owner owner = leasedOwner("목록외관");
        Long occupiedSlotId = booths.findById(owner.boothId()).orElseThrow().getCurrentSlotId();
        mockMvc.perform(facadeRequest(owner, """
                        {"themeCode":"MONO","primaryColor":"#3B82F6","signText":"목록 간판",
                         "logoUrl":"https://cdn.example.com/list.png"}"""))
                .andExpect(status().isOk());

        int occupied = orderedIndexOf(occupiedSlotId);
        int free = firstFreeIndex(occupiedSlotId);

        mockMvc.perform(get("/api/v1/booth-slots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[%d].boothId".formatted(occupied)).value(owner.boothId()))
                .andExpect(jsonPath("$[%d].facade.themeCode".formatted(occupied)).value("MONO"))
                .andExpect(jsonPath("$[%d].facade.primaryColor".formatted(occupied)).value("#3B82F6"))
                .andExpect(jsonPath("$[%d].facade.signText".formatted(occupied)).value("목록 간판"))
                .andExpect(jsonPath("$[%d].facade.logoUrl".formatted(occupied))
                        .value("https://cdn.example.com/list.png"))
                .andExpect(jsonPath("$[%d].status".formatted(free)).value("AVAILABLE"))
                .andExpect(jsonPath("$[%d].facade".formatted(free))
                        .value(org.hamcrest.Matchers.nullValue()));

        // 같은 값을 부스 상세도 낸다.
        mockMvc.perform(get("/api/v1/booths/{id}", owner.boothId()))
                .andExpect(jsonPath("$.facade.signText").value("목록 간판"))
                .andExpect(jsonPath("$.facade.themeCode").value("MONO"));
    }

    /**
     * 외관을 한 번도 손대지 않은 부스도 {@code facade} 객체를 받는다.
     *
     * <p>여기서 {@code null} 이 오면 클라이언트는 "빈 슬롯" 과 "기본 외관" 을 구별할 수 없다 —
     * 간판이 서지 않는다. 기본값은 부스 생성 시 {@code DEFAULT} 이고 나머지는 비어 있다.
     */
    @Test
    void anOccupiedSlotWithoutACustomFacadeStillCarriesTheDefault() throws Exception {
        Owner owner = leasedOwner("기본외관");
        int occupied = orderedIndexOf(booths.findById(owner.boothId()).orElseThrow().getCurrentSlotId());

        mockMvc.perform(get("/api/v1/booth-slots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[%d].status".formatted(occupied)).value("OCCUPIED"))
                .andExpect(jsonPath("$[%d].facade".formatted(occupied)).exists())
                .andExpect(jsonPath("$[%d].facade.themeCode".formatted(occupied)).value("DEFAULT"))
                .andExpect(jsonPath("$[%d].facade.signText".formatted(occupied))
                        .value(org.hamcrest.Matchers.nullValue()));
    }

    /**
     * 임대가 끝나면 그 자리는 빈 슬롯이고 옛 간판도 함께 사라진다.
     *
     * <p>`status` 는 `ends_at` 을 반영해 이미 `AVAILABLE` 로 나오는데, facade 만 남으면 월드에
     * <b>임차인이 없는 부스의 간판</b>이 선다. 부스 행은 콘텐츠 보존 때문에 그대로 살아 있으므로
     * (FR-010) 이 자리는 lease 유무로 갈려야 한다.
     */
    @Test
    void anExpiredLeaseLeavesTheSlotFreeAndHidesItsFacade() throws Exception {
        Owner owner = leasedOwner("만료외관목록");
        Long slotId = booths.findById(owner.boothId()).orElseThrow().getCurrentSlotId();
        mockMvc.perform(facadeRequest(owner, """
                        {"themeCode":"MONO","signText":"사라질 간판"}"""))
                .andExpect(status().isOk());
        BoothLayoutTestSupport.expireLease(jdbc, owner.boothId());

        int index = orderedIndexOf(slotId);
        mockMvc.perform(get("/api/v1/booth-slots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[%d].status".formatted(index)).value("AVAILABLE"))
                .andExpect(jsonPath("$[%d].boothName".formatted(index))
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[%d].facade".formatted(index))
                        .value(org.hamcrest.Matchers.nullValue()));
    }

    private int orderedIndexOf(Long slotId) {
        return slots.findAllOrdered().stream().map(BoothSlot::getId).toList().indexOf(slotId);
    }

    private int firstFreeIndex(Long occupiedSlotId) {
        var ordered = slots.findAllOrdered();
        for (int index = 0; index < ordered.size(); index++) {
            if (!ordered.get(index).getId().equals(occupiedSlotId)) {
                return index;
            }
        }
        throw new IllegalStateException("빈 슬롯이 없습니다.");
    }

    private org.springframework.test.web.servlet.RequestBuilder facadeRequest(Owner owner, String body) {
        return put("/api/v1/booths/{id}/facade", owner.boothId())
                .header("Authorization", bearerFor(owner.userId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
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
