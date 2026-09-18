package com.example.ssafesta.wallet;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.user.AdminActionRecorder;
import com.example.ssafesta.user.AdminGuard;
import com.example.ssafesta.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administrator coin adjustment, with its audit row, in one transaction (S15P21A604-806).
 *
 * <p>{@link AdminActionRecorder#record} is {@code MANDATORY}, so the audit row cannot be written
 * from the controller — it has no transaction. This service is the transaction, the same shape
 * {@code AdminAccountService} uses for promotion.
 *
 * <p><b>The idempotency key is built here, not taken from the client whole.</b> The caller supplies
 * only an {@code operationId}; the server scopes it to the target so one administrator's second
 * adjustment cannot be swallowed as a repeat of their first. The ledger's own reference columns
 * answer a different question — <i>who</i> did it — and keep carrying the actor.
 */
@Service
public class AdminWalletOperationService {

    private final WalletService wallets;
    private final UserRepository users;
    private final AdminGuard guard;
    private final AdminActionRecorder audit;

    public AdminWalletOperationService(WalletService wallets, UserRepository users, AdminGuard guard,
                                       AdminActionRecorder audit) {
        this.wallets = wallets;
        this.users = users;
        this.guard = guard;
        this.audit = audit;
    }

    /**
     * @param operationId one adjustment's identity, reused verbatim on every retry of that same
     *                    adjustment — a fresh value per retry would charge twice
     */
    @Transactional
    public LedgerResult adjust(Long targetUserId, Long actorUserId, int signedAmount, String note,
                               String operationId) {
        if (!users.existsById(targetUserId)) {
            throw new ApiException(ErrorCode.ADMIN_TARGET_NOT_FOUND);
        }
        // 마스터 계정 보호는 "타 관리자가 마스터를 건드리지 못한다" 는 규칙이다. 마스터 본인의
        // 자기 조정은 예외로 허용한다 — 운영 중 자기 코인을 넣고 빼며 흐름을 검증할 일이 있고,
        // 감사(actor=self)가 그 흔적을 그대로 남긴다.
        if (!targetUserId.equals(actorUserId)) {
            guard.requireTargetNotMaster(targetUserId);
        }

        LedgerResult result = wallets.adjustByAdmin(new CoinAdminAdjustCommand(
                targetUserId, signedAmount, note, actorUserId, adjustmentKey(targetUserId, operationId)));

        // 같은 조정을 다시 보낸 것이라면 원장이 안 움직였다. 감사도 움직이지 않아야 한다 —
        // 재시도 횟수만큼 감사 행이 쌓이면 "몇 번 조정했는가" 를 원장과 감사가 다르게 답한다.
        if (!result.alreadyApplied()) {
            audit.record(actorUserId, AdminActionRecorder.COIN_ADJUST, AdminActionRecorder.TARGET_USER,
                    targetUserId, detail(signedAmount, note, operationId));
        }
        return result;
    }

    /** {@code ADMIN_ADJUSTMENT:{targetUserId}:{operationId}} — 멱등 범위가 대상 사용자별이다. */
    static String adjustmentKey(Long targetUserId, String operationId) {
        return CoinReason.ADMIN_ADJUSTMENT + ":" + targetUserId + ":" + operationId;
    }

    /**
     * {@code operationId} 도 감사에 남긴다. 원장에는 키가 있고 감사에는 없으면, 나중에 두 기록을
     * 맞춰 볼 때 어느 조정이 어느 행인지 사람이 다시 추측해야 한다.
     */
    private static String detail(int signedAmount, String note, String operationId) {
        String reason = note == null || note.isBlank() ? "(사유 없음)" : note;
        return reason + " [amount=" + signedAmount + ", operationId=" + operationId + "]";
    }
}
