package com.example.ssafesta.project;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static com.example.ssafesta.booth.BoothTestSupport.releaseAllSlots;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothLayoutTestSupport;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.storage.FakeObjectStorage;
import com.example.ssafesta.storage.image.ImageBytesValidator;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 프로젝트 로고 업로드 왕복 (GitLab #241, spec 009 C-03 개정).
 *
 * <p>HTTP 경계에 못박는다 — FE 가 만나는 자리가 거기다. 가운데 단계(브라우저의 {@code PUT})는
 * HTTP 호출이 아니므로 {@link FakeObjectStorage#putBytes} 가 대신한다. 객체 키를 서비스에 묻지 않고
 * 여기 적어 두는 이유도 게임 Asset 테스트와 같다 — 서비스에 물어보면 키 모양이 조용히 바뀌어도
 * 왕복은 계속 초록이다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ProjectLogoUploadIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private MockMvc mockMvc;
    @Autowired private BoothRepository booths;
    @Autowired private ProjectRepository projects;
    @Autowired private ProjectLogoRepository logos;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private FakeObjectStorage storage;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProjectLogoCleanup cleanup;

    @BeforeEach
    void reset() {
        releaseAllSlots(jdbc);
        storage.reset();
        jdbc.update("DELETE FROM game_asset_delete_queue");
    }

    /** 시작 → PUT → 완료 → 저장 → 방문자 조회. 이 한 줄기가 서면 FE 가 붙을 수 있다. */
    @Test
    void theRoundTripEndsWithAVisitorReadingTheBytes() throws Exception {
        Owner owner = leasedOwner("로고왕복");
        byte[] image = png(64, 64);

        String logoId = startAndUpload(owner, image);
        MvcResult completed = mockMvc.perform(completeRequest(owner, logoId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andExpect(jsonPath("$.byteSize").value(image.length))
                .andExpect(jsonPath("$.width").value(64))
                .andExpect(jsonPath("$.height").value(64))
                .andReturn();
        String url = json(completed).path("url").asText();
        assertEquals("/api/v1/booths/" + owner.boothId() + "/project-logos/" + logoId + "/content", url);

        // 저장은 기존 계약 그대로다 — 새 endpoint 없이 thumbnailUrl 에 그 값을 넣는다.
        Long projectId = project(owner, "로고 붙인 전시");
        mockMvc.perform(patch("/api/v1/projects/{id}", projectId)
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thumbnailUrl\":\"" + url + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thumbnailUrl").value(url));

        BoothLayoutTestSupport.publishLayout(mockMvc, owner.boothId(), bearerFor(owner.userId()));

        // 방문자 — 토큰이 없다. 게시된 프로젝트가 가리키는 로고라 통과한다.
        mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(image));
    }

    /**
     * 아직 아무 프로젝트도 가리키지 않는 로고는 <b>편집자만</b> 읽는다.
     *
     * <p>{@code complete} 와 저장 사이, 그리고 "저장이 실패해 다시 확인" 경로가 이 갈래다. 이것이
     * 없으면 업로드 직후 새로고침한 작성자가 자기 이미지를 못 본다.
     */
    @Test
    void anUnreferencedLogoIsReadableByTheEditorOnly() throws Exception {
        Owner owner = leasedOwner("미참조로고");
        byte[] image = png(32, 32);
        String logoId = startAndUpload(owner, image);
        mockMvc.perform(completeRequest(owner, logoId)).andExpect(status().isOk());
        String url = contentPath(owner.boothId(), logoId);

        mockMvc.perform(get(url).header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isOk())
                .andExpect(content().bytes(image));

        // 게스트에게는 없는 것과 같다 — 있는지 여부가 남의 부스 편집 상태를 알려 주면 안 된다.
        mockMvc.perform(get(url))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_LOGO_NOT_FOUND"));

        // 남의 회원도 마찬가지다.
        mockMvc.perform(get(url).header("Authorization",
                        bearerFor(createMemberWithWallet(users, wallets, "남의회원"))))
                .andExpect(status().isNotFound());
    }

    /** 위장한 MIME 은 시작에서 통과해도 완료에서 잡힌다 — 판정은 도착한 바이트가 한다. */
    @Test
    void aFileThatIsNotTheDeclaredImageFailsAtComplete() throws Exception {
        Owner owner = leasedOwner("위장로고");
        byte[] notAnImage = "이건 PNG 가 아니다".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        MvcResult started = mockMvc.perform(startRequest(owner, notAnImage.length))
                .andExpect(status().isOk())
                .andReturn();
        String logoId = json(started).path("logoId").asText();
        storage.putBytes(objectKey(owner.boothId(), logoId), notAnImage);

        mockMvc.perform(completeRequest(owner, logoId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureRule").value("MIME_NOT_ALLOWED"))
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.nullValue()));

        // 실패한 자리의 객체는 정리 대상이다 — 배치가 좌표를 큐에 넣고 행을 지운다.
        cleanup.sweep();
        assertTrue(logos.findByLogoId(logoId).isEmpty(), "FAILED 행이 남아 있습니다.");
        assertEquals(1, queued(objectKey(owner.boothId(), logoId)));
    }

    /** 선언 크기가 상한을 넘으면 업로드 자리를 주지 않는다 — 올려 보게 하고 거절하지 않는다. */
    @Test
    void anOversizedDeclarationIsRefusedBeforeUploading() throws Exception {
        Owner owner = leasedOwner("큰로고");

        mockMvc.perform(startRequest(owner, ImageBytesValidator.MAX_BYTES + 1))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PROJECT_LOGO_TOO_LARGE"))
                .andExpect(jsonPath("$.errors[0].rule").value("SIZE_EXCEEDED"));

        mockMvc.perform(post("/api/v1/booths/{id}/project-logos", owner.boothId())
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/svg+xml\",\"byteSize\":100}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("PROJECT_LOGO_TYPE_UNSUPPORTED"));
    }

    /** 남의 부스에는 올릴 수 없다 — 권한은 부스 편집자 가드 하나가 답한다. */
    @Test
    void aStrangerCannotStartAnUploadOnSomeoneElsesBooth() throws Exception {
        Owner owner = leasedOwner("주인");
        Long stranger = createMemberWithWallet(users, wallets, "남");

        mockMvc.perform(post("/api/v1/booths/{id}/project-logos", owner.boothId())
                        .header("Authorization", bearerFor(stranger))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/png\",\"byteSize\":100}"))
                .andExpect(status().isForbidden());
    }

    /**
     * 로고를 바꾸면 옛 로고가 정리 후보가 되고, <b>되돌리면 지워지지 않는다.</b>
     *
     * <p>최종 판정이 표식이 아니라 삭제 시점의 {@code thumbnail_url} 이라는 것이 이 테스트의 전부다 —
     * A → B → 다시 A 를 표식만으로 판정하면 살아 있는 이미지를 지운다.
     */
    @Test
    void replacingTheLogoMarksTheOldOneButRevertingSavesIt() throws Exception {
        Owner owner = leasedOwner("교체로고");
        String first = readyLogo(owner, png(24, 24));
        String second = readyLogo(owner, png(28, 28));
        Long projectId = project(owner, "교체 전시");

        saveThumbnail(owner, projectId, contentPath(owner.boothId(), first));
        saveThumbnail(owner, projectId, contentPath(owner.boothId(), second));
        assertTrue(logos.findByLogoId(first).isPresent());
        assertEquals(1, unreferencedSince(first), "교체된 옛 로고에 표식이 없습니다.");

        // 되돌린다. 유예가 지나 배치가 돌아도 지워지지 않아야 한다.
        saveThumbnail(owner, projectId, contentPath(owner.boothId(), first));
        ageUnreferencedMark(first);
        ageUnreferencedMark(second);
        cleanup.sweep();

        assertTrue(logos.findByLogoId(first).isPresent(), "되돌린 로고가 지워졌습니다.");
        assertTrue(logos.findByLogoId(second).isEmpty(), "참조가 끊긴 로고가 남아 있습니다.");
        assertEquals(1, queued(objectKey(owner.boothId(), second)));
        assertEquals(0, queued(objectKey(owner.boothId(), first)));
    }

    /** 올려만 두고 저장하지 않은 로고는 24시간 뒤 정리된다 — 검증까지 끝난 객체가 영구히 남지 않는다. */
    @Test
    void aReadyLogoNobodyEverSavedIsCollectedAfterADay() throws Exception {
        Owner owner = leasedOwner("고아로고");
        String logoId = readyLogo(owner, png(20, 20));

        jdbc.update("UPDATE project_logo_uploads SET completed_at = now() - interval '25 hours'"
                + " WHERE logo_id = ?", logoId);
        cleanup.sweep();

        assertTrue(logos.findByLogoId(logoId).isEmpty(), "아무도 가리키지 않는 로고가 남아 있습니다.");
        assertEquals(1, queued(objectKey(owner.boothId(), logoId)));
    }

    /** 저장하지 않은 로고를 계속 올리는 것은 상한에서 막힌다 — 반복 업로드로 객체를 쌓지 못한다. */
    @Test
    void unsavedUploadsHitAPerBoothCeiling() throws Exception {
        Owner owner = leasedOwner("쌓기");
        for (int index = 0; index < ProjectLogoService.MAX_UNREFERENCED_PER_BOOTH; index++) {
            readyLogo(owner, png(16 + index, 16));
        }

        mockMvc.perform(startRequest(owner, 1024))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_LOGO_QUOTA_EXCEEDED"));
    }

    /** 만료된 grant 로는 완료할 수 없다. 표식은 남고 객체는 정리된다. */
    @Test
    void anExpiredGrantFailsAtComplete() throws Exception {
        Owner owner = leasedOwner("만료로고");
        byte[] image = png(18, 18);
        String logoId = startAndUpload(owner, image);
        jdbc.update("UPDATE project_logo_uploads SET expires_at = now() - interval '1 minute'"
                + " WHERE logo_id = ?", logoId);

        mockMvc.perform(completeRequest(owner, logoId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureRule").value("GRANT_EXPIRED"));
    }

    /** 완료는 멱등이다 — 두 번째 호출이 처음 판정을 그대로 돌려준다. */
    @Test
    void completeIsIdempotent() throws Exception {
        Owner owner = leasedOwner("멱등로고");
        String logoId = startAndUpload(owner, png(22, 22));

        mockMvc.perform(completeRequest(owner, logoId)).andExpect(jsonPath("$.status").value("READY"));
        mockMvc.perform(completeRequest(owner, logoId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.width").value(22));
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private String startAndUpload(Owner owner, byte[] image) throws Exception {
        MvcResult started = mockMvc.perform(startRequest(owner, image.length))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadUrl").exists())
                .andExpect(jsonPath("$.requiredContentType").value("image/png"))
                .andReturn();
        String logoId = json(started).path("logoId").asText();
        // 브라우저의 PUT 자리.
        storage.putBytes(objectKey(owner.boothId(), logoId), image);
        return logoId;
    }

    private String readyLogo(Owner owner, byte[] image) throws Exception {
        String logoId = startAndUpload(owner, image);
        mockMvc.perform(completeRequest(owner, logoId))
                .andExpect(jsonPath("$.status").value("READY"));
        return logoId;
    }

    private void saveThumbnail(Owner owner, Long projectId, String url) throws Exception {
        mockMvc.perform(patch("/api/v1/projects/{id}", projectId)
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thumbnailUrl\":\"" + url + "\"}"))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder startRequest(
            Owner owner, long byteSize) {
        return post("/api/v1/booths/{id}/project-logos", owner.boothId())
                .header("Authorization", bearerFor(owner.userId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentType\":\"image/png\",\"byteSize\":" + byteSize + "}");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder completeRequest(
            Owner owner, String logoId) {
        return post("/api/v1/booths/{id}/project-logos/{logoId}/complete", owner.boothId(), logoId)
                .header("Authorization", bearerFor(owner.userId()));
    }

    private static String contentPath(Long boothId, String logoId) {
        return "/api/v1/booths/" + boothId + "/project-logos/" + logoId + "/content";
    }

    /** 계약이 정한 키 모양. 서비스에 묻지 않는다 — 조용히 바뀌면 이 테스트가 알아야 한다. */
    private static String objectKey(Long boothId, String logoId) {
        return "booths/" + boothId + "/projects/logos/" + logoId;
    }

    private int queued(String objectKey) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM game_asset_delete_queue WHERE object_key = ?", Integer.class, objectKey);
        return count == null ? 0 : count;
    }

    private int unreferencedSince(String logoId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM project_logo_uploads"
                + " WHERE logo_id = ? AND unreferenced_since IS NOT NULL", Integer.class, logoId);
        return count == null ? 0 : count;
    }

    /** 유예를 기다리지 않고 지나가게 한다 — 10분을 실제로 기다릴 이유가 없다. */
    private void ageUnreferencedMark(String logoId) {
        jdbc.update("UPDATE project_logo_uploads SET unreferenced_since = now() - interval '1 hour'"
                + " WHERE logo_id = ? AND unreferenced_since IS NOT NULL", logoId);
    }

    private Long project(Owner owner, String name) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/booths/{id}/projects", owner.boothId())
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return json(created).path("projectId").asLong();
    }

    private Owner leasedOwner(String prefix) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(userId, prefix + " 부스")).getId();
        grantLease(jdbc, boothId, userId);
        return new Owner(userId, boothId);
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
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

    private record Owner(Long userId, Long boothId) { }
}
