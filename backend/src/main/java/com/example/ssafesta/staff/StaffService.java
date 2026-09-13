package com.example.ssafesta.staff;

import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothStaff;
import com.example.ssafesta.booth.BoothStaffRepository;
import com.example.ssafesta.booth.StaffRole;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 부스 직원 목록과 역할 관리 (spec 011 US3, FR-002·FR-018).
 *
 * <p><b>Owner 는 {@code booth_staffs} 행이 아니다.</b> 목록 응답에서만 역할 {@code OWNER} 의 읽기
 * 전용 행으로 합성한다(FR-018). 행으로 만들면 역할 변경·제거 대상이 되어 자기 부스에서 자신을
 * 지울 수 있게 되고, 그때 그 부스는 주인이 없어진다.
 */
@Service
public class StaffService {

    private final BoothStaffRepository staffs;
    private final UserRepository users;
    private final StaffAccessGuard staffGuard;

    public StaffService(BoothStaffRepository staffs, UserRepository users, StaffAccessGuard staffGuard) {
        this.staffs = staffs;
        this.users = users;
        this.staffGuard = staffGuard;
    }

    /**
     * 부스 구성원이면 누구나 본다 — 상담원 포함.
     *
     * <p>편집 게이트를 쓰지 않는 이유가 여기 있다. {@code CONSULTANT} 는 콘텐츠를 못 고치지만
     * 같은 부스에 누가 있는지는 알아야 한다.
     */
    @Transactional(readOnly = true)
    public List<StaffView> listStaff(Long boothId, Long viewerUserId) {
        Booth booth = staffGuard.requireBoothMember(boothId, viewerUserId);
        List<BoothStaff> rows = staffs.findByBoothIdOrderByJoinedAt(boothId);

        List<Long> userIds = new ArrayList<>(rows.stream().map(BoothStaff::getUserId).toList());
        userIds.add(booth.getOwnerUserId());
        Map<Long, String> nicknames = users.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getNickname));

        List<StaffView> views = new ArrayList<>();
        views.add(StaffView.owner(booth.getOwnerUserId(), nicknames.get(booth.getOwnerUserId())));
        rows.stream().map(row -> StaffView.of(row, nicknames.get(row.getUserId()))).forEach(views::add);
        return views;
    }

    /** Owner·{@code ADMIN} 만. Owner 행은 바꿀 수 없다 (FR-018). */
    @Transactional
    public StaffView changeRole(Long boothId, Long targetUserId, Long actorUserId, String role) {
        staffGuard.requireStaffManager(boothId, actorUserId);
        BoothStaff staff = requireStaffRow(boothId, targetUserId);
        StaffRole parsed = StaffRole.from(role)
                .orElseThrow(() -> ApiException.fieldInvalid("role",
                        "역할은 ADMIN, CONTENT_EDITOR, CONSULTANT 중 하나입니다."));

        staff.changeRole(parsed.name());
        return StaffView.of(staff, nicknameOf(targetUserId));
    }

    /** Owner·{@code ADMIN} 만. 자리를 비우는 것이라 초대와 반대 방향이다. */
    @Transactional
    public void remove(Long boothId, Long targetUserId, Long actorUserId) {
        staffGuard.requireStaffManager(boothId, actorUserId);
        staffs.delete(requireStaffRow(boothId, targetUserId));
    }

    /**
     * 직원 행을 찾되, <b>Owner 를 가리킨 요청은 따로 답한다</b>.
     *
     * <p>Owner 는 행이 없으므로 그냥 찾으면 {@code STAFF_NOT_FOUND} 가 되는데, 그 답은 "그 사람은
     * 이 부스와 무관하다" 로 읽힌다. 목록에는 분명히 보이던 사람이라 혼란스럽다.
     */
    private BoothStaff requireStaffRow(Long boothId, Long targetUserId) {
        return staffs.findByBoothIdAndUserId(boothId, targetUserId)
                .orElseThrow(() -> {
                    Booth booth = staffGuard.requireBooth(boothId);
                    return booth.isOwnedBy(targetUserId)
                            ? new ApiException(ErrorCode.STAFF_OWNER_IMMUTABLE)
                            : new ApiException(ErrorCode.STAFF_NOT_FOUND);
                });
    }

    private String nicknameOf(Long userId) {
        return users.findById(userId).map(User::getNickname).orElse(null);
    }

    public record ChangeRoleCommand(String role) { }

    /**
     * @param readOnly Owner 행 표시. 클라이언트가 역할 문자열을 비교해 분기하지 않도록 값으로 둔다
     * @param consultationStatus Owner 행에서는 {@code null} — Owner 는 상담 상태를 갖지 않는다
     */
    public record StaffView(Long userId, String nickname, String role, String consultationStatus,
                            boolean readOnly) {

        static StaffView owner(Long userId, String nickname) {
            return new StaffView(userId, nickname, "OWNER", null, true);
        }

        static StaffView of(BoothStaff staff, String nickname) {
            return new StaffView(staff.getUserId(), nickname, staff.getRole(),
                    staff.getConsultationStatus(), false);
        }
    }
}
