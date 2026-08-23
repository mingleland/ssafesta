# Tasks: 001 Auth (FE)

**Input**: [plan.md](plan.md) · [../spec.md](../spec.md) · [../contracts/oauth-completion.md](../contracts/oauth-completion.md) · [../contracts/nickname-policy.md](../contracts/nickname-policy.md)

**Tests**: vitest(기존 `package.json` `test` 스크립트, `entities/layout/__tests__/unit/` 패턴). 컴포넌트 테스트 라이브러리 미설치 — 순수 모듈·mock은 vitest, 화면 흐름은 quickstart 수동 시나리오(Phase 7)로 검증.

**Organization**: US1(소셜 가입·로그인)·US2(게스트)·US3(세션 유지·종료)는 ../spec.md User Story 번호. US4·US5·US6은 범위 외(plan.md §Summary).

## Format: `[ID] [P?] [Story] Description` — 각 태스크에 **완료조건** 명시

## Path Conventions

`festa-frontend/src/` 하위. 구조 근거는 plan.md §기존 코드와의 접점.

---

## Phase 1: Setup — 계약 전사

**Purpose**: oauth-completion.md 계약을 TS 타입으로 옮긴다. 여기가 틀리면 전부 틀린다.

- [ ] T001 [P] `entities/auth/types.ts` — `OAuthCompleteResponse` discriminated union 전사: `{status:'AUTHENTICATED', accessToken:string, expiresAt:string}` | `{status:'NICKNAME_REQUIRED', accessToken:null, expiresAt:null}`(oauth-completion.md 응답 예시 2종 그대로) + `SessionKind = 'anonymous'|'guest'|'member'`.
  **완료조건**: `tsc -b` 통과. 계약 문서에 없는 필드 0개(diff 대조).

**Checkpoint**: 타입만으로 커밋 가능.

---

## Phase 2: Foundational — API·mock·세션 store

**Purpose**: 모든 US가 딛는 기반. US 착수 전 완료 필수.

- [ ] T002 `entities/auth/api.ts` — `complete(body?: {nickname:string})` = `POST /api/v1/auth/oauth/complete`, **`credentials:'include'` 명시**(oauth-completion.md FE 의무 1), `client.ts`의 `api<T>()` 재사용. `guestEnter`·`refresh`·`logout`은 경로 미확정(plan.md §미결) — 호출 시 명시적 오류를 던지는 placeholder로 두고 `// TODO(001-BE): 계약 회수 후 경로 기입` 주석.
  **완료조건**: `complete` 요청에 `credentials:'include'` 포함(코드 리뷰 + T014 테스트). placeholder 3종이 침묵 실패 아닌 명시 오류.
- [ ] T003 `entities/auth/api.mock.ts` — plan.md §Mock 전략 전체: sessionStorage 기반 handoff·가입 계정 집합, 최초 로그인 `NICKNAME_REQUIRED`→닉네임 제출 성공 시 가입 기록+`AUTHENTICATED`(handoff 소비), 기존 회원 즉시 `AUTHENTICATED`(소비), `NICKNAME_REQUIRED` 응답은 handoff 보존(FR-021c), handoff 없음→400·소비 후 재호출→410(`ApiError` 봉투로 throw, `client.ts` 형식), 게스트 fake AT+`expiresAt` 30분(FR-009a), 닉네임 대표 금칙 케이스 소수만(`관리자`·`admin`·정규화 우회 1건 — 전체 목록 전사 금지, plan.md 헌법 16조 판정), "다른 브라우저 로그인" 트리거로 이후 refresh 실패 재현.
  **완료조건**: T014 mock 단위 테스트 전부 green.
- [ ] T004 [P] `entities/auth/api.select.ts` — `VITE_USE_MOCK==='true'` 분기(`entities/layout/api.select.ts` 패턴 복제).
  **완료조건**: mock/real 전환이 이 파일 한 곳.
- [ ] T005 `features/auth/model/session.ts` — `useSyncExternalStore` 기반 store `{kind, expiresAt}` + `setMemberSession`/`setGuestSession`/`clearSession`. `setAccessToken`(`client.ts:34` 기존)과 함께 갱신하는 단일 진입 함수로 묶는다.
  **완료조건**: 토큰과 kind가 어긋나는 경로가 없음(설정·해제가 단일 함수 경유, 단위 테스트).

**Checkpoint**: mock `complete()` 호출 결과가 콘솔/화면에 raw 표시되면 통과.

---

## Phase 3: US1 — 소셜 계정으로 가입·로그인 (P0) 🎯 MVP

**Goal**: `/login` 교체 + `/auth/callback` + `NICKNAME_REQUIRED` 흐름.

**Independent Test**: mock에서 최초 로그인→닉네임 제출→`/app/home` 도달, 재로그인→닉네임 폼 없이 즉시 로그인(spec US1 Independent Test의 FE 관측 가능 부분).

- [ ] T006 [US1] `pages/login/LoginPage.tsx` + `app/router/index.tsx`의 `/login` stub 교체 — Google/Kakao 버튼(real: `window.location.href = BASE_URL + /api/v1/auth/oauth/{provider}` 전체 페이지 이동, mock: handoff 심고 `/auth/callback` navigate — 분기는 `api.select` 뒤 mock 모듈이 제공), 게스트 입장 버튼(T010 결선 전 비활성 가능), 실패 안내 영역(FR-007 원인 범주+재시도 방법).
  **완료조건**: stub 텍스트 소멸. mock 모드에서 버튼 클릭 → `/auth/callback` 도달.
- [ ] T007 [US1] `pages/auth/CallbackPage.tsx` + `/auth/callback` 라우트 — plan.md 상태 기계 그대로: mount 시 body 없이 `complete()` 1회(StrictMode 이중 호출 방어 — 모듈 레벨 guard), `AUTHENTICATED`→`setMemberSession`+`/app/home`, `NICKNAME_REQUIRED`→닉네임 폼, 400/410→`/login` navigate+재시작 안내(FE 의무 3). **URL query·fragment를 읽는 코드 없음**(FR-021b).
  **완료조건**: mock 3분기(기존 회원·신규·직진입 400) 수동 재현 통과. `useSearchParams`·`location.hash` 참조 0건(grep).
- [ ] T008 [US1] `features/auth/ui/NicknameForm.tsx` — 닉네임 입력, FE 사전 검사는 공백/빈 값만(plan.md §미결 — 길이 제한 추측 금지), 제출 시 `complete({nickname})`, 실패 시 **이유 비특정 일반 안내+수정 방법**(FR-007e, nickname-policy.md 검사 규칙 4), 성공 시 T007과 동일 완료 처리.
  **완료조건**: mock 금칙 케이스 제출 → 일반 안내 표시(어떤 금칙어인지 미노출), 유효 닉네임 → 로그인 완료. 실패 후 재제출 가능(handoff 보존 — FR-021c).

**Checkpoint**: US1 Independent Test 통과 — **MVP 완성**.

---

## Phase 4: US2 — 게스트 입장 (P0)

**Goal**: 게스트 토큰 발급·만료 재입장·제한 기능 안내.

**Independent Test**: 게스트 입장→`/app/world`·`/app/home` 접근 가능, 회원 전용 라우트 진입 시 로그인 안내(spec US2 AS1·3의 FE 관측 부분).

- [ ] T009 [US2] LoginPage 게스트 버튼 결선 — `guestEnter()`(mock) → `setGuestSession`+`/app/home`. 응답 본문 AT만 사용, RT 관련 코드 없음(FR-008·009).
  **완료조건**: mock 게스트 입장 → kind `guest`로 `/app/home` 도달. cookie 접근 코드 0건.
- [ ] T010 [US2] 게스트 만료 UX — 401 시 guest면 재발급 시도 없이 "게스트 입장을 다시 선택" 안내+`/login` 진입점(FR-009a — 자동 재발급 금지).
  **완료조건**: mock 만료 sentinel로 안내 표시 확인. 자동 재발급 코드 없음.

**Checkpoint**: US2 Independent Test 통과.

---

## Phase 5: US3 — 세션 유지·종료 (P0)

**Goal**: `client.ts` 401 TODO 해소 + 새로고침 복원 + 로그아웃.

**Independent Test**: mock "다른 브라우저 로그인" 트리거 후 보호 요청 → 재로그인 안내(spec US3 AS4의 FE 관측 부분).

- [ ] T011 [US3] `shared/api/client.ts` 401 인터셉트 — `setUnauthorizedHandler(fn)` 등록 지점 추가(`:65` TODO 해소, 순환 import 방지 — plan.md §상태). member: refresh 1회→원요청 재시도 1회→실패 시 핸들러가 `clearSession`+재로그인 안내(FR-020b). guest: T010 경로. 인터셉트는 refresh 요청 자신에게는 적용하지 않는다(무한 루프 방지).
  **완료조건**: vitest — 401→refresh 성공→재시도 성공 / refresh 실패→세션 클리어, 재시도는 각 1회 상한.
- [ ] T012 [US3] 부트스트랩 복원 — 앱 시작 시(`AppProviders` 또는 router 로더) refresh 1회 조용히 시도, 성공 시 member 복원·실패 시 조용히 anonymous(plan.md §새로고침 복원 — `client.ts:33` 주석의 spec 001 이관분). 게스트는 복원하지 않음(FR-009a).
  **완료조건**: mock에서 로그인→새로고침→로그인 유지, 게스트→새로고침→anonymous.
- [ ] T013 [US3] 로그아웃 — `logout()`(mock)+`clearSession`+`/login`(FR-020). 버튼 위치는 가드 적용 화면 공통 헤더 최소 구현.
  **완료조건**: 로그아웃 후 회원 전용 라우트 재진입 차단(가드), AT 메모리 클리어.

**Checkpoint**: US3 Independent Test 통과.

---

## Phase 6: 라우트 가드

- [ ] T014a `app/router/RequireAuth.tsx` + `router/index.tsx` 적용 — 2등급(plan.md §라우트 설계): 게스트 허용(`/app/home`·`/app/world`), 회원 전용(`/app/studio/:boothId` 등 나머지 `/app/*`). anonymous→`/login` redirect, guest가 회원 전용 진입→소셜 로그인 안내+진입점(FR-011). 가드는 UX 보조, 서버 인가 불대체 주석(docs/10 §3).
  **완료조건**: kind 3종 × 라우트 2등급 조합이 표대로 동작(단위 테스트로 판정 함수 검증 + 수동 확인). 005 plan의 가드 TODO 주석(`router/index.tsx:16`) 갱신.

---

## Phase 7: Polish — 테스트·수동 시나리오

- [ ] T014 [P] mock·모듈 단위 테스트 `entities/auth/__tests__/unit/` — T003 완료조건의 시나리오 전부: 신규→`NICKNAME_REQUIRED`→제출→`AUTHENTICATED`, 재로그인 즉시 인증, handoff 소비/보존/400/410, 게스트 30분 `expiresAt`, 금칙 케이스 일반 안내용 코드, 세션 store 단일 진입.
  **완료조건**: `npm test` green, 위 시나리오 각 1개 이상 assert.
- [ ] T015 `FE/quickstart.md` 작성 — 수동 검증 시나리오: ①최초 가입 전 과정 ②재로그인 ③닉네임 실패→재제출 ④400/410→재시작 ⑤게스트 입장·만료·제한 라우트 ⑥새로고침 복원 ⑦다른 브라우저 로그인 종료 안내 ⑧`document.cookie`·storage에 토큰·handoff 부재 확인(FE 의무 4 — devtools 확인 절차 포함).
  **완료조건**: 시나리오 전부 mock 모드 브라우저에서 1회 통과 기록.
- [ ] T016 **[Blocked-on-BE]** real `api.ts` 경로 기입 — 게스트·refresh·logout endpoint 계약 회수 후 placeholder 교체 + 실서버 수동 검증.
  **완료조건**: BE 계약 문서 인용과 함께 경로 기입, `VITE_USE_MOCK=false`로 ①②⑤ 시나리오 통과.

---

## Dependencies

- T001 → 전부. T002~T005(Foundational) → US 전 Phase.
- Phase 3(US1) → Phase 4·5가 세션 store 사용 경로를 공유하나, T009~T013은 US1 완료 없이도 mock으로 독립 검증 가능(스토리 독립성).
- T014a(가드)는 T005 이후 언제든. T016만 외부(BE) 의존 — 나머지 전 태스크는 실서버 없이 완료 가능.
