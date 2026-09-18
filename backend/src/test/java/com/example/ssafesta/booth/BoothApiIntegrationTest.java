package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
    @Autowired private BoothRepository booths;
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

    /**
     * 슬롯 1은 이벤트 부스 자리다 (V28, S15P21A604-615 · GitLab #170).
     *
     * <p>두 단정이 한 테스트에 있는 것은 그 둘이 같은 사실이기 때문이다 — 목록의 {@code type}이
     * 임대 거절의 근거이고, 클라이언트는 슬롯 번호가 아니라 그 값을 읽는다. 표식만 확인하면
     * {@code isRentable()}이 {@code EVENT}를 통과시켜도 통과하고, 거절만 확인하면 Unity가 읽을
     * 값이 사라져도 통과한다.
     */
    @Test
    void theEventSlotIsMarkedAndCannotBeLeased() throws Exception {
        mockMvc.perform(get("/api/v1/booth-slots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slotId").value(1))
                .andExpect(jsonPath("$[0].type").value("EVENT"));

        Long userId = createMemberWithWallet(users, wallets, "이벤트슬롯");
        String bearer = bearerFor(userId);
        // 일일 지급은 인증 요청마다 일어난다 — 거절 전에 받아 두지 않으면 잔액 비교가 그 지급을
        // 거절 탓으로 읽는다 (같은 파일의 임대 성공 테스트와 같은 이유).
        mockMvc.perform(get("/api/v1/wallets/me").header("Authorization", bearer)).andExpect(status().isOk());
        int before = wallets.balanceOf(userId);
        long leasesBefore = leases.count();

        mockMvc.perform(leaseRequest(1L, bearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOTH_SLOT_NOT_RENTABLE"));

        assertEquals(leasesBefore, leases.count(), "이벤트 슬롯에 임대가 생기면 안 됩니다.");
        assertEquals(before, wallets.balanceOf(userId), "거절된 임대는 코인을 쓰지 않습니다.");
        BoothTestSupport.assertBalanceMatchesLedger(wallets, userId);
    }

    /**
     * 이벤트 슬롯에 활성 임대가 얹혀 있어도 시스템이 평소대로 돈다 (S15P21A604-615).
     *
     * <p>V28 이 적용되는 순간 1번 슬롯에 임대가 살아 있을 수 있고, 마이그레이션은 그것을 만료까지
     * 그대로 둔다. 여기서 확인하는 것은 <b>그 상태에서의 동작</b>이다 — 목록에 그대로 보이고,
     * 만료 판정이 평소대로 돌고, 만료 뒤 재임대만 거절된다.
     *
     * <p><b>마이그레이션 순서는 여기서 검증되지 않는다.</b> 테스트 DB 는 늘 V1부터 전부 적용된 뒤에
     * 시작하므로 이 임대는 V28 <i>뒤</i>에 생긴 것이고, V28 이 임대를 지우도록 고쳐도 이 테스트는
     * 통과한다(변이로 확인했다). 그 주장은 {@link EventSlotMigrationScopeTest} 가 마이그레이션
     * 파일의 범위로 고정한다.
     *
     * <p>조건부 UPDATE 로 만들지 않은 이유가 여기 있다 — 환경마다 결과가 달라지면 "슬롯 1 은
     * 이벤트다" 가 더 이상 계약이 아니게 된다.
     */
    @Test
    void anActiveLeaseOnTheEventSlotStillExpiresAndCannotBeRenewed() throws Exception {
        Long userId = createMemberWithWallet(users, wallets, "이전임대");
        Long boothId = booths.save(new Booth(userId, "이전임대 부스")).getId();
        BoothLayoutTestSupport.grantLeaseOnSlot(jdbc, boothId, userId, 1L);

        mockMvc.perform(get("/api/v1/booth-slots"))
                .andExpect(jsonPath("$[0].type").value("EVENT"))
                .andExpect(jsonPath("$[0].status").value("OCCUPIED"))
                .andExpect(jsonPath("$[0].boothId").value(boothId));

        BoothLayoutTestSupport.expireLease(jdbc, boothId);

        mockMvc.perform(get("/api/v1/booth-slots"))
                .andExpect(jsonPath("$[0].type").value("EVENT"))
                .andExpect(jsonPath("$[0].status").value("AVAILABLE"));

        // 비워졌다고 다시 빌릴 수 있는 것은 아니다 — 이제 이벤트 자리다.
        mockMvc.perform(leaseRequest(1L, bearerFor(createMemberWithWallet(users, wallets, "재임대시도"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOTH_SLOT_NOT_RENTABLE"));
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

    /**
     * The three ways a lease is refused all answer {@code 409}, so the status alone does not say
     * which rule fired. The code does, and the FE branches on it — "someone else has this room"
     * sends the visitor to another slot, "you already have a booth" does not. Asserted here as
     * well as in the two tests below so that swapping the three codes breaks the build
     * (S15P21A604-388).
     */
    /**
     * A member whose wallet is missing gets the same answer here as from {@code GET /wallets/me}.
     *
     * <p>The wallet is opened inside the member-creation transaction, so this is a broken state
     * rather than an expected one — but broken states still have to arrive as an answer the client
     * can read. Translating it in {@code WalletController} alone left this path reporting the
     * member's problem as the server's (T-113: a translation that lives in one controller is right
     * only in that controller).
     */
    @Test
    void leaseWithoutAWalletIsRefusedNotAnInternalError() throws Exception {
        Long userId = BoothTestSupport.createMember(users, "API임대무지갑");

        mockMvc.perform(leaseRequest(freeSlotId(), bearerFor(userId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WALLET_NOT_FOUND"));
    }

    @Test
    void leasingAnOccupiedSlotConflicts() throws Exception {
        Long owner = createMemberWithWallet(users, wallets, "API선점");
        Long other = createMemberWithWallet(users, wallets, "API후발");
        Long slotId = freeSlotId();
        leaseService.lease(owner, slotId, 1);

        mockMvc.perform(leaseRequest(slotId, bearerFor(other)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOTH_SLOT_ALREADY_LEASED"));
    }

    /**
     * A second booth for the same member is refused, and refused with <b>its own</b> code — not the
     * occupied-slot one. The slot asked for here is free, so a test that only read the status could
     * not tell the two rules apart (spec 004 FR-017, T-110).
     */
    @Test
    void aSecondBoothIsRefusedWithTheLeaseLimitCode() throws Exception {
        Long userId = createMemberWithWallet(users, wallets, "API두번째");
        String bearer = bearerFor(userId);
        mockMvc.perform(leaseRequest(freeSlotId(), bearer)).andExpect(status().isCreated());

        mockMvc.perform(leaseRequest(freeSlotId(), bearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_LEASE_LIMIT"));
    }

    /**
     * The {@code status} column is an operational switch — a room taken out of service is refused
     * before anything else is read, and the wallet is never touched.
     *
     * <p>The switch is put back in a {@code finally}: {@code releaseAllSlots} resets leases, not
     * slot status, and {@code freeSlotId()} filters on {@code slotType} only. A slot left off
     * would be handed to a later test as free and then refused.
     */
    @Test
    void aSlotTakenOutOfServiceIsRefusedAsNotRentable() throws Exception {
        Long userId = createMemberWithWallet(users, wallets, "API점검중");
        Long slotId = freeSlotId();
        long leasesBefore = leases.count();
        jdbc.update("UPDATE booth_slots SET status = 'MAINTENANCE' WHERE id = ?", slotId);
        try {
            mockMvc.perform(leaseRequest(slotId, bearerFor(userId)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("BOOTH_SLOT_NOT_RENTABLE"));
        } finally {
            jdbc.update("UPDATE booth_slots SET status = 'AVAILABLE' WHERE id = ?", slotId);
        }
        assertEquals(leasesBefore, leases.count(), "거부된 요청은 임대를 남기지 않습니다.");
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

    // -- 슬롯 점유 한 질의 (S15P21A604-682) ------------------------------------

    /**
     * 임대가 실린 슬롯은 부스 정보도 <b>함께</b> 실린다.
     *
     * <p>이것이 이 기능의 불변식이다. 예전에는 임대 목록과 부스를 따로 읽어서, 두 질의 사이에
     * 탈퇴가 커밋되면 {@code OCCUPIED} 인데 이름도 facade 도 없는 행이 나갔다 — 방문자에게는
     * 들어갈 수 없는 간판이 보였다. 지금은 셋이 한 문장에서 온다.
     *
     * <p><b>문장 수를 세지 않는다.</b> 그 방식은 {@code SessionFactory} 전역 카운터를 읽어서
     * 배경 스위퍼 하나만 늘어도 깨진다 (T-154). 여기서는 응답의 모양으로 고정한다.
     */
    @Test
    void anOccupiedSlotCarriesItsBoothInTheSameRow() throws Exception {
        Long userId = createMemberWithWallet(users, wallets, "점유조인");
        String bearer = bearerFor(userId);
        Long slotId = freeSlotId();
        mockMvc.perform(leaseRequest(slotId, bearer)).andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/booth-slots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.slotId == " + slotId + ")].status").value("OCCUPIED"))
                .andExpect(jsonPath("$[?(@.slotId == " + slotId + ")].boothId").isNotEmpty())
                .andExpect(jsonPath("$[?(@.slotId == " + slotId + ")].boothName").isNotEmpty())
                .andExpect(jsonPath("$[?(@.slotId == " + slotId + ")].facade").isNotEmpty())
                .andExpect(jsonPath("$[?(@.slotId == " + slotId + ")].entryAvailable").value(true));
    }

    /** 빈 슬롯은 부스 자리가 전부 비어 있다 — 조인이 왼쪽 바깥이라는 확인이기도 하다. */
    @Test
    void aFreeSlotCarriesNoBooth() throws Exception {
        Long slotId = freeSlotId();

        mockMvc.perform(get("/api/v1/booth-slots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.slotId == " + slotId + ")].status").value("AVAILABLE"))
                .andExpect(jsonPath("$[?(@.slotId == " + slotId + ")].boothId").value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$[?(@.slotId == " + slotId + ")].entryAvailable").value(false));
    }

    /**
     * 기한이 지난 임대는 행이 남아 있어도 빈 슬롯이다.
     *
     * <p>유효 판정이 조인 {@code on} 절로 들어갔으므로, 그 조건이 빠지면 만료된 임대가 다시
     * {@code OCCUPIED} 로 올라온다. 스위퍼가 옮겨 주기 전에도 비어 보여야 한다 (SC-003).
     */
    @Test
    void anExpiredLeaseLeavesTheSlotAvailable() throws Exception {
        Long userId = createMemberWithWallet(users, wallets, "만료조인");
        String bearer = bearerFor(userId);
        Long slotId = freeSlotId();
        mockMvc.perform(leaseRequest(slotId, bearer)).andExpect(status().isCreated());
        jdbc.update("UPDATE booth_leases SET starts_at = now() - interval '25 hours', "
                + "ends_at = now() - interval '1 hour' WHERE slot_id = ?", slotId);

        mockMvc.perform(get("/api/v1/booth-slots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.slotId == " + slotId + ")].status").value("AVAILABLE"))
                .andExpect(jsonPath("$[?(@.slotId == " + slotId + ")].boothId").value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())));
    }

    private org.springframework.test.web.servlet.RequestBuilder leaseRequest(Long slotId, String bearer) {
        return post("/api/v1/booth-slots/{slotId}/leases", slotId)
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"durationDays\":1}");
    }


    /**
     * 반납 한 바퀴 (spec 004 User Story 4, FR-020·FR-021).
     *
     * <p>단언이 여러 개인 이유가 있다. "반납한 사람이 다른 자리를 빌릴 수 있다" 만 보면
     * {@code detachSlot} 을 빠뜨린 구현도 통과한다 — 다른 자리를 빌 때 {@code attachSlot} 이
     * {@code current_slot_id} 를 덮어써 원래 자리가 덤으로 풀리기 때문이다. 해제를 실제로
     * 증명하는 것은 <b>다른 회원이 그 자리를 빌리는</b> 줄이다.
     */
    @Test
    void returningALeaseFreesTheSlotWithoutRefunding() throws Exception {
        Long userId = createMemberWithWallet(users, wallets, "반납");
        String bearer = bearerFor(userId);
        // 일일 지급을 먼저 받아 둔다 — 인증 요청이면 무엇이든 발동하므로, 여기서 받지 않으면
        // 아래 "잔액 불변" 단언이 지급분 때문에 흔들린다 (spec 003 FR-003).
        mockMvc.perform(get("/api/v1/wallets/me").header("Authorization", bearer)).andExpect(status().isOk());

        Long slotId = freeSlotId();
        mockMvc.perform(leaseRequest(slotId, bearer)).andExpect(status().isCreated());
        Long leaseId = leases.findValidByLesseeUserId(userId, Instant.now()).orElseThrow().getId();
        Long boothId = booths.findByOwnerUserIdAndAdminOwnedFalse(userId).orElseThrow().getId();
        int afterLease = wallets.balanceOf(userId);

        mockMvc.perform(delete("/api/v1/booth-slots/{slotId}/leases/mine", slotId)
                        .header("Authorization", bearer))
                .andExpect(status().isNoContent());

        // 만료가 아니라 반납으로 남는다 — 기록이 어느 쪽이었는지 말해야 한다.
        assertEquals(LeaseStatus.CANCELLED, leases.findById(leaseId).orElseThrow().getStatus());

        Booth booth = booths.findById(boothId).orElseThrow();
        assertEquals(null, booth.getCurrentSlotId(), "반납했으면 부스가 자리를 놓아야 합니다.");
        assertEquals(null, booth.getPublishedLayoutVersion(), "자리를 놓으면 공개본도 내려가야 합니다.");
        assertEquals(BoothStatus.INACTIVE, booth.getStatus());

        // 환불 없음 (FR-021). 잔액도 원장도 그대로다.
        assertEquals(afterLease, wallets.balanceOf(userId), "반납은 코인을 돌려주지 않습니다.");
        BoothTestSupport.assertBalanceMatchesLedger(wallets, userId);

        mockMvc.perform(get("/api/v1/booth-slots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.slotId == " + slotId + ")].status").value("AVAILABLE"));

        // 해제를 진짜로 증명하는 줄: 남이 그 자리를 빌 수 있어야 한다.
        mockMvc.perform(leaseRequest(slotId, bearerFor(createMemberWithWallet(users, wallets, "후임"))))
                .andExpect(status().isCreated());

        // 반납한 사람도 한도가 풀려 곧바로 다른 자리를 빌 수 있다 (D01).
        mockMvc.perform(leaseRequest(freeSlotId(), bearer)).andExpect(status().isCreated());
    }

    /** 반납할 것이 없는 세 경우가 한 코드로 온다 — 클라이언트가 할 일이 셋 다 같기 때문이다. */
    @Test
    void returningWhatYouDoNotHoldIs404AndLeavesTheOtherLeaseAlone() throws Exception {
        Long owner = createMemberWithWallet(users, wallets, "보유자");
        Long slotId = freeSlotId();
        mockMvc.perform(leaseRequest(slotId, bearerFor(owner))).andExpect(status().isCreated());

        // 남의 자리를 반납하려는 사람
        mockMvc.perform(delete("/api/v1/booth-slots/{slotId}/leases/mine", slotId)
                        .header("Authorization", bearerFor(createMemberWithWallet(users, wallets, "남"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACTIVE_LEASE_NOT_FOUND"));

        assertEquals(LeaseStatus.ACTIVE, leases.findValidByLesseeUserId(owner, Instant.now()).orElseThrow().getStatus(),
                "남의 요청이 보유자의 임대를 건드리면 안 됩니다.");

        // 두 번째 반납 — 이미 반납했으므로 같은 404 다. 오류가 아니라 새로고침 신호다.
        String bearer = bearerFor(owner);
        mockMvc.perform(delete("/api/v1/booth-slots/{slotId}/leases/mine", slotId).header("Authorization", bearer))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/booth-slots/{slotId}/leases/mine", slotId).header("Authorization", bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACTIVE_LEASE_NOT_FOUND"));
    }

    @Test
    void aGuestCannotReturnALease() throws Exception {
        Long owner = createMemberWithWallet(users, wallets, "게스트반납");
        Long slotId = freeSlotId();
        mockMvc.perform(leaseRequest(slotId, bearerFor(owner))).andExpect(status().isCreated());

        mockMvc.perform(delete("/api/v1/booth-slots/{slotId}/leases/mine", slotId)
                        .header("Authorization", "Bearer " + accessTokens.issueGuestToken().token()))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/booth-slots/{slotId}/leases/mine", slotId))
                .andExpect(status().isUnauthorized());

        assertEquals(LeaseStatus.ACTIVE, leases.findValidByLesseeUserId(owner, Instant.now()).orElseThrow().getStatus());
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
