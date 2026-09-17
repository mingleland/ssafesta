package com.example.ssafesta.project;

import com.example.ssafesta.storage.ObjectDeleteQueue;
import com.example.ssafesta.storage.StorageSweepTask;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 아무도 가리키지 않는 로고 객체를 걷어낸다 (GitLab #241).
 *
 * <p><b>grant 만료 정리만으로는 모자란다.</b> {@code complete} 까지 성공한 뒤 사용자가
 * {@code thumbnailUrl} 에 붙이지 않고 이탈하면, 검증까지 끝난 객체가 아무 참조 없이 남는다. 그래서
 * 세 갈래를 본다 — 만료된 {@code PENDING}, {@code FAILED}, 참조 없는 {@code READY}.
 *
 * <p>실행 지점은 둘이고 <b>새 스케줄러는 없다</b>. 하나는 {@link ProjectLogoService#start} 가 그 부스에
 * 대해 부르는 것이고, 다른 하나는 이미 도는 {@link ObjectDeleteQueue} 의 sweep 이 {@link StorageSweepTask}
 * 로 불러 주는 것이다. 앞의 것만 두면 <b>그 뒤로 아무도 업로드하지 않는 부스의 orphan 은 영원히
 * 검사되지 않는다</b>.
 *
 * <p><b>삭제의 최종 판정은 이 시점의 {@code thumbnail_url} 재조회다</b>
 * ({@link ProjectLogoRepository#findCollectable}). 참조가 끊긴 시각만 보고 지우면
 * A → B → 다시 A 로 되돌린 사용자의 살아 있는 이미지를 지운다.
 */
@Component
public class ProjectLogoCleanup implements StorageSweepTask {

    /**
     * 참조가 끊긴 뒤 이만큼은 남긴다.
     *
     * <p>되돌리기와 브라우저·CDN 캐시가 이 창을 쓴다. 즉시 지우면 방금 바꾼 것을 되돌린 사용자가
     * 깨진 이미지를 본다.
     */
    static final Duration UNREFERENCED_GRACE = Duration.ofMinutes(10);

    /** {@code complete} 뒤 이 시간까지 아무도 참조하지 않았으면 올리고 이탈한 것으로 본다. */
    static final Duration ORPHAN_AFTER = Duration.ofHours(24);

    /** 한 번에 도는 양. 남으면 다음 sweep 이 이어 받는다 — 긴 트랜잭션보다 여러 번이 낫다. */
    private static final int BATCH = 50;

    private final ProjectLogoRepository logos;
    private final ObjectDeleteQueue deleteQueue;

    public ProjectLogoCleanup(ProjectLogoRepository logos, ObjectDeleteQueue deleteQueue) {
        this.logos = logos;
        this.deleteQueue = deleteQueue;
    }

    @Override
    public void sweep() {
        collect(BATCH);
    }

    /**
     * 업로드 직전의 정리. 방금 실패한 업로드를 다시 시도하는 사람이 자기 실패분 때문에 상한에
     * 막히는 것이 가장 흔한 경로다.
     *
     * <p>부스로 좁히지 않고 같은 조회를 쓴다 — 조건이 하나면 두 경로가 갈라질 일이 없고, 배치 크기가
     * 작아 비용 차이도 없다.
     */
    void collectForBooth(Long boothId) {
        collect(BATCH);
    }

    /**
     * 좌표를 삭제 큐에 넣고 행을 지운다 — <b>순서가 이것이어야 한다.</b> 행을 먼저 지우면 좌표를 읽을
     * 곳이 없어져 객체가 영구히 남는다.
     *
     * <p>큐에 넣는 것은 평범한 INSERT 라 이 트랜잭션과 함께 커밋된다. 저장소 호출은 큐의 sweep 이
     * 트랜잭션 밖에서 하고, 그 삭제는 멱등이라 한 번 이상 실행돼도 안전하다.
     */
    @Transactional
    void collect(int limit) {
        Instant now = Instant.now();
        List<ProjectLogo> collectable = logos.findCollectable(now, now.minus(UNREFERENCED_GRACE),
                now.minus(ORPHAN_AFTER), limit);
        for (ProjectLogo logo : collectable) {
            deleteQueue.enqueue(logo.getProvider(), logo.getStorageBucket(), logo.getObjectKey());
            logos.delete(logo);
        }
    }
}
