package com.example.ssafesta.booth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * V28 은 슬롯 한 행의 종류만 바꾼다 — 그 위에 살아 있던 임대를 건드리지 않는다
 * (S15P21A604-615).
 *
 * <p><b>왜 파일을 읽는가.</b> "V28 적용 시점에 1번 슬롯에 있던 임대가 보존된다" 는 주장은
 * 마이그레이션이 <i>무엇을 하지 않는가</i>에 대한 것이다. 통합 테스트로는 잡히지 않는다 —
 * 테스트 DB 는 늘 V1부터 전부 적용된 뒤에 시작하므로, 그 안에서 만든 임대는 V28 <b>뒤</b>에
 * 생긴 것이라 V28 이 임대를 지우도록 고쳐도 그대로 통과한다. 실제로 그 변이를 걸어 확인했다.
 *
 * <p>V27 까지만 적용한 뒤 임대를 넣고 V28 을 돌리는 전용 하네스를 만들 수도 있지만, 검증하려는
 * 것이 단일 {@code UPDATE} 의 <b>범위</b>뿐이라 파일을 읽는 쪽이 같은 것을 더 작게 고정한다.
 * 상태 전이가 있는 마이그레이션이었다면 하네스 쪽이 맞다.
 *
 * <p>적용된 마이그레이션은 checksum 때문에 고칠 수 없으므로, 이 테스트가 실제로 무는 시점은
 * <b>병합 전</b>이다.
 */
class EventSlotMigrationScopeTest {

    private static final String MIGRATION = "/db/migration/V28__event_slot_type.sql";

    /** 임대·부스 표를 아예 언급하지 않는다. 언급이 없으면 지울 수도 비울 수도 없다. */
    @Test
    void theMigrationTouchesNothingButTheSlotRow() throws IOException {
        String sql = statementsOf(MIGRATION);

        assertFalse(sql.contains("booth_leases"),
                "V28 이 임대 표를 건드리면 적용 시점에 살아 있던 임대가 사라진다: " + sql);
        assertFalse(sql.contains("booths"),
                "V28 이 부스 표를 건드리면 임차인의 부스 연결이 끊긴다: " + sql);
        assertTrue(sql.contains("booth_slots"), "슬롯 종류를 바꾸는 것이 이 마이그레이션의 전부다: " + sql);
    }

    /** 구문은 {@code UPDATE} 하나다 — 지우거나 넣는 것이 없다. */
    @Test
    void theMigrationIsASingleUpdate() throws IOException {
        String sql = statementsOf(MIGRATION);
        List<String> statements = Arrays.stream(sql.split(";"))
                .map(String::trim)
                .filter(statement -> !statement.isEmpty())
                .toList();

        assertEquals(1, statements.size(), "구문이 늘면 범위가 늘어난 것이다: " + statements);
        assertTrue(statements.get(0).toUpperCase(Locale.ROOT).startsWith("UPDATE"),
                "UPDATE 하나여야 한다: " + statements.get(0));
    }

    /** 주석을 걷어낸 실행 구문만. 주석에는 표 이름이 설명으로 등장한다. */
    private static String statementsOf(String resource) throws IOException {
        try (InputStream source = EventSlotMigrationScopeTest.class.getResourceAsStream(resource)) {
            if (source == null) {
                throw new IllegalStateException(resource + " 가 클래스패스에 없습니다.");
            }
            String text = new String(source.readAllBytes(), StandardCharsets.UTF_8);
            return text.lines()
                    .map(line -> line.replaceFirst("--.*$", ""))
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .reduce("", (left, right) -> left.isEmpty() ? right : left + " " + right);
        }
    }
}
