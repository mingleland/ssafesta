# 001 Auth FE — 수동 검증 Quickstart

> S-20260823-53 구현분의 브라우저 수동 검증 절차. 실서버 없이 mock으로 전 시나리오를 돌린다.
> 자동 검증(vitest 128·typecheck·lint)과 별개의 사람 확인용이다.

## 기동

```bash
cd festa-frontend
npm run dev
```

- mock은 `.env.local`의 `VITE_USE_MOCK=true`로 켠다 (현행 기본). 분기는 `src/entities/auth/api.select.ts`.
- mock 상태는 모듈 스코프 in-memory + sessionStorage 백업 — 탭 새로고침에도 시나리오가 이어진다.

## 시나리오 8종

### 1. 최초 가입 (NICKNAME_REQUIRED)
`/login` → Google 또는 Kakao 버튼 → (mock이 handoff 발급 후 `/auth/callback`으로 이동)
→ 닉네임 폼 노출 확인 → 유효한 닉네임 제출 → `/app/home` 진입.

### 2. 재로그인 (AUTHENTICATED 즉시 통과)
시나리오 1 완료 상태에서 로그아웃 → 같은 provider로 다시 로그인
→ 닉네임 폼 없이 `/auth/callback`에서 바로 `/app/home`.

### 3. 닉네임 실패 → 재제출
닉네임 폼에서 금칙 케이스(`관리자`, `admin`, 정규화 우회 변형) 제출
→ **이유를 특정하지 않는 일반 안내**만 노출되는지 확인 (목록·사유 노출 금지가 정책 취지)
→ handoff는 보존되므로 같은 화면에서 다른 닉네임으로 재제출 → 성공.

### 4. 400 / 410 → `/login` 재시작
`/auth/callback`에 handoff 없이 직접 진입(주소창 입력) → 400 계열 안내 + `/login` 재시작 유도.
handoff 만료·재사용(mock 시맨틱) → 410 계열 안내 + 동일 재시작 유도.

### 5. 게스트 입장 · 만료 · 제한 라우트
`/login` → 게스트 입장 → `/app/home`·`/app/world` 접근 가능 확인.
회원 전용(`/app/studio/:boothId`, 게임 라우트) 접근 → `/login` 리다이렉트 확인.
게스트 만료(mock 30분 TTL — devtools에서 시간 조작 또는 mock 상태 클리어) → 즉시 세션 클리어 + 안내, 자동 재발급 없음(재입장이 계약).

### 6. 새로고침 복원
회원 로그인 상태에서 새로고침 → 부트스트랩 refresh 1회로 세션 복원(회원만).
게스트 상태에서 새로고침 → 복원 없이 재입장 유도(계약대로).

### 7. 다른 브라우저 로그인 → 기존 세션 종료
devtools 콘솔에서 `window.__festaTriggerOtherBrowserLogin()` 호출 후 **새로고침**
→ 복원 refresh가 실패해 `/login`으로 돌아온다(mock은 즉시 강제 이동시키지 않는다 —
플래그를 세워 다음 refresh/401부터 실패시키는 방식이고, 보호 요청 중의 401→종료 경로는
`unauthorizedHandler` vitest가 검증한다).

### 8. cookie / storage 미접촉
devtools Application 탭에서 확인:
- FE 코드가 RT cookie를 읽거나 쓰지 않는다 (HttpOnly 전제, 코드에 cookie 접근 0 — `grep -r document.cookie src/` 0건)
- AT는 localStorage/sessionStorage에 저장되지 않는다 (메모리 보관 — 새로고침 시 사라지고 refresh로만 복원)

## 알려진 미결 (검증 대상 아님)

- 게스트·refresh·logout endpoint 실경로: 기입 완료(`/api/v1/auth/guest`·`/refresh`·`/logout`, `S15P21A604-90`). 계약 문서는 없고 backend `GuestAuthController` 가 정본이다. 실서버 왕복 검증은 OAuth 자격증명(`-274`) 대기.
- 게임 라우트(`/app/games/:gameId/edit|play`) 가드 등급: plan 미명시로 보수적 member-only — 재분류 가능성 `router/index.tsx` 주석 참조.
- 오류 code 값(`OAUTH_HANDOFF_*`, `NICKNAME_REJECTED`)은 관례 명명 — BE 확정 시 mock과 함께 갱신.

---

## 검증 결과 (2026-08-23 실행)

- [x] **판정: 8/8 통과** — mock 모드 브라우저 실측. ①최초 가입 ②재로그인 즉시 인증 ③금칙 닉네임 일반 안내→재제출 성공 ④handoff 소비 후 직진입 410 안내 ⑤게스트 입장·회원 전용 라우트 차단 안내(SPA 내 이동 기준 — 전체 리로드는 FR-009a대로 게스트 미복원) ⑥새로고침 후 member 유지 ⑦다른 브라우저 트리거→새로고침→`/login` ⑧cookie 빈 값·localStorage 빈 값·sessionStorage는 mock 상태 키뿐
- 실행 중 발견·수정 2건: ⑥이 최초 실측에서 실패(부트스트랩 refresh와 가드 redirect의 레이스, [T-17](../../../docs/LJH/25_트러블슈팅.md)) — `bootstrapped` 플래그로 가드 판정 보류 후 통과. ⑦은 문서 절차가 구현과 불일치(즉시 이동 아님)라 본 문서 절차를 정정
- 자동 게이트: `tsc -b`·`oxlint`·vitest 129/129
