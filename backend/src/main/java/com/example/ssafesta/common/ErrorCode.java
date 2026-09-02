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
    REGISTRATION_CONFLICT(HttpStatus.CONFLICT, "가입 처리 중 충돌이 발생했습니다. 다시 시도해 주세요."),
    WITHDRAWAL_NOT_CONFIRMED(HttpStatus.BAD_REQUEST, "탈퇴 내용을 확인한 뒤 확정해야 합니다."),

    // ── 지갑 (spec 003) ─────────────────────────────────────────────────────
    WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "지갑을 찾을 수 없습니다."),
    INSUFFICIENT_COIN(HttpStatus.CONFLICT, "코인이 부족합니다."),

    // ── 카탈로그 · 인벤토리 (spec 012) ──────────────────────────────────────
    CATALOG_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "카탈로그 품목을 찾을 수 없습니다."),
    ITEM_NOT_ON_SALE(HttpStatus.CONFLICT, "현재 판매 중인 품목이 아닙니다."),
    ITEM_ALREADY_OWNED(HttpStatus.CONFLICT, "이미 보유한 품목입니다."),
    AVATAR_ITEM_NOT_OWNED(HttpStatus.CONFLICT, "보유하지 않은 파츠가 있습니다."),

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

    // ── Project 전시 (spec 009) ─────────────────────────────────────────────
    // 최상위 code 두 개뿐이고 신규 rule 은 없다 — 필드 위반은 기존 FIELD_INVALID 를 쓴다 (C-07, BE/research.md R-07).
    PROJECT_NOT_FOUND(HttpStatus.NOT_FOUND, "프로젝트를 찾을 수 없습니다."),
    /**
     * 부스당 프로젝트는 1개다 (spec 009 C-01). 두 번째 등록은 덮어쓰지 않고 거절한다 — 조용한
     * 덮어쓰기는 사고를 만들고, 수정 경로는 {@code PATCH} 로 따로 있다. {@code ACTIVE_LEASE_LIMIT}
     * (1인 1임대)와 같은 결이다.
     */
    PROJECT_ALREADY_EXISTS(HttpStatus.CONFLICT, "이 부스에는 이미 프로젝트가 있습니다. 수정으로 변경해 주세요."),

    // ── AI 직원 (spec 007) ──────────────────────────────────────────────────
    AGENT_NOT_FOUND(HttpStatus.NOT_FOUND, "AI 직원을 찾을 수 없습니다."),
    /** 부스당 1명 (C-13). 상한이 설정값이라 message 가 숫자와 해결 방법을 담는다. */
    AGENT_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "이 부스에는 이미 AI 직원이 있습니다. 수정으로 변경해 주세요."),
    /**
     * 그 직원을 가리키는 것이 남아 있다 (C-14). 무엇이 막는지는 thrower 가 message 에 담는다 —
     * 문서인지 상담인지 배치인지에 따라 사용자가 할 일이 다르다.
     */
    AGENT_DELETE_CONFLICT(HttpStatus.CONFLICT, "사용 중인 AI 직원은 삭제할 수 없습니다."),

    // ── AI 문서 · 저장소 (spec 007 US2) ─────────────────────────────────────
    // 중복은 여기 없다. FR-019c 가 "중복은 오류가 아니다" 로 못박았고 응답은 200 + duplicate 판별자다 —
    // DOCUMENT_DUPLICATE 를 만들면 계약 위반이다.
    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "문서를 찾을 수 없습니다."),
    /** 10개·100MB (FR-018). 둘 다 설정값이라 thrower 가 숫자와 해결 방법을 message 에 담는다. */
    DOCUMENT_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "AI 직원의 문서 상한을 초과했습니다."),
    /** 발급한 URL 로 올린 것이 저장소에 없거나 크기가 다르다 — 다시 올리면 되는 상태다. */
    DOCUMENT_UPLOAD_INCOMPLETE(HttpStatus.CONFLICT, "업로드가 완료되지 않았습니다. 다시 올려 주세요."),
    /**
     * 만료된 업로드의 원본이 이미 없다 (FR-027). {@link #OAUTH_HANDOFF_EXPIRED} 와 같은 결로 410 이다 —
     * 재시도가 아니라 <b>새 업로드 권한</b>이 필요하다는 뜻이라 409 와 구분한다.
     */
    DOCUMENT_UPLOAD_GONE(HttpStatus.GONE, "업로드가 만료되었습니다. 새로 업로드해 주세요."),
    /** 저장소가 답하지 못했거나 감시가 끊겼다 (C-10). <b>재시도 가능</b>하다는 것이 507 과의 차이다. */
    STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    /**
     * 저장소 할당량이 찼다 (C-10, #100 — usage guard 90%). <b>재시도로 풀리지 않아</b> 503 과 가른다.
     *
     * <p>업로드 실패가 아니라 <b>발급 거부</b>다. 브라우저가 저장소로 직행하는 PUT 은 Spring 을
     * 통과하지 않으므로, 용량은 grant 를 내주기 전에 막는 것이 유일한 자리다 — 그래서 행도 만들지
     * 않는다(차단 중 만든 행은 FR-018 의 10개 슬롯을 먹는다).
     */
    STORAGE_QUOTA_EXCEEDED(HttpStatus.INSUFFICIENT_STORAGE, "저장소 용량이 부족합니다. 관리자에게 문의해 주세요."),

    // ── Game Studio (spec 019) ──────────────────────────────────────────────
    // contracts/game-api.md v1.0 §봉투 code 표 14행이 정본이다. 여기 없는 GAME_* 가 응답에 나오면
    // 계약 위반이다. MEMBER_ONLY·VALIDATION_FAILED·BOOTH_LEASE_EXPIRED 는 위에 있는 것을 재사용한다 —
    // 새 이름을 만들면 같은 사건이 두 이름을 갖는다.
    GAME_VALIDATION_FAILED(HttpStatus.CONFLICT, "게임을 저장할 수 없습니다."),
    GAME_REVISION_CONFLICT(HttpStatus.CONFLICT, "다른 편집 내용이 먼저 저장되었습니다."),
    GAME_NOT_FOUND(HttpStatus.NOT_FOUND, "게임을 찾을 수 없습니다."),
    GAME_DELETED(HttpStatus.NOT_FOUND, "삭제된 게임입니다."),
    GAME_NOT_PUBLISHED(HttpStatus.NOT_FOUND, "아직 게시되지 않은 게임입니다."),
    GAME_NOT_PUBLIC(HttpStatus.FORBIDDEN, "현재 비공개 상태인 게임입니다."),
    GAME_FORBIDDEN(HttpStatus.FORBIDDEN, "이 게임을 편집할 권한이 없습니다."),
    GAME_SCHEMA_UNSUPPORTED(HttpStatus.CONFLICT, "지원하지 않는 게임 데이터 버전입니다."),
    /**
     * 500인 것은 이것뿐이다. 나머지는 클라이언트가 고칠 수 있는 사건이지만, 이것은 <b>서버가 저장을
     * 허용했던 데이터가 지금 검증을 통과하지 못한다</b>는 뜻이라 서버 결함이다. 조용히 200으로 빈
     * 프로젝트를 돌려주지 않는다 (T-24).
     */
    GAME_PROJECT_INVALID(HttpStatus.INTERNAL_SERVER_ERROR, "저장된 게임 데이터가 손상되었습니다."),
    /** 사용자가 스스로 풀 수 있는 상태다 — thrower 가 상한값과 해결 방법을 message 에 담는다. */
    GAME_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "만들 수 있는 게임 수를 초과했습니다."),
    CONFIG_NOT_FOUND(HttpStatus.NOT_FOUND, "게임 포털 연결을 찾을 수 없습니다."),

    // ── 공통 ────────────────────────────────────────────────────────────────
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "허용되지 않은 요청 방식입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식입니다."),
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
