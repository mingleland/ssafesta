package com.example.ssafesta.project;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * INSERT 가 깨뜨린 제약이 <b>무엇인지</b> 보고 답을 고르는지 (research R-02).
 *
 * <p>{@code DataIntegrityViolationException} 을 무조건 409 로 번역하면 등록 도중 부스가 사라져
 * {@code booth_id} 외래키가 깨진 경우까지 "이미 프로젝트가 있습니다"가 된다 — 사용자는 있지도
 * 않은 프로젝트를 찾으러 간다. 컨테이너 없이 도는 단위 테스트다.
 */
class ProjectConstraintTranslationTest {

    @Test
    void theUniqueIndexOnBoothIdMeansTheBoothAlreadyHasOne() {
        DataIntegrityViolationException violation = violationOf("ux_projects_booth");

        assertInstanceOf(ProjectAlreadyExistsException.class, ProjectService.translate(violation, 7L));
    }

    /** 대소문자는 드라이버·DB 마다 다르게 온다. 판정이 그것에 걸리면 안 된다. */
    @Test
    void theIndexNameIsMatchedCaseInsensitively() {
        DataIntegrityViolationException violation = violationOf("UX_PROJECTS_BOOTH");

        assertInstanceOf(ProjectAlreadyExistsException.class, ProjectService.translate(violation, 7L));
    }

    /**
     * 외래키 위반은 중복이 아니다.
     *
     * <p>설명할 수 없는 사건이므로 삼키지 않고 원본을 그대로 올린다 — 500 이 되고 로그에 크게
     * 남는다. 그게 조용히 틀린 409 를 주는 것보다 정직하다 (T-24).
     */
    @Test
    void aForeignKeyViolationIsNotTranslated() {
        DataIntegrityViolationException violation = violationOf("fk_projects_booth_id");

        assertSame(violation, ProjectService.translate(violation, 7L));
    }

    /** 드라이버가 제약 이름을 안 주면 추측하지 않는다. */
    @Test
    void anUnnamedViolationIsNotTranslated() {
        DataIntegrityViolationException violation =
                new DataIntegrityViolationException("이름 없는 위반");

        assertSame(violation, ProjectService.translate(violation, 7L));
    }

    private static DataIntegrityViolationException violationOf(String constraint) {
        return new DataIntegrityViolationException("위반",
                new ConstraintViolationException("위반", new SQLException("23505"), constraint));
    }
}
