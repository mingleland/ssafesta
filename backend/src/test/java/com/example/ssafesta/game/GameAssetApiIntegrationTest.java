package com.example.ssafesta.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The upload round trip end to end (contract game-asset-upload.md).
 *
 * <p>Pinned at the HTTP boundary because that is where the FE meets it: {@code
 * remoteAssetRepository.ts} is already written against these three calls, and a field renamed or a
 * status changed here shows up as a broken editor rather than as a failing unit test.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class GameAssetApiIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private MockMvc mockMvc;
    @Autowired private GameRepository games;
    @Autowired private UserRepository users;
    @Autowired private MemberSessionService sessions;

    @Test
    void startIssuesTheGrantShapeTheFrontendParses() throws Exception {
        Owner owner = owner("발급");
        byte[] image = png(16, 16);

        mockMvc.perform(startRequest(owner, image.length))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UPLOADING"))
                // The FE rejects anything that does not match its STABLE_ID pattern, so the shape of
                // the id is part of the contract and not an implementation detail.
                .andExpect(jsonPath("$.assetId").value(org.hamcrest.Matchers.matchesPattern(
                        "^[A-Za-z][A-Za-z0-9_-]{0,63}$")))
                .andExpect(jsonPath("$.uploadUrl").isNotEmpty())
                .andExpect(jsonPath("$.requiredHeaders['Content-Type']").value("image/png"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    void theWholeRoundTripEndsWithTheImageComingBack() throws Exception {
        Owner owner = owner("왕복");
        byte[] image = png(24, 12);
        Uploaded uploaded = upload(owner, image);

        mockMvc.perform(get(contentPath(owner.gameId(), uploaded.assetId()))
                        .header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "max-age=300, private"))
                .andExpect(content().bytes(image));
    }

    /**
     * {@code complete} answers the same thing however many times it is called.
     *
     * <p>The FE retries it, so a second call re-running verification would mean the stored metadata
     * could change under a Draft that already references the asset.
     */
    @Test
    void completeIsIdempotent() throws Exception {
        Owner owner = owner("멱등");
        Uploaded uploaded = upload(owner, png(10, 10));

        String second = mockMvc.perform(completeRequest(owner, uploaded.assetId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andReturn().getResponse().getContentAsString();

        assertEquals(uploaded.completeBody(), second);
    }

    /**
     * Once verified, the same upload URL cannot replace the bytes.
     *
     * <p>This is the hole the contract described for {@code FAILED} and left open for {@code READY}:
     * a grant is not a one-time token, so without this the row's checksum and dimensions would
     * describe one image while {@code /content} served another.
     */
    @Test
    void anUploadUrlCannotBeReusedAfterTheAssetIsReady() throws Exception {
        Owner owner = owner("덮어쓰기");
        byte[] original = png(20, 20);
        Uploaded uploaded = upload(owner, original);

        mockMvc.perform(put(uploaded.uploadUrl()).contentType(MediaType.IMAGE_PNG).content(png(30, 30)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GAME_ASSET_FORBIDDEN"));

        mockMvc.perform(get(contentPath(owner.gameId(), uploaded.assetId()))
                        .header("Authorization", bearerFor(owner.userId())))
                .andExpect(content().bytes(original));
    }

    @Test
    void aWrongTokenCannotUpload() throws Exception {
        Owner owner = owner("토큰");
        byte[] image = png(10, 10);
        JsonNode grant = start(owner, image.length);

        mockMvc.perform(put(grant.get("uploadUrl").asText().replaceAll("t=.*$", "t=forged"))
                        .contentType(MediaType.IMAGE_PNG).content(image))
                .andExpect(status().isForbidden());
    }

    /**
     * Completing without having uploaded anything is recorded, not rolled back.
     *
     * <p>Answering {@code 200} with {@code FAILED} is what lets the row keep the reason. Throwing
     * would undo the transaction and leave the asset {@code UPLOADING}, to be retried forever.
     */
    @Test
    void completingWithNoUploadFailsTheAssetAndSaysWhy() throws Exception {
        Owner owner = owner("업로드누락");
        JsonNode grant = start(owner, png(8, 8).length);

        mockMvc.perform(completeRequest(owner, grant.get("assetId").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureRule").value("UPLOAD_MISSING"));
    }

    @Test
    void aDisguisedFileFailsVerificationAndIsNotServed() throws Exception {
        Owner owner = owner("위장");
        byte[] disguised = new byte[64];
        System.arraycopy(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 0,
                disguised, 0, 8);
        JsonNode grant = start(owner, disguised.length);
        String assetId = grant.get("assetId").asText();

        mockMvc.perform(put(grant.get("uploadUrl").asText())
                .contentType(MediaType.IMAGE_PNG).content(disguised)).andExpect(status().isNoContent());
        mockMvc.perform(completeRequest(owner, assetId))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureRule").value("DECODE_FAILED"));
        mockMvc.perform(get(contentPath(owner.gameId(), assetId))
                        .header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GAME_ASSET_NOT_READY"));
    }

    // ── Draft/Publish 연동 (contract §9) ────────────────────────────────────

    @Test
    void aDraftMayReferenceAReadyAssetAndMayNotReferenceAnUnfinishedOne() throws Exception {
        Owner owner = owner("초안참조");
        JsonNode pending = start(owner, png(8, 8).length);

        mockMvc.perform(saveDraft(owner, 0, sourced(owner.gameId(),
                        "asset://game/" + owner.gameId() + "/" + pending.get("assetId").asText())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GAME_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].rule").value("ASSET_SOURCE_INVALID"));

        Uploaded ready = upload(owner, png(8, 8));
        mockMvc.perform(saveDraft(owner, 0, sourced(owner.gameId(), ready.source())))
                .andExpect(status().isOk());
    }

    /** Another game's asset is refused on the string, before anything is looked up (§2). */
    @Test
    void aDraftMayNotReferenceAnotherGamesAsset() throws Exception {
        Owner owner = owner("타게임");
        Owner other = owner("타게임소유자");
        Uploaded elsewhere = upload(other, png(8, 8));

        mockMvc.perform(saveDraft(owner, 0, sourced(owner.gameId(), elsewhere.source())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].rule").value("ASSET_SOURCE_INVALID"));
    }

    // ── 공개 판정 (contract §3.4 · §7) ──────────────────────────────────────

    @Test
    void aStrangerCannotReadAnAssetOfAPrivateGame() throws Exception {
        Owner owner = owner("비공개");
        Owner stranger = owner("남");
        Uploaded uploaded = upload(owner, png(8, 8));

        mockMvc.perform(get(contentPath(owner.gameId(), uploaded.assetId()))
                        .header("Authorization", bearerFor(stranger.userId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GAME_ASSET_FORBIDDEN"));
    }

    /**
     * Public is a property of the game; being readable is a property of the published revision.
     *
     * <p>Treating "the game is PUBLIC" as sufficient would publish every image the owner ever
     * uploaded, including ones that exist only in an unpublished draft. Both halves of that are
     * asserted here against the same published game.
     */
    @Test
    void onlyTheAssetsTheseVisitorsCanSeeInThePublishedGameAreReadable() throws Exception {
        Owner owner = owner("공개");
        Owner visitor = owner("방문자");
        Uploaded shown = upload(owner, png(8, 8));
        Uploaded unreferenced = upload(owner, png(9, 9));

        mockMvc.perform(saveDraft(owner, 0, sourced(owner.gameId(), shown.source())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/games/" + owner.gameId() + "/publish")
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(GameTestSupport.publishRequest(1)))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/games/" + owner.gameId())
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"PUBLIC\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(contentPath(owner.gameId(), shown.assetId()))
                        .header("Authorization", bearerFor(visitor.userId())))
                .andExpect(status().isOk());
        mockMvc.perform(get(contentPath(owner.gameId(), unreferenced.assetId()))
                        .header("Authorization", bearerFor(visitor.userId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GAME_ASSET_FORBIDDEN"));
    }

    @Test
    void everyIssuedAssetIdIsDifferent() throws Exception {
        Owner owner = owner("식별자");

        assertNotEquals(start(owner, 64).get("assetId").asText(),
                start(owner, 64).get("assetId").asText());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private Uploaded upload(Owner owner, byte[] image) throws Exception {
        JsonNode grant = start(owner, image.length);
        String assetId = grant.get("assetId").asText();
        String uploadUrl = grant.get("uploadUrl").asText();

        mockMvc.perform(put(uploadUrl).contentType(MediaType.IMAGE_PNG).content(image))
                .andExpect(status().isNoContent());
        MvcResult completed = mockMvc.perform(completeRequest(owner, assetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andReturn();
        String body = completed.getResponse().getContentAsString();
        String source = MAPPER.readTree(body).get("source").asText();
        assertTrue(source.startsWith("asset://game/" + owner.gameId() + "/"), source);
        return new Uploaded(assetId, uploadUrl, source, body);
    }

    private JsonNode start(Owner owner, int byteSize) throws Exception {
        String body = mockMvc.perform(startRequest(owner, byteSize))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder startRequest(
            Owner owner, int byteSize) {
        return post("/api/v1/games/" + owner.gameId() + "/assets")
                .header("Authorization", bearerFor(owner.userId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"IMAGE\",\"contentType\":\"image/png\",\"byteSize\":" + byteSize
                        + ",\"fileName\":\"sprite.png\"}");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder completeRequest(
            Owner owner, String assetId) {
        return post("/api/v1/games/" + owner.gameId() + "/assets/" + assetId + "/complete")
                .header("Authorization", bearerFor(owner.userId()));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder saveDraft(
            Owner owner, int expectedRevision, ObjectNode project) {
        return put("/api/v1/games/" + owner.gameId() + "/draft")
                .header("Authorization", bearerFor(owner.userId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(GameTestSupport.saveRequest(expectedRevision, project));
    }

    private ObjectNode sourced(Long gameId, String source) {
        ObjectNode project = GameTestSupport.validProjectFor(gameId);
        ((ObjectNode) project.withArray("assets").get(0)).put("source", source);
        return project;
    }

    private String contentPath(Long gameId, String assetId) {
        return "/api/v1/games/" + gameId + "/assets/" + assetId + "/content";
    }

    private byte[] png(int width, int height) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setColor(new Color(0x2F, 0x6F, 0xDF));
            graphics.fillRect(0, 0, width, height);
            graphics.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Owner owner(String prefix) {
        Long userId = GameTestSupport.createMember(users, prefix);
        Long gameId = games.save(new Game(userId, prefix + " 게임")).getId();
        return new Owner(userId, gameId);
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }

    private record Owner(Long userId, Long gameId) { }

    private record Uploaded(String assetId, String uploadUrl, String source, String completeBody) { }
}
