package com.example.ssafesta.game;

import com.example.ssafesta.common.ApiErrorDetail;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import java.util.List;

/**
 * Somebody else saved this draft first, or the client's expectation was never valid (FR-025).
 *
 * <p>Reports the revision the server actually holds so the editor can tell "I am one behind" from
 * "I was looking at something ancient" and reload with the right expectation.
 *
 * <p><b>The detail message is a decimal string, not a sentence.</b> That is a contract value, not a
 * style choice: {@code contracts/game-api.md} fixes {@code errors[].message} for
 * {@code CURRENT_REVISION} as a plain number, and the editor parses it with {@code /^\d+$/} to open
 * its conflict-recovery screen. A sentence here would fail that check silently — no exception, no
 * failing test, just a generic error where the recovery UI should have been.
 *
 * <p>Spec 005's {@code LayoutRevisionConflictException} keeps a sentence in the same slot because
 * nothing reads its value. The two are deliberately different and the contract says so.
 */
public class GameRevisionConflictException extends ApiException {

    private final transient int currentRevision;

    public GameRevisionConflictException(int currentRevision) {
        super(ErrorCode.GAME_REVISION_CONFLICT,
                ErrorCode.GAME_REVISION_CONFLICT.defaultMessage(),
                List.of(ApiErrorDetail.of("CURRENT_REVISION", Integer.toString(currentRevision))),
                null);
        this.currentRevision = currentRevision;
    }

    public int currentRevision() {
        return currentRevision;
    }
}
