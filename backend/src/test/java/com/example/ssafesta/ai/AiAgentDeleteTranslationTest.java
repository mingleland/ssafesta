package com.example.ssafesta.ai;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 삭제가 깨뜨린 외래키가 <b>무엇인지</b> 보고 답을 고르는지 (spec 007 C-14).
 *
 * <p>사전 검사와 DELETE 사이에 문서나 상담이 생기는 경쟁이 있어 DELETE 자체도 실패할 수 있다.
 * 그때 모든 {@code DataIntegrityViolationException} 을 409 로 번역하면 알 수 없는 원인까지
 * "참조가 있습니다"가 된다. 아는 두 제약만 번역한다. 컨테이너 없이 도는 단위 테스트다.
 */
class AiAgentDeleteTranslationTest {

    @Test
    void theDocumentsForeignKeyMeansDocumentsAreHoldingIt() {
        DataIntegrityViolationException violation = violationOf("ai_documents_agent_id_fkey");

        AiAgentDeleteConflictException translated = assertInstanceOf(
                AiAgentDeleteConflictException.class,
                AiAgentService.translateDeleteViolation(violation));
        org.junit.jupiter.api.Assertions.assertTrue(translated.getMessage().contains("문서"));
    }

    @Test
    void theConsultationsForeignKeyMeansConsultationsAreHoldingIt() {
        DataIntegrityViolationException violation = violationOf("consultations_agent_id_fkey");

        AiAgentDeleteConflictException translated = assertInstanceOf(
                AiAgentDeleteConflictException.class,
                AiAgentService.translateDeleteViolation(violation));
        org.junit.jupiter.api.Assertions.assertTrue(translated.getMessage().contains("상담"));
    }

    /** 대소문자는 드라이버·DB 마다 다르게 온다. 판정이 그것에 걸리면 안 된다. */
    @Test
    void theForeignKeyNameIsMatchedCaseInsensitively() {
        DataIntegrityViolationException violation = violationOf("AI_DOCUMENTS_AGENT_ID_FKEY");

        assertInstanceOf(AiAgentDeleteConflictException.class,
                AiAgentService.translateDeleteViolation(violation));
    }

    /**
     * 청크 외래키는 번역하지 않는다.
     *
     * <p>{@code ai_document_chunks} 는 V1 에 남아 있지만 C-11 로 AI DB 소유가 됐다 — Spring 은
     * 여기에 쓰지 않는다. 문서가 있으면 이미 거부되므로 이 위반은 도달할 수 없고, 그럼에도
     * 도달한다면 우리가 모르는 사건이다. 조용히 409 로 덮지 않고 그대로 올려 500 과 로그로 드러낸다.
     */
    @Test
    void theChunksForeignKeyIsNotTranslated() {
        DataIntegrityViolationException violation = violationOf("ai_document_chunks_agent_id_fkey");

        assertSame(violation, AiAgentService.translateDeleteViolation(violation));
    }

    /** 드라이버가 제약 이름을 안 주면 추측하지 않는다. */
    @Test
    void anUnnamedViolationIsNotTranslated() {
        DataIntegrityViolationException violation =
                new DataIntegrityViolationException("이름 없는 위반");

        assertSame(violation, AiAgentService.translateDeleteViolation(violation));
    }

    private static DataIntegrityViolationException violationOf(String constraint) {
        return new DataIntegrityViolationException("위반",
                new ConstraintViolationException("위반", new SQLException("23503"), constraint));
    }
}
