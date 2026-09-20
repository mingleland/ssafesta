package com.example.ssafesta.project;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 로고 업로드 행. 바이트는 객체 저장소에 있고 여기엔 없다 (GitLab #241). */
public interface ProjectLogoRepository extends JpaRepository<ProjectLogo, Long> {

    Optional<ProjectLogo> findByLogoId(String logoId);

    /**
     * 한 행을 트랜잭션 끝까지 잠근다 ({@code SELECT ... FOR UPDATE}).
     *
     * <p>{@code complete} 가 이것을 지나므로 동시에 온 두 호출이 둘 다 {@code PENDING} 을 읽고 둘 다
     * 검증을 돌리지 못한다. 뒤에 온 쪽은 기다린 뒤 {@code READY} 를 보고 앞선 결과를 돌려준다 —
     * 그것이 {@code complete} 를 "대체로 멱등" 이 아니라 멱등으로 만든다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM ProjectLogo l WHERE l.logoId = :logoId")
    Optional<ProjectLogo> findForUpdate(@Param("logoId") String logoId);

    /**
     * 이 부스에서 <b>아직 아무 프로젝트도 가리키지 않는</b> READY 로고 수.
     *
     * <p>업로드만 반복해 영구 객체를 쌓는 것을 start 에서 막는 근거다. 참조 여부를
     * {@code thumbnail_url} 로 직접 묻는다 — 행에 캐시해 두면 그 캐시가 곧 두 번째 진실이 된다.
     */
    @Query(value = """
            SELECT count(*) FROM project_logo_uploads l
             WHERE l.booth_id = :boothId AND l.status = 'READY'
               AND NOT EXISTS (SELECT 1 FROM projects p
                                WHERE p.thumbnail_url = '/api/v1/booths/' || l.booth_id
                                      || '/project-logos/' || l.logo_id || '/content')
            """, nativeQuery = true)
    long countUnreferencedReady(@Param("boothId") Long boothId);

    /**
     * 정리 대상 — 그리고 <b>이 조회가 삭제의 최종 판정이다.</b>
     *
     * <p>세 갈래다: 만료된 {@code PENDING}(브라우저가 PUT 을 했는지 우리는 모른다), {@code FAILED},
     * 그리고 참조가 없는 {@code READY}. 마지막 갈래는 표식({@code unreferenced_since})이 아니라
     * <b>지금의 {@code thumbnail_url}</b> 로 판정한다 — A → B → 다시 A 로 되돌린 경우 표식만 보면
     * 살아 있는 이미지를 지운다.
     *
     * @param candidateBefore 참조가 끊긴 뒤 이 시각 전이면 지운다 (되돌릴 여유)
     * @param orphanBefore    완료 후 이 시각까지 아무도 참조하지 않았으면 지운다 (저장을 누르지 않고 이탈)
     */
    @Query(value = """
            SELECT * FROM project_logo_uploads l
             WHERE (l.status = 'PENDING' AND l.expires_at < :now)
                OR l.status = 'FAILED'
                OR (l.status = 'READY'
                    AND (l.unreferenced_since < :candidateBefore OR l.completed_at < :orphanBefore)
                    AND NOT EXISTS (SELECT 1 FROM projects p
                                     WHERE p.thumbnail_url = '/api/v1/booths/' || l.booth_id
                                           || '/project-logos/' || l.logo_id || '/content'))
             ORDER BY l.id
             LIMIT :limit
            """, nativeQuery = true)
    List<ProjectLogo> findCollectable(@Param("now") Instant now,
                                      @Param("candidateBefore") Instant candidateBefore,
                                      @Param("orphanBefore") Instant orphanBefore,
                                      @Param("limit") int limit);

    /** 이 부스의 것인지까지 함께 본다 — 남의 부스 경로로 부른 조회는 없는 것과 같다. */
    Optional<ProjectLogo> findByBoothIdAndLogoId(Long boothId, String logoId);

    boolean existsByLogoId(String logoId);
}
