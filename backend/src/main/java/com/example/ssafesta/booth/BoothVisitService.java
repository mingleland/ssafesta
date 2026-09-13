package com.example.ssafesta.booth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 부스 방문·체류 계측 (S15P21A604-240, GitLab #94).
 *
 * <p>발신자는 <b>React 호스트</b>다. Unity 는 Spring 에 아무 신호도 보내지 않으므로(2026-09-07
 * 확정), 부스 구역 진입·이탈을 브릿지 이벤트로 받은 React 가 이 경로를 부른다.
 *
 * <p><b>코인 흐름은 여기서 다시 적지 않는다.</b> 이미 {@code coin_ledger_entries} 에 사유 코드와
 * 함께 남고 있어서(헌법 20조), 같은 사실을 두 곳에 쓰면 둘이 갈리는 날이 온다. 부스별 코인
 * 집계의 모양은 `spec 015` contracts 가 서는 `S15P21A604-501` 의 몫이다.
 *
 * <p><b>{@code booth_daily_metrics} 도 아직 채우지 않는다.</b> 날짜별 선집계는 원본 스캔이 느려질
 * 때 얹는 것인데, 부스가 12개이고 방문 행이 그 규모다. 설문 집계에서 "원본에서 실시간 계산" 을
 * 고른 것과 같은 판단이다 — 느려지면 그때 얹는다.
 */
@Service
public class BoothVisitService {

    private final BoothVisitRepository visits;
    private final BoothAccessGuard accessGuard;

    public BoothVisitService(BoothVisitRepository visits, BoothAccessGuard accessGuard) {
        this.visits = visits;
        this.accessGuard = accessGuard;
    }

    /**
     * 방문자가 부스 구역에 들어왔다.
     *
     * <p>회원의 입장 신호가 두 번 오면 <b>새 행을 만들지 않는다</b> — 브릿지 이벤트는 재전송될 수
     * 있고 그때마다 행이 늘면 방문 수가 실제보다 커진다. 게스트는 식별자가 없어 합치지 못한다.
     */
    @Transactional
    public VisitView enter(Long boothId, Long visitorUserId, EnterCommand command) {
        // 방문자가 실제로 볼 수 있는 부스인지까지 본다 — 만료된 부스에 들어갔다는 기록은
        // 집계를 오염시킨다.
        accessGuard.requireVisitorVisible(boothId);
        String worldChannel = requireWorldChannel(command);

        if (visitorUserId != null) {
            var open = visits.findOpenVisit(boothId, visitorUserId);
            if (open.isPresent()) {
                return VisitView.of(open.get());
            }
        }
        return VisitView.of(visits.save(
                new BoothVisit(boothId, visitorUserId, worldChannel, Instant.now())));
    }

    /**
     * 방문자가 부스 구역을 떠났다.
     *
     * <p>남의 방문을 닫을 수 없다. 회원의 방문은 본인만, 게스트가 만든 방문은 아무도 닫지 못한다 —
     * 게스트에게는 그 행을 자기 것이라고 말할 식별자가 없다.
     */
    @Transactional
    public void exit(Long boothId, Long visitId, Long visitorUserId) {
        BoothVisit visit = visits.findById(visitId)
                .orElseThrow(() -> new ApiException(ErrorCode.BOOTH_NOT_FOUND, "그런 방문 기록이 없습니다."));
        boolean mine = visitorUserId != null
                && visitorUserId.equals(visit.getVisitorUserId())
                && boothId.equals(visit.getBoothId());
        if (!mine) {
            throw new ApiException(ErrorCode.FORBIDDEN, "내 방문 기록이 아닙니다.");
        }
        visit.exit(Instant.now());
    }

    /**
     * 부스 운영자용 집계.
     *
     * <p><b>플랫폼 전체 집계는 여기 없다.</b> 티켓 본문의 "관리자용" 은 전역 관리자 개념을 전제로
     * 하는데 그 권한 모델이 아직 미정이고 `docs/26` 에 결정 요청으로 올라가 있다
     * (`S15P21A604-165`). 부스 단위 집계는 기존 편집 권한으로 판정할 수 있어 먼저 연다.
     */
    @Transactional(readOnly = true)
    public MetricsView metrics(Long boothId, Long viewerUserId, Instant from, Instant to) {
        accessGuard.requireEditor(boothId, viewerUserId);
        if (!from.isBefore(to)) {
            throw ApiException.fieldInvalid("from", "조회 시작이 끝보다 앞서야 합니다.");
        }
        return rawMetrics(boothId, from, to);
    }

    /**
     * 게이트 없는 집계 — 이미 권한을 판정한 쪽이 쓴다 (부스 대시보드, {@code S15P21A604-501}).
     *
     * <p>패키지 밖으로 열지 않는다. 게이트를 건너뛸 수 있는 문을 공개 API 로 두면 언젠가 게이트 없이
     * 호출된다.
     */
    MetricsView rawMetrics(Long boothId, Instant from, Instant to) {
        Object[] row = visits.summarize(boothId, from, to);
        // 네이티브 집계는 [count, count, avg, count] 를 한 행으로 준다. 드라이버가 감싸는 방식이
        // 버전마다 달라 첫 원소가 다시 배열인 경우가 있어 풀어 본다.
        Object[] values = row.length == 1 && row[0] instanceof Object[] nested ? nested : row;
        return new MetricsView(from, to,
                asLong(values[0]), asLong(values[1]), asLong(values[2]), asLong(values[3]));
    }

    private static long asLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.longValue();
        }
        return ((Number) value).longValue();
    }

    private static String requireWorldChannel(EnterCommand command) {
        if (command == null || command.worldChannel() == null || command.worldChannel().isBlank()) {
            throw ApiException.fieldInvalid("worldChannel", "어느 채널에서 들어왔는지가 필요합니다.");
        }
        return command.worldChannel().trim();
    }

    /**
     * @param worldChannel 어느 월드 채널에서 들어왔는가. 채널이 여럿이라(2026-09-07 확정, 정원 20)
     *        같은 부스라도 채널별로 트래픽이 갈린다
     */
    public record EnterCommand(String worldChannel) { }

    public record VisitView(String visitId, Instant enteredAt) {

        static VisitView of(BoothVisit visit) {
            return new VisitView(String.valueOf(visit.getId()), visit.getEnteredAt());
        }
    }

    /**
     * @param uniqueVisitors <b>회원 기준</b>이다. 게스트는 식별자가 없어 각 방문이 따로 세어진다
     * @param averageDwellSeconds <b>닫힌 방문만</b>의 평균. 열린 방문은 {@code openVisits} 로 따로 본다
     */
    public record MetricsView(Instant from, Instant to, long visits, long uniqueVisitors,
                              long averageDwellSeconds, long openVisits) { }
}
