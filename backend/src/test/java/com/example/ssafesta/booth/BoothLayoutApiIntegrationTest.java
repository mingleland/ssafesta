package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothLayoutTestSupport.saveRequest;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.AccessTokenService;
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

/** REST contract (spec 005 contracts/layout-api.md §2~§5). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BoothLayoutApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private BoothLayoutService layouts;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private AccessTokenService accessTokens;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void freeSlots() {
        BoothTestSupport.releaseAllSlots(jdbc);
    }

    @Test
    void anUneditedBoothHasNoDraft() throws Exception {
        Owner owner = leasedOwner("초안없음API");

        mockMvc.perform(get(draftPath(owner.boothId())).header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isNoContent());
    }

    @Test
    void savingReturnsTheNewRevision() throws Exception {
        Owner owner = leasedOwner("저장API");

        mockMvc.perform(put(draftPath(owner.boothId()))
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveRequest(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.objects[0].objectId").value("screen-1"));
    }

    /** SC-003, the core of it: saving is not publishing. */
    @Test
    void aSavedButUnpublishedBoothServesNoLayout() throws Exception {
        Owner owner = leasedOwner("미공개API");
        layouts.saveDraft(owner.boothId(), owner.userId(), saveRequest(0));

        mockMvc.perform(get(publishedPath(owner.boothId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LAYOUT_NOT_PUBLISHED"));
    }

    @Test
    void aVisitorReadsThePublishedLayoutWithoutAToken() throws Exception {
        Owner owner = leasedOwner("공개API");
        layouts.saveDraft(owner.boothId(), owner.userId(), saveRequest(0));
        layouts.publish(owner.boothId(), owner.userId());

        mockMvc.perform(get(publishedPath(owner.boothId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.template").value("PROJECT_EXHIBITION"))
                .andExpect(jsonPath("$.objects[0].configId").value(152));
    }

    @Test
    void aStaleRevisionConflictsAndSaysWhereTheServerIs() throws Exception {
        Owner owner = leasedOwner("충돌API");
        layouts.saveDraft(owner.boothId(), owner.userId(), saveRequest(0));

        mockMvc.perform(put(draftPath(owner.boothId()))
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveRequest(0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAYOUT_REVISION_CONFLICT"))
                .andExpect(jsonPath("$.errors[0].rule").value("CURRENT_REVISION"));
    }

    @Test
    void someoneElsesBoothIsNotEditable() throws Exception {
        Owner owner = leasedOwner("남의부스");
        Long stranger = createMemberWithWallet(users, wallets, "타인");

        mockMvc.perform(put(draftPath(owner.boothId()))
                        .header("Authorization", bearerFor(stranger))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveRequest(0)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BOOTH_EDITOR_FORBIDDEN"));
    }

    @Test
    void aGuestCannotEdit() throws Exception {
        Owner owner = leasedOwner("게스트편집");

        mockMvc.perform(put(draftPath(owner.boothId()))
                        .header("Authorization", "Bearer " + accessTokens.issueGuestToken().token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveRequest(0)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aMissingBoothIs404() throws Exception {
        Long userId = createMemberWithWallet(users, wallets, "없는부스");

        mockMvc.perform(get(draftPath(9_999_999L)).header("Authorization", bearerFor(userId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOTH_NOT_FOUND"));
    }

    @Test
    void anExpiredBoothRefusesItsPublishedLayout() throws Exception {
        Owner owner = leasedOwner("만료API");
        layouts.saveDraft(owner.boothId(), owner.userId(), saveRequest(0));
        layouts.publish(owner.boothId(), owner.userId());
        BoothLayoutTestSupport.expireLease(jdbc, owner.boothId());

        mockMvc.perform(get(publishedPath(owner.boothId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOTH_LEASE_EXPIRED"));
    }

    /** FR-011: the owner keeps working on preserved content even after the lease ends. */
    @Test
    void anExpiredBoothStillOpensItsDraftForTheOwner() throws Exception {
        Owner owner = leasedOwner("만료초안");
        layouts.saveDraft(owner.boothId(), owner.userId(), saveRequest(0));
        BoothLayoutTestSupport.expireLease(jdbc, owner.boothId());

        mockMvc.perform(get(draftPath(owner.boothId())).header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isOk());
    }

    @Test
    void publishingReportsTheVersion() throws Exception {
        Owner owner = leasedOwner("공개응답");
        layouts.saveDraft(owner.boothId(), owner.userId(), saveRequest(0));

        mockMvc.perform(post(publishPath(owner.boothId())).header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.publishedAt").isString());
    }

    private Owner leasedOwner(String prefix) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(userId, prefix + " 부스")).getId();
        grantLease(jdbc, boothId, userId);
        return new Owner(userId, boothId);
    }

    private String draftPath(Long boothId) {
        return "/api/v1/booths/" + boothId + "/layouts/draft";
    }

    private String publishPath(Long boothId) {
        return "/api/v1/booths/" + boothId + "/layouts/publish";
    }

    private String publishedPath(Long boothId) {
        return "/api/v1/booths/" + boothId + "/layouts/published";
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }

    private record Owner(Long userId, Long boothId) { }
}
