package com.example.ssafesta.staff;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.booth.BoothStaffRepository;
import com.example.ssafesta.booth.StaffRole;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 같은 초대를 동시에 두 번 수락하고, 같은 대상을 동시에 두 번 초대한다 (S15P21A604-693).
 *
 * <p>둘 다 read-then-write 라 두 스레드가 사전 조회를 함께 통과할 수 있다. 여기서 보는 것은
 * <b>성공 1 + 409 1, 500 0</b> 이다. 진 쪽을 붙잡는 자리는 경로마다 다르다 — 초대는
 * {@code ux_staff_invitations_pending} 위반을 번역하고, 수락은 {@code seatIfAbsent} 가 돌려주는
 * 행 수 0 을 본다.
 *
 * <p><b>두 경합 테스트는 보조 증거다.</b> 사전 조회가 직렬화되면 두 번째 요청은 쓰기 경로를 밟지
 * 않고 409 를 얻으므로 옛 코드에서도 초록일 수 있다 — 2026-09-14 CI 에서 한 번 붉어진 것이 그
 * 틈이었다(GitLab #190). 그래서 {@link #writingOverATakenSeatChangesNothingAndReportsIt} 이 진
 * 쪽의 쓰기만 떼어 결정적으로 고정하고, {@code StaffInvitationServiceTranslationTest} 가 서비스가
 * 그 신호를 실제로 거절로 옮기는지 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class StaffInvitationConcurrencyIntegrationTest {
    private static final int RACERS = 2;

    @Autowired private StaffInvitationService service;
    @Autowired private StaffInvitationRepository invitations;
    @Autowired private BoothRepository booths;
    @Autowired private BoothStaffRepository staffs;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;

    @Test
    void acceptingTheSameInvitationTwiceAtOnceSeatsTheStaffOnceAndRefusesTheOther() throws Exception {
        Long ownerId = createMemberWithWallet(users, wallets, "경합수락소유");
        Long inviteeId = createMemberWithWallet(users, wallets, "경합수락대상");
        Long boothId = booths.save(new Booth(ownerId, "경합 부스")).getId();
        Long invitationId = invitations.save(
                new StaffInvitation(boothId, inviteeId, ownerId, StaffRole.CONSULTANT, Instant.now())).getId();

        Outcome outcome = race(() -> service.accept(invitationId, inviteeId));

        assertEquals(0, outcome.other(), "제약 위반이 번역되지 않고 새어 나왔다.");
        assertEquals(1, outcome.succeeded());
        assertEquals(RACERS - 1, outcome.refused().getOrDefault(ErrorCode.STAFF_ALREADY_MEMBER, new AtomicInteger()).get()
                + outcome.refused().getOrDefault(ErrorCode.STAFF_INVITATION_NOT_PENDING, new AtomicInteger()).get(),
                "진 쪽은 이미 구성원이거나 초대가 처리된 것이지 서버 오류가 아니다.");
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM booth_staffs WHERE booth_id = ? AND user_id = ?",
                Integer.class, boothId, inviteeId));
    }

    /**
     * 진 쪽의 쓰기만 떼어 내 결정적으로 고정한다 (GitLab #190).
     *
     * <p>위 경합 테스트는 사전 조회가 직렬화되면 쓰기 경로를 밟지 않아 옛 코드로도 초록이다. 실제로
     * 2026-09-14 CI 에서 한 번 붉어졌을 때 {@code succeeded} 가 2 였다 — 둘 다 성공했다는 뜻이다.
     *
     * <p>여기서는 승자가 커밋을 끝낸 뒤 패자가 하는 일, 즉 이미 찬 자리에 대고 쓰는 것만 부른다.
     * <b>역할을 다르게 주는 것이 핵심이다</b> — 옛 코드의 merge 는 UPDATE 를 내보내므로 자리를
     * 빼앗지는 않아도 역할을 덮어쓴다. 행 수만 보면 그 차이가 보이지 않는다.
     */
    @Test
    void writingOverATakenSeatChangesNothingAndReportsIt() {
        Long ownerId = createMemberWithWallet(users, wallets, "선점소유");
        Long inviteeId = createMemberWithWallet(users, wallets, "선점대상");
        Long boothId = booths.save(new Booth(ownerId, "선점 부스")).getId();

        // 각자의 트랜잭션에서 부른다 — 승자가 커밋을 끝낸 뒤 패자가 도착하는 그 순서다.
        assertEquals(1, seat(boothId, inviteeId, StaffRole.CONSULTANT), "빈 자리는 이 호출이 채운다");
        assertEquals(0, seat(boothId, inviteeId, StaffRole.ADMIN),
                "찬 자리에 대고 쓴 쪽은 0 을 받아야 한다 — 조용히 성공하면 둘 다 수락에 성공한다");

        assertEquals("CONSULTANT", jdbc.queryForObject(
                "SELECT role FROM booth_staffs WHERE booth_id = ? AND user_id = ?",
                String.class, boothId, inviteeId), "진 쪽이 역할을 덮어쓰지 않았다");
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM booth_staffs WHERE booth_id = ? AND user_id = ?",
                Integer.class, boothId, inviteeId));
    }

    @Test
    void invitingTheSameMemberTwiceAtOnceLeavesOnePendingInvitation() throws Exception {
        Long ownerId = createMemberWithWallet(users, wallets, "경합초대소유");
        Long inviteeId = createMemberWithWallet(users, wallets, "경합초대대상");
        String nickname = users.findById(inviteeId).orElseThrow().getNickname();
        Long boothId = booths.save(new Booth(ownerId, "경합 부스")).getId();

        Outcome outcome = race(() -> service.invite(boothId, ownerId,
                new StaffInvitationService.InviteCommand(nickname, "CONSULTANT")));

        assertEquals(0, outcome.other(), "제약 위반이 번역되지 않고 새어 나왔다.");
        assertEquals(1, outcome.succeeded());
        assertEquals(RACERS - 1, outcome.refused().getOrDefault(ErrorCode.STAFF_INVITATION_PENDING, new AtomicInteger()).get());
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM staff_invitations WHERE booth_id = ? AND invited_user_id = ? AND status = 'PENDING'",
                Integer.class, boothId, inviteeId));
    }

    /** {@code @Modifying} 은 트랜잭션을 요구한다. 요청 하나를 흉내 내므로 호출마다 새로 연다. */
    private int seat(Long boothId, Long userId, StaffRole role) {
        return new TransactionTemplate(txManager).execute(status ->
                staffs.seatIfAbsent(boothId, userId, role.name()));
    }

    private Outcome race(Runnable action) throws Exception {
        AtomicInteger succeeded = new AtomicInteger();
        Map<ErrorCode, AtomicInteger> refused = new ConcurrentHashMap<>();
        AtomicInteger other = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(RACERS);
        try (ExecutorService pool = Executors.newFixedThreadPool(RACERS)) {
            for (int i = 0; i < RACERS; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        action.run();
                        succeeded.incrementAndGet();
                    } catch (ApiException refusal) {
                        refused.computeIfAbsent(refusal.errorCode(), key -> new AtomicInteger()).incrementAndGet();
                    } catch (Exception exception) {
                        other.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            done.await();
        }
        return new Outcome(succeeded.get(), refused, other.get());
    }

    private record Outcome(int succeeded, Map<ErrorCode, AtomicInteger> refused, int other) { }
}
