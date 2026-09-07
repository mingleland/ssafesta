package com.example.ssafesta.game;

/**
 * Upload lifecycle (contract §4).
 *
 * <pre>
 * UPLOADING ──complete 성공──> READY
 *     │
 *     └──검증 실패·만료──> FAILED
 * </pre>
 *
 * <p><b>Only {@link #READY} may be referenced from a Draft or a Publish.</b> Letting the other two
 * through turns a save-time error into a broken image at play time, which is harder to diagnose.
 *
 * <p>{@link #FAILED} is terminal — a retry starts over with a new {@code assetId} (§3.2). Reviving
 * it would mean the verified bytes and the stored bytes could differ.
 */
public enum GameAssetStatus {
    UPLOADING,
    READY,
    FAILED
}
