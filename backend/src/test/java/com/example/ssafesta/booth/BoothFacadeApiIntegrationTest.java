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
