package com.example.ssafesta.booth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/**
 * The booth's lease has ended (spec 004 FR-019, spec 005 FR-015).
 *
 * <p>Expiry is not pushed to the running world, so a visitor can still see the booth and try to
 * enter it. Saying so explicitly is the point — an empty screen with no explanation would leave
 * the user unable to tell a bug from an expiry.
 *
 * <p>{@code BOOTH_LEASE_EXPIRED} is deliberately the same code the AI conversation contract will
 * use (spec 008): to a user, "부스에 못 들어감" and "AI가 답을 거부함" are one event.
 */
public class BoothExpiredException extends ApiException {

    public BoothExpiredException(Long boothId) {
        super(ErrorCode.BOOTH_LEASE_EXPIRED, "임대가 만료된 부스입니다 — boothId=" + boothId);
    }
}
