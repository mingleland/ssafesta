package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 직원 역할이 편집 권한을 가른다 (spec 011 FR-002 · C-09, tasks T006·T007).
 *
 * <p>이 클래스가 지키는 것은 한 줄이다 — {@code requireEditor} 가 <b>행 존재</b>가 아니라
 * <b>역할</b>을 본다는 것. 그 한 줄이 무너지면 `-136` 이 초대를 여는 순간 {@code CONSULTANT} 가
 * Layout·AI 직원·문서·설문·프로젝트 편집까지 함께 얻는다.
 *
 * <p>호출처가 아홉이라 아홉 벌을 쓰는 대신, 게이트 자체를 직접 검증하고 <b>실제 endpoint 한
 * 경로</b>로 그 게이트를 정말 지나간다는 것만 확인한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BoothAccessGuardRoleTest {

    @Autowired private BoothAccessGuard guard;
    @Autowired private BoothRepository booths;
    @Autowired private BoothStaffRepository staffs;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void freeSlots() {
        BoothTestSupport.releaseAllSlots(jdbc);
    }

    @Test
    void ownerAlwaysPasses() {
        Owner owner = leasedOwner("소유자");

        assertDoesNotThrow(() -> guard.requireEditor(owner.boothId(), owner.userId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "CONTENT_EDITOR"})
    void editingRolesPass(String role) {
        Owner owner = leasedOwner("편집역할" + role);
        Long staff = staffWithRole(owner.boothId(), role);

        assertDoesNotThrow(() -> guard.requireEditor(owner.boothId(), staff));
    }

    /** C-09 가 이름으로 짚은 역할. 행은 있지만 편집은 못 한다. */
    @Test
    void consultantIsRejectedEvenThoughTheRowExists() {
        Owner owner = leasedOwner("상담원");
        Long staff = staffWithRole(owner.boothId(), "CONSULTANT");

        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM booth_staffs WHERE booth_id = ? AND user_id = ?",
                Integer.class, owner.boothId(), staff),
                "행은 있어야 한다 — 행이 없어서 거부되면 이 테스트는 아무것도 증명하지 않는다.");
        assertThrows(BoothEditorForbiddenException.class,
                () -> guard.requireEditor(owner.boothId(), staff));
    }

    @Test
    void nonMemberIsRejected() {
        Owner owner = leasedOwner("남");
        Long stranger = createMemberWithWallet(users, wallets, "외부인");

        assertThrows(BoothEditorForbiddenException.class,
                () -> guard.requireEditor(owner.boothId(), stranger));
    }

    /**
     * 어휘 밖 값은 <b>편집 권한 없음</b>으로 읽힌다 — 예외도 아니고 통과도 아니다.
     *
     * <p>V31 이 컬럼을 막으므로 그런 행을 만들어 낼 수는 없다. 고정하는 것은 그 앞단의 판단이다:
     * 마이그레이션보다 오래된 행이나 미래 빌드가 추가한 역할이 도착했을 때 <b>기본값이 거부</b>여야
     * 한다. 통과가 기본값이면 이 enum 이 존재할 이유가 없다.
     */
    @Test
    void unknownRoleGrantsNothing() {
        assertTrue(StaffRole.from("EDITOR").isEmpty(), "어휘 밖 값이 역할로 해석되면 안 된다.");
        assertTrue(StaffRole.from(null).isEmpty());
        assertTrue(StaffRole.from("").isEmpty());
        assertTrue(StaffRole.from("content_editor").filter(StaffRole::mayEditBoothContent).isPresent(),
                "대소문자는 같은 역할이다 — 저장값이 대문자라도 비교가 그것에 기대지 않는다.");
    }

    /** V31 — 어휘 밖 값은 애초에 저장되지 않는다. */
    @Test
    void roleVocabularyIsEnforcedByTheDatabase() {
        Owner owner = leasedOwner("제약");
        Long staff = createMemberWithWallet(users, wallets, "잘못된역할");

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO booth_staffs(booth_id, user_id, role) VALUES(?, ?, ?)",
                        owner.boothId(), staff, "SUPERVISOR"));
    }

    /**
     * 게이트를 실제로 지나가는지 — endpoint 한 경로로 확인한다.
     *
     * <p>{@code requireEditor} 를 쓰는 호출처는 아홉이지만 전부 같은 메서드를 부른다. 여기서 보는
     * 것은 "그 메서드가 요청 경로에 실제로 걸려 있는가" 이고, 역할별 판정 자체는 위에서 끝났다.
     */
    @Test
    void consultantIsRejectedThroughARealEndpoint() throws Exception {
        Owner owner = leasedOwner("경로확인");
        Long staff = staffWithRole(owner.boothId(), "CONSULTANT");

        mockMvc.perform(put("/api/v1/booths/{id}/homepage", owner.boothId())
                        .header("Authorization", "Bearer " + sessions.issue(staff).accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"homepageUrl\":\"https://consultant.example.com\"}"))
                .andExpect(status().isForbidden());
    }

    private Long staffWithRole(Long boothId, String role) {
        Long staff = createMemberWithWallet(users, wallets, "직원" + role);
        staffs.save(new BoothStaff(boothId, staff, role));
        return staff;
    }

    private Owner leasedOwner(String prefix) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(userId, prefix + " 부스")).getId();
        grantLease(jdbc, boothId, userId);
        return new Owner(userId, boothId);
    }

    private record Owner(Long userId, Long boothId) { }
}
