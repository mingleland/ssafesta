package com.example.ssafesta.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.user.OAuthIdentity;
import com.example.ssafesta.user.OAuthIdentityRepository;
import com.example.ssafesta.user.OAuthProvider;
import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

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
    @MockitoSpyBean private OAuthIdentityRepository identities;

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
     * A unique violation the service cannot name must not be excused as a race.
     *
     * <p>This goes through {@code RegistrationService} rather than round the side of it. The first
     * version inserted a duplicate wallet row with {@code JdbcTemplate} and asserted the database
     * refused it — which proves the database has a constraint, and nothing at all about the
     * translation the test is named after (raised in review of !56).
     *
     * <p>{@code oauth_identities_user_id_provider_key} is the stand-in: a real constraint on a table
     * the signup writes to, and one the service deliberately does not list because it cannot fire on
     * this path. If it somehow did, that is our bug and has to come out as a fault.
     */
    @Test
    void aUniqueViolationTheServiceCannotNameIsNotReadAsARace() {
        DataIntegrityViolationException unknown = violationOf("oauth_identities_user_id_provider_key");
        doThrow(unknown).when(identities).save(any(OAuthIdentity.class));

        DataIntegrityViolationException thrown = assertThrows(DataIntegrityViolationException.class,
                () -> registrations.complete(OAuthProvider.GOOGLE, "subject-" + UUID.randomUUID(),
                        "모르는제약" + UUID.randomUUID().toString().substring(0, 6)));

        assertSame(unknown, thrown, "서비스가 이름을 모르는 unique 위반은 409 가 아니라 그대로 올라가야 합니다.");
    }

    /** The one identity constraint that <i>can</i> fire is still translated. */
    @Test
    void theIdentityRaceTheServiceKnowsAboutIsTranslated() {
        doThrow(violationOf("oauth_identities_provider_provider_subject_key"))
                .when(identities).save(any(OAuthIdentity.class));

        RegistrationConflictException thrown = assertThrows(RegistrationConflictException.class,
                () -> registrations.complete(OAuthProvider.GOOGLE, "subject-" + UUID.randomUUID(),
                        "아는제약" + UUID.randomUUID().toString().substring(0, 6)));

        assertEquals(ErrorCode.REGISTRATION_CONFLICT, thrown.errorCode());
    }

    /** Shaped the way the driver and Hibernate hand it over — the name rides on the nested cause. */
    private DataIntegrityViolationException violationOf(String constraint) {
        return new DataIntegrityViolationException(constraint, new ConstraintViolationException(
                "duplicate key", new SQLException("duplicate key", "23505"), constraint));
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

    private List<String> uniqueConstraintsOf(String table) {
        return jdbc.queryForList(
                "select conname from pg_constraint where conrelid = ?::regclass and contype = 'u'",
                String.class, table);
    }
}
