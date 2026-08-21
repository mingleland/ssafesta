package com.example.ssafesta.booth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/**
 * The booth has never published a layout, or its published layout was released on re-lease
 * (spec 005 FR-011/FR-017).
 *
 * <p>404 rather than an empty layout: Unity already treats 404 on this path as "nothing to build"
 * and skips gracefully, whereas an empty {@code objects} array would have it clear a booth that
 * may simply not be ready yet.
 */
public class LayoutNotPublishedException extends ApiException {

    public LayoutNotPublishedException() {
        super(ErrorCode.LAYOUT_NOT_PUBLISHED);
    }
}
