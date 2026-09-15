package com.example.ssafesta.consultation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssafesta.booth.BoothAccessGuard;
import com.example.ssafesta.consultation.ws.ConsultationEventPublisher;
import com.example.ssafesta.staff.StaffAccessGuard;
import com.example.ssafesta.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 종료 알림이 <b>그 상담의 부스</b>로 나가는지 (S15P21A604-719).
 *
 * <p>{@code ConsultationEventPublisherTest} 는 publisher 가 받은 boothId 로 어디에 보내는지를
 * 고정한다. 그런데 서비스가 <b>무엇을 boothId 자리에 넣는지</b>는 거기서 보이지 않고, 세 인자가
 * 전부 {@code Long} 이라 방문자 id 나 요청 id 를 넣어도 컴파일된다. 그 경우 이벤트는 엉뚱한 부스의
 * 직원들에게 가고 정작 그 부스는 아무것도 못 받는다 — 고치기 전과 증상이 같다.
 *
 * <p>기존 통합 테스트로는 잡히지 않는다. {@code ConsultationApiIntegrationTest} 는 DB 상태만 보고
 * {@code ConsultationEventFailureIntegrationTest} 는 destination 을 보지 않는다.
 *
 * <p>세 id 는 <b>서로 다른 값</b>이어야 한다 — 같은 값을 쓰면 인자 순서가 뒤바뀌어도 통과한다.
 * 컨테이너 없이 돈다.
 */
class ConsultationServiceEndWiringTest {

    private final ConsultationRepository consultations = mock(ConsultationRepository.class);
    private final ConsultationEventPublisher events = mock(ConsultationEventPublisher.class);
    private final ConsultationService service = new ConsultationService(
            consultations, mock(BoothAccessGuard.class), mock(StaffAccessGuard.class),
            mock(HandoffSummaryClient.class), mock(UserRepository.class), events);

    @Test
    void endPublishesWithTheBoothOfTheConsultation() {
        Consultation consultation = mock(Consultation.class);
        when(consultation.getId()).thenReturn(901L);
        when(consultation.getBoothId()).thenReturn(7L);
        when(consultation.getVisitorUserId()).thenReturn(42L);
        when(consultation.getStatus()).thenReturn(ConsultationStatus.ACCEPTED);
        when(consultations.findById(901L)).thenReturn(Optional.of(consultation));

        service.end(901L, 42L);

        verify(events).ended(7L, 42L, 901L);
    }
}
