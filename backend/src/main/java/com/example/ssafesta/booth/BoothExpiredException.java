package com.example.ssafesta.booth;

/**
 * The booth's lease has ended (spec 004 FR-019: {@code BOOTH_LEASE_EXPIRED}).
 *
 * <p>Expiry is not pushed to the running world, so a visitor can still see the booth and try to
 * enter it. Saying so explicitly is the point — an empty screen with no explanation would leave
 * the user unable to tell a bug from an expiry.
 */
public class BoothExpiredException extends RuntimeException {

    public BoothExpiredException(Long boothId) {
        super("임대가 만료된 부스입니다 — boothId=" + boothId);
    }
}
