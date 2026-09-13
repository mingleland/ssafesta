package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 슬롯 목록이 부스를 <b>같은 문장</b>에서 읽는다 (S15P21A604-682).
 *
 * <p>이 테스트가 따로 있는 이유는 {@code BoothApiIntegrationTest} 의 응답 모양 단정만으로는
 * <b>회귀를 막지 못하기 때문</b>이다. 임대를 읽고 부스를 따로 읽는 옛 구현으로 되돌려도 순차
 * 상태에서는 응답이 똑같아서 전부 통과한다. 깨지는 것은 두 문장 사이에 탈퇴가 커밋될 때뿐이고,
 * 그 창은 테스트로 재현하기 까다롭다 — 그래서 창이 <b>존재하지 않는다</b>는 쪽을 직접 잰다.
 *
 * <p>판정은 SQL 본문으로 거른다. 전역 문장 카운터를 읽으면 배경 스위퍼 하나에 흔들린다(T-154).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties =
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.example.ssafesta.booth.CapturingStatementInspector")
class BoothSlotOccupancySingleStatementTest {

    @Autowired private BoothQueryService query;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private BoothLeaseRepository leases;
    @Autowired private BoothSlotRepository slots;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void freeSlots() {
        BoothTestSupport.releaseAllSlots(jdbc);
    }

    @Test
    void listingSlotsReadsSlotsLeasesAndBoothsInOneStatement() {
        occupyOneSlot("단일문장");

        CapturingStatementInspector.clear();
        query.listSlots(null);

        List<String> slotReads = CapturingStatementInspector.matching("from booth_slots");
        assertEquals(1, slotReads.size(),
                "슬롯 목록은 한 문장이어야 한다. 실행된 문장: " + slotReads);

        String sql = slotReads.getFirst();
        assertTrue(sql.contains("booth_leases"),
                "임대가 같은 문장에 없다 — 따로 읽으면 두 스냅샷이 갈린다: " + sql);
        assertTrue(sql.contains("booths"),
                "부스가 같은 문장에 없다 — 이것이 없으면 OCCUPIED 인데 부스가 사라진 행이 나간다: " + sql);
    }

    /**
     * 부스를 <b>낱개로</b> 읽는 문장이 없다.
     *
     * <p>옛 구현의 흔적이 이것이다 — 임대마다 {@code select ... from booths where id=?} 를 한 번씩
     * 더 쐈다. 위 단정만 두면 조인을 넣은 채 낱개 조회를 남겨 둬도 통과하므로 함께 잠근다.
     */
    @Test
    void listingSlotsDoesNotLoadBoothsOneByOne() {
        occupyOneSlot("낱개조회");

        CapturingStatementInspector.clear();
        query.listSlots(null);

        List<String> boothReads = CapturingStatementInspector.matching("from booths");
        assertTrue(boothReads.isEmpty(),
                "부스를 따로 읽는 문장이 남아 있다: " + boothReads);
    }

    /**
     * 두 유효성 조건이 같은 뜻을 유지한다.
     *
     * <p>`findOccupancy` 의 조인 `on` 절과 `findAllValid` 가 각자 {@code ACTIVE and endsAt > now}
     * 를 적고 있다. 한쪽만 고치면 목록과 다른 판정이 갈리므로, 같은 fixture 에서 둘이 같은 슬롯
     * 집합을 가리키는지 잠근다.
     */
    @Test
    void theTwoValidityPredicatesAgree() {
        Long slotId = occupyOneSlot("판정일치");
        jdbc.update("UPDATE booth_leases SET starts_at = now() - interval '25 hours', "
                + "ends_at = now() - interval '1 hour' WHERE slot_id = ?", slotId);
        Instant now = Instant.now();

        List<Long> validSlotIds = leases.findAllValid(now).stream().map(BoothLease::getSlotId).toList();
        List<Long> occupiedSlotIds = slots.findOccupancy(now).stream()
                .filter(row -> row.lease() != null)
                .map(row -> row.slot().getId())
                .toList();

        assertEquals(validSlotIds, occupiedSlotIds,
                "두 질의의 유효 임대 판정이 갈렸다 — 한쪽만 고친 것이다");
    }

    private Long occupyOneSlot(String prefix) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(userId, prefix + " 부스")).getId();
        return grantLease(jdbc, boothId, userId);
    }
}
