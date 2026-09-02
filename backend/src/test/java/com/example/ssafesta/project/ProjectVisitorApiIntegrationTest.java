package com.example.ssafesta.project;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.expireLease;
import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static com.example.ssafesta.booth.BoothTestSupport.releaseAllSlots;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.AccessTokenService;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothLayoutTestSupport;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 방문자 Project 조회와 좋아요 (spec 009 FR-005·SC-002, contracts/project-api.md §6·§8,
 * S15P21A604-177·-135).
 *
 * <p>좋아요 쓰기가 같은 클래스에 있는 이유: §8 이 바꾸는 것은 §6 응답의 두 필드뿐이고,
 * 픽스처(임대·게시·회원)도 전부 같다. 클래스를 나누면 {@code leasedOwner}·{@code publishedOwner}·
 * {@code bearerFor} 를 복사하게 된다.
 *
 * <p>게시는 {@code BoothLayoutTestSupport.publishLayout} 으로 실제 발행을 태운다. 컬럼을 손으로
 * 세우는 길은 없다 — {@code fk_booths_published_layout_version} 이 그것을 거부하고, 그 제약이
 * 게이트가 이 컬럼을 믿을 수 있는 이유다. 016 홈페이지 테스트가 같은 픽스처를 쓴다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ProjectVisitorApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private BoothRepository booths;
    @Autowired private ProjectRepository projects;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private AccessTokenService accessTokens;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void freeSlots() {
        releaseAllSlots(jdbc);
    }

    // ── 게이트 (계약 §6, 순서가 계약이다) ───────────────────────────────────

    @Test
    void missingBoothIsNotFound() throws Exception {
        mockMvc.perform(get(published(999_999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOTH_NOT_FOUND"));
    }

    /**
     * 만료는 세계에 밀어내지 않으므로 방문자는 여기서 안다 (004 FR-019).
     *
     * <p>게시된 채로 임대만 끝난 부스를 만든다 — 만료가 게시 게이트보다 먼저 걸리는지 보는 것이
     * 이 테스트의 요점이다. 순서가 뒤집히면 만료 부스가 {@code LAYOUT_NOT_PUBLISHED} 로 답해
     * 방문자는 임대가 끝났다는 사실을 알 수 없다.
     */
    @Test
    void expiredBoothIsConflictEvenWhenItWasPublished() throws Exception {
        Owner owner = publishedOwner("만료");
        project(owner, "만료된 전시");
        expireLease(jdbc, owner.boothId());

        mockMvc.perform(get(published(owner.boothId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOTH_LEASE_EXPIRED"));
    }

    /**
     * <b>미게시는 404 이고 빈 배열이 아니다.</b>
     *
     * <p>프로젝트가 있는 부스로 만든다 — 빈 배열로 답하는 구현이라면 "전시가 없다"와 구분되지
     * 않는데, 여기서는 전시가 실제로 있으므로 그 혼동이 그대로 드러난다.
     */
    @Test
    void unpublishedBoothIsNotFoundRatherThanAnEmptyList() throws Exception {
        Owner owner = leasedOwner("미게시");
        project(owner, "아직 공개 안 한 전시");

        mockMvc.perform(get(published(owner.boothId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LAYOUT_NOT_PUBLISHED"));
    }

    /** 게시됐는데 전시가 없는 것은 오류가 아니다 — 아직 안 만들었을 뿐이다 (-134 빈 상태). */
    @Test
    void publishedBoothWithoutAProjectIsAnEmptyArray() throws Exception {
        Owner owner = publishedOwner("빈전시");

        mockMvc.perform(get(published(owner.boothId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects").isArray())
                .andExpect(jsonPath("$.projects").isEmpty());
    }

    // ── 게스트 (완료조건 "방문자(게스트) 조회 가능") ────────────────────────

    /** 토큰이 아예 없어도 200 이다. 401·403 이 없는 것이 이 endpoint 의 요점이다. */
    @Test
    void anyoneReadsAPublishedExhibitionWithoutAToken() throws Exception {
        Owner owner = publishedOwner("무토큰");
        Long projectId = project(owner, "공개된 전시");

        mockMvc.perform(get(published(owner.boothId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects[0].projectId").value(projectId))
                .andExpect(jsonPath("$.projects[0].name").value("공개된 전시"))
                .andExpect(jsonPath("$.projects[0].likeCount").value(0))
                .andExpect(jsonPath("$.projects[0].likedByMe").value(false));
    }

    /** 게스트 토큰도 정상 경로다 — 다른 쓰기 endpoint 들이 내는 MEMBER_ONLY 가 여기서는 없다. */
    @Test
    void guestTokenReadsItToo() throws Exception {
        Owner owner = publishedOwner("게스트");
        project(owner, "게스트도 보는 전시");

        mockMvc.perform(get(published(owner.boothId()))
                        .header("Authorization", "Bearer " + accessTokens.issueGuestToken().token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects[0].likedByMe").value(false));
    }

    /**
     * <b>헤더가 없으면 200 이지만, 만료·손상된 토큰을 실으면 401 이다.</b>
     *
     * <p>인증 필터가 인가({@code permitAll})보다 먼저 돌아 거기서 응답을 끝낸다. 계약 §6 이 이
     * 예외를 명시하는데, 명시만 하고 테스트가 없으면 다음 사람이 "게스트 경로니까 401 이 나오면
     * 안 된다"고 읽고 필터 설정을 고치러 간다. 여기서 못박는다.
     */
    @Test
    void aStaleTokenIsUnauthorizedEvenThoughTheAnonymousCallSucceeds() throws Exception {
        Owner owner = publishedOwner("만료토큰");
        project(owner, "토큰 없이는 보이는 전시");

        mockMvc.perform(get(published(owner.boothId()))
                        .header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        // 같은 부스를 헤더 없이 부르면 읽힌다 — FE 의 우회 경로가 실제로 있는지까지 본다.
        mockMvc.perform(get(published(owner.boothId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects[0].name").value("토큰 없이는 보이는 전시"));
    }

    /**
     * 이 변경에서 가장 조용히 깨질 자리다.
     *
     * <p>{@code permitAll} 이 한 세그먼트를 넘어 편집자 경로까지 열면, 남의 부스의 미게시 전시가
     * 토큰 없이 읽힌다. 그 사고는 이 테스트가 없으면 초록 화면 뒤에서 일어난다.
     */
    @Test
    void openingTheVisitorPathDoesNotOpenTheEditorPath() throws Exception {
        Owner owner = publishedOwner("경로");
        project(owner, "편집자만 볼 것");

        mockMvc.perform(get("/api/v1/booths/{id}/projects", owner.boothId()))
                .andExpect(status().isUnauthorized());
    }

    // ── 좋아요 (계약 §6 읽기 · §8 쓰기) ─────────────────────────────────────

    @Test
    void likeCountIsTheNumberOfRowsAndLikedByMeIsAboutTheViewer() throws Exception {
        Owner owner = publishedOwner("좋아요");
        Long projectId = project(owner, "인기 전시");
        Long liker = createMemberWithWallet(users, wallets, "누른사람");
        Long other = createMemberWithWallet(users, wallets, "안누른사람");
        likeAs(projectId, liker);
        likeAs(projectId, other);

        mockMvc.perform(get(published(owner.boothId())).header("Authorization", bearerFor(liker)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects[0].likeCount").value(2))
                .andExpect(jsonPath("$.projects[0].likedByMe").value(true));

        Long neverLiked = createMemberWithWallet(users, wallets, "제3자");
        mockMvc.perform(get(published(owner.boothId())).header("Authorization", bearerFor(neverLiked)))
                .andExpect(jsonPath("$.projects[0].likeCount").value(2))
                .andExpect(jsonPath("$.projects[0].likedByMe").value(false));
    }

    /**
     * 완료 조건 두 줄이 여기 있다 — 중복 방지와 취소 후 재좋아요 (Jira 테스트 방법: 왕복 3회).
     *
     * <p>방문자 조회를 다시 부르지 않는다. 응답의 두 필드만 보고 화면을 갱신할 수 있다는 것이
     * §8 의 약속이고, 여기서 조회를 태우면 그 약속을 검증하지 않은 채 통과한다.
     */
    @Test
    void likeAndUnlikeRoundTripThreeTimes() throws Exception {
        Owner owner = publishedOwner("왕복");
        Long projectId = project(owner, "왕복 전시");
        String liker = bearerFor(createMemberWithWallet(users, wallets, "왕복회원"));

        for (int round = 1; round <= 3; round++) {
            mockMvc.perform(put(likePath(projectId)).header("Authorization", liker))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.likeCount").value(1))
                    .andExpect(jsonPath("$.likedByMe").value(true));

            mockMvc.perform(delete(likePath(projectId)).header("Authorization", liker))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.likeCount").value(0))
                    .andExpect(jsonPath("$.likedByMe").value(false));
        }
    }

    /**
     * 멱등이 {@code PUT}·{@code DELETE} 를 고른 이유다 — 토글이면 두 번째 호출이 방금 누른 것을
     * 취소하고, 사용자는 누른 적 없는 취소를 본다.
     *
     * <p>취소도 같이 본다. 누른 적 없는 {@code DELETE} 가 404 를 내면 더블탭이 오류로 보이고,
     * 그건 "결과가 같으면 답도 같다"(§8)가 깨진 것이다.
     */
    @Test
    void repeatedCallsDoNotFlipTheLike() throws Exception {
        Owner owner = publishedOwner("멱등");
        Long projectId = project(owner, "멱등 전시");
        String liker = bearerFor(createMemberWithWallet(users, wallets, "멱등회원"));

        // 두 번을 똑같이 단정한다. 첫 호출을 단정하지 않으면 그것이 깨져도 두 번째가 첫
        // 성공이 되어 이 테스트가 초록으로 통과한다 — 멱등을 본다면서 아무것도 못 보는 것이다.
        for (int call = 1; call <= 2; call++) {
            mockMvc.perform(put(likePath(projectId)).header("Authorization", liker))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.likeCount").value(1))
                    .andExpect(jsonPath("$.likedByMe").value(true));
        }

        for (int call = 1; call <= 2; call++) {
            mockMvc.perform(delete(likePath(projectId)).header("Authorization", liker))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.likeCount").value(0))
                    .andExpect(jsonPath("$.likedByMe").value(false));
        }
    }

    /**
     * 쓰기 경로가 방문자 게이트를 <b>실제로 부르는지</b>만 본다 — 게이트 세 갈래는 위쪽 세
     * 테스트가 이미 같은 함수({@code requireVisitorVisible})에서 검증한다.
     *
     * <p>이 한 줄이 없으면 호출이 빠져도 전부 초록이고, 임대가 끝난 부스의 전시에 좋아요가
     * 계속 쌓인다.
     */
    @Test
    void likingAnExpiredBoothIsConflict() throws Exception {
        Owner owner = publishedOwner("만료좋아요");
        Long projectId = project(owner, "만료된 전시");
        String liker = bearerFor(createMemberWithWallet(users, wallets, "만료회원"));
        expireLease(jdbc, owner.boothId());

        mockMvc.perform(put(likePath(projectId)).header("Authorization", liker))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOTH_LEASE_EXPIRED"));
    }

    // ── 응답 모양 (§1 불변식 I-4 가 방문자 응답에도 유지된다) ───────────────

    /** 안 채운 URL 필드는 키가 있고 값이 {@code null} 이다 — {@code undefined} 가 아니다. */
    @Test
    void unsetFieldsKeepTheirKeysAndAreNull() throws Exception {
        Owner owner = publishedOwner("널");
        project(owner, "이름만 있는 전시");

        mockMvc.perform(get(published(owner.boothId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects[0].description").value(nullValue()))
                .andExpect(jsonPath("$.projects[0].thumbnailUrl").value(nullValue()))
                .andExpect(jsonPath("$.projects[0].videoUrl").value(nullValue()))
                .andExpect(jsonPath("$.projects[0].deployUrl").value(nullValue()))
                .andExpect(jsonPath("$.projects[0].gitUrl").value(nullValue()))
                .andExpect(jsonPath("$.projects[0].portfolioUrl").value(nullValue()));
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private static String published(Long boothId) {
        return "/api/v1/booths/" + boothId + "/projects/published";
    }

    /** 서비스를 거치지 않고 행을 넣는다 — 등록 경로는 이미 {@code ProjectApiIntegrationTest} 가 본다. */
    private Long project(Owner owner, String name) {
        return projects.save(new Project(owner.boothId(), name, null, null, null, null, null, null,
                java.time.Instant.now())).getId();
    }

    private static String likePath(Long projectId) {
        return "/api/v1/projects/" + projectId + "/like";
    }

    /**
     * 좋아요를 실제 경로로 넣는다. 전에는 {@code jdbc.update} 로 행을 직접 세웠는데, 쓰기
     * endpoint 가 없어서 그랬을 뿐이다 — 이제 있으니 픽스처가 계약을 우회할 이유가 없다.
     */
    private void likeAs(Long projectId, Long userId) throws Exception {
        mockMvc.perform(put(likePath(projectId)).header("Authorization", bearerFor(userId)))
                .andExpect(status().isOk());
    }

    private Owner leasedOwner(String prefix) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(userId, prefix + " 부스")).getId();
        grantLease(jdbc, boothId, userId);
        return new Owner(userId, boothId);
    }

    private Owner publishedOwner(String prefix) throws Exception {
        Owner owner = leasedOwner(prefix);
        BoothLayoutTestSupport.publishLayout(mockMvc, owner.boothId(), bearerFor(owner.userId()));
        return owner;
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }

    private record Owner(Long userId, Long boothId) { }
}
