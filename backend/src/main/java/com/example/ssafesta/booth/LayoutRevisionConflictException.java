package com.example.ssafesta.booth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/**
 * Somebody else saved this draft first (spec 005 FR-014, invariant I-6).
 *
 * <p>Holds the revision the server actually has so the response can carry the current draft with
 * it — the editor then reloads or merges without a second round trip (research R-03).
 */
public class LayoutRevisionConflictException extends ApiException {

    private final transient long currentRevision;

    public LayoutRevisionConflictException(long currentRevision) {
        super(ErrorCode.LAYOUT_REVISION_CONFLICT);
        this.currentRevision = currentRevision;
    }

    public long currentRevision() {
        return currentRevision;
    }
}
