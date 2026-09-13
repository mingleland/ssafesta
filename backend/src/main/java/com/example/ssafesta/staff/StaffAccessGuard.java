package com.example.ssafesta.staff;

import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothNotFoundException;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.booth.BoothStaffRepository;
import com.example.ssafesta.booth.StaffRole;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사람을 들이고 뺄 수 있는가 — {@code BoothAccessGuard} 가 답하는 "콘텐츠를 고칠 수 있는가" 와
 * 다른 물음이다 (spec 011 FR-001·FR-002).
 *
 * <p>두 물음이 갈리는 자리가 {@code CONTENT_EDITOR} 다. 배치는 고칠 수 있지만 직원을 들일 수는
 * 없다. 한 게이트로 묶으면 그 구분이 사라지므로 여기 따로 둔다.
 *
 * <p><b>booth 패키지를 키우지 않는다.</b> 편집 권한 판정은 spec 005 부터 booth 의 일이었고 그
 * 자리에 그대로 있다. 직원 관리는 011 이 들고 온 개념이라 011 의 패키지에서 묻는다.
 */
@Component
public class StaffAccessGuard {

    private final BoothRepository booths;
    private final BoothStaffRepository staffs;

    public StaffAccessGuard(BoothRepository booths, BoothStaffRepository staffs) {
        this.booths = booths;
        this.staffs = staffs;
    }

    /**
     * Owner 이거나 {@code ADMIN} 직원이다.
     *
     * @return the booth, so callers do not load it a second time
     * @throws BoothNotFoundException 그런 부스가 없다
     * @throws ApiException           {@code STAFF_MANAGER_FORBIDDEN}
     */
    @Transactional(readOnly = true)
    public Booth requireStaffManager(Long boothId, Long userId) {
        Booth booth = booths.findById(boothId).orElseThrow(() -> new BoothNotFoundException(boothId));
        if (!booth.isOwnedBy(userId) && !isAdmin(boothId, userId)) {
            throw new ApiException(ErrorCode.STAFF_MANAGER_FORBIDDEN);
        }
        return booth;
    }

    private boolean isAdmin(Long boothId, Long userId) {
        return staffs.findRole(boothId, userId)
                .flatMap(StaffRole::from)
                .filter(role -> role == StaffRole.ADMIN)
                .isPresent();
    }
}
