package com.example.ssafesta.game;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 오락기별 전 사용자 TOP 5 랭킹 (S15P21A604-963, GitLab #264).
 *
 * <p><b>바인딩을 보지 않는다.</b> 랭킹은 게임기에 귀속되므로 지금 무슨 게임이 걸렸는지는 상관이
 * 없고, 게임이 걸리지 않은 빈 자리도 과거 기록을 들고 있다. 유효한 오락기인지는 자리 배정과 같은
 * 화이트리스트({@link ArcadeProperties})가 판정한다 — 씬에 없는 번호를 통과시키면 아무도 갈 수
 * 없는 자리에 순위표가 쌓인다.
 *
 * <p><b>게임 공개 상태로도 막지 않는다.</b> 클라이언트는 게임을 플레이한 뒤에야 점수를 올리므로,
 * 그 사이 비공개로 바뀌었다고 방금 낸 기록을 버릴 이유가 없다.
 */
@Service
public class ArcadeRankingService {

    /** #264 — "조회 결과는 최대 5명". */
    static final int MAX_LIMIT = 5;

    private final ArcadeScoreRecordRepository records;
    private final ArcadeProperties properties;

    public ArcadeRankingService(ArcadeScoreRecordRepository records, ArcadeProperties properties) {
        this.records = records;
        this.properties = properties;
    }

    /**
     * 내 최고점을 등록하거나 갱신한다.
     *
     * <p>같거나 낮은 점수는 <b>오류가 아니다</b> — 클라이언트가 같은 결과를 다시 보낼 수 있어야
     * 하고, 무슨 일이 일어났는지는 {@link SubmitResult#updated()} 가 말한다.
     */
    @Transactional
    public SubmitResult submit(String machineId, Long userId, int score) {
        requireKnownMachine(machineId);
        boolean updated = records.upsertIfHigher(machineId, userId, score) > 0;
        // upsert 직후의 내 기록을 그대로 돌려준다 — 갱신됐든 아니든 클라이언트가 보여줄 값은
        // "지금 내 최고점" 하나다.
        MyScoreView mine = myRank(machineId, userId);
        return new SubmitResult(updated, mine.bestScore(), mine.achievedAt(), mine.rank());
    }

    /** 이 오락기의 TOP N. 기록이 없으면 빈 목록이다 — 없는 오락기와 구별되어야 한다. */
    @Transactional(readOnly = true)
    public List<RankEntry> top(String machineId, int limit) {
        requireKnownMachine(machineId);
        List<ArcadeScoreRecordRepository.ScoreRow> rows =
                records.findTop(machineId, PageRequest.of(0, clampLimit(limit)));
        List<RankEntry> entries = new ArrayList<>(rows.size());
        int rank = 1;
        for (ArcadeScoreRecordRepository.ScoreRow row : rows) {
            entries.add(new RankEntry(rank++, row.getNickname(), row.getBestScore(), row.getAchievedAt()));
        }
        return entries;
    }

    /** 내 최고점과 순위. 기록이 없으면 전 필드가 {@code null} 이다 — 404 를 쓰지 않는 이유는 §계약 참조. */
    @Transactional(readOnly = true)
    public MyScoreView me(String machineId, Long userId) {
        requireKnownMachine(machineId);
        return myRank(machineId, userId);
    }

    private MyScoreView myRank(String machineId, Long userId) {
        Optional<ArcadeScoreRecord> mine = records.findByMachineIdAndUserId(machineId, userId);
        if (mine.isEmpty()) {
            return new MyScoreView(null, null, null);
        }
        ArcadeScoreRecord record = mine.get();
        long ahead = records.countAhead(machineId, record.getBestScore(), record.getAchievedAt(), userId);
        return new MyScoreView(record.getBestScore(), record.getAchievedAt(), (int) ahead + 1);
    }

    /**
     * 씬에 없는 오락기는 거절한다.
     *
     * <p>{@code arcade_machine_bindings} 가 아니라 화이트리스트를 보는 것은 빈 자리와 없는 자리를
     * 구별해야 하기 때문이다 — 바인딩 표만 보면 {@code arcade-07}(아직 아무도 안 잡은 자리)과
     * {@code arcade-99}(존재하지 않는 자리)가 똑같이 비어 있다. {@link ArcadeSeatService} 가 같은
     * 목록을 같은 이유로 쓴다.
     */
    private void requireKnownMachine(String machineId) {
        if (!properties.knows(machineId)) {
            throw new ApiException(ErrorCode.MACHINE_NOT_FOUND);
        }
    }

    /**
     * 범위 밖 {@code limit} 은 조용히 1~5 로 당긴다.
     *
     * <p>거절하지 않는 것은 이 값이 표시 개수일 뿐이라 고쳐서 다시 보낼 것이 없기 때문이다.
     * 숫자가 아닌 값은 여기 오기 전에 타입 변환에서 400 으로 걸린다.
     */
    private static int clampLimit(int limit) {
        return Math.min(Math.max(limit, 1), MAX_LIMIT);
    }

    /** TOP 목록의 한 줄. */
    public record RankEntry(int rank, String nickname, int bestScore, Instant achievedAt) {
    }

    /**
     * 내 기록. 기록이 없으면 세 필드가 모두 {@code null} 이다 — 이 경로의 404 는 "그런 오락기가
     * 없다" 하나여야 클라이언트가 둘을 구별할 수 있다.
     */
    public record MyScoreView(Integer bestScore, Instant achievedAt, Integer rank) {
    }

    /**
     * 점수 등록의 응답 — 호출 뒤의 내 기록에 {@code updated} 를 얹은 것이다.
     *
     * @param updated 이 요청으로 최고점이 올라갔는가. 같거나 낮은 점수였으면 {@code false} 이고
     *                나머지 필드는 기존 기록 그대로다
     */
    public record SubmitResult(boolean updated, Integer bestScore, Instant achievedAt, Integer rank) {
    }
}
