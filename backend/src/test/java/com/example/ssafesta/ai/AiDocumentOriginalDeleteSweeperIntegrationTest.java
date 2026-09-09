package com.example.ssafesta.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.storage.FakeObjectStorage;
import com.example.ssafesta.storage.FakeObjectStorageConfiguration;
import com.example.ssafesta.storage.StorageUnavailableException;
import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link AiDocumentOriginalDeleteSweeper} (FR-028, S15P21A604-556).
 *
 * <p>Rows are seeded straight into {@code ai_documents} by JDBC rather than through the upload API
 * — the sweeper only ever reads and writes that table, and going through upload-url/complete/expire
 * for every case would test three endpoints' worth of behaviour this class does not touch.
 */
@Import({TestcontainersConfiguration.class, FakeObjectStorageConfiguration.class})
@SpringBootTest
class AiDocumentOriginalDeleteSweeperIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired private AiDocumentOriginalDeleteSweeper sweeper;
    @Autowired private AiDocumentRepository documents;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private FakeObjectStorage storage;
    @Autowired private JdbcTemplate jdbc;

    private Long userId;
    private Long boothId;
    private Long agentId;

    @BeforeEach
    void setUp() {
        storage.reset();
        userId = users.save(new User("스윕" + SEQUENCE.incrementAndGet())).getId();
        boothId = booths.save(new Booth(userId, "스윕 부스")).getId();
        agentId = jdbc.queryForObject("""
                INSERT INTO ai_agents (booth_id, name, role_code, tone_code, system_prompt,
                    response_length, service_price, handoff_enabled, created_at, updated_at)
                VALUES (?, '스윕직원', 'PROJECT_DOCENT', 'FRIENDLY', '문서 기반 답변', 'MEDIUM', 0, false,
                    now(), now())
                RETURNING id
                """, Long.class, boothId);
    }

    @Test
    void deletesTheOriginalAndClearsTheKeyPastTheRecoveryWindow() {
        long documentId = seed("EXPIRED","s3-key-A", Duration.ofHours(24).plusMinutes(1));
        storage.putObject("s3-key-A", 1024);

        sweeper.deleteExpiredOriginals();

        assertFalse(storage.hasObject("s3-key-A"), "원본이 저장소에 남아 있다");
        assertNull(documents.findById(documentId).orElseThrow().getObjectKey(),
                "s3_key 가 비워지지 않았다");
    }

    /** FR-027's window — 23h59m is still inside it, and the sweep must not touch it. */
    @Test
    void leavesRowsStillInsideTheRecoveryWindowAlone() {
        long documentId = seed("EXPIRED","s3-key-B", Duration.ofHours(23).plusMinutes(59));
        storage.putObject("s3-key-B", 1024);

        sweeper.deleteExpiredOriginals();

        assertTrue(storage.hasObject("s3-key-B"), "복구 창 안인데 원본이 지워졌다");
        assertEquals("s3-key-B", documents.findById(documentId).orElseThrow().getObjectKey());
    }

    /** A storage failure writes nothing — the row is retried whole on the next pass. */
    @Test
    void aStorageFailureLeavesTheRowUntouchedForTheNextPass() {
        long documentId = seed("EXPIRED","s3-key-C", Duration.ofHours(25));
        storage.putObject("s3-key-C", 1024);
        storage.failDeleteWith(new StorageUnavailableException("일시 장애"));

        sweeper.deleteExpiredOriginals();

        assertTrue(storage.hasObject("s3-key-C"), "실패했는데 객체가 지워졌다");
        assertEquals("s3-key-C", documents.findById(documentId).orElseThrow().getObjectKey(),
                "실패했는데 s3_key 가 비워졌다");
        assertEquals("EXPIRED", documents.findById(documentId).orElseThrow().getProcessingStatus());

        // Next pass, storage recovered: the same row is retried and now succeeds.
        storage.failDeleteWith(null);
        sweeper.deleteExpiredOriginals();

        assertFalse(storage.hasObject("s3-key-C"));
        assertNull(documents.findById(documentId).orElseThrow().getObjectKey());
    }

    /** A cleaned row drops out of the next pass's candidate set — {@code s3_key IS NOT NULL}. */
    @Test
    void aClearedRowIsNotRevisitedByTheNextPass() {
        long documentId = seed("EXPIRED","s3-key-D", Duration.ofHours(25));
        storage.putObject("s3-key-D", 1024);
        sweeper.deleteExpiredOriginals();
        assertNull(documents.findById(documentId).orElseThrow().getObjectKey());

        Integer stillCandidate = jdbc.queryForObject(
                "SELECT count(*) FROM ai_documents WHERE id = ? AND s3_key IS NOT NULL",
                Integer.class, documentId);
        assertEquals(0, stillCandidate, "지운 행이 다음 조회 대상에 다시 걸린다");
    }

    /**
     * Race: the row's storage coordinates change between the snapshot read and the delete call (a
     * reconcile landing mid-sweep). The clearing update must then touch zero rows — the coordinates
     * the sweeper just deleted are the <b>old</b> ones, and the row's new key must survive untouched.
     */
    @Test
    void aCoordinateChangeDuringTheSweepIsNotClearedAway() {
        long documentId = seed("EXPIRED","s3-key-old", Duration.ofHours(25));
        storage.putObject("s3-key-old", 1024);
        storage.putObject("s3-key-new", 2048);
        storage.onDelete(key -> {
            if ("s3-key-old".equals(key)) {
                jdbc.update("UPDATE ai_documents SET s3_key = 's3-key-new' WHERE id = ?", documentId);
            }
        });

        sweeper.deleteExpiredOriginals();

        AiDocument after = documents.findById(documentId).orElseThrow();
        assertEquals("s3-key-new", after.getObjectKey(), "새 좌표가 지워지거나 바뀌었다");
        assertTrue(storage.hasObject("s3-key-new"), "새 위치의 객체가 함께 지워졌다");
    }

    /**
     * Same race, {@code storage_bucket} leg — the compare-and-swap WHERE clause has five columns and
     * only {@code s3_key} was ever exercised. A mutant that dropped every column but {@code id} and
     * {@code s3_key} would still pass every other test in this class; this one specifically fails if
     * {@code storage_bucket} is not part of the predicate.
     */
    @Test
    void aBucketChangeDuringTheSweepIsNotClearedAway() {
        long documentId = seed("EXPIRED", "s3-key-bucket", Duration.ofHours(25));
        storage.putObject("s3-key-bucket", 1024);
        storage.onDelete(key -> {
            if ("s3-key-bucket".equals(key)) {
                jdbc.update("UPDATE ai_documents SET storage_bucket = 'moved-bucket' WHERE id = ?",
                        documentId);
            }
        });

        sweeper.deleteExpiredOriginals();

        AiDocument after = documents.findById(documentId).orElseThrow();
        assertEquals("s3-key-bucket", after.getObjectKey(), "버킷만 바뀌었는데 s3_key가 비워졌다");
        assertEquals("moved-bucket", after.getStorageBucket(), "새 버킷값이 갱신 조건에 걸려 되돌아갔다");
    }

    /** Same race, {@code storage_provider} leg — see {@link #aBucketChangeDuringTheSweepIsNotClearedAway}. */
    @Test
    void aProviderChangeDuringTheSweepIsNotClearedAway() {
        long documentId = seed("EXPIRED", "s3-key-provider", Duration.ofHours(25));
        storage.putObject("s3-key-provider", 1024);
        storage.onDelete(key -> {
            if ("s3-key-provider".equals(key)) {
                jdbc.update("UPDATE ai_documents SET storage_provider = 'S3' WHERE id = ?", documentId);
            }
        });

        sweeper.deleteExpiredOriginals();

        AiDocument after = documents.findById(documentId).orElseThrow();
        assertEquals("s3-key-provider", after.getObjectKey(), "provider만 바뀌었는데 s3_key가 비워졌다");
        assertEquals("S3", after.getStorageProvider(), "새 provider값이 갱신 조건에 걸려 되돌아갔다");
    }

    /**
     * P1: a document recovered via a late {@code /complete} while an <b>earlier</b> row in the same
     * batch is still being processed must not have its bytes destroyed. {@link #due} snapshots up to
     * 50 rows at once; without a fresh re-check right before each row's own delete, a row near the
     * end of the batch would be acted on against a read that is stale by however long the rows ahead
     * of it took (real network deletes) — long enough for exactly this recovery to land in between.
     */
    @Test
    void aRowRecoveredWhileAnEarlierRowInTheBatchIsStillBeingDeletedSurvives() {
        long recovering = seed("EXPIRED", "s3-key-later", Duration.ofHours(25));
        seed("EXPIRED", "s3-key-first", Duration.ofHours(30)); // sorts first — ORDER BY expired_at
        storage.putObject("s3-key-later", 1024);
        storage.putObject("s3-key-first", 1024);
        // Fires while the earlier row ("s3-key-first") is being deleted — before "recovering"'s own
        // turn in the loop — simulating the late /complete landing in that gap.
        storage.onDelete(key -> {
            if ("s3-key-first".equals(key)) {
                jdbc.update("UPDATE ai_documents SET processing_status = 'QUEUED', expired_at = NULL "
                        + "WHERE id = ?", recovering);
            }
        });

        sweeper.deleteExpiredOriginals();

        assertTrue(storage.hasObject("s3-key-later"), "복구된 문서의 원본이 지워졌다");
        AiDocument after = documents.findById(recovering).orElseThrow();
        assertEquals("s3-key-later", after.getObjectKey());
        assertEquals("QUEUED", after.getProcessingStatus());
    }

    /**
     * P2: a recovery landing in the sliver between {@link AiDocumentOriginalDeleteSweeper#stillEligible}
     * and the actual delete call — the one gap that check cannot close — must not be logged the same
     * way as a harmless coordinate race. The row is gone from {@code EXPIRED} but {@code s3_key} is
     * untouched by {@code recover()}, so the clearing update's zero rows means "I just deleted bytes a
     * live document still points at", not "reconcile moved it, next pass will catch up" — that has to
     * come out as {@code ERROR}, not the same {@code WARN} both cases used to share.
     */
    @Test
    void aRowRecoveredInTheSliverBeforeItsOwnDeleteIsLoggedAsAnError() {
        long documentId = seed("EXPIRED", "s3-key-sliver", Duration.ofHours(25));
        storage.putObject("s3-key-sliver", 1024);
        storage.onDelete(key -> {
            if ("s3-key-sliver".equals(key)) {
                jdbc.update("UPDATE ai_documents SET processing_status = 'QUEUED', expired_at = NULL "
                        + "WHERE id = ?", documentId);
            }
        });

        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(AiDocumentOriginalDeleteSweeper.class);
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        logger.addAppender(capture);
        try {
            sweeper.deleteExpiredOriginals();
        } finally {
            logger.detachAppender(capture);
        }

        assertTrue(capture.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR
                        && event.getFormattedMessage().contains(String.valueOf(documentId))),
                "복구된 문서의 원본을 지운 사고가 ERROR로 남지 않았다: " + capture.list);
        assertTrue(capture.list.stream().noneMatch(event -> event.getLevel() == Level.WARN),
                "사고인데 무해한 좌표-경합과 같은 WARN으로도 찍혔다: " + capture.list);

        AiDocument after = documents.findById(documentId).orElseThrow();
        assertEquals("QUEUED", after.getProcessingStatus());
        assertEquals("s3-key-sliver", after.getObjectKey(), "s3_key는 그대로인데 원본은 실제로 지워졌다");
        assertFalse(storage.hasObject("s3-key-sliver"), "이 테스트가 재현하는 사고 자체 — 원본이 지워졌다");
    }

    /** {@code EXPIRED} only — a {@code FAILED} document's original is out of this sweep's scope. */
    @Test
    void aFailedDocumentIsNotTouched() {
        long documentId = seed("FAILED", "s3-key-failed", Duration.ofHours(25));
        storage.putObject("s3-key-failed", 1024);

        sweeper.deleteExpiredOriginals();

        assertTrue(storage.hasObject("s3-key-failed"));
        assertEquals("s3-key-failed", documents.findById(documentId).orElseThrow().getObjectKey());
    }

    private long seed(String status, String objectKey, Duration expiredAgo) {
        String sha = "%064x".formatted(SEQUENCE.incrementAndGet());
        return jdbc.queryForObject("""
                INSERT INTO ai_documents (booth_id, agent_id, original_filename, content_type,
                    size_bytes, s3_key, processing_status, uploaded_by_user_id, content_sha256,
                    storage_provider, storage_bucket, uploaded_at, expired_at)
                VALUES (?, ?, 'sweep.pdf', 'application/pdf', 1024, ?, ?, ?, ?, 'R2',
                    'test-ai-documents', now(), ?)
                RETURNING id
                """, Long.class, boothId, agentId, objectKey, status, userId, sha,
                Timestamp.from(Instant.now().minus(expiredAgo)));
    }
}
