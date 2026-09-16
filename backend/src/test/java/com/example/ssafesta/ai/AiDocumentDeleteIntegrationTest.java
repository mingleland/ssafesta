package com.example.ssafesta.ai;

import com.example.ssafesta.storage.FakeObjectStorageConfiguration;
import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static com.example.ssafesta.booth.BoothTestSupport.releaseAllSlots;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** 사용자가 누르는 삭제 버튼 (spec 007 FR-012, S15P21A604-831). */
@Import({TestcontainersConfiguration.class, FakeObjectStorageConfiguration.class})
@SpringBootTest
@AutoConfigureMockMvc
class AiDocumentDeleteIntegrationTest {

    private static final String AGENT = """
            {"name": "도슨트", "role": "PROJECT_DOCENT", "systemPrompt": "문서를 근거로 답한다."}""";

    @Autowired private MockMvc mockMvc;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;

    private Long userId;

    @BeforeEach
    void freeSlots() {
        releaseAllSlots(jdbc);
    }

    @Test
    void ownerCanDeleteAReadyDocumentAndItLeavesTheListAndQueuesStorageCleanup() throws Exception {
        long agentId = agent("소유자삭제");
        long documentId = seedDocument(agentId, "READY", "docs/owner-delete/" + agentId);
        seedSearchableChunk(documentId, agentId);

        mockMvc.perform(delete("/api/v1/documents/{id}", documentId)
                        .header("Authorization", bearer()))
                .andExpect(status().isNoContent());

        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_documents WHERE id = ?", Integer.class, documentId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_document_chunks WHERE document_id = ?",
                Integer.class, documentId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM game_asset_delete_queue WHERE object_key = ?",
                Integer.class, "docs/owner-delete/" + agentId));
    }

    @Test
    void ownerCanDeleteAQueuedDocumentBeforeUploadCompletes() throws Exception {
        long agentId = agent("업로드전삭제");
        long documentId = seedAwaitingDocument(agentId, "docs/awaiting/" + agentId);

        mockMvc.perform(delete("/api/v1/documents/{id}", documentId)
                        .header("Authorization", bearer()))
                .andExpect(status().isNoContent());

        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_documents WHERE id = ?", Integer.class, documentId));
    }

    @Test
    void deletingSomeoneElsesDocumentIsForbidden() throws Exception {
        long agentId = agent("문서소유자");
        long documentId = seedDocument(agentId, "READY", "docs/forbidden/" + agentId);
        Long otherUserId = createMemberWithWallet(users, wallets, "다른사람");
        String otherBearer = "Bearer " + sessions.issue(otherUserId).accessToken();

        mockMvc.perform(delete("/api/v1/documents/{id}", documentId)
                        .header("Authorization", otherBearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BOOTH_EDITOR_FORBIDDEN"));

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_documents WHERE id = ?", Integer.class, documentId));
    }

    @Test
    void deletingAProcessingDocumentIsRefused() throws Exception {
        long agentId = agent("처리중삭제");
        long documentId = seedDocument(agentId, "PROCESSING", "docs/processing/" + agentId);

        mockMvc.perform(delete("/api/v1/documents/{id}", documentId)
                        .header("Authorization", bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_DELETABLE"));

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_documents WHERE id = ?", Integer.class, documentId));
    }

    @Test
    void deletingAnUploadedQueuedDocumentIsRefused() throws Exception {
        long agentId = agent("처리대기삭제");
        long documentId = seedDocument(agentId, "QUEUED", "docs/queued/" + agentId);

        mockMvc.perform(delete("/api/v1/documents/{id}", documentId)
                        .header("Authorization", bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_DELETABLE"));

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_documents WHERE id = ?", Integer.class, documentId));
    }

    @Test
    void ownerCanDeleteADisabledDocumentAfterTheLeaseEnds() throws Exception {
        long agentId = agent("임대종료삭제");
        long documentId = seedDocument(agentId, "DISABLED", "docs/disabled/" + agentId);
        releaseAllSlots(jdbc);

        mockMvc.perform(delete("/api/v1/documents/{id}", documentId)
                        .header("Authorization", bearer()))
                .andExpect(status().isNoContent());

        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_documents WHERE id = ?", Integer.class, documentId));
    }

    @Test
    void deletingANonexistentDocumentIs404() throws Exception {
        agent("존재안함");

        mockMvc.perform(delete("/api/v1/documents/{id}", 9_999_999L)
                        .header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    private long agent(String prefix) throws Exception {
        userId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(userId, prefix + " 부스")).getId();
        grantLease(jdbc, boothId, userId);
        String json = mockMvc.perform(post("/api/v1/booths/{id}/agents", boothId)
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(AGENT))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return jsonMapper.readTree(json).get("agentId").asLong();
    }

    private long seedDocument(long agentId, String status, String objectKey) {
        return seedDocument(agentId, status, objectKey, true);
    }

    private long seedAwaitingDocument(long agentId, String objectKey) {
        return seedDocument(agentId, "QUEUED", objectKey, false);
    }

    private long seedDocument(long agentId, String status, String objectKey, boolean uploaded) {
        return jdbc.queryForObject("""
                INSERT INTO ai_documents (booth_id, agent_id, original_filename, content_type,
                    size_bytes, s3_key, processing_status, uploaded_by_user_id, content_sha256,
                    storage_provider, storage_bucket, uploaded_at)
                SELECT booth_id, id, 'v1.pdf', 'application/pdf', 1048576, ?, ?, ?,
                       '2222222222222222222222222222222222222222222222222222222222222222',
                       'R2', 'test-ai-documents', CASE WHEN ? THEN now() ELSE NULL END
                  FROM ai_agents WHERE id = ?
                RETURNING id
                """, Long.class, objectKey, status, userId, uploaded, agentId);
    }

    private void seedSearchableChunk(long documentId, long agentId) {
        jdbc.update("""
                INSERT INTO ai_document_chunks (document_id, booth_id, agent_id, chunk_no, content,
                    embedding, embedding_model_id, searchable)
                SELECT ?, booth_id, id, 0, '삭제 후 검색되면 안 되는 근거',
                       CAST(('[' || repeat('0,', 1535) || '0]') AS vector),
                       'test-embedding-model', TRUE
                  FROM ai_agents WHERE id = ?
                """, documentId, agentId);
    }

    private String bearer() {
        return "Bearer " + sessions.issue(userId).accessToken();
    }
}
