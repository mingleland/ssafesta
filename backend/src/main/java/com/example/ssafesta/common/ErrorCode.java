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
    /**
     * Distinguished from {@link #INVALID_MEMBER_TOKEN} on purpose — one is retryable, one is not
     * (S15P21A604-764, GitLab #198), the same split {@link #OAUTH_HANDOFF_EXPIRED} makes.
     *
     * <p>The Refresh Token was rotated a moment ago and this request carried the one it replaced —
     * a second tab that bootstrapped at the same time, not a stolen token. The session is alive and
     * the cookie has already been refreshed, so sending it again succeeds; the client must not show
     * "로그인이 끝났다". {@code INVALID_MEMBER_TOKEN} is deliberately not reused: it means
     * "you are not logged in", and a client cannot tell the two apart from the status alone.
     */
    REFRESH_TOKEN_ROTATED(HttpStatus.UNAUTHORIZED, "로그인 정보가 방금 갱신되었습니다. 다시 시도해 주세요."),
    USER_NOT_FOUND(HttpStatus.UNAUTHORIZED, "존재하지 않는 회원입니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
    MEMBER_ONLY(HttpStatus.FORBIDDEN, "회원 계정만 이용할 수 있습니다."),
    UNTRUSTED_ORIGIN(HttpStatus.FORBIDDEN, "허용되지 않은 요청 출처입니다."),

    // ── 관리자 (S15P21A604-742) ─────────────────────────────────────────────
    /**
     * The master account, or something it owns, was named as the target of an admin action
     * (S15P21A604-743). Distinct from {@link #FORBIDDEN} on purpose: the caller <i>is</i> an admin,
     * so a generic refusal would read as "your session is wrong" rather than "this target is
     * off-limits", and the console cannot tell the two apart to explain either.
     */
    MASTER_PROTECTED(HttpStatus.FORBIDDEN, "보호된 계정입니다."),
    /**
     * Demoting, suspending or deleting this admin would leave the service with none — and the
     * promotion API itself is behind the admin gate, so there would be no way back in short of a
     * migration.
     */
    ADMIN_LAST_ONE(HttpStatus.CONFLICT, "마지막 관리자는 해제할 수 없습니다."),
    ADMIN_ALREADY(HttpStatus.CONFLICT, "이미 관리자입니다."),

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
    ACTIVE_LEASE_LIMIT(HttpStatus.CONFLICT, "이미 임대 중인 부스가 있습니다. 반납하거나 만료된 뒤 다시 임대할 수 있습니다."),
    /**
     * Nothing of the caller's to hand back here (FR-020).
     *
     * <p>Deliberately does not separate "you hold no lease" from "your lease is on another
     * slot": both mean the caller's screen is stale, and the client does the same thing about
     * either — re-read the slot list. Which one it was is in the server log. A retried
     * {@code DELETE} lands here too, and that is not an error worth showing.
     */
    ACTIVE_LEASE_NOT_FOUND(HttpStatus.NOT_FOUND, "반납할 활성 임대가 없습니다."),
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
    /**
     * 수정본 교체를 지금 걸 수 없다 (FR-019, S15P21A604-386). 교체 대상은 {@code READY} 하나이고,
     * 그 위에 이미 다른 교체가 진행 중이거나 같은 파일이 다른 문서로 등록돼 있으면 여기로 온다.
     *
     * <p>무엇이 막는지는 thrower 가 message 에 담는다 — 대상 상태·진행 중인 교체·다른 문서의 중복은
     * 사용자가 할 일이 서로 다르다. {@link #DOCUMENT_LIMIT_EXCEEDED} 와 같은 결이다.
     */
    DOCUMENT_NOT_REPLACEABLE(HttpStatus.CONFLICT, "이 문서는 교체할 수 없습니다."),
    /**
     * 늦게 도착한 이전 attempt 의 결과다 (GitLab #119 §3, S15P21A604-400).
     *
     * <p>lease 가 만료돼 Job 을 회수하고 {@code attempt_no} 를 올린 뒤, 죽은 줄 알았던 이전 워커가
     * 결과를 보내오는 경우다. 받아 주면 두 attempt 의 chunk 가 섞인다. <b>재시도로 풀리지 않는다</b> —
     * 보내는 쪽은 자기 attempt 가 끝났음을 알고 버려야 한다.
     */
    JOB_ATTEMPT_STALE(HttpStatus.CONFLICT, "이미 지난 attempt 의 결과입니다."),
    /**
     * Job 이 끝났거나 사라졌다 ({@code SUCCEEDED}·{@code DEAD}·{@code CANCELLED}, 또는 문서 삭제로
     * CASCADE). {@link #DOCUMENT_UPLOAD_GONE} 과 같은 결로 410 이다 — 같은 Job 으로는 다시 시도할
     * 곳이 없다.
     */
    JOB_GONE(HttpStatus.GONE, "이미 종료된 처리 작업입니다."),
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

    // ── 저장소 reconcile 수신 (spec 007 FR-035, S15P21A604-500) ─────────────
    // 계약 spring-storage-reconciliation-api.yaml v0.2.0 이 정본이다. Infra 가 이 네 code 로
    // 재시도 여부를 가른다 — 500 하나만 재시도가 의미 있고 나머지는 terminal 이다.
    /**
     * 요청 형식이 계약에 맞지 않거나 자기모순이다 (예: {@code VERIFIED} 인데 sha256 이 서로 다르다).
     *
     * <p>이 API 만 422 를 쓴다. 계약이 그렇게 정했고, 소비자가 사람이 아니라 Infra 스크립트라
     * 400(사용자 입력 오류)과 구분되는 편이 낫다.
     */
    RECONCILIATION_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "reconcile 결과 형식이 올바르지 않습니다."),
    /**
     * 결과는 적재했지만 문서에 반영할 수 없다 — 문서가 요청의 source provider·object key 를 더
     * 이상 갖지 않는다.
     *
     * <p><b>재시도로 풀리지 않는다.</b> 같은 payload 는 영원히 이 응답을 받는다. 늦게 도착한 결과가
     * 최신 저장 위치를 과거 값으로 되돌리지 않게 하는 것이 이 거부의 목적이다.
     */
    RECONCILIATION_STALE(HttpStatus.CONFLICT, "이미 반영할 수 없는 reconcile 결과입니다."),
    /**
     * 같은 {@code runId + documentId} 로 <b>다른 내용</b>이 왔다. 먼저 저장된 결과가 유지된다.
     *
     * <p>조용히 무시하면 보내는 쪽의 버그가 감춰진다 — 멱등은 "같은 요청을 다시 보내도 안전하다"
     * 이지 "같은 키로 다른 것을 보내도 된다" 가 아니다.
     */
    RECONCILIATION_REPLAY_CONFLICT(HttpStatus.CONFLICT, "같은 reconcile 키로 다른 결과가 도착했습니다."),
    /**
     * 요청은 유효한데 Spring 배포가 그 provider 를 모른다 — 설정 누락이거나 문서 행의 버킷이
     * 설정과 어긋난다. 요청 오류가 아니므로 422 가 아니다.
     *
     * <p>이 네 code 중 <b>유일하게 재시도가 의미 있다</b>. 다만 고쳐야 할 것은 payload 가 아니라
     * Spring 배포 설정이다.
     */
    RECONCILIATION_CONFIGURATION_ERROR(HttpStatus.INTERNAL_SERVER_ERROR,
            "reconcile 결과를 반영할 수 없습니다. 서버 저장소 설정을 확인해 주세요."),

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
    /**
     * 오락기 해석의 유일한 non-200 이다 (S15P21A604-602). 게임이 미게시·비공개·삭제인 것은 오류가
     * 아니라 {@code playable:false} + {@code unavailableReason} 으로 나간다 — 오락기는 월드
     * 고정물이라 월드를 끊지 않는다 (spec 019 FR-020).
     */
    MACHINE_NOT_FOUND(HttpStatus.NOT_FOUND, "등록되지 않은 게임기입니다."),

    // ── Game Asset 업로드 (spec 019, #69) ───────────────────────────────────
    // contracts/game-asset-upload.md §6 의 11행이 정본이다. 여기 없는 GAME_ASSET_* 가 응답에
    // 나오면 계약 위반이다. 위반 항목의 구체값은 새 code 가 아니라 errors[].rule 로 나간다
    // (§6) — grant 만료·PUT 누락도 그래서 GAME_ASSET_NOT_READY + rule 이고 새 code 가 아니다.
    GAME_ASSET_KIND_UNSUPPORTED(HttpStatus.BAD_REQUEST, "지원하지 않는 자산 종류입니다."),
    GAME_ASSET_TYPE_UNSUPPORTED(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 이미지 형식입니다."),
    GAME_ASSET_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "이미지 용량이 너무 큽니다."),
    GAME_ASSET_DIMENSION_EXCEEDED(HttpStatus.BAD_REQUEST, "이미지 크기가 너무 큽니다."),
    GAME_ASSET_QUOTA_EXCEEDED(HttpStatus.CONFLICT, "이 게임에 올릴 수 있는 이미지 수를 초과했습니다."),
    GAME_ASSET_CORRUPTED(HttpStatus.BAD_REQUEST, "이미지 파일이 손상되었습니다."),
    GAME_ASSET_NOT_FOUND(HttpStatus.NOT_FOUND, "자산을 찾을 수 없습니다."),
    GAME_ASSET_FORBIDDEN(HttpStatus.FORBIDDEN, "이 자산에 접근할 권한이 없습니다."),
    GAME_ASSET_NOT_READY(HttpStatus.CONFLICT, "자산이 아직 사용할 수 없는 상태입니다."),
    GAME_ASSET_DELETED(HttpStatus.CONFLICT, "삭제된 자산입니다."),
    GAME_ASSET_IN_USE(HttpStatus.CONFLICT, "사용 중인 자산입니다."),

    // ── Survey (010) ────────────────────────────────────────────────────────
    // CLOSED · ALREADY_RESPONDED 는 docs/08 §18 이 예약해 둔 어휘다. 신설은 뒤 둘이다.
    SURVEY_NOT_FOUND(HttpStatus.NOT_FOUND, "설문을 찾을 수 없습니다."),
    SURVEY_CLOSED(HttpStatus.CONFLICT, "마감된 설문입니다."),
    SURVEY_ALREADY_RESPONDED(HttpStatus.CONFLICT, "이미 응답한 설문입니다."),
    /** 응답이 있는 설문은 문항 구조가 잠긴다 (C-08). 제목·설명·보상·마감은 수정된다. */
    SURVEY_LOCKED(HttpStatus.CONFLICT, "응답이 있는 설문은 문항을 바꿀 수 없습니다."),

    // ── 미니게임 (014) ──────────────────────────────────────────────────────
    // 하나뿐이다. 판정 거부·일일 한도 도달·재제출은 전부 200 이라 오류 어휘가 필요 없고
    // (spec 014 Acceptance Scenario 4), 게스트·요청 값 오류는 MEMBER_ONLY·VALIDATION_FAILED 를
    // 재사용한다. 남의 세션도 이 코드로 답한다 — 구분하면 세션의 존재를 알려주게 된다.
    MINIGAME_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "게임 세션을 찾을 수 없습니다."),

    // ── 공통 ────────────────────────────────────────────────────────────────
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    // ── 상담 (spec 011 US1) ─────────────────────────────────────────────────
    // specs/011-staff-consultation/contracts/staff-consultation-api.md §B 가 정본이다.
    // 게스트 거부는 MEMBER_ONLY 를, 만료 부스는 BOOTH_LEASE_EXPIRED 를 재사용한다 — 같은 사건에
    // 두 이름을 만들지 않는다.
    CONSULTATION_NOT_FOUND(HttpStatus.NOT_FOUND, "상담 요청을 찾을 수 없습니다."),
    /** 이 방문자가 그 부스에 걸어 둔 대기 중 요청이 이미 있다. */
    CONSULTATION_REQUEST_PENDING(HttpStatus.CONFLICT, "이미 보낸 상담 요청이 기다리고 있습니다."),
    /**
     * 수락하려는 직원에게 이미 활성 상담이 있다 (C-06, FR-021).
     *
     * <p>{@code ux_consultations_active_staff} 가 DB 에서도 막으므로, 동시 요청에서도 둘째는
     * 이 code 로 떨어진다.
     */
    CONSULTATION_ALREADY_ACTIVE(HttpStatus.CONFLICT, "이미 진행 중인 상담이 있습니다."),
    /** 이미 다른 직원이 가져갔거나 만료·취소됐다. 만료는 스위퍼를 기다리지 않고 읽는 쪽이 본다. */
    CONSULTATION_NOT_REQUESTED(HttpStatus.CONFLICT, "더 이상 수락할 수 없는 상담 요청입니다."),
    /** 내 요청도, 내가 맡은 상담도 아니다. */
    CONSULTATION_FORBIDDEN(HttpStatus.FORBIDDEN, "이 상담에 대한 권한이 없습니다."),

    // ── Staff 초대·권한 (spec 011 US3) ──────────────────────────────────────
    // specs/011-staff-consultation/contracts/staff-consultation-api.md §A 가 정본이다.
    // BOOTH_EDITOR_FORBIDDEN·MEMBER_ONLY·VALIDATION_FAILED 는 위에 있는 것을 재사용한다.
    /**
     * 부스 운영진이 아니다 — 초대·역할 변경·직원 제거는 Owner 와 {@code ADMIN} 만 한다 (FR-001).
     *
     * <p>{@code BOOTH_EDITOR_FORBIDDEN} 과 가르는 이유는 물음이 다르기 때문이다. 저쪽은 "콘텐츠를
     * 고칠 수 있는가", 이쪽은 "사람을 들이고 뺄 수 있는가" 다. {@code CONTENT_EDITOR} 는 앞의
     * 답이 예이고 뒤의 답이 아니오라서, 한 code 로 묶으면 FE 가 그 둘을 구분할 수 없다.
     */
    STAFF_MANAGER_FORBIDDEN(HttpStatus.FORBIDDEN, "직원을 관리할 권한이 없습니다."),
    /** 초대하려는 닉네임의 회원이 없다. {@code USER_NOT_FOUND} 는 401 이라 이 자리에 쓸 수 없다. */
    STAFF_INVITEE_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 닉네임의 회원이 없습니다."),
    STAFF_INVITATION_NOT_FOUND(HttpStatus.NOT_FOUND, "초대를 찾을 수 없습니다."),
    /** {@code ux_staff_invitations_pending} 이 DB 에서도 막는다. */
    STAFF_INVITATION_PENDING(HttpStatus.CONFLICT, "이미 보낸 초대가 처리되기를 기다리고 있습니다."),
    /** 수락·취소됐거나 48시간이 지났다 (C-07). 만료는 스위퍼를 기다리지 않고 읽는 쪽이 판정한다. */
    STAFF_INVITATION_NOT_PENDING(HttpStatus.CONFLICT, "더 이상 수락할 수 없는 초대입니다."),
    /** 내게 온 초대가 아니다. 존재 여부는 이미 아는 사람만 물을 수 있으므로 404 가 아니라 403 이다. */
    STAFF_INVITATION_FORBIDDEN(HttpStatus.FORBIDDEN, "내게 온 초대가 아닙니다."),
    STAFF_ALREADY_MEMBER(HttpStatus.CONFLICT, "이미 이 부스의 구성원입니다."),
    /** Owner 는 {@code booth_staffs} 행이 아니다 (FR-018) — 역할 변경·제거 대상이 아니다. */
    STAFF_OWNER_IMMUTABLE(HttpStatus.CONFLICT, "부스 소유자는 직원 목록에서 변경할 수 없습니다."),
    STAFF_NOT_FOUND(HttpStatus.NOT_FOUND, "그 부스의 직원이 아닙니다."),

    // 월드 공용 채팅 (spec 002 contracts, S15P21A604-687). STOMP 로만 나가지만 ErrorCode 를
    // 싣는다 — 싣지 않으면 500 INTERNAL_ERROR 로 나가고, 보낸 쪽이 고칠 수 있는 거절과 서버
    // 결함이 같은 모양이 된다 (DomainExceptionEnvelopeTest 가 지키는 규약).
    CHAT_TOO_FAST(HttpStatus.TOO_MANY_REQUESTS, "잠시 후 다시 보내 주세요."),
    CHAT_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "채팅을 잠시 사용할 수 없습니다."),

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

    /**
     * The code that answers a bare HTTP status.
     *
     * <p>Two callers need this and they have to agree: {@code GlobalExceptionHandler} for a
     * framework rejection that carries a status and nothing else, and {@code ApiErrorController}
     * for a container ERROR dispatch, where the exception is already gone and the status is all
     * that is left to answer from.
     *
     * @param status resolved HTTP status, or {@code null} for a non-standard code
     */
    public static ErrorCode of(HttpStatus status) {
        if (status == null) {
            return VALIDATION_FAILED;
        }
        return switch (status) {
            case UNAUTHORIZED -> UNAUTHORIZED;
            case FORBIDDEN -> FORBIDDEN;
            case NOT_FOUND -> NOT_FOUND;
            case METHOD_NOT_ALLOWED -> METHOD_NOT_ALLOWED;
            case BAD_REQUEST -> VALIDATION_FAILED;
            case UNSUPPORTED_MEDIA_TYPE -> UNSUPPORTED_MEDIA_TYPE;
            default -> status.is5xxServerError() ? INTERNAL_ERROR : VALIDATION_FAILED;
        };
    }
}
