package com.example.ssafesta.world.booth;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothLayoutTestSupport;
import com.example.ssafesta.booth.BoothLeaseService;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 부스 변경이 월드에 있는 모두에게 나가는지 (S15P21A604-727, GitLab #193).
 *
 * <p>고정하는 것은 <b>어느 경로가 신호를 내는가</b>다. 신호가 빠진 것은 오류가 아니라 침묵이라
 * 경로 하나가 빠져도 다른 테스트는 전부 초록이다 — 그래서 다섯 경로를 하나씩 센다.
 *
 * <p><b>클래스에 {@code @Transactional} 을 붙이지 않는다.</b> 붙이면 테스트 트랜잭션이 커밋되지
 * 않아 커밋 후 리스너가 영영 돌지 않고, 이 테스트가 통째로 거짓 빨강이 된다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BoothChangeBroadcastIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private BoothRepository booths;
    @Autowired private BoothLeaseService leases;
    @Autowired private JdbcTemplate jdbc;

    /** 발행을 가로채 무엇이 나갔는지 본다 — 브로커를 띄우지 않아 결과가 흔들리지 않는다. */
    @MockitoBean private SimpMessagingTemplate messaging;

    private String bearer;
    private Long slotId;

    @BeforeEach
    void aMemberAndAFreeSlot() {
        Long userId = createMemberWithWallet(users, wallets, "변경방송");
        bearer = "Bearer " + sessions.issue(userId).accessToken();
        slotId = freeSlotId();
    }

    @Test
    void leasingASlotIsBroadcast() throws Exception {
        lease();

        verify(messaging).convertAndSend(eq(BoothChangeBroadcaster.TOPIC),
                eq((Object) new BoothChanged(slotId)));
    }

    @Test
    void publishingALayoutIsBroadcast() throws Exception {
        Long boothId = lease();
        clearInvocations(messaging);

        BoothLayoutTestSupport.publishLayout(mockMvc, boothId, bearer);

        verify(messaging).convertAndSend(eq(BoothChangeBroadcaster.TOPIC),
                eq((Object) new BoothChanged(slotId)));
    }

    /** 외관은 배치 회차를 올리지 않는다 — 이 신호가 없으면 간판만 낡은 채 남는다. */
    @Test
    void changingTheFacadeIsBroadcast() throws Exception {
        Long boothId = lease();
        clearInvocations(messaging);

        mockMvc.perform(put("/api/v1/booths/{id}/facade", boothId)
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"themeCode\":\"WARM\",\"signText\":\"새 간판\"}"))
                .andExpect(status().isOk());

        verify(messaging).convertAndSend(eq(BoothChangeBroadcaster.TOPIC),
                eq((Object) new BoothChanged(slotId)));
    }

    @Test
    void changingTheHomepageIsBroadcast() throws Exception {
        Long boothId = lease();
        clearInvocations(messaging);

        mockMvc.perform(put("/api/v1/booths/{id}/homepage", boothId)
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"homepageUrl\":\"https://example.com/booth\"}"))
                .andExpect(status().isOk());

        verify(messaging).convertAndSend(eq(BoothChangeBroadcaster.TOPIC),
                eq((Object) new BoothChanged(slotId)));
    }

    /** 만료는 부스가 슬롯을 놓는 일이라 부스에서 슬롯을 읽을 수 없다 — 임대가 들고 있던 것을 쓴다. */
    @Test
    void expiringALeaseIsBroadcast() throws Exception {
        lease();
        clearInvocations(messaging);
        jdbc.update("UPDATE booth_leases SET starts_at = now() - interval '25 hours', "
                + "ends_at = now() - interval '1 hour' WHERE slot_id = ?", slotId);

        assertEquals(1, leases.expireStaleLeases());

        verify(messaging).convertAndSend(eq(BoothChangeBroadcaster.TOPIC),
                eq((Object) new BoothChanged(slotId)));
    }

    /**
     * <b>신호는 커밋 뒤에 나간다.</b>
     *
     * <p>트랜잭션 안에서 보내면 받는 쪽이 커밋 전에 재조회해 <i>옛 값</i>을 읽고 그 서명을 캐시해
     * 버린다 — 다음 변경이 올 때까지 낡은 부스가 굳는다. 화면에서는 "게시했는데 그대로" 로만
     * 보이고 오류는 어디에도 남지 않아, 이 단정이 없으면 회귀를 알아챌 방법이 없다.
     *
     * <p>보내는 순간 <b>다른 커넥션으로</b> 그 행을 읽어 커밋 여부를 가른다.
     */
    @Test
    void theSignalGoesOutAfterTheCommit() throws Exception {
        Long boothId = lease();
        clearInvocations(messaging);
        AtomicReference<Long> versionSeenAtSend = new AtomicReference<>();
        doAnswer(invocation -> {
            versionSeenAtSend.set(jdbc.queryForObject(
                    "SELECT published_layout_version FROM booths WHERE id = ?", Long.class, boothId));
            return null;
        }).when(messaging).convertAndSend(eq(BoothChangeBroadcaster.TOPIC), any(Object.class));

        BoothLayoutTestSupport.publishLayout(mockMvc, boothId, bearer);

        assertNotNull(versionSeenAtSend.get(),
                "방송 시점에 공개 회차가 아직 커밋되지 않았습니다 — 받는 쪽이 옛 배치를 읽습니다.");
        assertEquals(1L, versionSeenAtSend.get());
    }

    /** @return 임대로 생긴 부스의 id */
    private Long lease() throws Exception {
        mockMvc.perform(post("/api/v1/booth-slots/{slotId}/leases", slotId)
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"durationDays\":1}"))
                .andExpect(status().isCreated());
        return booths.findByCurrentSlotId(slotId).map(Booth::getId).orElseThrow();
    }

    private Long freeSlotId() {
        return jdbc.queryForObject("""
                SELECT id FROM booth_slots
                 WHERE slot_type = 'USER_RENTAL'
                   AND id NOT IN (SELECT slot_id FROM booth_leases WHERE status = 'ACTIVE')
                 ORDER BY id LIMIT 1
                """, Long.class);
    }
}
