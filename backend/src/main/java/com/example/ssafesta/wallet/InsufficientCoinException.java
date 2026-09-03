package com.example.ssafesta.wallet;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/**
 * Raised when a spend would take a wallet below zero (spec 003 FR-009).
 *
 * <p>Carries the required amount and the current balance so the caller can state a concrete
 * reason. Callers must not swallow this and fall back to a default behaviour — the refusal has to
 * reach the user (spec 003 FR-009, T-24).
 *
 * <p>The shortfall sentence is built here rather than at each catch site. Both callers were
 * assembling the identical string, which is one edit away from a booth lease and a catalog
 * purchase explaining the same refusal differently.
 */
public class InsufficientCoinException extends ApiException {

    private final transient int required;
    private final transient int balance;

    public InsufficientCoinException(int required, int balance) {
        super(ErrorCode.INSUFFICIENT_COIN,
                "코인이 부족합니다. 필요: " + required + ", 잔액: " + balance);
        this.required = required;
        this.balance = balance;
    }

    public int getRequired() { return required; }
    public int getBalance() { return balance; }
}
