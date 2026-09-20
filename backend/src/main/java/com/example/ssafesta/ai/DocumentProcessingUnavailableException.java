package com.example.ssafesta.ai;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/**
 * FastAPI could not be handed a Job (S15P21A604-175).
 *
 * <p>Pinned to {@code STORAGE_UNAVAILABLE} rather than a new code. The contract table is the
 * 정본 and has no code for this, because <b>no user request ever fails on it</b>: the Job is
 * committed before the call goes out, so a failed delegation is retried by the dispatch sweeper and
 * the person who uploaded the document sees a normal response either way. The status only matters
 * if this ever reaches a controller, and 503 "retryable" is the truthful answer there.
 */
public class DocumentProcessingUnavailableException extends ApiException {

    public DocumentProcessingUnavailableException(String message) {
        super(ErrorCode.STORAGE_UNAVAILABLE, message);
    }
}
