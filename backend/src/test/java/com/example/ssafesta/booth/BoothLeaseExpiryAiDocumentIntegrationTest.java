package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.ai.DocumentProcessingClient.CancelRequest;
import com.example.ssafesta.ai.FakeDocumentProcessingClient;
import com.example.ssafesta.ai.FakeDocumentProcessingClientConfiguration;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The AI half of lease expiry (spec 007 FR-015 · FR-041, S15P21A604-496).
 *
 * <p>Three properties carry this ticket, and each is here for a reason the happy path does not
 * show. The transition and the lease have to share one transaction, so a rollback has to take the
 * documents back with it (spec.md:71). The FastAPI cancel must not be able to decide the
 * transition, so a failing client has to leave {@code CANCELLED} and {@code DISABLED} standing.
 * And every path that expires a lease has to do this, not only the batch — the lazy re-lease path
 * goes through the same private method precisely so it cannot drift.
 */
@Import({TestcontainersConfiguration.class, FakeDocumentProcessingClientConfiguration.class})
@SpringBootTest
class BoothLeaseExpiryAiDocumentIntegrationTest {

    /**
     * {@code ux_ai_documents_agent_active_sha} (V17) is unique per agent over the active statuses,
     * so two seeded documents on one agent cannot share a hash — and the container database is
     * shared across classes, so the counter alone is not enough either.
     */
    private static final AtomicInteger HASHES = new AtomicInteger();

    private static final String MODEL = "text-embedding-004";
    private static final int DIMENSIONS = 1536;

    @Autowired private BoothLeaseExpirySweeper sweeper;
    @Autowired private BoothLeaseService leaseService;
    @Autowired private BoothSlotRepository slots;
    @Autowired private BoothRepository booths;
    @Autowired private BoothLeaseRepository leases;
    @Autowired private WalletService wallets;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private FakeDocumentProcessingClient client;

    @BeforeEach
    void freeSlotsAndClearRecordings() {
        BoothTestSupport.releaseAllSlots(jdbc);
        client.reset();
    }

    /**
     * The three writes this ticket exists for, plus the cancel. {@code CANCELLED} and
     * {@code DISABLED} had readers and no writer before it — the states were reachable only in the
     * check constraints.
     */
    @Test
    void expiryCancelsLiveJobsDisablesDocumentsAndClearsWhatTheyStaged() {
        Leased booth = leasedBooth("만료");
        long agentId = seedAgent(booth.boothId());
        long queued = seedDocumentWithJob(booth, agentId, "QUEUED", "QUEUED", 0);
        long running = seedDocumentWithJob(booth, agentId, "PROCESSING", "RUNNING", 2);
        long waiting = seedDocumentWithJob(booth, agentId, "PROCESSING", "RETRY_WAIT", 1);
        long ready = seedDocument(booth.boothId(), agentId, "READY");
        seedChunk(ready, booth.boothId(), agentId);
        seedStaging(jobOf(running));
        seedStaging(jobOf(waiting));

        sweeper.expireStaleLeases();

        assertEquals(List.of("CANCELLED", "CANCELLED", "CANCELLED"),
                List.of(jobStatus(jobOf(queued)), jobStatus(jobOf(running)),
                        jobStatus(jobOf(waiting))));
        assertNotNull(finishedAt(jobOf(running)), "취소된 Job 은 종료 시각이 남아야 합니다.");
        assertEquals("BOOTH_LEASE_EXPIRED", errorCode(jobOf(running)),
                "왜 취소됐는지가 Job 에 남아야 합니다.");
        assertEquals("worker-1", workerId(jobOf(running)),
                "어느 워커의 attempt 였는지는 남아야 합니다 — markSucceeded 와 같은 판단입니다.");
        assertEquals(List.of("DISABLED", "DISABLED", "DISABLED", "DISABLED"),
                List.of(documentStatus(queued), documentStatus(running), documentStatus(waiting),
                        documentStatus(ready)));
        assertEquals(0, stagingCount(booth.boothId()),
                "취소된 Job 의 staging 은 남지 않아야 합니다 (V21).");
        assertEquals(0, chunkCount(booth.boothId()),
                "임대가 끝난 부스의 chunk 는 정리돼야 합니다 (FR-041).");

        assertEquals(List.of(new CancelRequest(jobOf(queued), 0),
                        new CancelRequest(jobOf(running), 2),
                        new CancelRequest(jobOf(waiting), 1)),
                sortedCancels(), "취소는 각 Job 의 현재 attempt 로 나가야 합니다.");
    }

    /**
     * A finished Job is the audit trail of an earlier attempt, and a {@code FAILED} document already
     * says why it is unusable. Overwriting either would erase what happened.
     */
    @Test
    void terminalJobsAndAlreadyInactiveDocumentsAreLeftAlone() {
        Leased booth = leasedBooth("종료");
        long agentId = seedAgent(booth.boothId());
        long succeeded = seedDocumentWithJob(booth, agentId, "READY", "SUCCEEDED", 0);
        long dead = seedDocumentWithJob(booth, agentId, "FAILED", "DEAD", 3);
        long expired = seedDocument(booth.boothId(), agentId, "EXPIRED");

        sweeper.expireStaleLeases();

        assertEquals("SUCCEEDED", jobStatus(jobOf(succeeded)));
        assertEquals("DEAD", jobStatus(jobOf(dead)));
        assertEquals("DISABLED", documentStatus(succeeded), "READY 였던 문서는 꺼져야 합니다.");
        assertEquals("FAILED", documentStatus(dead), "실패 사유를 DISABLED 로 덮으면 안 됩니다.");
        assertEquals("EXPIRED", documentStatus(expired), "업로드 만료는 임대 만료와 다른 축입니다.");
        assertEquals(List.of(), client.cancelled(), "끝난 Job 에는 취소를 보내지 않습니다.");
    }

    /**
     * An outstanding upload grant is not a document to turn off. FR-026 and FR-028 reach it only
     * through {@code EXPIRED}, and {@code expireAbandonedGrants} matches on
     * ({@code QUEUED}, {@code uploaded_at IS NULL}) — {@code DISABLED} here would strand the row
     * and whatever a late PUT left in storage where no sweeper can see either again.
     */
    @Test
    void anOutstandingUploadGrantIsLeftForTheUploadExpirySweeper() {
        Leased booth = leasedBooth("발급대기");
        long agentId = seedAgent(booth.boothId());
        long grant = seedDocument(booth.boothId(), agentId, "QUEUED", nextHash(), false);
        long uploaded = seedDocument(booth.boothId(), agentId, "QUEUED");

        sweeper.expireStaleLeases();

        assertEquals("QUEUED", documentStatus(grant), "업로드 대기 그랜트는 임대 만료가 건드리지 않습니다.");
        assertEquals(1, abandonedGrantCount(grant),
                "expireAbandonedGrants 의 조건에 그대로 남아야 합니다 — 여기서 벗어나면 원본을 지울 경로가 없습니다.");
        assertEquals("DISABLED", documentStatus(uploaded), "업로드가 끝난 문서는 꺼져야 합니다.");
    }

    @Test
    void anotherBoothsDocumentsAreNotTouched() {
        Leased expiring = leasedBooth("만료측");
        Leased surviving = leasedBooth("유효측");
        long expiringAgent = seedAgent(expiring.boothId());
        long survivingAgent = seedAgent(surviving.boothId());
        long doomed = seedDocumentWithJob(expiring, expiringAgent, "PROCESSING", "RUNNING", 0);
        long safe = seedDocumentWithJob(surviving, survivingAgent, "PROCESSING", "RUNNING", 0);
        seedChunk(safe, surviving.boothId(), survivingAgent);
        reviveLease(surviving.leaseId());

        sweeper.expireStaleLeases();

        assertEquals("CANCELLED", jobStatus(jobOf(doomed)));
        assertEquals("RUNNING", jobStatus(jobOf(safe)));
        assertEquals("PROCESSING", documentStatus(safe));
        assertEquals(1, chunkCount(surviving.boothId()));
        assertEquals(List.of(new CancelRequest(jobOf(doomed), 0)), client.cancelled());
    }

    /**
     * FR-041: the delivery must not decide the database's state. A worker that never hears is
     * refused at its next callback — {@code CANCELLED} is terminal — so losing the call costs
     * compute, not consistency.
     */
    @Test
    void theTransitionStandsWhenTheCancelCallFails() {
        Leased booth = leasedBooth("전송실패");
        long agentId = seedAgent(booth.boothId());
        long document = seedDocumentWithJob(booth, agentId, "PROCESSING", "RUNNING", 1);
        client.failWith(new IllegalStateException("FastAPI 에 닿지 못했습니다."));

        sweeper.expireStaleLeases();

        assertEquals("CANCELLED", jobStatus(jobOf(document)));
        assertEquals("DISABLED", documentStatus(document));
        assertEquals(LeaseStatus.EXPIRED, leases.findById(booth.leaseId()).orElseThrow().getStatus());
        assertEquals(1, client.cancelled().size(), "전송은 시도돼야 합니다 — 삼키는 것은 실패뿐입니다.");
    }

    /**
     * The Jobs after a failed cancel go to the same FastAPI process, so sending them buys nothing
     * and costs a read timeout each — and on the lazy re-lease path that thread is a member's
     * request. The DB transition is already committed for all of them.
     */
    @Test
    void aFailedCancelStopsTheRestOfTheBatch() {
        Leased booth = leasedBooth("전송중단");
        long agentId = seedAgent(booth.boothId());
        seedDocumentWithJob(booth, agentId, "PROCESSING", "RUNNING", 0);
        seedDocumentWithJob(booth, agentId, "PROCESSING", "RUNNING", 0);
        seedDocumentWithJob(booth, agentId, "PROCESSING", "RUNNING", 0);
        client.failWith(new IllegalStateException("FastAPI 에 닿지 못했습니다."));

        sweeper.expireStaleLeases();

        assertEquals(1, client.cancelled().size(), "첫 실패에서 멈춰야 합니다.");
        assertEquals(3, cancelledJobCount(booth.boothId()), "DB 전이는 세 건 모두 확정입니다.");
    }

    /**
     * The other side of the same rule. spec.md:71 puts the lease, the Job and the document in one
     * transaction, so a rollback takes all three — and the cancel, which only goes out after the
     * commit, must not have been sent at all.
     */
    @Test
    void aRolledBackExpiryRestoresEverythingAndSendsNoCancel() {
        Leased booth = leasedBooth("롤백");
        long agentId = seedAgent(booth.boothId());
        long document = seedDocumentWithJob(booth, agentId, "PROCESSING", "RUNNING", 0);

        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                leaseService.expireStaleLeases();
                throw new IllegalStateException("의도된 롤백");
            });
        } catch (IllegalStateException expected) {
            // 롤백 자체가 단정 대상이다 — 아래 네 줄이 그것을 본다.
        }

        assertEquals(LeaseStatus.ACTIVE, leases.findById(booth.leaseId()).orElseThrow().getStatus());
        assertEquals("RUNNING", jobStatus(jobOf(document)));
        assertEquals("PROCESSING", documentStatus(document));
        assertEquals(List.of(), client.cancelled(), "커밋되지 않은 취소를 FastAPI 에 알리면 안 됩니다.");
    }

    /** Nothing is live any more, so the second pass has nothing to tell FastAPI about. */
    @Test
    void aSecondPassSendsNoFurtherCancel() {
        Leased booth = leasedBooth("두번");
        long agentId = seedAgent(booth.boothId());
        seedDocumentWithJob(booth, agentId, "PROCESSING", "RUNNING", 0);

        sweeper.expireStaleLeases();
        int afterFirst = client.cancelled().size();
        sweeper.expireStaleLeases();

        assertEquals(1, afterFirst);
        assertEquals(afterFirst, client.cancelled().size(), "만료는 한 번만 취소를 보냅니다.");
    }

    /**
     * The batch is not the only way a lease expires. A re-lease of the same slot transitions the
     * stale row on the request path, and it has to carry the documents with it — the two paths share
     * {@code BoothLeaseService}'s private expiry method for exactly this reason.
     */
    @Test
    void theLazyReLeasePathTransitionsTheDocumentsToo() {
        Leased booth = leasedBooth("지연");
        long agentId = seedAgent(booth.boothId());
        long document = seedDocumentWithJob(booth, agentId, "PROCESSING", "RUNNING", 4);

        leaseService.lease(createMemberWithWallet(users, wallets, "재임대"), booth.slotId(), 1);

        assertEquals("CANCELLED", jobStatus(jobOf(document)));
        assertEquals("DISABLED", documentStatus(document));
        assertEquals(List.of(new CancelRequest(jobOf(document), 4)), client.cancelled());
    }

    // ── 읽기 ────────────────────────────────────────────────────────────────

    private List<CancelRequest> sortedCancels() {
        return client.cancelled().stream()
                .sorted((left, right) -> Long.compare(left.jobId(), right.jobId()))
                .toList();
    }

    private String jobStatus(long jobId) {
        return jdbc.queryForObject("SELECT status FROM ai_document_jobs WHERE id = ?", String.class,
                jobId);
    }

    private String errorCode(long jobId) {
        return jdbc.queryForObject("SELECT last_error_code FROM ai_document_jobs WHERE id = ?",
                String.class, jobId);
    }

    private String errorMessage(long jobId) {
        return jdbc.queryForObject("SELECT last_error FROM ai_document_jobs WHERE id = ?",
                String.class, jobId);
    }

    private String workerId(long jobId) {
        return jdbc.queryForObject("SELECT worker_id FROM ai_document_jobs WHERE id = ?",
                String.class, jobId);
    }

    private Instant finishedAt(long jobId) {
        return jdbc.queryForObject("SELECT finished_at FROM ai_document_jobs WHERE id = ?",
                Instant.class, jobId);
    }

    private long jobOf(long documentId) {
        return jdbc.queryForObject("SELECT id FROM ai_document_jobs WHERE document_id = ?",
                Long.class, documentId);
    }

    private String documentStatus(long documentId) {
        return jdbc.queryForObject("SELECT processing_status FROM ai_documents WHERE id = ?",
                String.class, documentId);
    }

    /** The predicate {@code AiDocumentRepository.expireAbandonedGrants} runs on. */
    private int abandonedGrantCount(long documentId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM ai_documents
                 WHERE id = ? AND processing_status = 'QUEUED' AND uploaded_at IS NULL
                """, Integer.class, documentId);
    }

    private int cancelledJobCount(Long boothId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ai_document_jobs WHERE booth_id = ? AND status = 'CANCELLED'",
                Integer.class, boothId);
    }

    private int chunkCount(Long boothId) {
        return jdbc.queryForObject("SELECT count(*) FROM ai_document_chunks WHERE booth_id = ?",
                Integer.class, boothId);
    }

    private int stagingCount(Long boothId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM ai_document_chunk_staging s
                  JOIN ai_document_jobs j ON j.id = s.job_id
                 WHERE j.booth_id = ?
                """, Integer.class, boothId);
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    /** A booth on a slot whose lease has already run out — the fixture every case starts from. */
    /**
     * 임차인이 반납해도 같은 세 가지가 일어난다 (spec 004 D12·FR-020, spec 007 FR-015·FR-041).
     *
     * <p>반납과 만료는 한 메서드({@code BoothLeaseService.release})를 지나므로 여기서 다시 볼 것은
     * <b>그 메서드가 반납 경로에서도 불린다는 사실</b>과 <b>기록이 사실대로 남는가</b> 둘이다.
     *
     * <p>Job 의 {@code last_error_code} 는 반납에서도 {@code BOOTH_LEASE_EXPIRED} 다 — 2026-09-14
     * 에 그 값을 "유효한 임대가 없음" 의 포괄 코드로 재정의했다. AI 파트가 자기 임대 검사에서
     * 돌려보내는 어휘와 같은 값이라(FR-041, GitLab #162) 새 값을 BE 단독으로 만들지 않는다.
     * 사람이 읽는 {@code last_error} 문구만 반납용으로 갈린다.
     */
    @Test
    void returningALeaseCancelsLiveJobsAndDisablesDocumentsJustLikeExpiryDoes() {
        Long userId = createMemberWithWallet(users, wallets, "반납AI");
        Long slotId = freeSlotId();
        BoothLease lease = leaseService.lease(userId, slotId, 1).lease();
        Leased booth = new Leased(lease.getBoothId(), slotId, lease.getId());
        long agentId = seedAgent(booth.boothId());
        long running = seedDocumentWithJob(booth, agentId, "PROCESSING", "RUNNING", 2);
        long ready = seedDocument(booth.boothId(), agentId, "READY");
        seedChunk(ready, booth.boothId(), agentId);
        seedStaging(jobOf(running));

        leaseService.cancel(userId, slotId);

        assertEquals(LeaseStatus.CANCELLED, leases.findById(booth.leaseId()).orElseThrow().getStatus());
        assertEquals("CANCELLED", jobStatus(jobOf(running)));
        assertEquals(List.of("DISABLED", "DISABLED"),
                List.of(documentStatus(running), documentStatus(ready)));
        assertEquals(0, stagingCount(booth.boothId()));
        assertEquals(0, chunkCount(booth.boothId()));

        assertEquals("BOOTH_LEASE_EXPIRED", errorCode(jobOf(running)),
                "반납도 AI 와 공유하는 '유효한 임대 없음' 코드를 쓴다 — 새 값을 만들지 않는다.");
        assertEquals("임대를 반납해 처리를 취소했습니다.", errorMessage(jobOf(running)),
                "사람이 읽는 문구는 사실대로 반납이라고 적혀야 합니다.");

        // 살아 있던 Job 수만큼, 정확히 그만큼만 나간다.
        assertEquals(List.of(new CancelRequest(jobOf(running), 2)), sortedCancels());
    }

    private Leased leasedBooth(String prefix) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        Long slotId = freeSlotId();
        BoothLease lease = leaseService.lease(userId, slotId, 1).lease();
        endLease(lease.getId());
        return new Leased(lease.getBoothId(), slotId, lease.getId());
    }

    /** Pushes both ends into the past — {@code CHECK(ends_at > starts_at)} forbids moving one. */
    private void endLease(Long leaseId) {
        jdbc.update("UPDATE booth_leases SET starts_at = now() - interval '25 hours', "
                + "ends_at = now() - interval '1 hour' WHERE id = ?", leaseId);
    }

    /** Undoes {@link #endLease} for the booth a case wants the pass to walk past. */
    private void reviveLease(Long leaseId) {
        jdbc.update("UPDATE booth_leases SET starts_at = now() - interval '1 hour', "
                + "ends_at = now() + interval '23 hours' WHERE id = ?", leaseId);
    }

    private Long freeSlotId() {
        Instant now = Instant.now();
        return slots.findAllOrdered().stream()
                .filter(slot -> slot.getSlotType() == SlotType.USER_RENTAL)
                .filter(slot -> leases.findValidBySlotId(slot.getId(), now).isEmpty())
                .filter(slot -> booths.findByCurrentSlotId(slot.getId()).isEmpty())
                .map(BoothSlot::getId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("빈 USER_RENTAL 슬롯이 없습니다."));
    }

    private long seedAgent(Long boothId) {
        return jdbc.queryForObject("""
                INSERT INTO ai_agents (booth_id, name, role_code, tone_code, system_prompt, status)
                VALUES (?, '도슨트', 'PROJECT_DOCENT', 'FRIENDLY', '문서를 근거로 답한다.', 'ACTIVE')
                RETURNING id
                """, Long.class, boothId);
    }

    /**
     * {@code s3_key} is UNIQUE and the container database is shared across test classes, so the key
     * is made globally unique rather than derived from a label another class might also use.
     */
    private long seedDocument(Long boothId, long agentId, String status) {
        return seedDocument(boothId, agentId, status, nextHash(), true);
    }

    /**
     * {@code uploaded} is what separates a document from an outstanding grant: {@code uploaded_at}
     * is set by {@code complete}, and {@code NULL} there is the whole predicate
     * {@code expireAbandonedGrants} runs on. The literal is interpolated rather than bound because
     * a bound {@code NULL} timestamptz needs a cast to be typed at all.
     */
    private long seedDocument(Long boothId, long agentId, String status, String sourceHash,
                              boolean uploaded) {
        Long userId = jdbc.queryForObject("SELECT owner_user_id FROM booths WHERE id = ?",
                Long.class, boothId);
        return jdbc.queryForObject("""
                INSERT INTO ai_documents (booth_id, agent_id, original_filename, content_type,
                    size_bytes, s3_key, processing_status, uploaded_by_user_id, content_sha256,
                    storage_provider, storage_bucket, uploaded_at)
                VALUES (?, ?, 'seed.pdf', 'application/pdf', 1024, ?, ?, ?, ?,
                    'R2', 'test-ai-documents', %s)
                RETURNING id
                """.formatted(uploaded ? "now()" : "NULL"), Long.class, boothId, agentId,
                "seed/expiry/" + UUID.randomUUID(), status, userId, sourceHash);
    }

    private static String nextHash() {
        return "%032x%032x".formatted(HASHES.incrementAndGet(), UUID.randomUUID().hashCode() & 0xffffffffL);
    }

    private long seedDocumentWithJob(Leased booth, long agentId, String documentStatus,
                                     String jobStatus, int attemptNo) {
        String sourceHash = nextHash();
        long documentId = seedDocument(booth.boothId(), agentId, documentStatus, sourceHash, true);
        jdbc.update("""
                INSERT INTO ai_document_jobs (document_id, booth_id, agent_id, source_hash,
                    original_filename, content_type, file_size_bytes, storage_provider,
                    storage_bucket, object_key, status, attempt_no, worker_id, lease_expires_at)
                VALUES (?, ?, ?, ?, 'seed.pdf', 'application/pdf', 1024, 'R2',
                    'test-ai-documents', ?, ?, ?, 'worker-1', now() + interval '90 seconds')
                """, documentId, booth.boothId(), agentId, sourceHash,
                "seed/expiry/job/" + UUID.randomUUID(), jobStatus, attemptNo);
        return documentId;
    }

    private void seedChunk(long documentId, Long boothId, long agentId) {
        jdbc.update("""
                INSERT INTO ai_document_chunks (document_id, booth_id, agent_id, chunk_no, content,
                    embedding, embedding_model_id, searchable)
                VALUES (?, ?, ?, 0, '본문', CAST(? AS vector), ?, TRUE)
                """, documentId, boothId, agentId, vector(), MODEL);
    }

    private void seedStaging(long jobId) {
        jdbc.update("""
                INSERT INTO ai_document_chunk_staging (job_id, batch_seq, chunk_no, content,
                    embedding, embedding_model_id)
                VALUES (?, 0, 0, '적재본', CAST(? AS vector), ?)
                """, jobId, vector(), MODEL);
    }

    private static String vector() {
        StringBuilder value = new StringBuilder("[");
        for (int index = 0; index < DIMENSIONS; index++) {
            value.append(index == 0 ? "" : ",").append("0.001");
        }
        return value.append("]").toString();
    }

    private record Leased(Long boothId, Long slotId, Long leaseId) { }
}
