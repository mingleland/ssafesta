package com.example.ssafesta.booth;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BoothLayoutPublishedVersionRepository
        extends JpaRepository<BoothLayoutPublishedVersion, Long> {

    Optional<BoothLayoutPublishedVersion> findByBoothIdAndVersionNo(Long boothId, int versionNo);

    /** Publish history, newest first (C-07 — kept, not yet exposed). */
    List<BoothLayoutPublishedVersion> findAllByBoothIdOrderByVersionNoDesc(Long boothId);

    /**
     * The next publish number for this booth.
     *
     * <p>Deliberately <b>not</b> "the currently published version" — that is
     * {@code booths.published_layout_version}, which can be {@code null} while this keeps counting
     * (research R-02). Confusing the two is how a re-leased booth would republish itself.
     */
    @Query("SELECT COALESCE(MAX(v.versionNo), 0) FROM BoothLayoutPublishedVersion v WHERE v.boothId = :boothId")
    int highestVersionNo(@Param("boothId") Long boothId);
}
