package com.example.ssafesta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * 실행된 SQL 을 문자열로 모은다 — <b>무엇을</b> 쐈는지 보려고 (S15P21A604-682).
 *
 * <p>{@code Statistics#getPrepareStatementCount()} 를 쓰지 않는 이유가 있다. 그 카운터는
 * {@code SessionFactory} 전역이라 배경 스위퍼가 문장을 하나만 쏴도 숫자가 어긋난다 — 실제로
 * 스위퍼가 여덟 번째로 붙었을 때 {@code ArcadeMachineSingleQueryTest} 가 그렇게 깨졌다(T-154).
 *
 * <p>여기서는 **SQL 본문으로 거른다.** {@code booth_slots} 를 읽는 문장만 세면 다른 표를 건드리는
 * 배경 작업은 애초에 집계에 들어오지 않는다. 같은 전역 수집이지만 판정이 텍스트에 매여 있어서
 * 관계없는 문장에 흔들리지 않는다.
 *
 * <p>등록은 프로퍼티로 한다 —
 * {@code spring.jpa.properties.hibernate.session_factory.statement_inspector}.
 *
 * <p>테스트 루트 패키지에 둔 이유는 소비자가 도메인을 넘기 때문이다. 처음에는 {@code booth}
 * 테스트에만 있었는데(S15P21A604-682), 같은 취약성이 {@code game} 쪽에도 있어
 * (S15P21A604-685) 두 곳이 같은 도구를 쓴다.
 */
public class CapturingStatementInspector implements StatementInspector {

    private static final List<String> CAPTURED = Collections.synchronizedList(new ArrayList<>());

    @Override
    public String inspect(String sql) {
        CAPTURED.add(sql.toLowerCase(Locale.ROOT));
        return sql;
    }

    public static void clear() {
        CAPTURED.clear();
    }

    /** 그 조각을 포함한 문장들. 소문자로 비교하므로 조각도 소문자로 준다. */
    public static List<String> matching(String fragment) {
        synchronized (CAPTURED) {
            return CAPTURED.stream().filter(sql -> sql.contains(fragment)).toList();
        }
    }
}
