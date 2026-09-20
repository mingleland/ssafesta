package com.example.ssafesta.booth;

import com.example.ssafesta.ai.AiAgent;
import com.example.ssafesta.ai.AiAgentRepository;
import com.example.ssafesta.project.Project;
import com.example.ssafesta.project.ProjectRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of booths and slots (spec 004 FR-001, FR-007).
 *
 * <p>Occupancy is always decided by the same predicate — {@code status = ACTIVE and endsAt > now} —
 * so a lease whose time has passed reads as free before anyone transitions it. That is what keeps
 * an expired booth out of the world without a scheduler (SC-003, research R-03).
 *
 * <p><b>The predicate is written twice.</b> {@link BoothLeaseRepository}'s validity queries serve
 * the single-booth reads, and {@link BoothSlotRepository#findOccupancy} repeats it inside a join
 * because the slot list has to read the booth in the <i>same statement</i> (S15P21A604-682) —
 * JPQL gives no way to share the fragment. The duplication is deliberate and load-bearing, so it is
 * pinned by a test that asserts both queries select the same leases from one fixture
 * ({@code BoothSlotOccupancySingleStatementTest}); change one and that test fails.
 */
@Service
public class BoothQueryService {

    private static final Logger log = LoggerFactory.getLogger(BoothQueryService.class);

    private final BoothSlotRepository slots;
    private final BoothRepository booths;
    private final BoothLeaseRepository leases;
    private final BoothAccessGuard accessGuard;
    private final ProjectRepository projects;
    private final AiAgentRepository agents;

    public BoothQueryService(BoothSlotRepository slots, BoothRepository booths,
                             BoothLeaseRepository leases, BoothAccessGuard accessGuard,
                             ProjectRepository projects, AiAgentRepository agents) {
        this.slots = slots;
        this.booths = booths;
        this.leases = leases;
        this.accessGuard = accessGuard;
        this.projects = projects;
        this.agents = agents;
    }

    /**
     * All slots with their current occupancy.
     *
     * @param viewerUserId the requester, or {@code null} for an unauthenticated view
     */
    @Transactional(readOnly = true)
    public List<SlotView> listSlots(Long viewerUserId) {
        Instant now = Instant.now();

        return slots.findOccupancy(now).stream().map(row -> {
            BoothLease lease = row.lease();
            if (lease == null) {
                return SlotView.available(row.slot());
            }
            if (row.booth() == null) {
                // 한 문장 안에서는 성립할 수 없다 — booth_leases.booth_id 가 NOT NULL REFERENCES
                // booths(id) 다. 그래도 조용히 넘기지 않는다: 없는 부스를 OCCUPIED·입장가능으로
                // 보여 주면 방문자는 들어갈 수 없는 간판을 보고, 원인은 로그에 남지 않는다 (T-24).
                log.error("임대가 없는 부스를 가리킨다 — slotId={}, leaseId={}, boothId={}",
                        row.slot().getId(), lease.getId(), lease.getBoothId());
                return SlotView.available(row.slot());
            }
            // 부스를 통째로 들고 간다. 이름만 뽑고 버리던 자리인데, 같은 행에 facade 4필드가
            // 있어 그것을 함께 실으면 Unity 가 간판 12개를 요청 하나로 그린다 (GitLab #171).
            boolean mine = viewerUserId != null && viewerUserId.equals(lease.getLesseeUserId());
            return SlotView.occupied(row.slot(), lease, row.booth(), mine, now);
        }).toList();
    }

    /**
     * The member's own booth and its lease, or empty when they have never leased (FR-007).
     *
     * <p><b>An administrator's booth answers here too</b> (S15P21A604-905 의 핫픽스). That ticket left it out
     * on the assumption that the console would find it through {@code GET /booth-slots}'s {@code
     * mine}, but nothing reads that field: 부스 관리 and the studio owner gate both ask this endpoint
     * alone, so an administrator who had just leased was told they have no booth — they could not
     * open, edit or publish the booth they were standing in.
     *
     * <p>The response shape is unchanged: one booth. An administrator holding several slots gets the
     * most recent one, which is the one they just leased.
     */
    // ponytail: 다중 슬롯 관리자는 마지막 부스만 보인다 — 목록을 주는 응답이 필요해지면 그때 계약을 늘린다.
    @Transactional(readOnly = true)
    public Optional<MyBoothView> findMyBooth(Long userId) {
        Instant now = Instant.now();
        // 관리자 부스가 먼저다: 그 부스는 임대를 들고 있는 동안에만 존재하고(반납하면 삭제된다),
        // 회원 시절의 부스는 임대 없이도 남아 있어서 그것을 먼저 주면 지금 운영 중인 부스가 가려진다.
        return booths.findFirstByOwnerUserIdAndAdminOwnedTrueOrderByIdDesc(userId)
                .or(() -> booths.findByOwnerUserIdAndAdminOwnedFalse(userId))
                .map(booth -> {
                    BoothLease lease = leases.findValidByBoothId(booth.getId(), now).orElse(null);
                    BoothSlot slot = lease == null ? null : slots.findById(lease.getSlotId()).orElse(null);
                    return MyBoothView.of(booth, lease, slot, now);
                });
    }

    /**
     * A visitor's view of a booth.
     *
     * @throws BoothExpiredException when the lease has ended — expiry is not pushed to the world,
     *         so this refusal is how the visitor learns about it (FR-019)
     */
    @Transactional(readOnly = true)
    public PublicBoothView findPublicBooth(Long boothId) {
        Booth booth = booths.findById(boothId).orElseThrow(() -> new BoothNotFoundException(boothId));
        BoothLease lease = accessGuard.requireActiveLease(boothId);
        return new PublicBoothView(booth.getId(), lease.getSlotId(), booth.getName(),
                lease.getStatus().name(), true, lease.getEndsAt(),
                BoothFacadeService.FacadeView.of(booth), booth.getPublishedLayoutVersion(),
                visibleHomepageUrl(booth), handoffEnabled(booth.getId()));
    }

    /**
     * Whether this booth takes a human handoff, read by the visitor's chat overlay to hide the
     * '사람 상담 요청' button before it can be pressed (GitLab #249, S15P21A604-914).
     *
     * <p>No agent means no one to hand off to, so the absent row reads {@code false} rather than
     * an error — the button is simply not offered. There is no {@code published} gate here, unlike
     * {@link #visibleHomepageUrl}: a visitor cannot reach an unpublished booth at all, and this is
     * a boolean about the booth rather than an address pointing out of it. {@code AiAgent.status}
     * is not consulted either, so this answer is the same value the owner's
     * {@code GET /booths/{id}/agents} reports — two screens, one number.
     */
    private boolean handoffEnabled(Long boothId) {
        return agents.findByBoothId(boothId).map(AiAgent::isHandoffEnabled).orElse(false);
    }

    /**
     * "Only while the booth is public" (spec 016 FR-003), read as <b>only while a published layout
     * exists</b>.
     *
     * <p>The laptop is an object inside the published layout, so that predicate lines up exactly
     * with the moment a visitor could click it — there is no window where the URL is readable but
     * unreachable. A booth with neither a registered URL nor a project service address
     * ({@link #projectServiceUrl}) is {@code null} too, which lets the overlay decide "nothing to
     * open" from one value instead of three (FR-009, research R-05).
     *
     * <p>Expiry needs no branch here: {@link #findPublicBooth} has already refused with
     * {@code BOOTH_LEASE_EXPIRED} by this point.
     */
    private String visibleHomepageUrl(Booth booth) {
        if (!booth.isPublished()) {
            return null;
        }
        if (booth.getHomepageUrl() != null) {
            return booth.getHomepageUrl();
        }
        return projectServiceUrl(booth);
    }

    /**
     * The laptop falls back to the project's <b>service address</b> (2026-09-14 결정).
     *
     * <p>{@code booths.homepage_url} has an endpoint but no screen — nothing in the frontend calls
     * {@code PUT /booths/{id}/homepage}, so in practice the column is always empty and the laptop
     * was unreachable for every booth that ever existed. The one place an owner actually types an
     * address for their own service is 프로젝트 관리's "서비스 주소" ({@code projects.deploy_url}),
     * and that is the address they expect the laptop to open.
     *
     * <p><b>Registered beats derived.</b> A value someone put in {@code homepage_url} is an explicit
     * choice, so it wins; this only fills the hole underneath it. Which means the fallback
     * disappears on its own the day the registration screen ships — nothing to undo.
     *
     * <p><b>Visitor path only.</b> {@link MyBoothView} still reports the stored column verbatim: it
     * is the owner's prefill, and prefilling a form with a value that was never registered would
     * make them save a copy of it under a different name.
     */
    private String projectServiceUrl(Booth booth) {
        return projects.findByBoothId(booth.getId()).map(Project::getDeployUrl).orElse(null);
    }

    /**
     * @param facade the occupying booth's exterior, or {@code null} on a free slot
     *        (S15P21A604-622, GitLab #171). Unity draws twelve signs on entering the festival;
     *        without this it needed a second call per booth — thirteen requests where one will do.
     *        The booth row is already loaded to get {@code boothName}, so carrying it costs no
     *        query. Same values as {@code GET /booths/{boothId}}'s {@code facade}.
     */
    public record SlotView(Long slotId, String slotCode, short floorNo, String type, String status,
                           Long boothId, String boothName, Instant leaseEndsAt, Long remainingSeconds,
                           boolean entryAvailable, boolean mine,
                           BoothFacadeService.FacadeView facade) {

        static SlotView available(BoothSlot slot) {
            return new SlotView(slot.getId(), slot.getSlotCode(), slot.getFloorNo(), slot.getSlotType().name(),
                    "AVAILABLE", null, null, null, null, false, false, null);
        }

        /**
         * @param booth the occupying booth — <b>never {@code null}</b>. The lease and the booth now
         *        come from one statement, so "lease without booth" is not a shape this can be
         *        called with; the caller drops such a row before it gets here (S15P21A604-682)
         */
        static SlotView occupied(BoothSlot slot, BoothLease lease, Booth booth, boolean mine, Instant now) {
            return new SlotView(slot.getId(), slot.getSlotCode(), slot.getFloorNo(), slot.getSlotType().name(),
                    "OCCUPIED", lease.getBoothId(), booth.getName(),
                    lease.getEndsAt(), lease.remainingSecondsAt(now), true, mine,
                    BoothFacadeService.FacadeView.of(booth));
        }
    }

    /**
     * @param homepageUrl the stored value, <b>never gated</b> — unlike {@link PublicBoothView}. The
     *        owner of an unpublished booth still has to see their own URL to edit it, and this
     *        surface is already theirs alone (spec 016 research R-06)
     */
    public record MyBoothView(Long boothId, String name, String status, LeaseView lease,
                              String homepageUrl) {

        static MyBoothView of(Booth booth, BoothLease lease, BoothSlot slot, Instant now) {
            // The booth stays even when the lease is over — its content is preserved (FR-010).
            return new MyBoothView(booth.getId(), booth.getName(),
                    lease == null ? BoothStatus.INACTIVE.name() : BoothStatus.ACTIVE.name(),
                    lease == null ? null : LeaseView.of(lease, slot, now),
                    booth.getHomepageUrl());
        }
    }

    public record LeaseView(Long leaseId, Long slotId, String slotCode, Instant startsAt, Instant endsAt,
                            long remainingSeconds, int chargedCoin) {

        static LeaseView of(BoothLease lease, BoothSlot slot, Instant now) {
            return new LeaseView(lease.getId(), lease.getSlotId(), slot == null ? null : slot.getSlotCode(),
                    lease.getStartsAt(), lease.getEndsAt(), lease.remainingSecondsAt(now), lease.getChargedCoin());
        }
    }

    /**
     * What docs/08 §3 promised all along. {@code facade} and {@code publishedLayoutVersion} were in
     * the documented contract before spec 005; they are only now backed by columns (V8·V9).
     */
    public record PublicBoothView(Long boothId, Long slotId, String name, String leaseStatus,
                                  boolean entryAvailable, Instant endsAt,
                                  BoothFacadeService.FacadeView facade, Integer publishedLayoutVersion,
                                  String homepageUrl, boolean handoffEnabled) {
    }
}
