package com.example.ssafesta.ai;

import com.example.ssafesta.storage.FakeObjectStorage;
import com.example.ssafesta.storage.FakeObjectStorageConfiguration;
import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static com.example.ssafesta.booth.BoothTestSupport.releaseAllSlots;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 발급 경로의 두 경쟁 (spec 007 FR-018·FR-019b).
 *
 * <p>같은 파일 경쟁은 부분 유니크 인덱스가 최후 방어이고, <b>서로 다른</b> 파일의 상한 경쟁은
 * 인덱스가 잡지 못한다 — 각자 "아홉 개"를 읽고 각자 INSERT 하면 열한 개가 된다. 그쪽을 막는 것은
 * agent 행 잠금 하나뿐이라 테스트가 있어야 한다.
 */
@Import({TestcontainersConfiguration.class, FakeObjectStorageConfiguration.class})
@SpringBootTest
class AiDocumentConcurrencyIntegrationTest {

    private static final String MINIMAL = """
            {"name": "도슨트", "role": "PROJECT_DOCENT", "systemPrompt": "문서를 근거로 답한다."}""";
    private static final int TIMEOUT_SECONDS = 30;
    private static final long ONE_MB = 1024L * 1024;

    @Autowired private AiDocumentService documentService;
    @Autowired private AiAgentService agentService;
    @Autowired private AiDocumentRepository documents;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private FakeObjectStorage storage;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private tools.jackson.databind.json.JsonMapper jsonMapper;

    @BeforeEach
    void reset() {
        releaseAllSlots(jdbc);
        storage.reset();
    }

    /**
     * 같은 파일을 여덟이 동시에 올리면 <b>여덟 다 URL 을 받고 문서는 하나</b>다.
     *
     * <p>"하나만 성공하고 일곱은 중복" 이 아니다 — 아직 아무도 업로드를 끝내지 않았으므로 이들은
     * 전부 같은 발급을 재개하는 요청이고, 재개에는 URL 이 나간다 (#84). 중복 응답은 업로드가
     * 끝난 뒤에야 맞는 답이다.
     */
    @RepeatedTest(5)
    void eightRacingGrantsForTheSameFileShareOneDocument() throws Exception {
        Owner owner = agentOwner("같은파일");
        String sha = shaOf(1);

        List<Outcome<AiDocumentService.UploadGrantView>> outcomes = together(8, () ->
                documentService.issueUploadUrl(owner.agentId(), owner.userId(),
                        new AiDocumentService.UploadCommand("project.pdf", "application/pdf",
                                ONE_MB, sha)));

        List<AiDocumentService.UploadGrantView> grants = outcomes.stream()
                .peek(outcome -> {
                    if (!outcome.succeeded()) {
                        throw new AssertionError("발급이 실패했다", outcome.failure());
                    }
                })
                .map(Outcome::value).toList();
        assertEquals(1, grants.stream().map(AiDocumentService.UploadGrantView::documentId)
                .distinct().count(), "같은 파일인데 문서가 여러 개 생겼다");
        grants.forEach(grant -> {
            assertEquals(false, grant.duplicate(), "아직 업로드 전인데 중복으로 답했다");
            assertNotNull(grant.uploadUrl(), "재개 요청에 URL 이 없다");
        });
        assertEquals(1, documents.countActive(owner.agentId()), "행이 하나가 아니다");
    }

    /**
     * 서로 다른 파일 둘이 마지막 한 자리를 두고 겹치면 하나만 들어간다.
     *
     * <p>유니크 인덱스는 해시가 다른 이 둘을 구분하지 못한다. 잠금이 없으면 열한 개가 된다.
     */
    @RepeatedTest(5)
    void twoDifferentFilesRacingForTheLastSlotProduceOnlyOne() throws Exception {
        Owner owner = agentOwner("상한경합");
        for (int i = 0; i < 9; i++) {
            seedReady(owner, shaOf(100 + i));
        }

        CountDownLatch start = new CountDownLatch(1);
        List<Outcome<AiDocumentService.UploadGrantView>> outcomes = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Outcome<AiDocumentService.UploadGrantView>>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String sha = shaOf(200 + i);
                String name = "file-" + i + ".pdf";
                futures.add(pool.submit(() -> call(() -> {
                    await(start);
                    return documentService.issueUploadUrl(owner.agentId(), owner.userId(),
                            new AiDocumentService.UploadCommand(name, "application/pdf",
                                    ONE_MB, sha));
                })));
            }
            start.countDown();
            for (Future<Outcome<AiDocumentService.UploadGrantView>> future : futures) {
                outcomes.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, outcomes.stream().filter(Outcome::succeeded).count(),
                "마지막 한 자리에 둘 다 들어갔다");
        outcomes.stream().filter(outcome -> !outcome.succeeded()).forEach(outcome ->
                assertInstanceOf(AiDocumentLimitException.class, outcome.failure(),
                        "진 쪽도 DOCUMENT_LIMIT_EXCEEDED 여야 한다"));
        assertEquals(10, documents.countActive(owner.agentId()), "상한을 넘겼다");
    }

    /** 완료를 동시에 두 번 불러도 둘 다 성공하고 기록은 한 번이다. */
    @RepeatedTest(5)
    void twoCompletionsAtOnceBothSucceed() throws Exception {
        Owner owner = agentOwner("완료경합");
        AiDocumentService.UploadGrantView grant = documentService.issueUploadUrl(
                owner.agentId(), owner.userId(),
                new AiDocumentService.UploadCommand("project.pdf", "application/pdf",
                        ONE_MB, shaOf(2)));
        storage.putObject(grant.objectKey(), ONE_MB);

        List<Outcome<AiDocumentService.CompleteView>> outcomes = together(2, () ->
                documentService.complete(grant.documentId(), owner.userId()));

        assertEquals(2, outcomes.stream().filter(Outcome::succeeded).count(),
                "동시 완료 중 하나가 실패했다");
        assertNotNull(documents.findById(grant.documentId()).orElseThrow().getUploadedAt());
    }

    // ── 실행 도구 ────────────────────────────────────────────────────────────

    private <T> List<Outcome<T>> together(int threads, Callable<T> action) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Outcome<T>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> call(() -> {
                    await(start);
                    return action.call();
                })));
            }
            start.countDown();
            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<Outcome<T>> future : futures) {
                outcomes.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static <T> Outcome<T> call(Callable<T> action) {
        try {
            return new Outcome<>(true, action.call(), null);
        } catch (RuntimeException exception) {
            return new Outcome<>(false, null, exception);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("상대 스레드를 기다리다 시한이 지났다");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private void seedReady(Owner owner, String sha) {
        jdbc.update("""
                INSERT INTO ai_documents (booth_id, agent_id, original_filename, content_type,
                    size_bytes, s3_key, processing_status, uploaded_by_user_id, content_sha256,
                    storage_provider, storage_bucket, uploaded_at)
                VALUES (?, ?, 'seed.pdf', 'application/pdf', ?, ?, 'READY', ?, ?,
                    'R2', 'test-ai-documents', now())
                """, owner.boothId(), owner.agentId(), ONE_MB,
                "seed/" + owner.agentId() + "/" + sha, owner.userId(), sha);
    }

    private static String shaOf(int index) {
        return "%064x".formatted(index + 1);
    }

    private Owner agentOwner(String prefix) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(userId, prefix + " 부스")).getId();
        grantLease(jdbc, boothId, userId);
        Long agentId = agentService.create(boothId, userId,
                jsonMapper.readValue(MINIMAL, AiAgentService.AgentCommand.class)).agentId();
        return new Owner(userId, boothId, agentId);
    }

    private record Owner(Long userId, Long boothId, Long agentId) { }

    private record Outcome<T>(boolean succeeded, T value, RuntimeException failure) { }
}
