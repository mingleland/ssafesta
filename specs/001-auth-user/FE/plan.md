# Implementation Plan: 001 Auth (FE)

**Branch**: `front` | **Date**: 2026-08-23 | **Spec**: [../spec.md](../spec.md) (394줄 확정판)

**Input**: [../spec.md](../spec.md) · [../contracts/oauth-completion.md](../contracts/oauth-completion.md) · [../contracts/nickname-policy.md](../contracts/nickname-policy.md) · `docs/LJH/backlog.md` A항목

> **계약 소비 전용 plan이다.** BE가 회수해 준 확정 계약 3종(spec 394줄판·oauth-completion.md·nickname-policy.md)에 있는 것만 확정으로 다루고, 계약에 없는 것(게스트·refresh·logout endpoint 경로 등)은 **추측하지 않고 미결로 남긴다** — mock으로 선개발하고 real 경로는 BE 계약 회수 후 기입한다(§미결).

---

## Summary

`/login` stub을 실제 로그인 화면(Google/Kakao 버튼 + 게스트 입장)으로 교체하고, 단일 `/auth/callback` 화면에서 `POST /api/v1/auth/oauth/complete`를 호출해 기존 회원 로그인·`NICKNAME_REQUIRED` 닉네임 보완 가입을 처리한다(spec FR-021b·FR-021c, oauth-completion.md). 게스트 입장(FR-008~012a), 401 인터셉트·refresh·세션 종료 안내(US3), 라우트 가드까지가 범위다. **전부 mock 패턴으로 실서버 없이 완전 개발**한다(backlog A항목, 기존 `api.select.ts` 패턴).

**범위 제외**: US4 마이페이지(내 정보·닉네임 변경·탈퇴)·US5 관리자 — backlog A항목 열거 범위(`/auth/callback`·로그인 화면·게스트·`NICKNAME_REQUIRED`·`credentials:'include'`·라우트 가드)에 없고, BE도 관리자 기능을 이연했다(../tasks.md T013·T014 Deferred). US6 월드 입장 권한은 spec 002 범위(spec FR-021d).

## Technical Context

**Language/Version**: TypeScript ~6.0 / React 19.2 (`festa-frontend`, Vite 8) — `package.json` 현행

**Primary Dependencies**: `@tanstack/react-query` 5.101, `react-router-dom` 7.18 — **신규 런타임 의존성 없음**(005 FE plan과 동일 제약)

**Storage**: Access Token은 JS 메모리만(`shared/api/client.ts:31~36` 기존 구현, docs/26 2026-08-14 결정). **Refresh Token·`oauth_handoff` cookie는 읽지도 저장하지도 않는다**(oauth-completion.md FE 의무 4) — `document.cookie` 접근·`localStorage` 토큰 저장 금지

**Testing**: vitest(`package.json` `test` 스크립트, `entities/layout/__tests__/unit/` 기존 패턴). 컴포넌트 테스트 라이브러리는 미설치 — 순수 모듈·mock 단위 테스트 + quickstart 수동 시나리오로 검증

**Mock**: `VITE_USE_MOCK=true` + `*.select.ts` 분기(`entities/layout/api.select.ts` 기존 패턴 재사용)

## Constitution Check

| 조항 | 게이트 | 판정 |
|---|---|---|
| 11 (소셜 로그인 전용) | 이메일/비밀번호 UI 없음. docs/10 §3의 `/signup` 라우트는 만들지 않는다 — spec 충돌 기록 표가 docs/04·06·08의 자체 가입 흐름을 폐기 대상으로 명시 | ✅ |
| 13 (토큰 경계) | AT는 응답 본문→메모리, RT는 HttpOnly cookie로 FE 코드가 접촉 불가. Unity로 credential 전달 없음(`client.ts:38~46` TODO(013a-AT) 경계 유지) | ✅ |
| 16 (클라이언트 불신) | 닉네임 유효성 최종 판정은 서버(FR-007e). FE는 금칙어 목록을 **번들에 전사하지 않는다** — 목록 노출 자체가 정책 위반 취지(nickname-policy.md 검사 규칙 4 "일치 단어를 노출하지 않는다"). 라우트 가드는 UX 보조이며 서버 401/403을 대체하지 않는다(docs/10 §3 "메뉴를 숨겨도 서버 인가를 대체하지 않는다") | ✅ |
| 30 (임의 확정 금지) | 계약에 없는 endpoint 경로·오류 코드는 확정하지 않고 §미결로 이관 | ✅ |

## 라우트 설계

근거: spec FR-021b(단일 `/auth/callback`), spec Assumptions:334(`/exchange`·`/registration/complete` 계약 불사용), docs/10 §3(기존 라우트 표), `app/router/index.tsx` 현행.

| 경로 | 상태 | 접근 | 내용 |
|---|---|---|---|
| `/login` | stub 교체 | 공개 | Google/Kakao 버튼 + 게스트 입장 + 실패 안내 영역(FR-007: 원인 범주·재시도 방법 표시) |
| `/auth/callback` | 신규 | 공개 | mount 시 `complete()` 1회 호출 → `AUTHENTICATED`/`NICKNAME_REQUIRED`/오류 분기 |
| `/app/*` 기존 라우트 | 가드 추가 | 표 아래 | `RequireAuth` 래퍼 |

- **소셜 버튼은 SPA fetch가 아니라 전체 페이지 이동**이다: `window.location.href = {BASE_URL}/api/v1/auth/oauth/google|kakao` — provider 인증·backend callback·302 redirect가 브라우저 내비게이션으로 일어나는 흐름이기 때문(oauth-completion.md 브라우저 흐름 §).
- `/auth/callback`은 query·fragment를 **읽지 않는다** — handoff는 HttpOnly cookie로만 전달된다(FR-021b, oauth-completion.md "`ticket` query parameter를 읽거나 전송하지 않는다").
- `AUTHENTICATED` 후 이동지는 `/app/home`(docs/10 §3 라우트 표 + `router/index.tsx:12` 기존 라우트). returnTo 보존은 범위 밖 — 필요해지면 후속.
- 가드 등급(spec Permissions Matrix): **게스트 허용** = `/app/home`·`/app/world`(공개 화면·허용된 월드 둘러보기 행) / **회원 전용** = `/app/studio/:boothId` 등 상태 변경 기능(외형·부스·코인·스튜디오 행이 게스트 차단). 게스트가 회원 전용 라우트 진입 시 "소셜 로그인이 필요하다" 안내 + 로그인 진입점(FR-011).

### `/auth/callback` 상태 기계

```text
completing ── AUTHENTICATED ──────────→ 토큰 저장 → /app/home
    │
    ├─ NICKNAME_REQUIRED ─→ 닉네임 폼 ─ 제출 → complete({nickname})
    │                          │              ├ AUTHENTICATED → 위와 동일
    │                          │              └ 검증 실패 → 폼에 일반 안내 재표시
    │                          └ (handoff는 서버가 보존 — 재제출 가능, FR-021c)
    └─ 400(handoff 누락)·410(만료·재사용) ─→ /login 이동 + OAuth 처음부터 재시작 안내
                                             (oauth-completion.md FE 의무 3)
```

- 첫 호출은 body 없이(`{}` 아님 — "Request body는 선택 사항", oauth-completion.md), `NICKNAME_REQUIRED`일 때만 닉네임 UI를 연다(FE 의무 2).
- **StrictMode 이중 effect 방어**: 기존 회원 `complete()`는 handoff를 소비하므로 두 번 호출하면 두 번째가 410이 된다. 모듈 레벨 in-flight/완료 guard로 1회만 호출한다(React 19 StrictMode는 `main.tsx:9` 현행).
- 닉네임 검증 실패 표시는 **이유를 특정하지 않는 일반 안내 + 수정 방법**만(FR-007e, nickname-policy.md 검사 규칙 4). FE 사전 검사는 공백/빈 값 수준만 한다 — 길이 제한은 계약에 없어 확정하지 않는다(§미결).

## 상태·저장 위치

| 상태 | 위치 | 근거 |
|---|---|---|
| Access Token | `shared/api/client.ts` 메모리 변수(기존) | docs/26 2026-08-14 결정, oauth-completion.md FE 의무 4 |
| 세션 종류 `anonymous \| guest \| member` | `features/auth/model/session.ts` 신규 — 모듈 store(`useSyncExternalStore`) | 가드·화면이 반응해야 하고, complete/게스트 응답에 사용자 정보가 없어 발급 경로로만 구분 가능(oauth-completion.md 응답 스키마) |
| `expiresAt` | 위 store에 함께 | complete 응답 필드(oauth-completion.md) |
| Refresh Token · handoff | **FE에 없음** | HttpOnly cookie — FE 의무 4 |
| 서버 상태(추후 me 등) | react-query | `app/providers/index.tsx` 기존 |

**새로고침 복원**: AT는 메모리라 새로고침 시 소실된다(`client.ts:33` 주석이 spec 001로 미룬 항목). 회원은 앱 부트 시 refresh endpoint를 1회 조용히 호출해 AT를 재취득한다 — RT cookie 존재를 FE가 알 수 없으므로(HttpOnly) 실패하면 조용히 `anonymous`로 둔다. rotation(FR-013c)이 서버 몫이므로 FE는 재시도 루프를 만들지 않는다. 게스트는 복원하지 않는다 — 만료·소실 시 게스트 입장을 다시 선택하는 것이 계약(FR-009a).

**401 처리**(`client.ts:65` TODO 해소): `member`면 refresh 1회 → 원요청 재시도 1회 → 실패 시 토큰·세션 클리어 후 "세션이 종료되었습니다" 재로그인 안내(US3 AS2, FR-020b — 다른 브라우저 로그인 포함). `guest`면 재발급 시도 없이 게스트 재입장 안내(FR-009a "다시 선택해"). 순환 import 방지: `client.ts`에 `setUnauthorizedHandler(fn)` 등록 지점만 두고 refresh 로직은 `features/auth`가 주입한다.

## 계약 의무 이행 방법 (oauth-completion.md FE 의무 4항 전체)

1. **`credentials: 'include'`** — `complete`·refresh·logout 호출에 명시 전달. `api()`는 `init`을 spread하므로(`client.ts:59~63`) 호출부 인자로 충분하고 전역 기본값 변경은 하지 않는다(RT cookie Path가 갱신 endpoint로 제한되어 있어 다른 요청엔 무의미 — FR-013b).
2. **`NICKNAME_REQUIRED`일 때만 닉네임 UI** — 상태 기계에서 해당 분기에서만 폼 렌더.
3. **400/410 → 로그인 화면 + OAuth 재시작** — `/login`으로 navigate, 안내 문구 표시. `isApiError`(`client.ts:20`)로 봉투 식별.
4. **AT 본문 수신·메모리 보관, RT·handoff 비접촉** — `setAccessToken()`만 사용, cookie·storage API 접근 금지(리뷰 체크 항목으로 tasks에 명시).

## Mock 전략 — 실서버 없이 완전 개발

기존 `VITE_USE_MOCK` + `*.select.ts` 패턴 그대로(`entities/layout/api.select.ts`, `.env`에 `VITE_USE_MOCK=true` 현행).

- `entities/auth/api.select.ts`가 `api.ts`(real) / `api.mock.ts`를 분기.
- **provider 왕복 재현**: mock 모드에서 `/login`의 소셜 버튼은 provider로 가는 대신 mock 내부 상태(sessionStorage — layout mock의 `festa-mock-layout-drafts` 휘발성 근거와 동일)에 handoff를 심고 `/auth/callback`으로 navigate한다. FE 소비 코드는 real/mock 어느 쪽에서도 동일 경로를 탄다.
- **신규/기존 회원 시나리오**: mock이 "가입된 계정" 집합을 sessionStorage에 유지 — 최초 로그인은 `NICKNAME_REQUIRED`, 닉네임 제출 성공 시 가입 기록 후 `AUTHENTICATED`, 재로그인은 즉시 `AUTHENTICATED`(US1 AS1·2·7 재현).
- **handoff 시맨틱 재현**: 기존 회원 성공·닉네임 제출 성공 시 handoff 소비(재호출 → 410), `NICKNAME_REQUIRED`는 보존(FR-021c). handoff 없이 `/auth/callback` 직진입 → 400. 이로써 FE 의무 3 경로를 브라우저에서 검증 가능.
- **닉네임 검증 mock**: 금칙어 전체 목록 전사 금지(헌법 16조 판정 참조). 대표 케이스 소수(`관리자`(RESERVED)·`admin`·정규화 우회 1건)만 하드코딩해 "일반 안내" UX를 재현하고, 목록 완전성 검증은 서버 테스트 몫임을 주석으로 명시.
- **게스트 mock**: fake AT 발급 + `expiresAt` 30분(FR-009a). 만료 재현용으로 짧은 TTL sentinel 시나리오 제공(layout mock의 `LEASE_EXPIRED_BOOTH_ID` sentinel 패턴).
- **401·세션 종료 재현**: mock에 "다른 브라우저 로그인" 트리거를 두어 이후 refresh가 실패하게 함(US3 AS4·FR-020b UX 검증).

## 기존 코드와의 접점

| 파일 | 변경 | 내용 |
|---|---|---|
| `app/router/index.tsx` | 수정 | `/login` stub 교체, `/auth/callback` 추가, `RequireAuth` 래퍼 적용(005 plan이 남긴 "Owner/Staff Guard는 spec 001 인증 확정 후 추가" TODO 중 인증 가드 몫) |
| `shared/api/client.ts` | 수정 | 401 인터셉트 + `setUnauthorizedHandler` 등록 지점(`:65` TODO 해소). `setAccessToken`/`getAccessToken`은 그대로 재사용 |
| `pages/login/LoginPage.tsx` | 신규 | 로그인 화면 |
| `pages/auth/CallbackPage.tsx` | 신규 | 상태 기계 + 닉네임 폼 조립 |
| `features/auth/model/session.ts` | 신규 | 세션 store + 부트스트랩 복원 + 401 핸들러 주입 |
| `features/auth/ui/NicknameForm.tsx` | 신규 | 닉네임 입력 + 일반 안내 표시 |
| `app/router/RequireAuth.tsx` | 신규 | 가드(게스트 허용/회원 전용 2등급) |
| `entities/auth/{types,api,api.mock,api.select}.ts` | 신규 | 계약 타입 전사 + real/mock |

`TODO(013a-AT)`(`client.ts:38`) Unity credential handoff는 이 plan에서 건드리지 않는다 — #60에서 BE로 이관된 미결.

## 미결 / 후속 (추측 금지 항목)

| 항목 | 현황 | 처리 |
|---|---|---|
| 게스트 입장·refresh·logout endpoint 경로 | 확정 계약 3종에 없음. docs/08 §2(`/auth/refresh`·`/auth/logout`)는 spec 충돌 기록 표에서 "계약 재작성 대상" | mock으로 선개발. real `api.ts`의 해당 함수는 경로 미확정 명시 오류로 두고, BE 계약 회수 후 경로만 기입(tasks T016) |
| 세션 종료 사유별 오류 코드(다른 브라우저 로그인 등) | 계약에 코드 미정의 | 서버 `message` 표시로 시작, 코드 확정 시 분기 추가 |
| 닉네임 길이·문자 제한 | nickname-policy.md에 없음 | FE 사전 검사는 공백/빈 값만. 서버 응답으로 처리 |
| US4 마이페이지·닉네임 변경·탈퇴, US5 관리자 | backlog A항목 범위 외, BE 관리자 이연 | 별도 backlog 항목으로 |
| `AUTHENTICATED` 후 returnTo 복귀 | 계약·spec 무관 UX | 필요 시 후속 |
