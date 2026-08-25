package com.example.ssafesta.common;

import org.springframework.http.HttpStatus;

/**
 * Every error this API can return, in one place (spec 005 research R-09, docs/08 §18).
 *
 * <p>Before this enum existed the codes lived as string literals inside controllers — and only one
 * of twenty-three throw sites actually carried one. The rest returned a Korean sentence and nothing
 * else, which would have forced the frontend to match on prose. Anything a client is expected to
 * branch on belongs here, so that "what can go wrong" is a list someone can read.
 *
 * <p>The message on each constant is a <b>default</b>. A thrower may replace it with something more
 * specific (a shortfall amount, a field name), but never with anything that leaks internals — no
 * stack traces, no exception class names, no SQL.
 */
public enum ErrorCode {

    // ── 인증 · 권한 ──────────────────────────────────────────────────────────
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Access Token이 필요합니다."),
    INVALID_MEMBER_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 회원 토큰입니다."),
    USER_NOT_FOUND(HttpStatus.UNAUTHORIZED, "존재하지 않는 회원입니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
    MEMBER_ONLY(HttpStatus.FORBIDDEN, "회원 계정만 이용할 수 있습니다."),
    UNTRUSTED_ORIGIN(HttpStatus.FORBIDDEN, "허용되지 않은 요청 출처입니다."),

    // ── OAuth ───────────────────────────────────────────────────────────────
    OAUTH_PROVIDER_NOT_SUPPORTED(HttpStatus.NOT_FOUND, "지원하지 않는 소셜 로그인 제공자입니다."),
    OAUTH_HANDOFF_MISSING(HttpStatus.BAD_REQUEST, "OAuth 로그인 handoff cookie가 없습니다."),
    /** Distinguished from {@link #OAUTH_HANDOFF_MISSING} on purpose — one is retryable, one is not (T-103). */
    OAUTH_HANDOFF_EXPIRED(HttpStatus.GONE, "OAuth 로그인 정보가 만료되었거나 이미 사용되었습니다."),

    // ── 회원 (spec 001) ─────────────────────────────────────────────────────
    NICKNAME_INVALID(HttpStatus.BAD_REQUEST, "사용할 수 없는 닉네임입니다."),
    NICKNAME_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),
    WITHDRAWAL_NOT_CONFIRMED(HttpStatus.BAD_REQUEST, "탈퇴 내용을 확인한 뒤 확정해야 합니다."),

    // ── 지갑 (spec 003) ─────────────────────────────────────────────────────
    WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "지갑을 찾을 수 없습니다."),
    INSUFFICIENT_COIN(HttpStatus.CONFLICT, "코인이 부족합니다."),

    // ── 부스 · 임대 (spec 004) ──────────────────────────────────────────────
    BOOTH_NOT_FOUND(HttpStatus.NOT_FOUND, "부스를 찾을 수 없습니다."),
    BOOTH_SLOT_NOT_FOUND(HttpStatus.NOT_FOUND, "슬롯을 찾을 수 없습니다."),
    BOOTH_SLOT_NOT_RENTABLE(HttpStatus.CONFLICT, "임대할 수 없는 슬롯입니다."),
    BOOTH_SLOT_ALREADY_LEASED(HttpStatus.CONFLICT, "이미 임대 중인 슬롯입니다."),
    ACTIVE_LEASE_LIMIT(HttpStatus.CONFLICT, "이미 임대 중인 부스가 있습니다. 만료 후 다시 임대할 수 있습니다."),
    /**
     * Reused by the AI conversation contract (spec 008): "booth entry refused" and "AI question
     * refused" are the same event to a user, so they must not carry two different names
     * (docs/HDD/임대만료_AI대화_종료계약_검토.md §3).
     */
    BOOTH_LEASE_EXPIRED(HttpStatus.CONFLICT, "임대가 만료된 부스입니다."),

    // ── Booth Studio / Layout (spec 005) ────────────────────────────────────
    BOOTH_EDITOR_FORBIDDEN(HttpStatus.FORBIDDEN, "부스를 편집할 권한이 없습니다."),
    LAYOUT_VALIDATION_FAILED(HttpStatus.CONFLICT, "배치를 저장할 수 없습니다."),
    LAYOUT_REVISION_CONFLICT(HttpStatus.CONFLICT, "다른 편집자가 먼저 저장했습니다."),
    LAYOUT_NOT_PUBLISHED(HttpStatus.NOT_FOUND, "공개된 배치가 없습니다."),

    // ── 공통 ────────────────────────────────────────────────────────────────
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "허용되지 않은 요청 방식입니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
