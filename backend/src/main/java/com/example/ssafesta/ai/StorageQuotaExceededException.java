package com.example.ssafesta.ai;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/**
 * New upload grants are refused because the storage quota is spent (spec 007 C-10, #100).
 *
 * <p>507 rather than 503 because retrying cannot help — an operator has to add capacity or wait for
 * the next period. The usage guard blocks at 90% and the two block reasons stay apart for exactly
 * this reason: "try again shortly" and "not this month" are different instructions.
 */
class StorageQuotaExceededException extends ApiException {

    StorageQuotaExceededException() {
        super(ErrorCode.STORAGE_QUOTA_EXCEEDED);
    }
}
