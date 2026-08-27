# S15P21A604-87 — BoothSlot 목록·상태 조회 실서버 검증

> 검증일: 2026-08-27 | 검증자: 이정헌 | develop 기준 `25043c3` | 대상: `GET /booth-slots` · `GET /booths/mine`

## 환경

배포된 실서버 주소가 아직 없어 **로컬 BE 기동**으로 검증했다(`backend/compose.yaml` + `mvnw spring-boot:run`, `localhost:8080`). FE 는 `VITE_USE_MOCK=false` · `VITE_API_BASE_URL=http://localhost:8080`.

기동 중 `app.world.connection-token-secret must decode to at least 32 bytes` 로 한 번 실패했다 — `-84`(World Session 발급 API)가 develop 에 들어오면서 `CONNECTION_TOKEN_SECRET` 이 새 필수 항목이 됐다. **로컬 서명키라 발급받을 자격증명이 아니다** — 48바이트 난수의 base64 로 채웠고 `.env` 는 커밋되지 않는다(`backend/.gitignore:2`).

## 실서버 응답 실측

```http
GET /api/v1/booth-slots        (무인증)  → 200, 12건
POST /api/v1/auth/guest                  → 200 (accessToken·expiresAt)
GET /api/v1/booths/mine        (guest)   → 403 MEMBER_ONLY
```

```json
{"slotId":1,"slotCode":"F11-R01","floorNo":11,"type":"USER_RENTAL","status":"AVAILABLE",
 "boothId":null,"boothName":null,"leaseEndsAt":null,"remainingSeconds":null,
 "entryAvailable":false,"mine":false}
```

- **필드 11개가 FE `SlotView` 와 정확히 일치한다.** 타입 수정 없음
- `status` 는 12건 전부 `AVAILABLE` — 로컬 DB 가 비어 있어 임대가 하나도 없다
- `GET /booths/mine` 게스트 403 은 FE 가 이미 알고 있다 — `SlotListPage` 가 `enabled: isMember` 로 **요청 자체를 만들지 않는다.** 실측에서도 게스트 세션에서 이 요청이 나가지 않았다

## 브라우저 관통 (게스트)

`/app/booths` 로 직접 들어가면 가드가 `/login` 으로 보내고, 게스트 입장 후 **원래 목적지로 복귀**했다(G-2 returnTo 동작 재확인). 슬롯 12칸이 실서버 응답으로 렌더되고 각 칸에 `임대 가능` + 게스트 안내(`임대는 소셜 로그인 회원만 가능합니다.`)가 붙었다. `WalletBadge` 는 게스트라 렌더되지 않았다(정상).

네트워크 실측:

```text
POST /api/v1/auth/refresh  → 500   ← BE 결함(아래)
POST /api/v1/auth/guest    → 200
GET  /api/v1/booth-slots   → 200
OPTIONS /api/v1/booth-slots→ 200   (CORS preflight 정상)
```

## 완료 조건 판정

| 완료 조건 | 실서버 | 대체 검증 |
|---|---|---|
| 슬롯 상태 3종이 구분 표기 | **AVAILABLE 만** 재현됨 | 컴포넌트 테스트로 3종 고정 |
| 만료 임박(`leaseEndsAt`) 표시 | 재현 불가 | 컴포넌트 테스트(남은 시간·만료 2케이스) |
| 로딩·오류 상태 처리 | 재현 불가 | 컴포넌트 테스트 |

**왜 실서버로 못 했는가 — 두 가지가 서로 다른 이유로 막는다.**

1. `OCCUPIED`·`mine` 은 **회원 임대가 있어야 생긴다.** 게스트는 임대할 수 없고(`MEMBER_ONLY`), 회원 세션은 **D5(OAuth 자격증명 6종) 부재**로 만들 수 없다. DB 에 직접 행을 넣어 만들 수도 있지만 그것은 화면 렌더만 확인할 뿐 임대 경로를 검증하지 않으므로 하지 않았다.
2. 오류 분기는 **세션이 메모리 전용**이라 재현이 구조적으로 불가능하다 — API 를 죽이면 게스트 로그인 자체가 실패해 페이지에 도달하지 못한다.

그래서 `src/pages/booth/__tests__/unit/SlotListPage.test.tsx` 6건으로 고정했다(`-153` 로 들어온 G-4 환경의 첫 페이지 테스트).

**실효성 실측** — 구현을 일부러 깨뜨려 테스트가 잡는지 확인했다.
- `isError` 분기 제거 → `조회 실패는 조용히 빈 목록으로…` 실패 ✅
- `mine` 분기 제거 → **처음엔 통과했다.** fixture 의 부스 이름이 `'내 부스 이름'` 이라 `'내 부스'` 가 부분문자열로 겹쳤다. 이름을 `'내가 빌린 부스'` 로 바꾸고 `not.toContain` 을 더한 뒤 재실측하니 실패했다 ✅

## BE 결함 재확인 (blocker 아님, BE 소관)

`POST /api/v1/auth/refresh` 가 **쿠키 없이 호출되면 500** 이다. `bootstrapAuth` 가 새로고침마다 시도하므로 **비로그인·게스트 상태에서 매 페이지 로드마다 서버 500 이 찍힌다** — 이번엔 브라우저 네트워크 로그로 3회 관측했다. 계약상 4xx 가 맞다고 보고 `-90` 에서 이미 BE 판단을 요청했다. FE 가 호출을 건너뛸 수는 없다 — HttpOnly 쿠키의 존재 여부를 FE 코드가 알 수 없기 때문이고, 그것이 그 쿠키 설계의 의도다.

## 별건으로 남긴 FE 결함 2건

- **`/` 경로에 라우트가 없다.** 도메인 루트로 들어오면 React Router 기본 ErrorBoundary 가 영어 404 를 그린다(`No route matches URL "/"`). 08-27 `-113` 스모크 테스트도 이걸 보고 "기존 동작"으로 넘겼다 — 기존 동작인 것은 맞지만 정상은 아니다.
- **조사 결함** — `RequireAuth` 헤더가 `회원로 이용 중` 으로 렌더된다(`{kind}` 보간). `RequireAuth.test.tsx` 주석에 이미 적혀 있다.

둘 다 `-87` 범위 밖이라 이 MR 에 섞지 않았다.

## 검증 수치

```text
develop 25043c3 baseline : 51 files / 277 tests · tsc 0 · oxlint 0 · build 176ms
이 브랜치               : 52 files / 283 tests · tsc 0 · oxlint 0 · build 195ms
```
