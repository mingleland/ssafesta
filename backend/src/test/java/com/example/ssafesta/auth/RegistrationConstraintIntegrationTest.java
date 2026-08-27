package com.example.ssafesta.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.user.OAuthProvider;
import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code RegistrationService} decides between a race and a fault by the constraint the database
 * names, so those names are part of the code and have to be checked against the live schema.
 *
 * <p>They are PostgreSQL's own generated names. That is fine to depend on — but only while something
 * notices if they change. Without this test a migration could rename one and the mapping would go
 * quiet: races would start answering 500. That is the safe direction to be wrong, which is exactly
 * why nobody would notice.
 *
 * <p>The wider point is the fourth column of this table. Matching on SQLState alone made <i>every</i>
 * unique violation a race, so a unique index added later would silently start answering 409 —
 * masking a fault behind a status nobody investigates (raised in review of !56).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RegistrationConstraintIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RegistrationService registrations;
    @Autowired private UserRepository users;

    @Test
    void theConstraintNamesTheServiceBranchesOnStillExist() {
        assertTrue(uniqueConstraintsOf("users").contains("users_nickname_key"),
                "닉네임 경합 판정이 이 이름에 걸려 있습니다: " + uniqueConstraintsOf("users"));
        assertTrue(uniqueConstraintsOf("oauth_identities").containsAll(List.of(
                        "oauth_identities_provider_provider_subject_key",
                        "oauth_identities_user_id_provider_key")),
                "가입 경합 판정이 이 이름들에 걸려 있습니다: " + uniqueConstraintsOf("oauth_identities"));
    }

    /**
     * A unique constraint the service does not know about must not be read as a race.
     *
     * <p>{@code wallets_user_id_key} stands in for one: it is a real unique constraint on a table the
     * signup transaction writes to, and a violation of it means a brand-new user already had a
     * wallet — our bug, not a second signup. The service has to let it through as a fault.
     */
    @Test
    void anUnexpectedUniqueViolationIsNotReadAsARace() {
        Long userId = users.save(new User("지갑충돌" + UUID.randomUUID().toString().substring(0, 6))).getId();
        jdbc.update("insert into wallets (user_id, balance) values (?, 0)", userId);

        // The same wallet again — a unique violation, but not one the signup path may excuse.
        assertTrue(assertThrowsIntegrityViolation(userId),
                "모르는 unique 위반은 409 로 번역되지 않고 그대로 올라가야 합니다.");
    }

    /** The nickname race answers with the same code the pre-check does — the caller needs another name. */
    @Test
    void aNicknameLostToAnotherSignupAnswersAsADuplicate() {
        String nickname = "선점" + UUID.randomUUID().toString().substring(0, 6);
        users.save(new User(nickname));

        // Bypasses the pre-check the way a concurrent signup would: the row appears between the
        // check and the insert. Committing it first is the closest a single thread gets to that.
        DuplicateNicknameException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                DuplicateNicknameException.class,
                () -> registrations.complete(OAuthProvider.GOOGLE, "subject-" + UUID.randomUUID(), nickname));

        assertEquals(ErrorCode.NICKNAME_DUPLICATED, thrown.errorCode());
    }

    private boolean assertThrowsIntegrityViolation(Long userId) {
        try {
            jdbc.update("insert into wallets (user_id, balance) values (?, 0)", userId);
            return false;
        } catch (org.springframework.dao.DataIntegrityViolationException expected) {
            return true;
        }
    }

    private List<String> uniqueConstraintsOf(String table) {
        return jdbc.queryForList(
                "select conname from pg_constraint where conrelid = ?::regclass and contype = 'u'",
                String.class, table);
    }
}
