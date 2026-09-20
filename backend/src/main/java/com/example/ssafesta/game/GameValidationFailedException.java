package com.example.ssafesta.game;

import com.example.ssafesta.common.ApiErrorDetail;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import java.util.List;

/**
 * The GameProject was refused (contracts §Draft 저장 · §Publish).
 *
 * <p>Carries the whole {@code errors[]} list rather than the first failure. The editor shows every
 * broken rule with its {@code instanceLocation} at once — one problem per attempt would make fixing
 * a 50-scene project a guessing game. Warnings ride along for the same reason.
 *
 * <p>Rule names come from {@code contracts/fixtures/}, not from this class. The reference validator
 * already fixed them and quickstart requires reproducing the same manifest, so inventing a name
 * here would break the fixture tests rather than the build.
 */
public class GameValidationFailedException extends ApiException {

    public GameValidationFailedException(String message,
                                        List<ApiErrorDetail> errors,
                                        List<ApiErrorDetail> warnings) {
        super(ErrorCode.GAME_VALIDATION_FAILED, message, errors, warnings);
    }

    /** A single named rule — the common case for one broken invariant. */
    public static GameValidationFailedException of(String message, String rule, String detail) {
        return new GameValidationFailedException(message, List.of(ApiErrorDetail.of(rule, detail)), null);
    }
}
