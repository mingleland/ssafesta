package com.example.ssafesta.ai;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.expireLease;
import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static com.example.ssafesta.booth.BoothTestSupport.releaseAllSlots;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.AccessTokenService;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * "업로드했다"는 것은 {@link FakeDocumentStorage#putObject} 다.
 */
@Import({TestcontainersConfiguration.class, FakeDocumentStorageConfiguration.class})
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
    @Autowired private FakeDocumentStorage storage;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;

    @BeforeEach
    void reset() {
        releaseAllSlots(jdbc);
        storage.reset();
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
