package com.example.ssafesta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 컨테이너를 공유하게 된 뒤(GitLab #261) 격리를 실제로 {@link TestDataReset} 이 하고 있는지
 * 본다. 이 검증이 없으면 확장이 등록되지 않아도 조용히 통과하고, 그 사실은 한참 뒤에 다른
 * 테스트가 순서에 따라 깨질 때에야 드러난다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TestDataResetIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    /** 자동 등록(junit-platform.properties + META-INF/services)이 걸리지 않으면 0 이다. */
    @Test
    void theResetExtensionIsRegistered() {
        assertTrue(TestDataReset.resets > 0, "TestDataReset 이 한 번도 돌지 않았다 — 자동 등록 확인");
    }

    /** 앞 클래스가 무엇을 만들었든 이 클래스는 빈 테이블에서 시작한다. */
    @Test
    void testCreatedRowsAreGone() {
        assertEquals(0, count("users"));
        assertEquals(0, count("booths"));
        assertEquals(0, count("coin_ledger_entries"));
        assertEquals(0, count("games"));
    }

    /** 시드는 살아 있어야 한다 — 지웠다 다시 넣지 않기로 한 부분이다. */
    @Test
    void seedRowsSurvive() {
        assertEquals(12, count("booth_slots"), "부스 슬롯 12칸 (V5·V12)");
        assertEquals("EVENT", jdbc.queryForObject(
                "SELECT slot_type FROM booth_slots WHERE id = 1", String.class), "1번 방은 이벤트 자리 (V28)");
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM surveys WHERE survey_key = 'SSAFESTA_2026'", Integer.class),
                "이벤트 설문 시드 (V29)");
        assertTrue(count("catalog_items") > 0, "아바타 파츠 카탈로그");
    }

    /** 자리 상태도 되돌아와야 한다 — 임대가 사라졌는데 OCCUPIED 로 남으면 다음 클래스가 막힌다. */
    @Test
    void seededSlotsAreAvailableAgain() {
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM booth_slots WHERE status <> 'AVAILABLE'", Integer.class));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

}
