package com.example.ssafesta.booth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.consultation.ConsultationCounts;
import com.example.ssafesta.consultation.ConsultationRepository;
import com.example.ssafesta.survey.SurveyResponseRepository;
import com.example.ssafesta.wallet.CoinLedgerEntryRepository;
import com.example.ssafesta.wallet.CoinReason;
import java.math.BigDecimal;
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
 * <p><b>부스는 코인을 벌지 않는다.</b> 원장 사유를 전수로 보면 부스와 닿는 것은 둘뿐이고 둘 다
 * 수익이 아니다 — 임대료는 소유자가 <i>내는</i> 돈이고, 설문 보상은 차감되는 지갑 없이
 * <i>발행</i>된다. 그래서 FR-005 의 "수익" 을 없는 개념으로 두지 않고 <b>쓴 코인과 뿌린 코인</b>으로
 * 바꿔 읽는다(2026-09-13 백엔드 결정, {@code docs/26}). 진짜 유입 경로가 생기면
 * ({@code S15P21A604-634} AI 상담 과금) 그때 이 둘 옆에 더한다.
 *
 * <p><b>{@code aiUsages} 만 {@code null} 이다.</b> AI 대화를 남기는 표가 저장소에 아직 없다.
 * 0 으로 채우면 FE 는 "AI 이용 0건" 카드를 영원히 띄운다 — {@code null} 은 "셀 원천이 없다" 는 뜻이고
 * {@code 0} 과 다르다.
 */
@Service
public class BoothDashboardService {

    private final BoothAccessGuard accessGuard;
    private final BoothVisitService visits;
    private final ConsultationRepository consultations;
    private final SurveyResponseRepository surveyResponses;
    private final CoinLedgerEntryRepository ledger;

    public BoothDashboardService(BoothAccessGuard accessGuard, BoothVisitService visits,
                                 ConsultationRepository consultations,
                                 SurveyResponseRepository surveyResponses,
                                 CoinLedgerEntryRepository ledger) {
        this.accessGuard = accessGuard;
        this.visits = visits;
        this.consultations = consultations;
        this.surveyResponses = surveyResponses;
        this.ledger = ledger;
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
        Object[] coin = unwrap(ledger.sumBoothCoinFlow(boothId, from, to,
                CoinReason.LEASE_PAYMENT, CoinReason.LEASE_REFERENCE_TYPE,
                CoinReason.SURVEY_REWARD, CoinReason.SURVEY_REFERENCE_TYPE));

        return new SummaryView(from, to,
                visit.visits(), visit.uniqueVisitors(), visit.averageDwellSeconds(), visit.openVisits(),
                consultation.requested(), consultation.ended(), responses,
                asLong(coin[0]), asLong(coin[1]), null);
    }

    /**
     * 네이티브 집계는 한 행을 준다. 드라이버가 감싸는 방식이 버전마다 달라 첫 원소가 다시 배열인
     * 경우가 있어 풀어 본다 ({@code BoothVisitService.rawMetrics} 와 같은 사정이다).
     */
    private static Object[] unwrap(Object[] row) {
        return row.length == 1 && row[0] instanceof Object[] nested ? nested : row;
    }

    private static long asLong(Object value) {
        if (value == null) {
            return 0L;
        }
        return value instanceof BigDecimal decimal ? decimal.longValue() : ((Number) value).longValue();
    }

    /**
     * @param leaseCostCoin 이 부스가 <b>쓴</b> 코인 — 임대료. 소유자 지갑에서 나간 값이다
     * @param surveyRewardCoin 이 부스의 설문이 <b>뿌린</b> 코인. 부스가 내는 것이 아니라 발행되지만,
     *        응답 수 옆에 놓으면 보상 구조가 먹히는지가 바로 읽힌다
     * @param aiUsages <b>{@code null} 은 0 이 아니다</b> — AI 대화를 남기는 표가 아직 없다.
     *        표가 생기면 채운다 (AI 파트 소관, {@code S15P21A604-139} 계열)
     */
    public record SummaryView(Instant from, Instant to,
                              long visits, long uniqueVisitors, long averageDwellSeconds, long openVisits,
                              long consultations, long consultationsEnded, long surveyResponses,
                              long leaseCostCoin, long surveyRewardCoin,
                              Long aiUsages) { }
}
