package com.example.ssafesta.staff;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 같은 초대를 동시에 두 번 수락하고, 같은 대상을 동시에 두 번 초대한다 (S15P21A604-693).
 *
 * <p>둘 다 read-then-write 라 두 스레드가 사전 조회를 함께 통과하면 두 번째 INSERT 가
 * {@code booth_staffs_pkey}·{@code ux_staff_invitations_pending} 에 걸리고, 그것이 번역되지
 * 않으면 500 이다. 여기서 보는 것은 <b>성공 1 + 409 1, 500 0</b> 이다.
 *
 * <p><b>보조 증거다.</b> 사전 조회가 직렬화되면 두 번째 요청은 catch 를 밟지 않고 409 를 얻으므로
 * 이 테스트는 옛 코드에서도 초록일 수 있다. 핵심 회귀는
 * {@code StaffInvitationServiceTranslationTest} 가 저장소를 던지게 강제해 결정적으로 고정한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class StaffInvitationConcurrencyIntegrationTest {
    private static final int RACERS = 2;

    @Autowired private StaffInvitationService service;
    @Autowired private StaffInvitationRepository invitations;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private JdbcTemplate jdbc;

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
