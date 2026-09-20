package com.example.ssafesta.storage;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/**
 * Storage could not answer (spec 007 C-10).
 *
 * <p>Separate from {@code STORAGE_QUOTA_EXCEEDED} because the two lead to opposite client
 * behaviour: this one is worth retrying, a full bucket is not. Collapsing both into 503 would have
 * clients retry a request that can never succeed.
 */
public class StorageUnavailableException extends ApiException {

    public StorageUnavailableException(String message) {
        super(ErrorCode.STORAGE_UNAVAILABLE, message);
    }
}
