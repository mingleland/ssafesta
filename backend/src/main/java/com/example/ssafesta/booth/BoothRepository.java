package com.example.ssafesta.booth;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface BoothRepository extends JpaRepository<Booth, Long> {

    /**
     * Serialises the operations that must not interleave on one booth.
     *
     * <p>Publishing a layout and deleting an AI agent both read "is this agent referenced?" and then
     * act on the answer. Nothing in the database links them — the reference lives inside the layout
     * JSON, so there is no foreign key to catch the loser of a race, and a publish that validated an
     * agent a moment before it was deleted would leave a public booth pointing at nothing
     * (spec 007 C-14, data-model invariant A-3).
     *
     * <p>The booth row is the natural thing to lock: both operations are scoped to one booth, and
     * nothing else contends for it at publish frequency.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Booth> findWithLockById(Long id);

    /**
     * The same row, held so it cannot be rewritten underneath — but shared, so respondents do not
     * queue behind each other (spec 010).
     *
     * <p>A survey submission and a survey edit race on the question rows: the editor replaces the
     * question set while a visitor is inserting an answer that points at one of them, and
     * {@code survey_answers.question_id} has no {@code ON DELETE}, so whichever loses gets a
     * constraint error rather than an answer. The editor takes {@link #findWithLockById}; a
     * submission takes this one. Two submissions never conflict with each other, which a write lock
     * would have made them do.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<Booth> findWithSharedLockById(Long id);

    /**
     * A member keeps one booth across leases, so re-leasing continues their own content rather
     * than handing them someone else's (spec 004 C-01).
     *
     * <p><b>Administrator booths are excluded, and that is what keeps this {@code Optional} true.</b>
     * One administrator holds several of them at once (S15P21A604-905), so counting them here would
     * make this query throw {@code IncorrectResultSizeDataAccessException} and lock that account out
     * of leasing entirely — the same trap V7 was written to close. {@code ux_booths_owner} is
     * partial on the same predicate, so the database enforces exactly what this signature promises.
     */
    Optional<Booth> findByOwnerUserIdAndAdminOwnedFalse(Long ownerUserId);

    Optional<Booth> findByCurrentSlotId(Long currentSlotId);
}
