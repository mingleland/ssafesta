package com.example.ssafesta.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.AccessTokenService;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 오락기별 TOP 5 랭킹 (S15P21A604-963, GitLab #264).
 *
 * <p>기계는 {@code app.arcade.machine-ids} 화이트리스트에서 고른다 — 바인딩을 만들지 않는 것이
 * 의도다. 랭킹은 게임기에 귀속되므로 게임이 걸리지 않은 빈 자리도 기록을 들고 있어야 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ArcadeRankingApiIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private MemberSessionService sessions;
    @Autowired private AccessTokenService accessTokens;
    @Autowired private JdbcTemplate jdbc;

    /**
     * 경로가 {@code /api/v1} 밖이라 CSRF 예외 목록에 따로 올라가야 한다 — 빠뜨리면 유효한 토큰을
     * 들고도 403 이다. 이 테스트가 그 회귀를 잡는다.
     */
    @Test
    void aMemberSubmitsAScoreAndBecomesFirst() throws Exception {
        Member member = member("일등");

        submit("arcade-01", member, 9800)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.updated").value(true))
                .andExpect(jsonPath("$.bestScore").value(9800))
                .andExpect(jsonPath("$.rank").value(1));

        mockMvc.perform(get("/api/arcade/rankings/arcade-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].rank").value(1))
                .andExpect(jsonPath("$[0].nickname").value(member.nickname()))
                .andExpect(jsonPath("$[0].bestScore").value(9800));
    }

    /**
     * 더 낮은 점수는 기존 최고점을 건드리지 않는다 — upsert 의 {@code WHERE} 절이 하는 일이다.
     * 그 절을 지우면 이 테스트가 실패한다.
     */
    @Test
    void aLowerScoreDoesNotReplaceTheBest() throws Exception {
        Member member = member("최고점");
        submit("arcade-02", member, 500).andExpect(jsonPath("$.updated").value(true));

        submit("arcade-02", member, 100)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(false))
                .andExpect(jsonPath("$.bestScore").value(500));

        // 같은 점수도 갱신이 아니다 — 달성 시각이 뒤로 밀리면 동점 정렬이 뒤집힌다.
        submit("arcade-02", member, 500).andExpect(jsonPath("$.updated").value(false));

        mockMvc.perform(get("/api/arcade/rankings/arcade-02/me")
                        .header("Authorization", bearerFor(member)))
                .andExpect(jsonPath("$.bestScore").value(500));
    }

    /** 사용자당 한 줄이고 목록은 5명에서 끊긴다 (#264 완료 조건). */
    @Test
    void theBoardKeepsOneRowPerUserAndStopsAtFive() throws Exception {
        for (int i = 1; i <= 6; i++) {
            Member member = member("여섯" + i);
            submit("arcade-03", member, i * 100).andExpect(status().isOk());
            // 같은 사람이 여러 번 해도 줄은 하나다.
            submit("arcade-03", member, i * 100 + 1).andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/arcade/rankings/arcade-03"))
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].bestScore").value(601))
                .andExpect(jsonPath("$[4].bestScore").value(201));
    }

    /** 오락기마다 독립된 순위표다. */
    @Test
    void machinesDoNotShareABoard() throws Exception {
        Member first = member("사번");
        Member second = member("오번");
        submit("arcade-04", first, 10).andExpect(status().isOk());
        submit("arcade-05", second, 20).andExpect(status().isOk());

        mockMvc.perform(get("/api/arcade/rankings/arcade-04"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].nickname").value(first.nickname()));
        mockMvc.perform(get("/api/arcade/rankings/arcade-05"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].nickname").value(second.nickname()));
    }

    /** 동점이면 먼저 달성한 기록이 위다. */
    @Test
    void aTieGoesToTheEarlierRecord() throws Exception {
        Member early = member("먼저");
        Member late = member("나중");
        submit("arcade-06", early, 777).andExpect(status().isOk());
        submit("arcade-06", late, 777).andExpect(status().isOk());

        mockMvc.perform(get("/api/arcade/rankings/arcade-06"))
                .andExpect(jsonPath("$[0].nickname").value(early.nickname()))
                .andExpect(jsonPath("$[1].nickname").value(late.nickname()));
    }

    /**
     * 점수와 달성 시각이 <b>모두</b> 같을 때 목록 순서와 {@code /me} 순위가 어긋나지 않아야 한다.
     *
     * <p>정렬 3키({@code user_id})가 없으면 목록은 DB 가 정한 임의 순서로 나오고 {@code /me} 는
     * 둘 다 1등이라고 답한다. 실제 동시 등록에서 같은 시각이 찍힐 수 있어 재현이 어려우므로 시각을
     * 직접 같은 값으로 박는다.
     */
    @Test
    void anExactTieRanksTheSameInBothViews() throws Exception {
        Member first = member("동시갑");
        Member second = member("동시을");
        submit("arcade-07", first, 1234).andExpect(status().isOk());
        submit("arcade-07", second, 1234).andExpect(status().isOk());
        jdbc.update("update arcade_score_records set achieved_at = timestamptz '2026-09-22 00:00:00Z' "
                + "where machine_id = 'arcade-07'");

        Member ahead = first.userId() < second.userId() ? first : second;
        Member behind = ahead == first ? second : first;

        mockMvc.perform(get("/api/arcade/rankings/arcade-07"))
                .andExpect(jsonPath("$[0].nickname").value(ahead.nickname()))
                .andExpect(jsonPath("$[1].nickname").value(behind.nickname()));
        mockMvc.perform(get("/api/arcade/rankings/arcade-07/me").header("Authorization", bearerFor(ahead)))
                .andExpect(jsonPath("$.rank").value(1));
        mockMvc.perform(get("/api/arcade/rankings/arcade-07/me").header("Authorization", bearerFor(behind)))
                .andExpect(jsonPath("$.rank").value(2));
    }

    /** 화이트리스트에 없는 기계는 세 경로 모두 404 다. 광장 고정물도 여기 없다. */
    @Test
    void anUnknownMachineIsNotFoundOnEveryPath() throws Exception {
        Member member = member("없는기계");

        submit("arcade-99", member, 1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MACHINE_NOT_FOUND"));
        mockMvc.perform(get("/api/arcade/rankings/plaza-arcade-01"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MACHINE_NOT_FOUND"));
        mockMvc.perform(get("/api/arcade/rankings/arcade-99/me").header("Authorization", bearerFor(member)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MACHINE_NOT_FOUND"));
    }

    /**
     * 기록이 없는 것은 오류가 아니다 — 빈 배열과 전 필드 {@code null} 이다. 404 로 답하면
     * 클라이언트가 "없는 오락기" 와 구별할 수 없다. 게임이 걸리지 않은 빈 자리라는 점도 함께 본다.
     */
    @Test
    void anEmptyMachineAnswersWithoutRecords() throws Exception {
        Member member = member("빈자리");

        mockMvc.perform(get("/api/arcade/rankings/arcade-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/arcade/rankings/arcade-08/me").header("Authorization", bearerFor(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bestScore").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.achievedAt").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.rank").value(Matchers.nullValue()));

        // 바인딩이 없어도 등록된다 — 랭킹은 걸린 게임이 아니라 기계의 것이다.
        submit("arcade-08", member, 42).andExpect(jsonPath("$.updated").value(true));
    }

    /** 점수 범위는 서버가 본다. */
    @Test
    void scoresOutsideTheRangeAreRejected() throws Exception {
        Member member = member("범위");

        submit("arcade-09", member, -1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        submit("arcade-09", member, 100_001)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/arcade/rankings/arcade-09/scores")
                        .header("Authorization", bearerFor(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        submit("arcade-09", member, 100_000).andExpect(status().isOk());
    }

    /** 본문의 사용자 식별자는 신뢰하지 않는다 (#264). */
    @Test
    void aClientSuppliedUserIdIsIgnored() throws Exception {
        Member author = member("진짜");
        Member victim = member("사칭당함");

        mockMvc.perform(post("/api/arcade/rankings/arcade-10/scores")
                        .header("Authorization", bearerFor(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"score\":30,\"userId\":" + victim.userId() + "}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/arcade/rankings/arcade-10"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].nickname").value(author.nickname()));
    }

    /** 게스트도 순위판은 본다 (FR-023 과 같은 이유). 등록과 {@code /me} 는 회원 전용이다. */
    @Test
    void aGuestReadsTheBoardButHasNoRecord() throws Exception {
        Member member = member("게스트본다");
        submit("arcade-11", member, 55).andExpect(status().isOk());
        String guest = "Bearer " + accessTokens.issueGuestToken().token();

        mockMvc.perform(get("/api/arcade/rankings/arcade-11").header("Authorization", guest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/arcade/rankings/arcade-11/me").header("Authorization", guest))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_ONLY"));
        mockMvc.perform(post("/api/arcade/rankings/arcade-11/scores")
                        .header("Authorization", guest)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"score\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_ONLY"));
    }

    /** {@code limit} 은 조용히 1~5 로 당기고, 숫자가 아닌 값만 거절한다. */
    @Test
    void theLimitIsClampedAndOnlyNonNumbersFail() throws Exception {
        for (int i = 1; i <= 3; i++) {
            submit("arcade-12", member("한도" + i), i * 10).andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/arcade/rankings/arcade-12").param("limit", "0"))
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/arcade/rankings/arcade-12").param("limit", "99"))
                .andExpect(jsonPath("$.length()").value(3));
        mockMvc.perform(get("/api/arcade/rankings/arcade-12").param("limit", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /**
     * 회원이 사라지면 기록도 사라진다 (FR-040). 표의 {@code ON DELETE CASCADE} 가 지키는 것이며,
     * 수동 삭제 목록에 기대면 목록을 빠뜨린 쪽이 외래키 위반으로 죽는다 (S15P21A604-681).
     */
    @Test
    void deletingAMemberTakesTheirRecordWithIt() throws Exception {
        Member leaving = member("탈퇴");
        Member staying = member("잔류");
        submit("arcade-13", leaving, 900).andExpect(status().isOk());
        submit("arcade-13", staying, 100).andExpect(status().isOk());

        users.deleteById(leaving.userId());

        mockMvc.perform(get("/api/arcade/rankings/arcade-13"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].nickname").value(staying.nickname()))
                .andExpect(jsonPath("$[0].rank").value(1));
        assertThat(jdbc.queryForObject(
                "select count(*) from arcade_score_records where user_id = ?", Integer.class,
                leaving.userId())).isZero();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.ResultActions submit(String machineId, Member member,
                                                                      int score) throws Exception {
        return mockMvc.perform(post("/api/arcade/rankings/" + machineId + "/scores")
                .header("Authorization", bearerFor(member))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"score\":" + score + "}"));
    }

    private Member member(String prefix) {
        // nickname VARCHAR(30) 예산. 응답의 표시명을 그대로 대조하므로 값을 들고 다닌다.
        String nickname = prefix + "r" + SEQUENCE.incrementAndGet();
        return new Member(users.save(new User(nickname)).getId(), nickname);
    }

    private String bearerFor(Member member) {
        return "Bearer " + sessions.issue(member.userId()).accessToken();
    }

    private record Member(Long userId, String nickname) { }
}
