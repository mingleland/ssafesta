package com.example.ssafesta.booth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.consultation.ConsultationCounts;
import com.example.ssafesta.consultation.ConsultationRepository;
import com.example.ssafesta.survey.SurveyResponseRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 부스 운영 요약 (spec 015 US1, S15P21A604-501).
 *
 * <p><b>집계는 서버가 한다</b>(FR-010). 프론트가 원본을 받아 세면 화면마다 정의가 갈리고, 그 정의가
 * 무엇이었는지 나중에 아무도 모른다.
 *
 * <p><b>실시간 계산이다</b>(C-04). {@code booth_daily_metrics} 는 비워 둔다 — 부스가 12개인 규모에서
 * 원본 스캔이 사전 집계보다 싸고, 사전 집계는 갱신이 실패해도 조용히 그럴듯한 숫자를 계속 보여준다.
 * 느려지면 그때 얹는다.
 *
 * <p><b>{@code null} 은 0 이 아니다.</b> 이 화면의 두 칸은 아직 셀 원천이 없다 — AI 이용(FR-002)은
 * 대화 기록 표가 저장소에 없고, 수익(FR-005)은 부스로 코인이 들어오는 경로 자체가 설계에 없다.
 * 0 으로 채우면 FE 는 "AI 이용 0건" 카드를 영원히 띄운다.
 */
@Service
public class BoothDashboardService {

    private final BoothAccessGuard accessGuard;
    private final BoothVisitService visits;
    private final ConsultationRepository consultations;
    private final SurveyResponseRepository surveyResponses;

    public BoothDashboardService(BoothAccessGuard accessGuard, BoothVisitService visits,
                                 ConsultationRepository consultations,
                                 SurveyResponseRepository surveyResponses) {
        this.accessGuard = accessGuard;
        this.visits = visits;
        this.consultations = consultations;
        this.surveyResponses = surveyResponses;
    }

    /**
     * 한 부스, 한 기간.
     *
     * <p>세 표를 세 질의로 읽는다. 한 질의로 합칠 수 있지만 합치지 않았다 — 그러면 세 패키지의 표
     * 이름이 {@code booth} 패키지의 네이티브 SQL 문자열에 박혀, 그 표가 바뀌어도 컴파일이 깨지지
     * 않는다. 질의 사이의 짧은 시차는 해가 없다: 이 숫자들끼리 맞춰 보는 계산이 없기 때문이다
     * (방문 수를 상담 수로 나누는 식의 파생 지표를 넣게 되면 그때는 한 질의여야 한다).
     */
    @Transactional(readOnly = true)
    public SummaryView summary(Long boothId, Long viewerUserId, Instant from, Instant to) {
        // 부스 콘텐츠 편집 권한과 같은 게이트다 — CONSULTANT 는 상담을 하지 운영 지표를 보지 않는다.
        accessGuard.requireEditor(boothId, viewerUserId);
        if (!from.isBefore(to)) {
            throw ApiException.fieldInvalid("from", "조회 시작이 끝보다 앞서야 합니다.");
        }

        BoothVisitService.MetricsView visit = visits.rawMetrics(boothId, from, to);
        ConsultationCounts consultation = consultations.countForBoothBetween(boothId, from, to);
        long responses = surveyResponses.countForBoothBetween(boothId, from, to);

        return new SummaryView(from, to,
                visit.visits(), visit.uniqueVisitors(), visit.averageDwellSeconds(), visit.openVisits(),
                consultation.requested(), consultation.ended(), responses,
                null, null);
    }

    /**
     * @param aiUsages <b>{@code null} 은 0 이 아니다</b> — AI 대화를 남기는 표가 아직 없다.
     *        표가 생기면 채운다 (AI 파트 소관, {@code S15P21A604-139} 계열)
     * @param revenueCoin <b>{@code null} 은 0 이 아니다</b> — 부스로 코인이 들어오는 경로가 설계에
     *        없다. 원장의 사유 중 부스와 닿는 것은 임대료(소유자의 <i>지출</i>)와 설문 보상(부스가
     *        내지 않고 발행된다)뿐이다. 결정 요청은 {@code docs/26} 에 있다
     */
    public record SummaryView(Instant from, Instant to,
                              long visits, long uniqueVisitors, long averageDwellSeconds, long openVisits,
                              long consultations, long consultationsEnded, long surveyResponses,
                              Long aiUsages, Long revenueCoin) { }
}
