package com.example.ssafesta.ai;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/**
 * The storage bucket is full (spec 007 C-10).
 *
 * <p>507 rather than 503 because retrying cannot help — an operator has to add capacity. The two are
 * split in the contract for exactly this reason.
 */
class StorageQuotaExceededException extends ApiException {

    StorageQuotaExceededException() {
        super(ErrorCode.STORAGE_QUOTA_EXCEEDED);
    }
}
