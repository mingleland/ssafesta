package com.example.ssafesta.consultation;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.booth.BoothTestSupport;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 상담 수락의 두 불변식을 <b>동시</b>에 친다 (spec 011 SC-001·SC-006, FR-007·FR-021).
 *
 * <p>순차로만 확인하면 읽고-판단하고-쓰는 구현도 통과한다. 두 요청이 같은 순간에 읽으면 둘 다
 * 통과하는 것이 이 기능의 위험이고, 그래서 판정을 DB 에 맡겼다 — 조건부 갱신(승자 하나)과
 * 부분 유니크 인덱스(직원당 활성 1건). 그 둘이 실제로 막는지는 동시에 던져야만 드러난다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ConsultationConcurrencyIntegrationTest {

    private static final int RACERS = 6;

    @Autowired private ConsultationService consultations;
    @Autowired private ConsultationRepository repository;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void freeSlots() {
        BoothTestSupport.releaseAllSlots(jdbc);
    }

    /**
     * 같은 요청에 여섯 직원이 동시에 손을 뻗어도 <b>한 명만</b> 가져간다 (SC-001).
     *
     * <p>진 쪽은 전부 {@code CONSULTATION_NOT_REQUESTED} 다 — "이미 누가 가져갔다" 와 "만료됐다"
     * 가 같은 답인 것은 의도다. 클라이언트가 할 일이 둘 다 "대기열을 다시 읽는다" 로 같다.
     */
    @Test
    void onlyOneStaffWinsTheSameRequest() throws Exception {
        Long boothId = leasedBooth("동시수락");
        List<Long> staff = staffOf(boothId, RACERS);
        Long requestId = requestFrom(boothId, "동시수락방문자");

        Outcome outcome = raceAccept(staff.stream().map(id -> new Attempt(requestId, id)).toList());

        assertEquals(1, outcome.succeeded(), "한 명만 수락해야 합니다. " + outcome);
        assertEquals(RACERS - 1, outcome.countOf(ErrorCode.CONSULTATION_NOT_REQUESTED), outcome.toString());
        assertEquals(1, activeCountOf(requestId), "수락된 상담은 하나입니다.");
    }

    /**
     * 한 직원이 서로 다른 두 요청을 동시에 잡아도 <b>하나만</b> 성립한다 (SC-006, FR-021).
     *
     * <p>진 쪽은 {@code CONSULTATION_ALREADY_ACTIVE} 다 — 요청은 멀쩡하고 그 직원이 더 받을 수
     * 없을 뿐이다. 실패한 쪽 요청은 {@code REQUESTED} 로 남아 다른 직원이 가져갈 수 있어야 한다.
     */
    @Test
    void aStaffMemberNeverHoldsTwoActiveConsultations() throws Exception {
        Long boothId = leasedBooth("동시두건");
        Long staffUserId = staffOf(boothId, 1).getFirst();
        List<Long> requestIds = new ArrayList<>();
        for (int index = 0; index < RACERS; index++) {
            requestIds.add(requestFrom(boothId, "동시두건방문자" + index));
        }

        Outcome outcome = raceAccept(requestIds.stream()
                .map(requestId -> new Attempt(requestId, staffUserId)).toList());

        assertEquals(1, outcome.succeeded(), "한 건만 잡혀야 합니다. " + outcome);
        assertEquals(RACERS - 1, outcome.countOf(ErrorCode.CONSULTATION_ALREADY_ACTIVE), outcome.toString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM consultations WHERE staff_user_id = ? AND status = 'ACCEPTED'",
                Integer.class, staffUserId));
        assertEquals(RACERS - 1, jdbc.queryForObject(
                "SELECT count(*) FROM consultations WHERE booth_id = ? AND status = 'REQUESTED'",
                Integer.class, boothId),
                "잡히지 않은 요청은 그대로 남아 다른 직원이 가져갈 수 있어야 합니다.");
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private Outcome raceAccept(List<Attempt> attempts) throws Exception {
        AtomicInteger succeeded = new AtomicInteger();
        Map<ErrorCode, AtomicInteger> refused = new ConcurrentHashMap<>();
        AtomicInteger other = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts.size());

        try (ExecutorService pool = Executors.newFixedThreadPool(attempts.size())) {
            for (Attempt attempt : attempts) {
                pool.submit(() -> {
                    try {
                        start.await();
                        consultations.accept(attempt.requestId(), attempt.staffUserId());
                        succeeded.incrementAndGet();
                    } catch (ApiException refusal) {
                        refused.computeIfAbsent(refusal.errorCode(), key -> new AtomicInteger())
                                .incrementAndGet();
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

    private Long leasedBooth(String prefix) {
        Long ownerId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(ownerId, prefix + " 부스")).getId();
        grantLease(jdbc, boothId, ownerId);
        return boothId;
    }

    private List<Long> staffOf(Long boothId, int howMany) {
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < howMany; index++) {
            Long userId = createMemberWithWallet(users, wallets, "직원" + boothId + "_" + index);
            jdbc.update("INSERT INTO booth_staffs(booth_id, user_id, role) VALUES(?, ?, 'CONSULTANT')",
                    boothId, userId);
            ids.add(userId);
        }
        return ids;
    }

    private Long requestFrom(Long boothId, String prefix) {
        Long visitorId = createMemberWithWallet(users, wallets, prefix);
        return Long.valueOf(consultations.request(visitorId,
                new ConsultationService.RequestCommand(boothId, null, null)).requestId());
    }

    private int activeCountOf(Long requestId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM consultations WHERE id = ? AND status = 'ACCEPTED'",
                Integer.class, requestId);
        return count == null ? 0 : count;
    }

    private record Attempt(Long requestId, Long staffUserId) { }

    private record Outcome(int succeeded, Map<ErrorCode, AtomicInteger> refused, int other) {

        int countOf(ErrorCode code) {
            AtomicInteger counter = refused.get(code);
            return counter == null ? 0 : counter.get();
        }

        @Override
        public String toString() {
            return "성공=" + succeeded + ", 거절=" + refused + ", 기타예외=" + other;
        }
    }
}
