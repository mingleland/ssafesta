package com.example.ssafesta.booth;

import com.example.ssafesta.user.AdminActionRecorder;
import com.example.ssafesta.user.AdminGuard;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two questions every booth path has to ask first: <b>may this member edit it</b> (spec 005
 * FR-012) and <b>is it still leased</b> (spec 004 만료 계약).
 *
 * <p>The editor check was here from the start. Spreading those two lines across three services is
 * how one of them eventually forgets the staff branch, or worse, forgets the owner check entirely.
 * Since spec 011 the same check also reads the staff <b>role</b>, which is the other reason it has
 * to stay in one place: a role gate copied nine times is a role gate that is wrong in one of them.
 *
 * <p>Expiry joined for the same reason, but after it had already happened: the lease check had
 * been copied into eight services, three of them behind a private {@code requireValidLease}, and a
 * new path had nothing to inherit it from. The predicate itself still belongs to spec 004's
 * repository — this class only decides <b>who has to pass it</b>.
 *
 * <p><b>Access, not editing.</b> The name says so because three callers are visitor reads with no
 * editor in sight ({@code BoothQueryService.findPublicBooth}, {@code
 * BoothLayoutQueryService.findPublished}, {@code ProjectService.requireVisitorVisible}) — expiry
 * blocks the visitor too, and a guard named for editors would have read as the wrong check there.
 */
@Component
public class BoothAccessGuard {

    private final BoothRepository booths;
    private final BoothStaffRepository staffs;
    private final BoothLeaseRepository leases;
    private final AdminGuard admins;
    private final AdminActionRecorder adminActions;

    public BoothAccessGuard(BoothRepository booths, BoothStaffRepository staffs,
                            BoothLeaseRepository leases, AdminGuard admins,
                            AdminActionRecorder adminActions) {
        this.booths = booths;
        this.staffs = staffs;
        this.leases = leases;
        this.admins = admins;
        this.adminActions = adminActions;
    }

    /**
     * Owner, or a staff member whose <b>role</b> may edit (spec 011 FR-002, C-09).
     *
     * <p>This used to be "a row exists". That was safe only while nothing created rows — spec 005
     * read the table and 011 had not been built. Opening invitations without this line would hand a
     * {@code CONSULTANT} every path behind this method, which is not only booth studio: AI agents,
     * AI documents, surveys, survey results and projects all gate here.
     *
     * @return the booth, so callers do not load it a second time
     * @throws BoothNotFoundException        no such booth
     * @throws BoothEditorForbiddenException not the owner, or a staff member whose role cannot edit
     */
    @Transactional(readOnly = true)
    public Booth requireEditor(Long boothId, Long userId) {
        Booth booth = booths.findById(boothId).orElseThrow(() -> new BoothNotFoundException(boothId));
        // 전역 관리자는 부스의 읽기·운영 정보를 본다. 마스터 보호는 "조회"가 아니라
        // 관리자로서 대상을 바꾸는 행위를 막는 규칙이라 변경 전용 게이트에 둔다.
        if (!isOrdinaryEditor(booth, userId) && !admins.isAdmin(userId)) {
            throw new BoothEditorForbiddenException();
        }
        return booth;
    }

    /**
     * An editor who is about to change booth state.
     *
     * <p>Owner and booth staff keep their original authority. Only the global-admin fallback is
     * an administrative action: it cannot target the master's booth and it leaves an audit row in
     * the caller's transaction. A failed validation rolls both the attempted change and this row
     * back together.
     */
    @Transactional
    public Booth requireModifier(Long boothId, Long userId) {
        Booth booth = booths.findById(boothId).orElseThrow(() -> new BoothNotFoundException(boothId));
        if (isOrdinaryEditor(booth, userId)) {
            return booth;
        }

        if (!admins.isAdmin(userId)) {
            throw new BoothEditorForbiddenException();
        }
        admins.requireOwnerNotMaster(booth.getOwnerUserId());
        adminActions.record(userId, AdminActionRecorder.BOOTH_EDIT,
                AdminActionRecorder.TARGET_BOOTH, boothId, null);
        return booth;
    }

    /**
     * Owner or editing staff — the authority an ordinary booth carries.
     *
     * <p><b>An administrator booth carries none of it</b> (S15P21A604-905). It follows the admin
     * role rather than the person who set it up, so {@code owner_user_id} grants nothing here and
     * both callers fall through to their {@code isAdmin} branch: a demoted administrator loses the
     * booth on their next request, and every current administrator has it. Staff is refused for the
     * same reason — an invitation accepted while its inviter was an administrator must not outlive
     * that role either.
     */
    private boolean isOrdinaryEditor(Booth booth, Long userId) {
        if (booth.isAdminOwned()) {
            return false;
        }
        return booth.isOwnedBy(userId) || mayEditAsStaff(booth.getId(), userId);
    }

    /** Absent row, unknown role and non-editing role all answer the same way: no. */
    private boolean mayEditAsStaff(Long boothId, Long userId) {
        return staffs.findRole(boothId, userId)
                .flatMap(StaffRole::from)
                .filter(StaffRole::mayEditBoothContent)
                .isPresent();
    }

    /**
     * The booth is leased <b>right now</b> — no permission question asked (spec 004 만료 계약).
     *
     * <p>Blocks writes and visitor reads alike. An expired booth shows nothing to anyone, so editing
     * one would be changing something invisible, and a visitor is owed the reason rather than a
     * booth that quietly renders as if nothing ended (FR-015, FR-019, invariant I-5).
     *
     * <p>The one deliberate exception is the <b>editor's</b> read: an owner whose lease has expired
     * must still be able to open their own content, which is what FR-011's "보존" means in practice.
     * Those paths call {@link #requireEditor} alone — see {@code BoothLayoutQueryService.findDraft}.
     *
     * @return the valid lease, so callers that need its slot or end date do not query twice
     * @throws BoothExpiredException the booth has no lease valid at this instant
     */
    @Transactional(readOnly = true)
    public BoothLease requireActiveLease(Long boothId) {
        return leases.findValidByBoothId(boothId, Instant.now())
                .orElseThrow(() -> new BoothExpiredException(boothId));
    }

    /**
     * What a visitor may open: the booth exists, its lease is live, and a layout is published.
     *
     * <p>These three gates were already written out three times — {@code ProjectService
     * .requireVisitorVisible}, {@code BoothQueryService.findPublicBooth}, {@code
     * BoothLayoutQueryService.findPublished}. A fourth copy is the one that forgets the lease line,
     * and the way that failure looks is every happy path staying green while one expiry test goes
     * red. It lives here now so the line exists once.
     *
     * <p><b>Order is contractual.</b> Missing booth, then expired lease, then unpublished — the same
     * order the three copies use, so the frontend branches on one set of codes. It is also the order
     * that does not tell someone with no business here whether this booth is still alive.
     *
     * <p>The existing three are left alone: they are working code under test, and rewriting them
     * would put regression risk in a ticket that is adding a survey.
     *
     * @return the booth, so callers do not load it a second time
     * @throws BoothNotFoundException       no such booth
     * @throws BoothExpiredException        the lease ran out
     * @throws LayoutNotPublishedException  nothing is published yet
     */
    @Transactional(readOnly = true)
    public Booth requireVisitorVisible(Long boothId) {
        Booth booth = booths.findById(boothId).orElseThrow(() -> new BoothNotFoundException(boothId));
        requireActiveLease(boothId);
        if (!booth.isPublished()) {
            throw new LayoutNotPublishedException();
        }
        return booth;
    }

    /**
     * Editor <b>and</b> unexpired — the pair nine call sites were writing on two adjacent lines.
     *
     * <p>Permission first, availability second: a member with no claim on this booth should be told
     * they cannot edit it, not that its lease ran out. The order is what the callers had, and it is
     * the one that does not leak whether a booth someone has no business with is still alive.
     *
     * @return the booth, so callers do not load it a second time
     */
    @Transactional
    public Booth requireActiveEditor(Long boothId, Long userId) {
        Booth booth = requireModifier(boothId, userId);
        requireActiveLease(boothId);
        return booth;
    }
}
