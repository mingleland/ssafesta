package com.example.ssafesta.booth;

import com.example.ssafesta.common.ApiErrorDetail;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import java.util.List;

/**
 * Somebody else saved this draft first (spec 005 FR-014, invariant I-6).
 *
 * <p>Reports the revision the server actually holds, so the editor can tell "I am one behind" from
 * "I was looking at something ancient" and reload with the right expectation (research R-03).
 */
public class LayoutRevisionConflictException extends ApiException {

    private final transient long currentRevision;

    public LayoutRevisionConflictException(long currentRevision) {
        super(ErrorCode.LAYOUT_REVISION_CONFLICT,
                ErrorCode.LAYOUT_REVISION_CONFLICT.defaultMessage(),
                List.of(ApiErrorDetail.of("CURRENT_REVISION",
                        "서버의 현재 revision은 " + currentRevision + "입니다. 다시 불러온 뒤 저장하세요.")),
                null);
        this.currentRevision = currentRevision;
    }

    public long currentRevision() {
        return currentRevision;
    }
}
