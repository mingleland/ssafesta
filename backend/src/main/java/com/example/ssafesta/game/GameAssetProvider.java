package com.example.ssafesta.game;

/**
 * Where the bytes live.
 *
 * <p>{@link #DB} is what this feature uses: the only consumer of these images is the React web
 * runtime, and {@code /content} has to be served by Spring anyway because the FE fetches it with an
 * Authorization header rather than through {@code <img src>}. With no redirect to hand out, an
 * object store adds presigned URLs, CORS and immutable-key promotion without buying anything.
 *
 * <p>The other two exist so that moving to object storage later is additive — new rows are written
 * with the new provider and old rows are backfilled, with no schema change (V17 comment).
 */
public enum GameAssetProvider {
    DB,
    R2,
    MINIO_LOCAL
}
