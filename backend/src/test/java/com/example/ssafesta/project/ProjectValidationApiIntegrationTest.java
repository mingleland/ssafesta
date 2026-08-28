package com.example.ssafesta.project;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static com.example.ssafesta.booth.BoothTestSupport.releaseAllSlots;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * US2 — 잘못된 링크로 사고가 나지 않는다 (spec 009 FR-004, SC-004, D09).
 *
 * <p>거부되는지만이 아니라 <b>왜 거부됐는지가 전달되는지</b>를 본다. 사유를 뭉뚱그리면 사용자는
 * 어느 칸을 어떻게 고쳐야 하는지 알 수 없고, 그게 T-24가 남긴 교훈이다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ProjectValidationApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void freeSlots() {
        releaseAllSlots(jdbc);
    }

    /**
     * 다섯 필드 각각이 <b>자기 이름으로</b> 거부당하고, 사유가 스킴 위반임을 말한다.
     *
     * <p>{@code javascript:alert(1)}은 host가 없다. 검증기가 host를 스킴보다 먼저 봤다면 전부
     * "형식이 올바르지 않습니다"로 끝나고 진짜 사유가 사라진다 — 이 단언이 그 순서를 지키는
     * 유일한 자리다 (data-model §3 #2, research R-04).
     */
    @ParameterizedTest
    @CsvSource({
        "thumbnailUrl,대표 이미지",
        "videoUrl,영상",
        "deployUrl,배포",
        "gitUrl,저장소",
        "portfolioUrl,포트폴리오",
    })
    void aForbiddenSchemeIsRefusedForBeingASchemeInEveryUrlField(String field, String displayName)
            throws Exception {
        Owner owner = leasedOwner("스" + field.charAt(0)); // 닉네임 VARCHAR(30)
        String expected = displayName + " 주소는 http 또는 https로 시작해야 합니다.";

        mockMvc.perform(create(owner, """
                        {"name": "스킴", "%s": "javascript:alert(1)"}""".formatted(field)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(expected))
                .andExpect(jsonPath("$.errors[0].rule").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].message").value(expected));
    }

    /** host 없는 다른 스킴들도 같은 이유로 걸린다 — allowlist가 아니라 스킴 판정이다. */
    @ParameterizedTest
    @ValueSource(strings = {"data:text/html,hello", "ftp:notes.txt", "file:///etc/passwd"})
    void otherNonHttpSchemesAreRefusedTheSameWay(String url) throws Exception {
        Owner owner = leasedOwner("스킴기타" + url.length());

        mockMvc.perform(create(owner, """
                        {"name": "스킴", "videoUrl": "%s"}""".formatted(url)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("영상 주소는 http 또는 https로 시작해야 합니다."));
    }

    /**
     * {@code http}는 통과한다.
     *
     * <p>이 필드들은 사용자가 <b>이동</b>하는 목적지이지 우리 페이지에 박히는 자산이 아니다.
     * https로 좁히면 멀쩡한 배포·포트폴리오 링크를 거부하게 되고, SC-002(도달률 100%)를
     * 스스로 깬다. 제공자 allowlist를 두지 않는 것도 같은 이유다 (C-02는 아직 미결).
     */
    @Test
    void plainHttpPasses() throws Exception {
        Owner owner = leasedOwner("http통과");

        mockMvc.perform(create(owner, """
                        {"name": "http", "deployUrl": "http://plain.example.com"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deployUrl").value("http://plain.example.com"));
    }

    /** 형식 문제는 형식 문제라고 말한다 — 스킴 사유와 섞이지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"not a url", "/relative/path", "https://"})
    void aMalformedAddressIsRefusedAsMalformed(String url) throws Exception {
        Owner owner = leasedOwner("형식" + url.length());

        mockMvc.perform(create(owner, """
                        {"name": "형식", "gitUrl": "%s"}""".formatted(url)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("gitUrl"))
                .andExpect(jsonPath("$.message").value("저장소 주소 형식이 올바르지 않습니다."));
    }

    /**
     * 도달할 수 없는 포트는 거부한다.
     *
     * <p>{@code java.net.URI} 는 포트를 {@code *DIGIT} 로만 보므로 {@code :99999} 가 host 까지
     * 멀쩡히 파싱된다 — 검증기가 포트를 안 보면 <b>연결 불가능한 주소가 저장</b>되고 방문자는
     * 죽은 링크를 만난다 (SC-002). {@code :0} 도 클라이언트에게는 목적지가 아니다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"https://example.com:99999", "https://example.com:0",
                            "https://example.com:65536"})
    void anUnreachablePortIsRefusedAsAPort(String url) throws Exception {
        Owner owner = leasedOwner("포트" + url.length());

        mockMvc.perform(create(owner, """
                        {"name": "포트", "deployUrl": "%s"}""".formatted(url)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("deployUrl"))
                .andExpect(jsonPath("$.message")
                        .value("배포 주소의 포트 번호가 올바르지 않습니다. (1~65535)"));
    }

    /** 정상 포트와 포트 없는 주소는 그대로 통과한다 — 위 규칙이 과하게 걸리지 않는지. */
    @ParameterizedTest
    @ValueSource(strings = {"https://example.com", "https://example.com:1",
                            "https://example.com:8080", "https://example.com:65535"})
    void usablePortsPass(String url) throws Exception {
        Owner owner = leasedOwner("포통" + url.length());

        mockMvc.perform(create(owner, """
                        {"name": "포트", "deployUrl": "%s"}""".formatted(url)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deployUrl").value(url));
    }

    /** 빈 문자열은 "지우기"가 아니다. 지우려면 null 을 보내야 한다 (계약 §1). */
    @Test
    void anEmptyStringIsRefusedRatherThanTreatedAsClearing() throws Exception {
        Owner owner = leasedOwner("빈문자열");

        mockMvc.perform(create(owner, """
                        {"name": "빈문자열", "portfolioUrl": ""}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("portfolioUrl"))
                .andExpect(jsonPath("$.message").value("포트폴리오 주소를 입력해 주세요."));
    }

    /** V1 컬럼 폭이 상한이다. 2048 통과, 2049 거부. */
    @Test
    void theUrlLengthCeilingIsTheColumnWidth() throws Exception {
        Owner ok = leasedOwner("길이통과");
        String base = "https://long.example.com/";
        String at2048 = base + "a".repeat(2048 - base.length());

        mockMvc.perform(create(ok, """
                        {"name": "길이", "videoUrl": "%s"}""".formatted(at2048)))
                .andExpect(status().isCreated());

        Owner over = leasedOwner("길이초과");
        mockMvc.perform(create(over, """
                        {"name": "길이", "videoUrl": "%s"}""".formatted(at2048 + "b")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("영상 주소가 너무 깁니다. (최대 2048자)"));
    }

    // ── name (FR-001, R-09) ─────────────────────────────────────────────────

    @Test
    void aNameOfExactlyOneHundredCharactersPasses() throws Exception {
        Owner owner = leasedOwner("이름100");

        mockMvc.perform(create(owner, """
                        {"name": "%s"}""".formatted("가".repeat(100))))
                .andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OVER", "BLANK", "NULL", "MISSING"})
    void anUnusableNameIsRefusedWithTheNameField(String kind) throws Exception {
        Owner owner = leasedOwner("이름" + kind.charAt(0)); // 닉네임 VARCHAR(30)
        String body = switch (kind) {
            case "OVER" -> "{\"name\": \"" + "가".repeat(101) + "\"}";
            case "BLANK" -> "{\"name\": \"   \"}";
            case "NULL" -> "{\"name\": null}";
            default -> "{\"description\": \"이름 없음\"}";
        };
        String expected = "OVER".equals(kind)
                ? "프로젝트 이름이 너무 깁니다. (최대 100자)"
                : "프로젝트 이름을 입력해 주세요.";

        mockMvc.perform(create(owner, body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].rule").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.message").value(expected));
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private RequestBuilder create(Owner owner, String body) {
        return post("/api/v1/booths/{id}/projects", owner.boothId())
                .header("Authorization", "Bearer " + sessions.issue(owner.userId()).accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private Owner leasedOwner(String prefix) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(userId, prefix + " 부스")).getId();
        grantLease(jdbc, boothId, userId);
        return new Owner(userId, boothId);
    }

    private record Owner(Long userId, Long boothId) { }
}
