package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.AccessTokenService;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** REST contract (spec 004 contracts/lease-api.md). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BoothApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private BoothLeaseService leaseService;
    @Autowired private BoothSlotRepository slots;
    @Autowired private BoothLeaseRepository leases;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private AccessTokenService accessTokens;
    @Autowired private UserRepository users;
    @Autowired private LeaseProperties properties;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void freeSlots() {
        BoothTestSupport.releaseAllSlots(jdbc);
    }

    @Test
    void anyoneCanBrowseTheSlots() throws Exception {
        mockMvc.perform(get("/api/v1/booth-slots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(7)))
                .andExpect(jsonPath("$[0].slotCode").value("F11-R01"))
                .andExpect(jsonPath("$[0].floorNo").value(11));
    }

    @Test
    void leasingReturns201WithTheChargeAndTheNewBalance() throws Exception {
        Long userId = createMemberWithWallet(users, wallets, "API임대");
        String bearer = bearerFor(userId);
        // Take the daily grant first: any authenticated request triggers it (spec 003 FR-003), so
        // reading the balance before that would be comparing against a figure the lease never saw.
        mockMvc.perform(get("/api/v1/wallets/me").header("Authorization", bearer)).andExpect(status().isOk());
        int before = wallets.balanceOf(userId);

        mockMvc.perform(post("/api/v1/booth-slots/{slotId}/leases", freeSlotId())
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"durationDays\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chargedCoin").value(properties.priceCoin()))
                .andExpect(jsonPath("$.balanceAfter").value(before - properties.priceCoin()))
                .andExpect(jsonPath("$.leaseId").isNumber())
                .andExpect(jsonPath("$.endsAt").isString());
    }

    @Test
    void aRetriedLeaseReturns200AndDoesNotChargeAgain() throws Exception {
        Long userId = createMemberWithWallet(users, wallets, "API재요청");
        Long slotId = freeSlotId();
        String bearer = bearerFor(userId);
        mockMvc.perform(leaseRequest(slotId, bearer)).andExpect(status().isCreated());
        int afterFirst = wallets.balanceOf(userId);

        mockMvc.perform(leaseRequest(slotId, bearer)).andExpect(status().isOk());

        assertEquals(afterFirst, wallets.balanceOf(userId));
    }

    @Test
    void aGuestCannotLease() throws Exception {
        long leasesBefore = leases.count();

        mockMvc.perform(post("/api/v1/booth-slots/{slotId}/leases", freeSlotId())
                        .header("Authorization", "Bearer " + accessTokens.issueGuestToken().token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"durationDays\":1}"))
                .andExpect(status().isForbidden());

        assertEquals(leasesBefore, leases.count(), "게스트가 임대를 만들면 안 됩니다.");
    }

    @Test
    void anUnauthenticatedLeaseIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/booth-slots/{slotId}/leases", freeSlotId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"durationDays\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aMultiDayTermIsRejected() throws Exception {
        Long userId = createMemberWithWallet(users, wallets, "API기간");

        mockMvc.perform(post("/api/v1/booth-slots/{slotId}/leases", freeSlotId())
                        .header("Authorization", bearerFor(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"durationDays\":7}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void leasingAnOccupiedSlotConflicts() throws Exception {
        Long owner = createMemberWithWallet(users, wallets, "API선점");
        Long other = createMemberWithWallet(users, wallets, "API후발");
        Long slotId = freeSlotId();
        leaseService.lease(owner, slotId, 1);

        mockMvc.perform(leaseRequest(slotId, bearerFor(other)))
                .andExpect(status().isConflict());
    }

    @Test
    void myBoothShowsTheLeaseAndRemainingTime() throws Exception {
        Long userId = createMemberWithWallet(users, wallets, "API내부스");
        Long slotId = freeSlotId();
        leaseService.lease(userId, slotId, 1);

        mockMvc.perform(get("/api/v1/booths/mine").header("Authorization", bearerFor(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.lease.slotId").value(slotId))
                .andExpect(jsonPath("$.lease.remainingSeconds").isNumber());
    }

    @Test
    void aMemberWithoutABoothGets204() throws Exception {
        Long userId = createMemberWithWallet(users, wallets, "API부스없음");

        mockMvc.perform(get("/api/v1/booths/mine").header("Authorization", bearerFor(userId)))
                .andExpect(status().isNoContent());
    }

    @Test
    void anExpiredBoothTellsTheVisitorWhy() throws Exception {
        Long userId = createMemberWithWallet(users, wallets, "API만료");
        Long slotId = freeSlotId();
        BoothLease lease = leaseService.lease(userId, slotId, 1).lease();
        mockMvc.perform(get("/api/v1/booths/{id}", lease.getBoothId())).andExpect(status().isOk());

        // Move both ends: booth_leases has CHECK(ends_at > starts_at), so pulling only the end
        // date back into the past violates it.
        jdbc.update("UPDATE booth_leases SET starts_at = now() - interval '25 hours', "
                + "ends_at = now() - interval '1 hour' WHERE id = ?", lease.getId());

        mockMvc.perform(get("/api/v1/booths/{id}", lease.getBoothId()))
                .andExpect(status().isConflict());
    }

    private org.springframework.test.web.servlet.RequestBuilder leaseRequest(Long slotId, String bearer) {
        return post("/api/v1/booth-slots/{slotId}/leases", slotId)
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"durationDays\":1}");
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }

    private Long freeSlotId() {
        Instant now = Instant.now();
        return slots.findAllOrdered().stream()
                .filter(slot -> slot.getSlotType() == SlotType.USER_RENTAL)
                .filter(slot -> leases.findValidBySlotId(slot.getId(), now).isEmpty())
                .map(BoothSlot::getId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("빈 USER_RENTAL 슬롯이 없습니다."));
    }
}
