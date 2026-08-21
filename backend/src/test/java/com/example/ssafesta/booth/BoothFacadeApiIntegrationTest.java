package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
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
                        {"themeCode":"SSAFY_BLUE","primaryColor":"#1677C8","signText":"AI 프로젝트 전시관"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.themeCode").value("SSAFY_BLUE"));

        mockMvc.perform(get("/api/v1/booths/{id}", owner.boothId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facade.primaryColor").value("#1677C8"))
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
