package com.example.ssafesta.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
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
    @MockitoSpyBean private UserRepository users;
    @MockitoSpyBean private OAuthIdentityRepository identities;

    @Test
    void theConstraintNamesTheServiceDependsOnStillExist() {
        assertTrue(uniqueConstraintsOf("users").contains("users_nickname_key"),
                "닉네임 경합으로 번역하는 이름입니다: " + uniqueConstraintsOf("users"));
        assertTrue(uniqueConstraintsOf("oauth_identities").contains("oauth_identities_provider_provider_subject_key"),
                "가입 경합으로 번역하는 이름입니다: " + uniqueConstraintsOf("oauth_identities"));
        assertTrue(uniqueConstraintsOf("oauth_identities").contains("oauth_identities_user_id_provider_key"),
                "번역하지 않는 이름 — 미지 제약 테스트의 대역입니다: " + uniqueConstraintsOf("oauth_identities"));
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

    /**
     * The nickname race — the one the database catches, not the one the pre-check catches.
     *
     * <p>The first version committed a user with the nickname and called {@code complete}. That
     * never reached the insert: {@code existsByNickname} sees the row and throws from the pre-check,
     * so the test proved the pre-check works and left the {@code users_nickname_key} mapping
     * unexercised — it asserted the right code by the wrong route (raised in review of !56).
     *
     * <p>Stubbing the pre-check to pass is what forces the race path: the only way this exception can
     * arrive now is the translation under test.
     */
    @Test
    void aNicknameLostBetweenTheCheckAndTheInsertAnswersAsADuplicate() {
        doReturn(false).when(users).existsByNickname(anyString());
        doThrow(violationOf("users_nickname_key")).when(users).save(any(User.class));

        DuplicateNicknameException thrown = assertThrows(DuplicateNicknameException.class,
                () -> registrations.complete(OAuthProvider.GOOGLE, "subject-" + UUID.randomUUID(),
                        "경합닉" + UUID.randomUUID().toString().substring(0, 6)));

        assertEquals(ErrorCode.NICKNAME_DUPLICATED, thrown.errorCode());
    }

    private List<String> uniqueConstraintsOf(String table) {
        return jdbc.queryForList(
                "select conname from pg_constraint where conrelid = ?::regclass and contype = 'u'",
                String.class, table);
    }
}
