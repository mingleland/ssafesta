package com.example.ssafesta.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import com.example.ssafesta.storage.FakeObjectStorage;
import com.example.ssafesta.storage.FakeObjectStorageConfiguration;
import com.example.ssafesta.storage.StorageUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
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
 *
 * <p>The middle step is not an HTTP call any more — the browser {@code PUT}s straight into the
 * bucket — so {@link FakeObjectStorage#putBytes} stands in for it. The object key is written out
 * here rather than read from the service: its shape is in contract §8, and a key that quietly
 * changed shape would still round-trip through a helper that asked the service for it.
 */
@Import({TestcontainersConfiguration.class, FakeObjectStorageConfiguration.class})
@SpringBootTest
@AutoConfigureMockMvc
class GameAssetApiIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private MockMvc mockMvc;
    @Autowired private GameRepository games;
    @Autowired private UserRepository users;
    @Autowired private MemberSessionService sessions;
    @Autowired private FakeObjectStorage storage;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private GameAssetDeleteQueue deleteQueue;

    @BeforeEach
    void resetStorage() {
        storage.reset();
        jdbc.update("DELETE FROM game_asset_delete_queue");
    }

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
                // Absolute and pointing at the bucket, not at this application. A relative URL
                // would mean the presigned path silently regressed to a Spring endpoint.
                .andExpect(jsonPath("$.uploadUrl").value(
                        org.hamcrest.Matchers.startsWith("https://")))
                .andExpect(jsonPath("$.requiredHeaders['Content-Type']").value("image/png"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    /**
     * The signature has to name the object the row names, for exactly as long as the row's grant.
     *
     * <p>Nothing else in this class looks at {@code uploadUrl} — the middle step is faked by writing
     * to the key directly — so a {@code presignPut} handed the wrong key, the wrong bucket or a
     * longer lifetime would round-trip perfectly and still be a grant to write somewhere else. A
     * signature outliving {@code expiresAt} is the specific one that bites: bytes land after
     * {@code complete} has already refused the row, and the object is left with nothing pointing at
     * it (§3.1).
     */
    @Test
    void theGrantSignsTheObjectTheRowNamesForTheGrantsOwnLifetime() throws Exception {
        Owner owner = owner("서명대상");

        JsonNode grant = start(owner, 4096);
        String uploadUrl = grant.get("uploadUrl").asText();

        assertTrue(uploadUrl.contains("/test-ai-documents/"
                        + objectKey(owner.gameId(), grant.get("assetId").asText())),
                "서명이 이 행의 bucket·key 를 가리키지 않는다: " + uploadUrl);
        assertTrue(uploadUrl.contains("&len=4096"), "선언 크기가 서명에 실리지 않았다: " + uploadUrl);
        assertTrue(uploadUrl.contains("&ttl=" + GameAssetService.GRANT_TTL.toSeconds()),
                "서명 수명이 GRANT_TTL 과 다르다: " + uploadUrl);

        Instant expiresAt = Instant.parse(grant.get("expiresAt").asText());
        Duration drift = Duration.between(Instant.now().plus(GameAssetService.GRANT_TTL), expiresAt)
                .abs();
        assertTrue(drift.compareTo(Duration.ofMinutes(1)) < 0,
                "expiresAt 이 GRANT_TTL 과 어긋난다: " + expiresAt);
    }

    // ── 발급 거절 (contract §3.1 · §6) ──────────────────────────────────────

    /**
     * {@code AUDIO} is refused rather than defaulted to {@code IMAGE} (§1).
     *
     * <p>The FE's port type carries {@code AUDIO} and v1 does not support it, so the enum's silence
     * has to become an answer. Falling back to {@code IMAGE} would issue a grant, take the bytes and
     * fail them at verification — a decode error for a file that was never wrong.
     */
    @Test
    void aKindOutsideTheSupportedSetIsRefused() throws Exception {
        Owner owner = owner("종류");

        mockMvc.perform(startRequest(owner, "\"AUDIO\"", "\"audio/mpeg\"", "4096"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GAME_ASSET_KIND_UNSUPPORTED"))
                .andExpect(jsonPath("$.errors[0].rule").value("KIND_UNSUPPORTED"));
    }

    /**
     * The declared type is refused early when it is one we would never accept anyway.
     *
     * <p>Early, and only early: this decides nothing about what is stored — {@link
     * GameAssetImageValidator} re-reads the bytes at {@code complete}. Refusing here saves the
     * round trip for the cases that cannot possibly pass, and {@code image/svg+xml} is the one that
     * matters because SVG carries script.
     */
    @Test
    void aDeclaredTypeOutsideTheAllowlistIsRefused() throws Exception {
        Owner owner = owner("선언타입");

        for (String declared : List.of("\"image/svg+xml\"", "\"application/pdf\"", "null")) {
            mockMvc.perform(startRequest(owner, "\"IMAGE\"", declared, "4096"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.code").value("GAME_ASSET_TYPE_UNSUPPORTED"))
                    .andExpect(jsonPath("$.errors[0].rule").value("MIME_NOT_ALLOWED"));
        }
    }

    /**
     * Both ends of the declared size, and the absent one.
     *
     * <p>A missing {@code byteSize} becomes {@code 0} in the controller, so it lands on the same
     * refusal as a declared zero rather than on a {@code NullPointerException}. Zero matters on its
     * own: a grant for an empty file is a signature nobody can use, issued against the quota.
     */
    @Test
    void aDeclaredSizeOutsideTheBoundsIsRefused() throws Exception {
        Owner owner = owner("선언크기");
        String overLimit = String.valueOf(GameAssetImageValidator.MAX_BYTES + 1);

        for (String declared : List.of("0", "-1", "null", overLimit)) {
            mockMvc.perform(startRequest(owner, "\"IMAGE\"", "\"image/png\"", declared))
                    .andExpect(status().isPayloadTooLarge())
                    .andExpect(jsonPath("$.code").value("GAME_ASSET_TOO_LARGE"))
                    .andExpect(jsonPath("$.errors[0].rule").value("SIZE_EXCEEDED"));
        }
    }

    /**
     * Only the owner writes. Reading is judged separately and is already covered below.
     *
     * <p>Both write calls, not just the first: {@code complete} is the one that would be reached by
     * an id leaked out of a shared editor session, and it is a different method with its own guard
     * call. 403 rather than 404 — the contract names {@code GAME_FORBIDDEN} for exactly this and the
     * caller already knows the game id it asked for.
     */
    @Test
    void aStrangerMayNeitherStartAnUploadNorCompleteOne() throws Exception {
        Owner owner = owner("쓰기소유자");
        Owner stranger = owner("쓰기남");
        Uploaded uploaded = upload(owner, png(8, 8));

        mockMvc.perform(post("/api/v1/games/" + owner.gameId() + "/assets")
                        .header("Authorization", bearerFor(stranger.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody("\"IMAGE\"", "\"image/png\"", "4096")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GAME_FORBIDDEN"));

        mockMvc.perform(post("/api/v1/games/" + owner.gameId() + "/assets/"
                        + uploaded.assetId() + "/complete")
                        .header("Authorization", bearerFor(stranger.userId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GAME_FORBIDDEN"));
    }

    /** An id nobody issued is absent, not forbidden — the caller owns the game it asked about. */
    @Test
    void anAssetIdThatWasNeverIssuedIsNotFound() throws Exception {
        Owner owner = owner("없는자산");

        mockMvc.perform(completeRequest(owner, "aNeverIssuedAssetIdentifier"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GAME_ASSET_NOT_FOUND"));
        mockMvc.perform(get(contentPath(owner.gameId(), "aNeverIssuedAssetIdentifier"))
                        .header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GAME_ASSET_NOT_FOUND"));
    }

    /**
     * The quota counts what can still become an image, and an expired grant cannot.
     *
     * <p>Both halves are one test because the second is what stops the first from being a trap: a
     * count of every row would let ten minutes of abandoned uploads lock a project out permanently,
     * and the delete endpoint that would clear them is not built yet. The rows are inserted directly
     * — three hundred round trips through the API would test the same predicate far more slowly.
     *
     * <p>The freed row is expired but not yet stale ({@code UNUSABLE_RETENTION} is an hour), so it
     * is still on the table when the count runs. That keeps this about {@code countChargeable} and
     * not about the cleanup, which has its own test.
     */
    @Test
    void anExpiredGrantStopsCountingAgainstTheQuota() throws Exception {
        Owner owner = owner("한도");
        seedChargeableAssets(owner, GameAssetService.MAX_ASSETS_PER_GAME);

        mockMvc.perform(startRequest(owner, png(8, 8).length))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GAME_ASSET_QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.errors[0].rule").value("QUOTA_EXCEEDED"));

        jdbc.update("""
                UPDATE game_assets SET upload_expires_at = now() - interval '5 minutes'
                 WHERE game_id = ? AND asset_id = ?
                """, owner.gameId(), seededAssetId(1));

        mockMvc.perform(startRequest(owner, png(8, 8).length))
                .andExpect(status().isOk());
    }

    /**
     * Rows that can never become usable are dropped on the next issue, and their objects queued.
     *
     * <p>Two native statements with the same three-part predicate, run one after the other inside
     * issuance — the order is the whole point: after the delete there is nothing left to read the
     * coordinates from, so a queue-second version leaks every object it drops. Both are invisible to
     * the compiler, which is why this goes through real rows rather than a repository call.
     *
     * <p>The still-valid grant is here to fail the test if the predicate ever widens: sweeping a row
     * whose ten minutes have not run out would delete an upload that is on its way.
     */
    @Test
    void issuingAGrantSweepsTheRowsThatCanNeverBecomeUsable() throws Exception {
        Owner owner = owner("청소");
        insertAsset(owner, "aStaleFailedRowIdentifier", "FAILED",
                "now() + interval '10 minutes'", "now() - interval '2 hours'", "'DECODE_FAILED'");
        insertAsset(owner, "aStaleUploadingRowIdent01", "UPLOADING",
                "now() - interval '2 hours'", "now() - interval '2 hours'", "NULL");
        insertAsset(owner, "aLiveUploadingRowIdent001", "UPLOADING",
                "now() + interval '10 minutes'", "now()", "NULL");

        mockMvc.perform(startRequest(owner, png(8, 8).length)).andExpect(status().isOk());

        assertEquals(0, assetRows(owner, "aStaleFailedRowIdentifier"), "만료된 FAILED 행이 남았다");
        assertEquals(0, assetRows(owner, "aStaleUploadingRowIdent01"), "만료된 UPLOADING 행이 남았다");
        assertEquals(1, assetRows(owner, "aLiveUploadingRowIdent001"), "유효한 grant 를 지웠다");
        assertEquals(1, queuedObjects(objectKey(owner.gameId(), "aStaleFailedRowIdentifier")),
                "행을 지우기 전에 객체 좌표를 큐로 옮겨야 한다");
        assertEquals(1, queuedObjects(objectKey(owner.gameId(), "aStaleUploadingRowIdent01")),
                "만료된 grant 도 PUT 을 받았을 수 있다 — 좌표를 버리면 고아 객체가 된다");
        assertEquals(0, queuedObjects(objectKey(owner.gameId(), "aLiveUploadingRowIdent001")));
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
     * A swapped object does not change what the row promises, and is refused rather than served.
     *
     * <p>The presigned URL is a real permission to write that key until the grant expires, so Spring
     * cannot stop a second {@code PUT} the way the old application-hosted endpoint could. What it can
     * do is refuse to serve anything that is not the object it verified: {@code /content} requires
     * the length to match what verification recorded, so a replacement is refused rather than handed
     * over carrying a verified image's {@code Content-Type}.
     *
     * <p>ponytail: a replacement of exactly the same byte length is not caught. Any other length is,
     * larger or smaller. The window is the grant's ten minutes, needs the owner's own signed URL,
     * and reaches only their own game's assets — verify {@code sha256} on read if that stops being
     * acceptable.
     */
    @Test
    void aSwappedObjectIsRefusedRatherThanServedAsTheVerifiedOne() throws Exception {
        Owner owner = owner("덮어쓰기");
        byte[] original = png(20, 20);
        Uploaded uploaded = upload(owner, original);

        storage.putBytes(objectKey(owner.gameId(), uploaded.assetId()), png(300, 300));

        mockMvc.perform(get(contentPath(owner.gameId(), uploaded.assetId()))
                        .header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GAME_ASSET_NOT_READY"))
                .andExpect(jsonPath("$.errors[0].rule").value("OBJECT_MISSING"));

        // Smaller too, not only larger — the check is a length match, not an upper bound.
        storage.putBytes(objectKey(owner.gameId(), uploaded.assetId()), png(4, 4));
        mockMvc.perform(get(contentPath(owner.gameId(), uploaded.assetId()))
                        .header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].rule").value("OBJECT_MISSING"));

        // And the untouched object still comes back, so the check is not refusing everything.
        storage.putBytes(objectKey(owner.gameId(), uploaded.assetId()), original);
        mockMvc.perform(get(contentPath(owner.gameId(), uploaded.assetId()))
                        .header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isOk())
                .andExpect(content().bytes(original));
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

    /**
     * Bytes that arrive after the grant ran out are not verified, however good they are.
     *
     * <p>The image here is a perfectly valid PNG, so nothing but the clock refuses it. Verifying it
     * anyway would accept an object written under a signature this row had already stopped
     * vouching for, and the row's own {@code upload_expires_at} would mean nothing.
     *
     * <p>The object is queued like any other failure: an expired grant may well have received its
     * {@code PUT}, because the browser uploads without telling us.
     */
    @Test
    void bytesThatLandAfterTheGrantExpiredAreRefusedWithoutVerifying() throws Exception {
        Owner owner = owner("만료");
        byte[] image = png(8, 8);
        JsonNode grant = start(owner, image.length);
        String assetId = grant.get("assetId").asText();
        storage.putBytes(objectKey(owner.gameId(), assetId), image);
        jdbc.update("""
                UPDATE game_assets SET upload_expires_at = now() - interval '1 minute'
                 WHERE game_id = ? AND asset_id = ?
                """, owner.gameId(), assetId);

        mockMvc.perform(completeRequest(owner, assetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureRule").value("GRANT_EXPIRED"));

        assertEquals(1, queuedObjects(objectKey(owner.gameId(), assetId)));
    }

    /**
     * A client that declared a kilobyte and uploaded six megabytes is told which rule refused it.
     *
     * <p>The read is bounded by the contract's limit and comes back one byte too long, which the
     * validator turns into {@code SIZE_EXCEEDED} — the same rule a too-large <i>declared</i> size
     * gets at issuance. Reporting {@code UPLOAD_MISSING} instead, which an exception swallowed at
     * the read would produce, sends the uploader to re-upload a file that arrived perfectly well.
     */
    @Test
    void anObjectPastTheLimitIsRefusedForItsSizeAndNotAsAMissingUpload() throws Exception {
        Owner owner = owner("거대");
        JsonNode grant = start(owner, 1024);
        String assetId = grant.get("assetId").asText();

        storage.putBytes(objectKey(owner.gameId(), assetId),
                new byte[(int) GameAssetImageValidator.MAX_BYTES + 1]);

        mockMvc.perform(completeRequest(owner, assetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureRule").value("SIZE_EXCEEDED"));
    }

    /**
     * A verified-and-rejected object is queued for deletion, and the sweep removes it.
     *
     * <p>Queued rather than deleted on the spot: the delete would be a network call inside the
     * transaction that records {@code FAILED}, and its failure would either lose the record or leave
     * a committed row whose delete did not happen (§7.1). The sweep is asserted in the same test
     * because a queue nothing drains is a slower leak, not a fix.
     */
    @Test
    void aFailedUploadsObjectIsQueuedAndThenSwept() throws Exception {
        Owner owner = owner("실패정리");
        JsonNode grant = start(owner, 64);
        String assetId = grant.get("assetId").asText();
        String key = objectKey(owner.gameId(), assetId);
        storage.putBytes(key, new byte[64]);

        mockMvc.perform(completeRequest(owner, assetId))
                .andExpect(jsonPath("$.status").value("FAILED"));

        assertEquals(1, queuedObjects(), "FAILED 는 객체 좌표를 큐에 남긴다");
        assertTrue(storage.hasObject(key), "sweep 전에는 객체가 아직 있다");

        deleteQueue.sweep();

        assertEquals(0, queuedObjects(), "성공한 삭제는 큐에서 사라진다");
        assertFalse(storage.hasObject(key), "객체가 지워져야 한다");
    }

    /** Nothing to delete is a success, so the row is cleared instead of retried forever. */
    @Test
    void sweepingAnObjectThatIsAlreadyGoneClearsTheQueueRow() {
        deleteQueue.enqueue("R2", "test-ai-documents", "games/1/assets/neverExisted");

        deleteQueue.sweep();

        assertEquals(0, queuedObjects());
    }

    /**
     * A provider that cannot answer keeps the row, records why, and waits.
     *
     * <p>Three things, and dropping any one of them breaks the queue in a different way. Losing the
     * row leaves an object nobody will ever delete and nobody can find — the coordinates live only
     * here once the asset row is gone. Not backing off turns a bucket outage into a delete call
     * every minute per row. Not recording the reason is the T-24 shape: a queue that never drains
     * and no column saying what is wrong.
     *
     * <p>The second sweep asserts the lease actually respects {@code next_attempt_at} — without it
     * the first sweep's back-off would be decoration.
     */
    @Test
    void aDeleteThatFailsKeepsTheRowRecordsWhyAndBacksOff() {
        deleteQueue.enqueue("R2", "test-ai-documents", "games/1/assets/aStubbornObject");
        storage.failDeleteWith(new StorageUnavailableException("저장소에 연결할 수 없습니다."));

        deleteQueue.sweep();

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT attempts, last_error, next_attempt_at > now() AS deferred
                  FROM game_asset_delete_queue
                """);
        assertEquals(1, ((Number) row.get("attempts")).intValue());
        // The exception's class, never its message — the adapter's messages carry the endpoint and
        // the response detail, and this column is readable by anyone with the table.
        assertEquals("StorageUnavailableException", row.get("last_error"));
        assertEquals(Boolean.TRUE, row.get("deferred"), "실패한 행은 다음 시도를 미뤄야 한다");

        deleteQueue.sweep();

        assertEquals(1, ((Number) jdbc.queryForObject(
                        "SELECT attempts FROM game_asset_delete_queue", Integer.class)).intValue(),
                "아직 due 가 아닌 행을 다시 집으면 백오프가 장식이다");

        storage.failDeleteWith(null);
        jdbc.update("UPDATE game_asset_delete_queue SET next_attempt_at = now() - interval '1 minute'");
        deleteQueue.sweep();

        assertEquals(0, queuedObjects(), "저장소가 돌아오면 다음 sweep 이 비운다");
    }

    @Test
    void aDisguisedFileFailsVerificationAndIsNotServed() throws Exception {
        Owner owner = owner("위장");
        byte[] disguised = new byte[64];
        System.arraycopy(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 0,
                disguised, 0, 8);
        JsonNode grant = start(owner, disguised.length);
        String assetId = grant.get("assetId").asText();

        storage.putBytes(objectKey(owner.gameId(), assetId), disguised);
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

    /**
     * A guest reads a published game's asset, and is refused a private one.
     *
     * <p>Both halves matter and neither is the other's contrapositive. The first fails if the path
     * is missing from the security chain — the filter answers 401 before the service can judge
     * anything, which is the state this branch was in. The second fails if opening the path were
     * mistaken for making it public.
     *
     * <p>403 and not 401 for the refusal: the caller's identity is not the problem, so asking them
     * to log in would send them to do something that changes nothing.
     */
    @Test
    void aGuestReadsAPublishedAssetAndIsRefusedAPrivateOne() throws Exception {
        Owner owner = owner("익명");
        Uploaded shown = upload(owner, png(8, 8));
        Uploaded hidden = upload(owner, png(9, 9));

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

        mockMvc.perform(get(contentPath(owner.gameId(), shown.assetId())))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
        mockMvc.perform(get(contentPath(owner.gameId(), hidden.assetId())))
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

        storage.putBytes(objectKey(owner.gameId(), assetId), image);
        MvcResult completed = mockMvc.perform(completeRequest(owner, assetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andReturn();
        String body = completed.getResponse().getContentAsString();
        String source = MAPPER.readTree(body).get("source").asText();
        assertTrue(source.startsWith("asset://game/" + owner.gameId() + "/"), source);
        return new Uploaded(assetId, source, body);
    }

    private JsonNode start(Owner owner, int byteSize) throws Exception {
        String body = mockMvc.perform(startRequest(owner, byteSize))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder startRequest(
            Owner owner, int byteSize) {
        return startRequest(owner, "\"IMAGE\"", "\"image/png\"", String.valueOf(byteSize));
    }

    /**
     * Each field is written as raw JSON so a test can send {@code null} — or a type the record would
     * not hold — the way a browser can. Binding a DTO in the test would only prove the DTO binds.
     */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder startRequest(
            Owner owner, String kind, String contentType, String byteSize) {
        return post("/api/v1/games/" + owner.gameId() + "/assets")
                .header("Authorization", bearerFor(owner.userId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(startBody(kind, contentType, byteSize));
    }

    private String startBody(String kind, String contentType, String byteSize) {
        return "{\"kind\":" + kind + ",\"contentType\":" + contentType
                + ",\"byteSize\":" + byteSize + ",\"fileName\":\"sprite.png\"}";
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

    /** Contract §8. Written out, not asked for — see the class comment. */
    private String objectKey(Long gameId, String assetId) {
        return "games/" + gameId + "/assets/" + assetId;
    }

    private long queuedObjects() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM game_asset_delete_queue", Long.class);
    }

    private long queuedObjects(String objectKey) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM game_asset_delete_queue WHERE object_key = ?",
                Long.class, objectKey);
    }

    private long assetRows(Owner owner, String assetId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM game_assets WHERE game_id = ? AND asset_id = ?",
                Long.class, owner.gameId(), assetId);
    }

    /**
     * One row in whatever state and age the caller needs.
     *
     * <p>The three timestamps are pasted in as SQL rather than bound, because they are expressions
     * ({@code now() - interval '2 hours'}) and the point of every caller is the age. Nothing here
     * comes from outside the test.
     */
    private void insertAsset(Owner owner, String assetId, String status, String expiresAt,
                             String updatedAt, String failureRule) {
        jdbc.update("""
                INSERT INTO game_assets (game_id, asset_id, kind, status, provider, storage_bucket,
                                         object_key, declared_content_type, declared_byte_size,
                                         upload_expires_at, updated_at, failure_rule,
                                         created_by_user_id)
                VALUES (?, ?, 'IMAGE', '%s', 'R2', 'test-ai-documents', ?, 'image/png', 64,
                        %s, %s, %s, ?)
                """.formatted(status, expiresAt, updatedAt, failureRule),
                owner.gameId(), assetId, objectKey(owner.gameId(), assetId), owner.userId());
    }

    /**
     * Fills the quota straight through JDBC.
     *
     * <p>Three hundred issue calls would exercise the same {@code countChargeable} predicate three
     * hundred times and take a minute to say so. The ids match the issued shape (26 characters,
     * leading letter) so the rows are indistinguishable from real ones to every query under test.
     */
    private void seedChargeableAssets(Owner owner, int count) {
        jdbc.update("""
                INSERT INTO game_assets (game_id, asset_id, kind, status, provider, storage_bucket,
                                         object_key, declared_content_type, declared_byte_size,
                                         upload_expires_at, created_by_user_id)
                SELECT ?, 'aSeeded' || lpad(n::text, 19, '0'), 'IMAGE', 'UPLOADING', 'R2',
                       'test-ai-documents',
                       'games/' || ? || '/assets/aSeeded' || lpad(n::text, 19, '0'),
                       'image/png', 64, now() + interval '10 minutes', ?
                  FROM generate_series(1, ?) AS n
                """, owner.gameId(), owner.gameId(), owner.userId(), count);
    }

    private String seededAssetId(int index) {
        return "aSeeded" + String.format("%019d", index);
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

    private record Uploaded(String assetId, String source, String completeBody) { }
}
