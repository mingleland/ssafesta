package com.example.ssafesta.booth;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers "may this member edit this booth?" in one place (spec 005 FR-012).
 *
 * <p>Every editing path goes through here — draft save, publish, facade. Spreading the same
 * two-line check across three services is how one of them eventually forgets the staff branch, or
 * worse, forgets the owner check entirely.
 */
@Component
public class BoothEditorGuard {

    private final BoothRepository booths;
    private final BoothStaffRepository staffs;

    public BoothEditorGuard(BoothRepository booths, BoothStaffRepository staffs) {
        this.booths = booths;
        this.staffs = staffs;
    }

    /**
     * @return the booth, so callers do not load it a second time
     * @throws BoothNotFoundException        no such booth
     * @throws BoothEditorForbiddenException the member is neither owner nor staff
     */
    @Transactional(readOnly = true)
    public Booth requireEditor(Long boothId, Long userId) {
        Booth booth = booths.findById(boothId).orElseThrow(() -> new BoothNotFoundException(boothId));
        if (!booth.isOwnedBy(userId) && !staffs.existsByBoothIdAndUserId(boothId, userId)) {
            throw new BoothEditorForbiddenException();
        }
        return booth;
    }
}
