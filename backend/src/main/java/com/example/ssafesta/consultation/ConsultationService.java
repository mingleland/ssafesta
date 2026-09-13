package com.example.ssafesta.consultation;

import com.example.ssafesta.booth.BoothAccessGuard;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.consultation.ws.ConsultationEventPublisher;
import com.example.ssafesta.staff.StaffAccessGuard;
import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상담 요청·수락·종료 (spec 011 US1 P1,
 * {@code contracts/staff-consultation-api.md} §B).
 *
 * <p>P1 은 <b>요청·수락·종료까지</b>다. 실시간 메시지 송수신·저장은 P2 이고(C-12), 이 단계의
 * STOMP 는 알림 전용이라 정본은 언제나 REST 다.
 *
 * <p>요약은 {@link HandoffSummaryClient} 가 조달하며 지금은 항상 {@code null} 이다 — 그것이
 * 요청을 막지 않는 것이 계약이다(헌법 3조).
 */
@Service
public class ConsultationService {

    private final ConsultationRepository consultations;
    private final BoothAccessGuard boothGuard;
    private final StaffAccessGuard staffGuard;
    private final HandoffSummaryClient summaries;
    private final UserRepository users;
    private final ConsultationEventPublisher events;

    public ConsultationService(ConsultationRepository consultations, BoothAccessGuard boothGuard,
                               StaffAccessGuard staffGuard, HandoffSummaryClient summaries,
                               UserRepository users, ConsultationEventPublisher events) {
        this.consultations = consultations;
        this.boothGuard = boothGuard;
        this.staffGuard = staffGuard;
        this.summaries = summaries;
        this.users = users;
        this.events = events;
    }

    /**
     * 방문자가 사람 상담을 청한다.
     *
     * <p>게스트는 컨트롤러가 {@code MEMBER_ONLY} 로 막는다(FR-014, 헌법 12조) — 여기 도달하는
     * 것은 회원뿐이다.
     */
    @Transactional
    public RequestView request(Long visitorUserId, RequestCommand command) {
        Long boothId = requireBoothId(command);
        boothGuard.requireActiveLease(boothId);
        consultations.findPendingOf(boothId, visitorUserId).ifPresent(pending -> {
            throw new ApiException(ErrorCode.CONSULTATION_REQUEST_PENDING);
        });

        Instant now = Instant.now();
        Consultation saved = consultations.save(new Consultation(boothId, visitorUserId,
                command.agentId(), command.conversationId(),
                summaries.summarize(command.conversationId()), now));
        events.requested(boothId, saved.getId(), nicknameOf(visitorUserId), now, saved.getSummary());
        return new RequestView(String.valueOf(saved.getId()), saved.remainingSecondsAt(now));
    }

    /** 방문자가 자기 요청을 거둔다. 남의 요청은 만질 수 없다. */
    @Transactional
    public void cancel(Long requestId, Long visitorUserId) {
        Consultation consultation = require(requestId);
        if (!visitorUserId.equals(consultation.getVisitorUserId())) {
            throw new ApiException(ErrorCode.CONSULTATION_FORBIDDEN);
        }
        if (consultation.getStatus() != ConsultationStatus.REQUESTED) {
            throw new ApiException(ErrorCode.CONSULTATION_NOT_REQUESTED);
        }
        consultation.cancel(Instant.now());
        events.cancelled(consultation.getBoothId(), consultation.getId());
    }

    /** 그 부스의 대기열. 부스 구성원이면 누구나 본다 — 수락은 못 해도 상황은 알아야 한다. */
    @Transactional(readOnly = true)
    public List<QueueItemView> queue(Long boothId, Long viewerUserId) {
        staffGuard.requireBoothMember(boothId, viewerUserId);
        List<Consultation> waiting = consultations.findQueue(boothId, cutoff(Instant.now()));
        Map<Long, String> nicknames = nicknamesOf(waiting.stream()
                .map(Consultation::getVisitorUserId).toList());
        return waiting.stream()
                .map(item -> new QueueItemView(String.valueOf(item.getId()),
                        nicknames.get(item.getVisitorUserId()), item.getRequestedAt(), item.getSummary()))
                .toList();
    }

    /**
     * 직원이 요청을 가져간다 — <b>정확히 한 명만</b> 성공한다 (SC-001).
     *
     * <p>판정을 두 곳이 나눠 진다. 요청 쪽은 조건부 갱신이 가르고(영향 행 0이면 진 것), 직원 쪽은
     * {@code ux_consultations_active_staff} 가 막는다(SC-006). 둘 다 DB 가 승자를 정하므로 동시
     * 요청에서도 성립한다 — 읽고-판단하고-쓰는 경로였다면 같은 순간에 읽은 둘이 모두 통과한다.
     */
    @Transactional
    public AcceptedView accept(Long requestId, Long staffUserId) {
        Consultation consultation = require(requestId);
        staffGuard.requireBoothMember(consultation.getBoothId(), staffUserId);

        Instant now = Instant.now();
        int accepted;
        try {
            accepted = consultations.accept(requestId, staffUserId, now, cutoff(now));
        } catch (DataIntegrityViolationException violation) {
            // ux_consultations_active_staff — 이 직원에게 이미 활성 상담이 있다 (C-06).
            throw new ApiException(ErrorCode.CONSULTATION_ALREADY_ACTIVE);
        }
        if (accepted == 0) {
            throw new ApiException(ErrorCode.CONSULTATION_NOT_REQUESTED);
        }

        Consultation taken = require(requestId);
        String staffNickname = nicknameOf(staffUserId);
        // 진 직원들의 대기열에서 카드를 내린다. 이것이 없으면 그 카드가 화면에 남아 있다가
        // 누를 때 409 로 터진다.
        events.taken(taken.getBoothId(), taken.getId(), staffNickname);
        events.accepted(taken.getVisitorUserId(), taken.getId(), staffNickname);

        String id = String.valueOf(taken.getId());
        return new AcceptedView(id, id, nicknameOf(taken.getVisitorUserId()), taken.getSummary());
    }

    /** 방문자·직원 누구나 끝낸다 (FR-010). */
    @Transactional
    public void end(Long sessionId, Long actorUserId) {
        Consultation consultation = require(sessionId);
        boolean mine = actorUserId.equals(consultation.getVisitorUserId())
                || actorUserId.equals(consultation.getStaffUserId());
        if (!mine) {
            throw new ApiException(ErrorCode.CONSULTATION_FORBIDDEN);
        }
        if (consultation.getStatus() != ConsultationStatus.ACCEPTED) {
            throw new ApiException(ErrorCode.CONSULTATION_NOT_REQUESTED);
        }
        consultation.end(Instant.now());
        events.ended(consultation.getVisitorUserId(), consultation.getId());
    }

    /**
     * 스위퍼가 부르는 자리 — 트랜잭션 경계를 프록시로 타려고 서비스에 둔다.
     *
     * <p>벌크 갱신 <b>전에</b> 대상을 읽는다. 갱신은 몇 행을 옮겼는지만 알려 주므로, 누구의
     * 화면에서 카드를 내려야 하는지는 그 전에 확보해야 한다.
     */
    @Transactional
    public int expireStale() {
        Instant cutoff = cutoff(Instant.now());
        List<Consultation> overdue = consultations.findOverdue(cutoff);
        int moved = consultations.expireStaleAsOf(cutoff);
        overdue.forEach(item ->
                events.expired(item.getBoothId(), item.getVisitorUserId(), item.getId()));
        return moved;
    }

    private Consultation require(Long id) {
        return consultations.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.CONSULTATION_NOT_FOUND));
    }

    /** 이 시각보다 오래된 요청은 만료다 (C-01 — 10분). */
    private static Instant cutoff(Instant now) {
        return now.minus(Consultation.REQUEST_VALID_FOR);
    }

    private String nicknameOf(Long userId) {
        return users.findById(userId).map(User::getNickname).orElse(null);
    }

    private Map<Long, String> nicknamesOf(List<Long> userIds) {
        return users.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getNickname));
    }

    private static Long requireBoothId(RequestCommand command) {
        if (command == null || command.boothId() == null) {
            throw ApiException.fieldInvalid("boothId", "상담을 청할 부스가 필요합니다.");
        }
        return command.boothId();
    }

    /**
     * @param conversationId 보고 있던 AI 대화. <b>요약 텍스트는 받지 않는다</b> — 서버가 이 id 로
     *        FastAPI 에 요약을 청해 요청 시점 스냅샷으로 굳힌다 (FR-012)
     */
    public record RequestCommand(Long boothId, Long agentId, String conversationId) { }

    /** {@code expiresInSeconds} 로 FE 가 잔여 시간을 안내한다 (C-01). */
    public record RequestView(String requestId, long expiresInSeconds) { }

    public record QueueItemView(String requestId, String visitorNickname, Instant requestedAt,
                                String handoffSummary) { }

    /**
     * @param sessionId {@code requestId} 와 <b>같은 값</b>이다 — 한 행이 요청과 세션을 겸한다
     *        (research R-06). 둘 다 싣는 것은 FE 어댑터가 어느 쪽을 들고 있든 종료를 부를 수 있게
     *        하기 위해서다
     */
    public record AcceptedView(String requestId, String sessionId, String visitorNickname,
                               String handoffSummary) { }
}
