package com.example.ssafesta.ai;

import com.example.ssafesta.storage.FakeObjectStorage;
import com.example.ssafesta.storage.FakeObjectStorageConfiguration;
import static com.example.ssafesta.booth.BoothLayoutTestSupport.expireLease;
import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static com.example.ssafesta.booth.BoothTestSupport.releaseAllSlots;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.AccessTokenService;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.user.AccountDeletionService;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * 문서 업로드 URL 발급과 업로드 완료 (spec 007 US2 · FR-011·FR-018·FR-019a~c·FR-026~032).
 *
 * <p>T030 — 파일은 Spring 을 통과하지 않는다. 브라우저가 저장소로 직접 PUT 하므로, 여기서
 * "업로드했다"는 것은 {@link FakeObjectStorage#putObject} 다.
 */
@Import({TestcontainersConfiguration.class, FakeObjectStorageConfiguration.class,
        FakeDocumentProcessingClientConfiguration.class})
@SpringBootTest
@AutoConfigureMockMvc
class AiDocumentUploadIntegrationTest {

    private static final String AGENT = """
            {"name": "도슨트", "role": "PROJECT_DOCENT", "systemPrompt": "문서를 근거로 답한다."}""";

    private static final String SHA_A =
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
    private static final String SHA_B =
            "60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752";

    private static final long ONE_MB = 1024L * 1024;

    @Autowired private MockMvc mockMvc;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private AccessTokenService accessTokens;
    @Autowired private AiDocumentRepository documentRepository;
    @Autowired private FakeObjectStorage storage;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private AccountDeletionService deletions;
    @Autowired private FakeDocumentProcessingClient processing;
    @Autowired private DocumentJobDispatchSweeper sweeper;
    @Autowired private AiDocumentExpirySweeper expirySweeper;

    @BeforeEach
    void reset() {
        releaseAllSlots(jdbc);
        storage.reset();
        processing.reset();
    }

    // ── 발급 ────────────────────────────────────────────────────────────────

    @Test
    void aGrantNamesTheDocumentAndWhereItGoes() throws Exception {
        Owner owner = agentOwner("정상");

        String json = mockMvc.perform(uploadUrl(owner, body("project.pdf", "application/pdf",
                        ONE_MB, SHA_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.uploadUrl").exists())
                .andReturn().getResponse().getContentAsString();

        long documentId = idOf(json);
        String expectedKey = "booths/%d/agents/%d/documents/%d/project.pdf"
                .formatted(owner.boothId(), owner.agentId(), documentId);
        assertEquals(expectedKey, jsonMapper.readTree(json).get("objectKey").asString());

        AiDocument stored = documentRepository.findById(documentId).orElseThrow();
        assertEquals("QUEUED", stored.getProcessingStatus());
        assertEquals("R2", stored.getStorageProvider());
        assertEquals("test-ai-documents", stored.getStorageBucket());
        // 발급만으로 업로드가 된 것은 아니다 — 1시간 만료 판정이 이 칸을 본다.
        assertNull(stored.getUploadedAt(), "발급 시점에 uploaded_at 이 이미 차 있다");
    }

    /**
     * 허용 형식은 셋인데 PDF 만 관통돼 있었다 (spec 007 T041).
     *
     * <p>확장자와 MIME 의 짝을 서버가 검사하므로(같은 절의 {@code fileName} 거부 케이스), 나머지 둘도
     * 실제로 발급되는지 보지 않으면 "PDF 만 되는 서버" 가 형식 3종을 지원한다고 문서에 적히게 된다.
     */
    @ParameterizedTest
    @CsvSource({
            "notes.md, text/markdown",
            "notes.txt, text/plain"})
    void theOtherAcceptedFormatsAlsoGetAGrant(String fileName, String contentType) throws Exception {
        Owner owner = agentOwner("형식" + fileName.substring(fileName.indexOf('.') + 1));

        String json = mockMvc.perform(uploadUrl(owner, body(fileName, contentType, ONE_MB, SHA_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.uploadUrl").exists())
                .andReturn().getResponse().getContentAsString();

        AiDocument stored = documentRepository.findById(idOf(json)).orElseThrow();
        assertEquals(contentType, stored.getContentType());
        assertTrue(stored.getObjectKey().endsWith("/" + fileName), stored.getObjectKey());
    }

    /** 20MB 를 넘으면 <b>행을 만들지 않는다</b> — 만들면 10개 중 하나를 영영 먹는다. */
    @Test
    void aFileOverTheSizeCapIsRefusedWithoutCreatingARow() throws Exception {
        Owner owner = agentOwner("초과");

        mockMvc.perform(uploadUrl(owner, body("big.pdf", "application/pdf",
                        21L * 1024 * 1024, SHA_A)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("size"));

        assertEquals(0, documentRepository.countActive(owner.agentId()));
    }

    @ParameterizedTest
    @CsvSource({
            // 형식 화이트리스트 밖
            "report.hwp, application/x-hwp, " + SHA_A + ", contentType",
            // 확장자와 형식이 어긋난다 — 서명은 형식으로 하는데 파서는 확장자를 본다
            "report.txt, application/pdf, " + SHA_A + ", fileName",
            // objectKey 에 그대로 들어가는 값이라 경로 문자를 막는다
            "../../etc/passwd, application/pdf, " + SHA_A + ", fileName",
            // 대문자 해시는 계약 밖이다 (^[a-f0-9]{64}$)
            "report.pdf, application/pdf, 9F86D081884C7D659A2FEAA0C55AD015A3BF4F1B2B0B822CD15D6C15B0F00A08, contentSha256",
            "report.pdf, application/pdf, deadbeef, contentSha256"})
    void malformedRequestsNameTheOffendingField(String fileName, String contentType, String sha,
                                                String field) throws Exception {
        Owner owner = agentOwner("검증" + field.charAt(0) + fileName.length());

        mockMvc.perform(uploadUrl(owner, body(fileName, contentType, ONE_MB, sha)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value(field));
    }

    /** 파일 이름은 컬럼이 255자다. 통과시키면 DB 오류가 500 이 된다. */
    @Test
    void aFileNameLongerThanTheColumnIsRefused() throws Exception {
        Owner owner = agentOwner("긴이름");
        String tooLong = "가".repeat(253) + ".pdf";

        mockMvc.perform(uploadUrl(owner, body(tooLong, "application/pdf", ONE_MB, SHA_A)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("fileName"));
    }

    // ── 상한 (FR-018) ───────────────────────────────────────────────────────

    @Test
    void theEleventhDocumentIsRefused() throws Exception {
        Owner owner = agentOwner("개수");
        for (int i = 0; i < 10; i++) {
            seedActive(owner, "READY", shaOf(i), ONE_MB);
        }

        mockMvc.perform(uploadUrl(owner, body("one-more.pdf", "application/pdf", ONE_MB, SHA_A)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_LIMIT_EXCEEDED"));
    }

    @Test
    void aDocumentThatWouldPassTheTotalSizeCapIsRefused() throws Exception {
        Owner owner = agentOwner("총량");
        seedActive(owner, "READY", shaOf(0), 95L * ONE_MB);

        mockMvc.perform(uploadUrl(owner, body("big.pdf", "application/pdf", 6L * ONE_MB, SHA_A)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_LIMIT_EXCEEDED"));
    }

    /** 실패·만료 문서는 자리를 먹지 않는다 (FR-019b) — 막으면 사용자가 빠져나갈 길이 없다. */
    @Test
    void failedAndExpiredDocumentsDoNotConsumeTheQuota() throws Exception {
        Owner owner = agentOwner("비활성");
        for (int i = 0; i < 10; i++) {
            seedActive(owner, i % 2 == 0 ? "FAILED" : "EXPIRED", shaOf(i), ONE_MB);
        }

        mockMvc.perform(uploadUrl(owner, body("fresh.pdf", "application/pdf", ONE_MB, SHA_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));
    }

    // ── 중복·재발급 (FR-019b·c, #84) ────────────────────────────────────────

    /**
     * 처리에 실패한 파일은 <b>같은 파일 그대로</b> 다시 올릴 수 있다 (FR-019b).
     *
     * <p>자리를 비켜 주는 것(위 절)과 다른 조건이다. 자리가 남아도 중복 판정이 실패한 문서를
     * 붙잡고 있으면 사용자는 <b>그 파일만</b> 영영 못 올린다 — 다른 파일은 되는데 방금 실패한 그
     * 파일은 안 되는, 설명하기 어려운 상태가 된다.
     *
     * <p>S15P21A604-175 가 {@code FAILED} 를 실제로 쓰기 시작하면서 도달 가능해졌다. 그전에는
     * 이 상태를 만드는 코드가 없어 규칙만 있고 쓰이지 않았다.
     */
    @Test
    void aFailedDocumentDoesNotBlockReuploadingTheSameFile() throws Exception {
        Owner owner = agentOwner("실패후재업로드");
        long failed = seedActive(owner, "FAILED", SHA_A, ONE_MB);

        String json = mockMvc.perform(uploadUrl(owner, body("project.pdf", "application/pdf",
                        ONE_MB, SHA_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.uploadUrl").exists())
                .andReturn().getResponse().getContentAsString();

        assertNotEquals(failed, idOf(json), "실패한 행에 재발급하면 그 행이 되살아난다");
        assertEquals("QUEUED", documentRepository.findById(idOf(json)).orElseThrow()
                .getProcessingStatus());
    }

    /** 이미 올라간 파일이면 URL 을 주지 않는다. 키 자체가 빠진다 — 프론트가 duplicate 로 갈린다. */
    @Test
    void anAlreadyUploadedFileAnswersDuplicateWithoutAUrl() throws Exception {
        Owner owner = agentOwner("중복");
        long existing = seedActive(owner, "READY", SHA_A, ONE_MB);

        mockMvc.perform(uploadUrl(owner, body("project.pdf", "application/pdf", ONE_MB, SHA_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.documentId").value(existing))
                // doesNotExist 가 아니라 키 부재를 봐야 하는 자리다 — 계약이 "키가 빠진다" 이고,
                // 값이 null 로 실려 오면 계약 위반인데 doesNotExist 는 그것도 통과시킨다 (T-129).
                .andExpect(jsonPath("$.uploadUrl").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.objectKey").doesNotHaveJsonPath());
    }

    /** 아직 안 올린 발급은 다시 요청하면 <b>같은 문서</b>에 새 URL 이다 (#84 멱등 재발급). */
    @Test
    void anUnfinishedGrantIsReissuedOnTheSameDocument() throws Exception {
        Owner owner = agentOwner("재발급");
        String request = body("project.pdf", "application/pdf", ONE_MB, SHA_A);

        String first = grantJson(owner, request);
        String second = grantJson(owner, request);

        assertEquals(idOf(first), idOf(second), "재요청이 새 문서를 만들었다");
        assertEquals(keyOf(first), keyOf(second), "같은 문서인데 object key 가 달라졌다");
        assertNotEquals(urlOf(first), urlOf(second), "URL 이 재발급되지 않았다");
        assertEquals(1, documentRepository.countActive(owner.agentId()));
    }

    /**
     * 해시가 같아도 다른 파일 정보면 거절한다 (2026-08-31 확정).
     *
     * <p>해시는 클라이언트의 주장일 뿐이고(FR-019a), 상한 검사와 서명은 행의 크기로 했다.
     */
    @Test
    void aReissueWithDifferentFileFactsIsRefused() throws Exception {
        Owner owner = agentOwner("메타");
        grantJson(owner, body("project.pdf", "application/pdf", ONE_MB, SHA_A));

        mockMvc.perform(uploadUrl(owner, body("project.pdf", "application/pdf", 2 * ONE_MB, SHA_A)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("contentSha256"));
    }

    /**
     * 활성 쓰기 provider 가 바뀌면 기존 발급은 버린다 (FR-032).
     *
     * <p>옛 provider 에 올라갈 객체를 새 provider 가 읽을 수 없다.
     */
    @Test
    void aProviderSwitchAbandonsTheOldGrantAndStartsANewDocument() throws Exception {
        Owner owner = agentOwner("전환");
        String request = body("project.pdf", "application/pdf", ONE_MB, SHA_A);
        long before = idOf(grantJson(owner, request));

        storage.switchActiveProvider("MINIO_LOCAL", "fallback-bucket");
        String after = grantJson(owner, request);

        assertNotEquals(before, idOf(after), "provider 가 바뀌었는데 같은 문서를 재사용했다");
        assertEquals("EXPIRED", documentRepository.findById(before).orElseThrow()
                .getProcessingStatus());
        AiDocument fresh = documentRepository.findById(idOf(after)).orElseThrow();
        assertEquals("MINIO_LOCAL", fresh.getStorageProvider());
        assertEquals("fallback-bucket", fresh.getStorageBucket());
    }

    /**
     * bucket 만 바뀌어도 마찬가지다 — provider 이름은 그대로 {@code R2} 인데 쓰는 자리가 옮겨졌다.
     *
     * <p>이름만 비교하면 아무도 읽지 않는 옛 bucket 으로 URL 을 계속 발급한다.
     */
    @Test
    void aBucketSwitchWithinTheSameProviderAlsoStartsANewDocument() throws Exception {
        Owner owner = agentOwner("버킷전환");
        String request = body("project.pdf", "application/pdf", ONE_MB, SHA_A);
        long before = idOf(grantJson(owner, request));

        storage.switchActiveProvider("R2", "moved-bucket");
        String after = grantJson(owner, request);

        assertNotEquals(before, idOf(after), "bucket 이 바뀌었는데 옛 행을 재사용했다");
        assertEquals("EXPIRED", documentRepository.findById(before).orElseThrow()
                .getProcessingStatus());
        assertEquals("moved-bucket", documentRepository.findById(idOf(after)).orElseThrow()
                .getStorageBucket());
    }

    // ── 권한 ────────────────────────────────────────────────────────────────

    @Test
    void someoneElsesBoothIsForbidden() throws Exception {
        Owner owner = agentOwner("소유자");
        Long stranger = createMemberWithWallet(users, wallets, "남");

        mockMvc.perform(post("/api/v1/agents/{id}/documents/upload-url", owner.agentId())
                        .header("Authorization", bearerFor(stranger))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("project.pdf", "application/pdf", ONE_MB, SHA_A)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BOOTH_EDITOR_FORBIDDEN"));
    }

    @Test
    void guestsAreRefusedAtTheDoor() throws Exception {
        Owner owner = agentOwner("게스트");

        mockMvc.perform(post("/api/v1/agents/{id}/documents/upload-url", owner.agentId())
                        .header("Authorization", "Bearer " + accessTokens.issueGuestToken().token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("project.pdf", "application/pdf", ONE_MB, SHA_A)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_ONLY"));
    }

    @Test
    void anExpiredLeaseCannotUpload() throws Exception {
        Owner owner = agentOwner("만료임대");
        expireLease(jdbc, owner.boothId());

        mockMvc.perform(uploadUrl(owner, body("project.pdf", "application/pdf", ONE_MB, SHA_A)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOTH_LEASE_EXPIRED"));
    }

    // ── 완료 ────────────────────────────────────────────────────────────────

    @Test
    void completingAfterTheUploadRecordsIt() throws Exception {
        Owner owner = agentOwner("완료");
        String grant = grantJson(owner, body("project.pdf", "application/pdf", ONE_MB, SHA_A));
        storage.putObject(keyOf(grant), ONE_MB);

        mockMvc.perform(complete(owner, idOf(grant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(idOf(grant)))
                .andExpect(jsonPath("$.processingStatus").value("QUEUED"));

        assertNotNull(documentRepository.findById(idOf(grant)).orElseThrow().getUploadedAt());

        // 두 번 불러도 오류가 아니다 — 타임아웃 뒤 재시도가 실패로 보이면 안 된다.
        mockMvc.perform(complete(owner, idOf(grant))).andExpect(status().isOk());
    }

    @Test
    void completingWithoutTheObjectIsAConflict() throws Exception {
        Owner owner = agentOwner("미업로드");
        String grant = grantJson(owner, body("project.pdf", "application/pdf", ONE_MB, SHA_A));

        mockMvc.perform(complete(owner, idOf(grant)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_UPLOAD_INCOMPLETE"));
        assertNull(documentRepository.findById(idOf(grant)).orElseThrow().getUploadedAt());
    }

    /** 크기가 다르면 서명한 것과 다른 파일이 올라간 것이다. */
    @Test
    void completingWithADifferentSizeIsAConflict() throws Exception {
        Owner owner = agentOwner("크기");
        String grant = grantJson(owner, body("project.pdf", "application/pdf", ONE_MB, SHA_A));
        storage.putObject(keyOf(grant), ONE_MB + 1);

        mockMvc.perform(complete(owner, idOf(grant)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_UPLOAD_INCOMPLETE"));
    }

    /**
     * 없는 문서는 404 이고, 권한 검사보다 앞선다.
     *
     * <p>{@code readSnapshot} 이 행을 먼저 찾고 그 다음에 editor guard 를 부르므로, 남의 부스
     * 문서인지 아닌지와 무관하게 <b>존재하지 않는 id</b> 는 {@code DOCUMENT_NOT_FOUND} 로 끝난다.
     * 순서가 뒤집히면 있는지 없는지를 403 으로 알려주게 되고, 이 테스트가 그때 깨진다
     * (S15P21A604-388).
     */
    @Test
    void anUnknownDocumentIsNotFound() throws Exception {
        Owner owner = agentOwner("없는문서");

        mockMvc.perform(complete(owner, 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    // ── 만료 복구 (FR-027) ──────────────────────────────────────────────────

    @Test
    void aLateCompletionInsideTheGraceWindowRecoversTheSameDocument() throws Exception {
        Owner owner = agentOwner("복구");
        String grant = grantJson(owner, body("project.pdf", "application/pdf", ONE_MB, SHA_A));
        storage.putObject(keyOf(grant), ONE_MB);
        expireDocument(idOf(grant), Duration.ofHours(23).plusMinutes(59));

        mockMvc.perform(complete(owner, idOf(grant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(idOf(grant)))
                .andExpect(jsonPath("$.processingStatus").value("QUEUED"));

        AiDocument recovered = documentRepository.findById(idOf(grant)).orElseThrow();
        assertNull(recovered.getExpiredAt(), "복구했는데 expired_at 이 남아 있다");
        assertNotNull(recovered.getUploadedAt());
    }

    /**
     * 24시간이 지나면 <b>객체가 아직 있어도</b> 410 이다.
     *
     * <p>삭제는 sweeper 가 자기 주기로 한다. 남아 있다고 받아 주면 유예 기간이 무의미해진다.
     */
    @Test
    void aLateCompletionPastTheGraceWindowIsGoneEvenWithTheObjectPresent() throws Exception {
        Owner owner = agentOwner("유예초과");
        String grant = grantJson(owner, body("project.pdf", "application/pdf", ONE_MB, SHA_A));
        storage.putObject(keyOf(grant), ONE_MB);
        expireDocument(idOf(grant), Duration.ofHours(24).plusSeconds(1));

        mockMvc.perform(complete(owner, idOf(grant)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("DOCUMENT_UPLOAD_GONE"));
        assertEquals("EXPIRED", documentRepository.findById(idOf(grant)).orElseThrow()
                .getProcessingStatus());
    }

    @Test
    void aLateCompletionWithoutTheObjectIsGone() throws Exception {
        Owner owner = agentOwner("원본없음");
        String grant = grantJson(owner, body("project.pdf", "application/pdf", ONE_MB, SHA_A));
        expireDocument(idOf(grant), Duration.ofHours(1));

        mockMvc.perform(complete(owner, idOf(grant)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("DOCUMENT_UPLOAD_GONE"));
    }

    // ── 목록·상태 조회 (US2 시나리오 2·7 · US3 시나리오 1) ──────────────────

    /** 목록은 <b>모든 상태</b>를 최근 발급 순으로 준다 — 만료된 문서가 조용히 사라지면 안 된다. */
    @Test
    void theListShowsEveryStatusNewestFirst() throws Exception {
        Owner owner = agentOwner("목록");
        String old = grantJson(owner, body("old.pdf", "application/pdf", ONE_MB, SHA_A));
        ageGrant(idOf(old), Duration.ofHours(2));
        expirySweeper.expireAbandonedGrants();
        String recent = grantJson(owner, body("recent.pdf", "application/pdf", 2 * ONE_MB, SHA_B));

        mockMvc.perform(documents(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents.length()").value(2))
                .andExpect(jsonPath("$.documents[0].documentId").value(idOf(recent)))
                .andExpect(jsonPath("$.documents[0].fileName").value("recent.pdf"))
                .andExpect(jsonPath("$.documents[0].sizeBytes").value(2 * ONE_MB))
                .andExpect(jsonPath("$.documents[0].status").value("QUEUED"))
                .andExpect(jsonPath("$.documents[0].createdAt").isNotEmpty())
                // 발급만 됐고 바이트는 아직이다 — status 로는 구분할 수 없는 상태다.
                .andExpect(jsonPath("$.documents[0].uploadedAt").doesNotExist())
                .andExpect(jsonPath("$.documents[1].documentId").value(idOf(old)))
                .andExpect(jsonPath("$.documents[1].status").value("EXPIRED"))
                // 상한은 서버 설정이라 클라이언트가 알 수 없다. 쓴 양은 위 배열에서 읽는다.
                .andExpect(jsonPath("$.quota.countLimit").value(10))
                .andExpect(jsonPath("$.quota.bytesLimit").value(100L * 1024 * 1024))
                .andExpect(jsonPath("$.quota.count").doesNotHaveJsonPath());
    }

    /** 업로드가 확인되면 그 시각이 채워진다 — 화면이 "업로드 중" 과 "대기 중" 을 나누는 근거다. */
    @Test
    void completingFillsInTheUploadedAt() throws Exception {
        Owner owner = agentOwner("업로드시각");
        String grant = grantJson(owner, body("project.pdf", "application/pdf", ONE_MB, SHA_A));
        storage.putObject(keyOf(grant), ONE_MB);
        mockMvc.perform(complete(owner, idOf(grant))).andExpect(status().isOk());

        mockMvc.perform(documents(owner))
                .andExpect(jsonPath("$.documents[0].status").value("QUEUED"))
                .andExpect(jsonPath("$.documents[0].uploadedAt").isNotEmpty());
    }

    /**
     * 임대가 끝나도 소유자는 자기 문서를 볼 수 있다.
     *
     * <p>FR-015 는 만료 시 문서를 {@code DISABLED} 로 두되 <b>원본과 메타데이터는 보존</b>한다고
     * 한다. 조회까지 막으면 그 보존이 아무 의미가 없다 — 그래서 이 경로만 편집 권한(`requireEditor`)을
     * 쓰고 활성 임대(`requireActiveEditor`)를 요구하지 않는다.
     */
    @Test
    void anExpiredLeaseStillLetsTheOwnerReadTheList() throws Exception {
        Owner owner = agentOwner("만료임대");
        grantJson(owner, body("project.pdf", "application/pdf", ONE_MB, SHA_A));
        expireLease(jdbc, owner.boothId());

        mockMvc.perform(documents(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents.length()").value(1));
    }

    @Test
    void anotherMembersAgentIsForbidden() throws Exception {
        Owner owner = agentOwner("주인");
        Owner stranger = agentOwner("남");

        mockMvc.perform(get("/api/v1/agents/{id}/documents", owner.agentId())
                        .header("Authorization", bearerFor(stranger.userId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BOOTH_EDITOR_FORBIDDEN"));
    }

    @Test
    void listingAMissingAgentIsNotFound() throws Exception {
        Owner owner = agentOwner("없는직원");

        mockMvc.perform(get("/api/v1/agents/{id}/documents", 999_999_999L)
                        .header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AGENT_NOT_FOUND"));
    }

    // ── 만료 판정 (FR-026 · SC-010) ─────────────────────────────────────────

    /**
     * 발급만 받고 안 올린 문서는 1시간 뒤 만료되고, 갓 발급한 것은 그대로다.
     *
     * <p>{@code updated_at} 단정이 함께 있는 이유는 bulk update 가 엔티티 콜백을 타지 않기
     * 때문이다. 질의에서 그 칸을 빼면 만료된 행이 발급 시각을 그대로 들고 있게 되는데, 상태만
     * 보는 단정으로는 그게 보이지 않는다.
     */
    @Test
    void anUnusedGrantExpiresAfterAnHourAndAFreshOneDoesNot() throws Exception {
        Owner owner = agentOwner("만료");
        String stale = grantJson(owner, body("stale.pdf", "application/pdf", ONE_MB, SHA_A));
        String fresh = grantJson(owner, body("fresh.pdf", "application/pdf", ONE_MB, SHA_B));
        Instant beforeSweep = Instant.now();
        ageGrant(idOf(stale), Duration.ofHours(1).plusMinutes(1));

        expirySweeper.expireAbandonedGrants();

        AiDocument expired = documentRepository.findById(idOf(stale)).orElseThrow();
        assertEquals("EXPIRED", expired.getProcessingStatus());
        assertNotNull(expired.getExpiredAt(), "expired_at 이 비면 24시간 복구 창을 잴 수 없다");
        assertTrue(updatedAt(idOf(stale)).isAfter(beforeSweep),
                "bulk update 가 updated_at 을 안 건드렸다 — 만료된 행이 발급 시각을 들고 있다");
        assertEquals("QUEUED", documentRepository.findById(idOf(fresh)).orElseThrow()
                .getProcessingStatus());
    }

    /**
     * 업로드가 확인된 문서는 아무리 오래돼도 쓸리지 않는다.
     *
     * <p>질의의 {@code uploaded_at IS NULL} 을 지우면 여기서만 걸린다. 그 조건은 "QUEUED" 를 다시
     * 말한 것이 아니라 <b>경합 가드</b>다 — {@code /complete} 가 행을 쥔 채 커밋하는 동안 sweep 이
     * 대기하고, 깨어나서 술어를 다시 보기 때문에 그 항이 있어야 아무 일도 일어나지 않는다.
     * 없으면 방금 끝난 업로드를 만료로 덮고, 사용자에게는 {@code QUEUED} 라고 답한 뒤다.
     */
    @Test
    void aCompletedDocumentIsNeverSweptHoweverOld() throws Exception {
        Owner owner = agentOwner("완료본");
        String grant = grantJson(owner, body("project.pdf", "application/pdf", ONE_MB, SHA_A));
        storage.putObject(keyOf(grant), ONE_MB);
        mockMvc.perform(complete(owner, idOf(grant))).andExpect(status().isOk());
        ageGrant(idOf(grant), Duration.ofDays(3));

        expirySweeper.expireAbandonedGrants();

        AiDocument stored = documentRepository.findById(idOf(grant)).orElseThrow();
        assertEquals("QUEUED", stored.getProcessingStatus(), "업로드가 끝난 문서를 만료시켰다");
        assertNull(stored.getExpiredAt());
    }

    /**
     * HEAD 도중에 sweep 이 커밋해도 도착한 업로드가 이긴다.
     *
     * <p>{@code complete()} 는 읽기·HEAD·쓰기 세 단계이고 HEAD 시점에는 열린 트랜잭션이 없다.
     * 그래서 이 콜백 안의 sweep 은 상위에 얹히지 않고 자기 트랜잭션으로 커밋한다 — 운영에서
     * 일어나는 순서 그대로다. 쓰기 단계가 락을 잡고 다시 읽어 판단하므로 결과는 복구다.
     */
    @Test
    void aSweepDuringTheHeadDoesNotStealACompletionThatArrived() throws Exception {
        Owner owner = agentOwner("경합");
        String grant = grantJson(owner, body("project.pdf", "application/pdf", ONE_MB, SHA_A));
        storage.putObject(keyOf(grant), ONE_MB);
        ageGrant(idOf(grant), Duration.ofHours(2));
        storage.onHead(key -> {
            storage.onHead(k -> { });   // 한 번만
            expirySweeper.expireAbandonedGrants();
        });

        mockMvc.perform(complete(owner, idOf(grant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processingStatus").value("QUEUED"));

        AiDocument stored = documentRepository.findById(idOf(grant)).orElseThrow();
        assertNull(stored.getExpiredAt(), "복구했는데 expired_at 이 남아 있다");
        assertEquals(1, countBy("SELECT count(*) FROM ai_document_jobs WHERE document_id = ?",
                idOf(grant)));
    }

    /**
     * 만료된 grant 를 두고 같은 파일을 다시 올리기 시작했으면, 늦게 도착한 옛 grant 는 410 이다.
     *
     * <p>가드가 없으면 <b>이 테스트는 500 이다.</b> 복구가 {@code QUEUED} 로 되돌리는 순간 한
     * (agent, sha) 에 활성 행이 둘이 되어 {@code ux_ai_documents_agent_active_sha} 를 위반하고,
     * 이 경로에는 그 위반을 번역하는 catch 가 없다.
     *
     * <p>FR-027 을 약화시키지 않는다 — 원본 보존과 복구 가능성을 약속했지, 같은 파일로 이미 다시
     * 시작한 사용자를 이기는 것까지 약속하지 않았다.
     */
    @Test
    void aNewerGrantForTheSameFileSupersedesTheExpiredOne() throws Exception {
        Owner owner = agentOwner("대체됨");
        String old = grantJson(owner, body("project.pdf", "application/pdf", ONE_MB, SHA_A));
        storage.putObject(keyOf(old), ONE_MB);
        ageGrant(idOf(old), Duration.ofHours(2));
        expirySweeper.expireAbandonedGrants();

        // 만료됐으니 중복 판정에서 빠지고, 같은 파일이 새 행으로 시작한다 — 부분 인덱스의 의도다.
        String reissued = grantJson(owner, body("project.pdf", "application/pdf", ONE_MB, SHA_A));
        assertNotEquals(idOf(old), idOf(reissued), "만료된 행에 재발급되면 이 시나리오가 아니다");

        mockMvc.perform(complete(owner, idOf(old)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("DOCUMENT_UPLOAD_GONE"));

        assertEquals("EXPIRED", documentRepository.findById(idOf(old)).orElseThrow()
                .getProcessingStatus());
        assertEquals(1, countBy("""
                SELECT count(*) FROM ai_documents WHERE agent_id = ? AND content_sha256 = ?
                  AND processing_status IN ('QUEUED', 'PROCESSING', 'READY')
                """, owner.agentId(), SHA_A));
    }

    /**
     * 만료가 슬롯을 실제로 돌려준다 — 이 티켓이 존재하는 이유다 (FR-018).
     *
     * <p>발급만 받고 안 올린 문서 열 개면 그 AI 직원은 문서를 더 못 올린다. 완료는 객체가 없어
     * 거부되고, 같은 파일 재요청은 같은 행에 재발급이라 슬롯이 안 풀린다. 빠져나갈 길이 sweep
     * 하나뿐이었다.
     */
    @Test
    void expiringAnAbandonedGrantFreesTheAgentSlot() throws Exception {
        Owner owner = agentOwner("슬롯");
        for (int i = 0; i < 9; i++) {
            seedActive(owner, "READY", shaOf(i), ONE_MB);
        }
        String abandoned = grantJson(owner, body("abandoned.pdf", "application/pdf", ONE_MB, SHA_A));

        mockMvc.perform(uploadUrl(owner, body("next.pdf", "application/pdf", ONE_MB, SHA_B)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_LIMIT_EXCEEDED"));

        ageGrant(idOf(abandoned), Duration.ofHours(1).plusMinutes(1));
        expirySweeper.expireAbandonedGrants();

        mockMvc.perform(uploadUrl(owner, body("next.pdf", "application/pdf", ONE_MB, SHA_B)))
                .andExpect(status().isOk());
    }

    /**
     * 스키마가 자기 값 도메인을 지킨다 (V24).
     *
     * <p>Flyway 가 뜬다는 것은 "SQL 문법이 맞고 기존 데이터가 제약을 만족한다" 까지만 증명한다.
     * 이 제약을 넣은 이유는 raw JDBC 문자열의 오타인데, 그건 직접 써 봐야 확인된다. 오타 하나면
     * 그 문서는 중복 판정·쿼터 집계·검색 게이트 세 곳에서 동시에, 조용히 사라진다.
     */
    @Test
    void theSchemaRefusesAStatusOutsideTheContract() throws Exception {
        Owner owner = agentOwner("오타");
        String grant = grantJson(owner, body("project.pdf", "application/pdf", ONE_MB, SHA_A));

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE ai_documents SET processing_status = 'REDAY' WHERE id = ?", idOf(grant)));
    }

    // ── 저장소 장애·차단 ────────────────────────────────────────────────────

    /**
     * 문서가 이 배포에 없는 provider 를 가리키면 <b>모른다</b>는 뜻이다.
     *
     * <p>"없어졌다"(410)로 답하면 아직 있는 파일을 다시 올리라고 하게 된다.
     */
    @Test
    void aDocumentInAnUnavailableProviderAnswersUnavailable() throws Exception {
        Owner owner = agentOwner("미설정");
        String grant = grantJson(owner, body("project.pdf", "application/pdf", ONE_MB, SHA_A));
        jdbc.update("UPDATE ai_documents SET storage_provider = 'MINIO_LOCAL' WHERE id = ?",
                idOf(grant));

        mockMvc.perform(complete(owner, idOf(grant)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));
    }

    // ── 경합 (sweeper · reconcile) ──────────────────────────────────────────

    /**
     * HEAD 중에 sweeper 가 만료시키면 <b>오래된 판단으로 덮어쓰지 않는다</b>.
     *
     * <p>읽기와 쓰기 사이에 잠금이 없는 구간이 실제로 있고, 훅이 정확히 그 자리에 들어간다.
     */
    @Test
    void anExpiryCommittedDuringTheHeadIsNotOverwritten() throws Exception {
        Owner owner = agentOwner("만료경합");
        String grant = grantJson(owner, body("project.pdf", "application/pdf", ONE_MB, SHA_A));
        storage.putObject(keyOf(grant), ONE_MB);
        long documentId = idOf(grant);

        storage.onHead(key -> {
            storage.onHead(k -> { });   // 한 번만
            expireDocument(documentId, Duration.ofHours(1));
        });

        // 만료가 보이면 이제 이 요청은 복구 경로다 — 객체가 있고 유예 안이라 복구된다.
        mockMvc.perform(complete(owner, documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processingStatus").value("QUEUED"));
        assertNull(documentRepository.findById(documentId).orElseThrow().getExpiredAt());
    }

    /**
     * reconcile 이 저장 위치를 옮기면 옛 bucket 에 대한 HEAD 결과를 <b>쓰지 않는다</b>.
     *
     * <p>객체는 옮겨간 bucket 에만 있어 첫 HEAD 는 옛 bucket 을 보고 "없음" 으로 답한다. 그 답을
     * 그대로 믿으면 409 로 "다시 올려 주세요" 가 나가는데, 파일은 멀쩡히 있다.
     *
     * <p>재시도는 서버가 하지 않고 클라이언트가 한다 — 문서를 저장소 간에 옮기는 것은 reconcile
     * 이고, reconcile 은 업로드가 차단된 상태에서 운영자 승인과 재배포로만 일어난다. 한 요청 안에
     * 재배포가 끝나야 성립하는 경합이라 루프를 두지 않는다. 왕복 한 번이 아무도 못 밟는 루프보다
     * 싸다.
     */
    @Test
    void aStorageMoveDuringTheHeadIsNotTrusted() throws Exception {
        Owner owner = agentOwner("이동경합");
        String grant = grantJson(owner, body("project.pdf", "application/pdf", ONE_MB, SHA_A));
        long documentId = idOf(grant);
        storage.putObject("moved-bucket", keyOf(grant), ONE_MB);

        storage.onHead(key -> jdbc.update(
                "UPDATE ai_documents SET storage_bucket = 'moved-bucket' WHERE id = ?", documentId));

        mockMvc.perform(complete(owner, documentId))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));

        // 낡은 HEAD 로 결론내지 않았다는 증거 — 실패로도 성공으로도 표시하지 않고 그대로 둔다.
        AiDocument untouched = documentRepository.findById(documentId).orElseThrow();
        assertNull(untouched.getUploadedAt());
        assertEquals("QUEUED", untouched.getProcessingStatus());
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private RequestBuilder uploadUrl(Owner owner, String body) {
        return post("/api/v1/agents/{id}/documents/upload-url", owner.agentId())
                .header("Authorization", bearerFor(owner.userId()))
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private RequestBuilder documents(Owner owner) {
        return get("/api/v1/agents/{id}/documents", owner.agentId())
                .header("Authorization", bearerFor(owner.userId()));
    }

    private RequestBuilder complete(Owner owner, long documentId) {
        return post("/api/v1/documents/{id}/complete", documentId)
                .header("Authorization", bearerFor(owner.userId()));
    }

    private static String body(String fileName, String contentType, long size, String sha) {
        return """
                {"fileName": "%s", "contentType": "%s", "size": %d, "contentSha256": "%s"}"""
                .formatted(fileName, contentType, size, sha);
    }

    private String grantJson(Owner owner, String request) throws Exception {
        return mockMvc.perform(uploadUrl(owner, request))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private long idOf(String json) {
        return jsonMapper.readTree(json).get("documentId").asLong();
    }

    private String keyOf(String json) {
        return jsonMapper.readTree(json).get("objectKey").asString();
    }

    private String urlOf(String json) {
        return jsonMapper.readTree(json).get("uploadUrl").asString();
    }

    /** 발급 경로를 거치지 않고 상한·중복 상태를 만든다. */
    private long seedActive(Owner owner, String status, String sha, long size) {
        jdbc.update("""
                INSERT INTO ai_documents (booth_id, agent_id, original_filename, content_type,
                    size_bytes, s3_key, processing_status, uploaded_by_user_id, content_sha256,
                    storage_provider, storage_bucket, uploaded_at)
                VALUES (?, ?, ?, 'application/pdf', ?, ?, ?, ?, ?, 'R2', 'test-ai-documents', now())
                """, owner.boothId(), owner.agentId(), sha.substring(0, 8) + ".pdf", size,
                "seed/" + owner.agentId() + "/" + sha, status, owner.userId(), sha);
        return jdbc.queryForObject(
                "SELECT id FROM ai_documents WHERE agent_id = ? AND content_sha256 = ?",
                Long.class, owner.agentId(), sha);
    }

    /** 만료를 과거로 밀어 유예 경계를 만든다 — Clock 주입 없이 경계를 결정적으로 볼 수 있다. */
    private void expireDocument(long documentId, Duration ago) {
        jdbc.update("UPDATE ai_documents SET processing_status = 'EXPIRED', expired_at = ? "
                        + "WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(ago)), documentId);
    }

    /**
     * 탈퇴가 문서 객체 좌표를 삭제 큐에 남긴다 (docs/26 2026-08-19 · S15P21A604-485).
     *
     * <p>바이트는 저장소에 있고 좌표는 행에만 있다. 행을 먼저 지우면 객체를 다시 찾을 방법이 없어
     * 영구히 남는다 — 탈퇴 즉시 전체 하드삭제라는 결정과 정면으로 어긋난다.
     *
     * <p>여기서 객체를 직접 지우지 않는 이유는 게임 에셋과 같다. 저장소 실패가 탈퇴를 되돌리거나
     * 커밋 뒤에 조용히 유실되기 때문에, 같은 커밋이 큐 행을 쓰고 sweeper 가 재시도한다.
     */
    @Test
    void withdrawingHandsTheDocumentObjectsToTheDeleteQueue() throws Exception {
        Owner owner = agentOwner("탈퇴문서");
        String json = mockMvc.perform(uploadUrl(owner, body("project.pdf", "application/pdf",
                        ONE_MB, SHA_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String objectKey = jsonMapper.readTree(json).get("objectKey").asString();

        deletions.deleteUserGraph(owner.userId());

        assertEquals(0, countBy("SELECT count(*) FROM ai_documents WHERE s3_key = ?", objectKey),
                "문서 행은 탈퇴와 함께 사라진다");
        assertEquals(1, countBy("SELECT count(*) FROM game_asset_delete_queue WHERE object_key = ?",
                objectKey), "객체 좌표가 큐에 없으면 저장소에 영구히 남는다");
    }

    // ── FastAPI 위임 (S15P21A604-175) ───────────────────────────────────────

    /**
     * 업로드가 확인되면 Job 이 생기고 그 스냅샷이 계약 형태로 FastAPI 에 나간다.
     *
     * <p>Job 이 <b>호출보다 먼저</b> 커밋되는 것이 이 절의 핵심이다. 결과 수신은
     * {@code jobId + attemptNo} 로 펜싱하므로, 행이 없는 상태에서 워커가 먼저 시작하면 돌려보낼
     * 곳이 없고 모든 콜백이 stale 이 된다.
     */
    @Test
    void completingCreatesTheJobAndHandsItToFastApi() throws Exception {
        Owner owner = agentOwner("위임");
        String grant = grantJson(owner, body("project.pdf", "application/pdf", ONE_MB, SHA_A));
        storage.putObject(keyOf(grant), ONE_MB);

        mockMvc.perform(complete(owner, idOf(grant))).andExpect(status().isOk());

        long documentId = idOf(grant);
        Map<String, Object> job = jdbc.queryForMap(
                "SELECT * FROM ai_document_jobs WHERE document_id = ?", documentId);
        assertEquals("QUEUED", job.get("status"));
        assertEquals(0, job.get("attempt_no"));
        assertNotNull(job.get("next_retry_at"), "재배차 기준이 비면 sweeper 가 이 Job 을 못 본다");

        DocumentProcessingClient.ProcessingRequest sent = processing.onlyRequest();
        assertEquals(((Number) job.get("id")).longValue(), sent.jobId(), "보낸 jobId 가 그 행이어야 한다");
        assertEquals(0, sent.attemptNo());
        assertEquals(documentId, sent.documentId());
        assertEquals(owner.boothId(), sent.boothId());
        assertEquals(owner.agentId(), sent.agentId());
        assertEquals("project.pdf", sent.originalFilename());
        assertEquals("application/pdf", sent.contentType());
        assertEquals(ONE_MB, sent.fileSizeBytes());
        assertEquals("R2", sent.storageProvider());
        assertEquals("test-ai-documents", sent.storageBucket());
        assertEquals(keyOf(grant), sent.objectKey());
        assertEquals(SHA_A, sent.sourceHash());
    }

    /** 같은 문서를 두 번 완료해도 Job 은 하나다 — 두 번째는 업로드 관문 뒤라 조기 반환된다. */
    @Test
    void completingTwiceCreatesOneJob() throws Exception {
        Owner owner = agentOwner("위임중복");
        String grant = grantJson(owner, body("project.pdf", "application/pdf", ONE_MB, SHA_A));
        storage.putObject(keyOf(grant), ONE_MB);

        mockMvc.perform(complete(owner, idOf(grant))).andExpect(status().isOk());
        mockMvc.perform(complete(owner, idOf(grant))).andExpect(status().isOk());

        assertEquals(1, countBy("SELECT count(*) FROM ai_document_jobs WHERE document_id = ?",
                idOf(grant)), "활성 Job 은 문서당 하나다");
        assertEquals(1, processing.received().size(), "두 번째 완료는 위임을 다시 내지 않는다");
    }

    /**
     * 위임이 실패해도 업로드 완료는 성공한다. 바이트는 이미 저장됐고 Job 도 커밋됐다.
     *
     * <p>남의 서비스가 안 된다고 사용자 요청을 깨면, 이미 올라간 파일을 다시 올리라고 하는 셈이다.
     */
    @Test
    void aFailedDelegationStillCompletesTheUploadAndLeavesTheJobQueued() throws Exception {
        Owner owner = agentOwner("위임실패");
        String grant = grantJson(owner, body("project.pdf", "application/pdf", ONE_MB, SHA_A));
        storage.putObject(keyOf(grant), ONE_MB);
        processing.failWith(new DocumentProcessingUnavailableException("연결할 수 없습니다."));

        mockMvc.perform(complete(owner, idOf(grant))).andExpect(status().isOk());

        Map<String, Object> job = jdbc.queryForMap(
                "SELECT status, attempt_no, next_retry_at FROM ai_document_jobs WHERE document_id = ?",
                idOf(grant));
        assertEquals("QUEUED", job.get("status"), "실패한 위임의 Job 은 재시도 가능한 상태로 남아야 한다");
        assertNotNull(job.get("next_retry_at"));
    }

    /**
     * 배차기가 밀린 Job 을 같은 attempt 로 다시 보낸다.
     *
     * <p>{@code attempt_no} 를 올리면 안 된다. 그 값이 결과 수신의 펜스라서, 올리면 앞선 호출을
     * 실제로 받은 워커의 콜백이 전부 409 가 된다 — 확인하지 못한 배달을 확실한 실패로 바꾸는 셈이다.
     */
    @Test
    void theSweeperResendsAnUndeliveredJobWithTheSameAttempt() throws Exception {
        Owner owner = agentOwner("재배차");
        String grant = grantJson(owner, body("project.pdf", "application/pdf", ONE_MB, SHA_A));
        storage.putObject(keyOf(grant), ONE_MB);
        processing.failWith(new DocumentProcessingUnavailableException("연결할 수 없습니다."));
        mockMvc.perform(complete(owner, idOf(grant))).andExpect(status().isOk());
        processing.reset();

        // 시간을 재우지 않고 SQL 로 민다 — 생성 시 넣은 1분 대기를 지나게 한다.
        jdbc.update("UPDATE ai_document_jobs SET next_retry_at = now() - interval '1 minute'"
                + " WHERE document_id = ?", idOf(grant));
        sweeper.dispatchDueJobs();

        DocumentProcessingClient.ProcessingRequest resent = processing.onlyRequest();
        assertEquals(0, resent.attemptNo(), "재배차는 attempt 를 올리지 않는다");
        assertEquals(idOf(grant), resent.documentId());
        assertEquals(0, jdbc.queryForObject(
                "SELECT attempt_no FROM ai_document_jobs WHERE document_id = ?",
                Integer.class, idOf(grant)));
    }

    /** 배달된 Job 은 batch·heartbeat 가 RUNNING 으로 올리므로 배차 대상에서 빠진다. */
    @Test
    void theSweeperLeavesADeliveredJobAlone() throws Exception {
        Owner owner = agentOwner("배달됨");
        String grant = grantJson(owner, body("project.pdf", "application/pdf", ONE_MB, SHA_A));
        storage.putObject(keyOf(grant), ONE_MB);
        mockMvc.perform(complete(owner, idOf(grant))).andExpect(status().isOk());
        processing.reset();

        jdbc.update("UPDATE ai_document_jobs SET status = 'RUNNING',"
                + " next_retry_at = now() - interval '1 minute' WHERE document_id = ?", idOf(grant));
        sweeper.dispatchDueJobs();

        assertEquals(List.of(), processing.received(), "RUNNING 인 Job 을 다시 보내면 워커가 둘이 된다");
    }

    private int countBy(String sql, Object... arguments) {
        Integer found = jdbc.queryForObject(sql, Integer.class, arguments);
        return found == null ? 0 : found;
    }

    /** 발급 시각을 과거로 민다 — 1시간 만료 판정이 보는 칸이 {@code created_at} 이다. */
    private void ageGrant(long documentId, Duration ago) {
        jdbc.update("UPDATE ai_documents SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(ago)), documentId);
    }

    private Instant updatedAt(long documentId) {
        return jdbc.queryForObject("SELECT updated_at FROM ai_documents WHERE id = ?",
                java.sql.Timestamp.class, documentId).toInstant();
    }

    private static String shaOf(int index) {
        return "%064x".formatted(index + 1);
    }

    private Owner agentOwner(String prefix) throws Exception {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(userId, prefix + " 부스")).getId();
        grantLease(jdbc, boothId, userId);
        String json = mockMvc.perform(post("/api/v1/booths/{id}/agents", boothId)
                        .header("Authorization", bearerFor(userId))
                        .contentType(MediaType.APPLICATION_JSON).content(AGENT))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new Owner(userId, boothId, jsonMapper.readTree(json).get("agentId").asLong());
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }

    private record Owner(Long userId, Long boothId, Long agentId) {
    }
}
