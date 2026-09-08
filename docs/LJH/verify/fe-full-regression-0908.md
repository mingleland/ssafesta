# FE 전체 회귀 실측 — 2026-09-08 (S15P21A604-538)

> **이 회귀의 목표는 "될 것만 확인" 이 아니다.** 가능한 건 끝까지 관통하고, 안 되는 건 **어느
> 포인트에서 막히는지 재현 가능한 형태로** 남긴다.

## 결과 어휘

```text
PASS                관통했다
FAIL                관통 시도했고 기대와 다른 결과가 나왔다 (= 결함)
BLOCKED_AT_<POINT>  관통 시도했고 외부 조건에서 막혔다 — 6항(재현·마지막 성공·최초 실패·
                    원문·관련 파일·다음 필요 조건)을 반드시 적는다
NOT_EXECUTED        시도하지 않았다 — 사유를 적는다
```

## 총계

| | 건수 |
|---|---|
| **PASS** | 79 |
| **FAIL** | 5 |
| **BLOCKED_AT_** | 4 |
| **NOT_EXECUTED** | 2 |

**`BLOCKED_AT_` 4건의 구성**(§4) — 세는 기준을 적어 둔다. 처음 이 표를 쓸 때 구성을 안 적어
헤딩 수(6)와 숫자(4)가 어긋나 보였다.

```text
BLOCKED_AT_PROVIDER_LOGIN_CREDENTIAL_REQUIRED   Google OAuth
BLOCKED_AT_SERVER_BOOT                          AI FastAPI
BLOCKED_AT_TAB_NOT_VISIBLE                      #73 활성 탭 성능
BLOCKED_AT_REAL_ADAPTER_ABSENT                  Consultation

세지 않는 것
  BLOCKED_AT_PREVIEW_CWD_PINNED   내 도구 제약이지 검증 대상의 상태가 아니다 (기록용)
```

> **회귀 종료 뒤 1건이 PASS 로 바뀌었다** — SSAFY OAuth(`#114`)를 같은 날 16:45 에 관통했다(§4).
> BE 회신이 host 를 바꾸지 않고 판정하는 절차를 줬고, `POST /auth/oauth/complete` 가
> `200 NICKNAME_REQUIRED` 를 냈다. **`PASS 78 · BLOCKED 5` → `PASS 79 · BLOCKED 4`.**
> 이 문서의 판정은 그 시점 기준으로 갱신돼 있다.

**FAIL 은 이 회차에서 고치지 않고 티켓으로 분리했다** — 검증과 수정을 한 MR 에 섞으면
"무엇을 검증했나" 가 diff 에 묻힌다.

| 티켓 | 담는 것 |
|---|---|
| `S15P21A604-541` | F-1 ~ F-4 — Survey 오류·상태 표시 결함 4건 |
| `S15P21A604-543` | F-5 — 코인 내역 라벨 누락(`SURVEY_REWARD`·`PURCHASE`) |
| `S15P21A604-539` | 회귀 착수 중 발견한 **develop 빌드 깨짐**. 회귀가 그 위에서 진행될 수 없어 먼저 고쳤다(`!473` 머지) |

---

## 0. 검증 대상 고정 — 게이트 5개

**"서버가 떴다" 가 아니라 "내가 검증하려는 코드와 설정이 실제로 브라우저에 올라왔다" 를 먼저 증명했다.**
게이트를 통과하기 전에 얻은 값은 전부 폐기했다(아래 `BLOCKED_AT_PREVIEW_CWD_PINNED` 참조).

| # | 확인 | 결과 |
|---|---|---|
| 1 | 5173 서빙 워크트리·브랜치·HEAD | PID 38292 = `ssafesta-oauth/festa-frontend` · `docs/S15P21A604-538-…` · HEAD `e1279c65` == `origin/develop` **PASS** |
| 2 | 런타임 값(브라우저에서 실제로 쓰는 것) | `window.__FESTA_CONFIG__ = {}` (덮어쓰기 없음) · `apiBaseUrl()` = `http://localhost:8081` **PASS** |
| 3 | DEV 플래그 실반영 | `IS_DEV_INTERACTION_BAR=true` · `IS_DEV_ENTRY=true` · `IS_MOCK_WORLD` 는 Phase 별로 전환 **PASS** |
| 4 | 세션·Origin | 브라우저 전용 refresh 토큰을 curl 것과 **분리** 발급(T-71) → 개발자 진입이 실 BE `/auth/refresh` 200 **PASS** |
| 5 | Survey 방문자 선행조건 | booth 1 `lease=ACTIVE` · `published_layout_version=1` · survey OPEN **PASS** |

`aiApiBaseUrl()` 과 `unityBuildBase()` 는 게이트 시점에 빈 문자열이었다 — 각각 Phase 2-B·5 에서 다뤘다.

### 환경 실측치

```text
Spring 8081   내 것. develop head(e1279c65) 로 재빌드. OpenAPI 46 paths. V23 적용됨
Spring 8080   타 세션. OpenAPI 41 paths — survey 0건(머지 이전 빌드). frontend-base-url=dev.localhost:5174
Vite 5173     ssafesta-oauth. 5174=ssafesta-25dv, 5175=.claude/worktrees/…-527 (둘 다 타 세션)
포트 8000     Unity WebGL 정적 서버. 계획 시점엔 살아 있었고 세션 도중 죽어 내가 대신 띄웠다
E2E 도구      Playwright·Cypress 없음(vitest + testing-library 뿐) → 브라우저는 실 Chrome
```

### 기준선

```text
착수 시       vitest 144 files / 917 tests  ·  npm run build ✓      (develop 0ca8e782)
develop 갱신  vitest 145 files / 915 tests 중 3 failed · build 실패  ← S15P21A604-539 로 발견·수정
수정 후       vitest 145 files / 919 tests  ·  npm run build ✓      (develop e1279c65)
```

`npm run build` 가 `tsc -b && vite build` 라 **그것이 타입 게이트**다. `tsconfig.json` 이 project
references 를 쓰므로 루트 `tsc --noEmit` 은 참조 프로젝트를 같은 방식으로 검사하지 않는다 — 보조로만 썼다.

`rootRoute` known flake(T-67)는 이번 회차에서 **한 번도 재현되지 않았다**(3회 실행 전부 0 failed).

---

## 1. Survey 계약 전수 (curl) — **47/47 PASS**

오류 11종을 도메인으로 갈랐다. **Survey 어댑터가 11종을 다 소유하지 않는다.**

### Survey-native (6) — 어댑터·화면이 직접 책임진다

| code | 재현 | 결과 |
|---|---|---|
| `SURVEY_NOT_FOUND` | 설문 없는 부스 편집자 조회 / 없는 surveyId 결과 조회 | PASS ×2 |
| `SURVEY_CLOSED` | `ends_at` 과거로 두고 제출 | PASS |
| `SURVEY_ALREADY_RESPONDED` | 같은 회원 재제출 | PASS |
| `SURVEY_LOCKED` | 응답 있는 설문의 문항 문구 변경 | PASS |
| `VALIDATION_FAILED` | PUT 13케이스 + 제출 8케이스 | PASS ×21 |
| `MEMBER_ONLY` | 게스트 × 보상 설문 제출 / 게스트가 편집 API 호출 | PASS ×2 |

### Dependency-derived (5) — Booth·Layout·Wallet 에서 파생돼 지나갈 뿐이다

| code | 재현 | 결과 |
|---|---|---|
| `BOOTH_EDITOR_FORBIDDEN` | 남의 부스(user 900001 소유)에 쓰기·읽기 | PASS ×2 |
| `BOOTH_NOT_FOUND` | boothId 99999 | PASS |
| `BOOTH_LEASE_EXPIRED` | 임대 `ends_at` 과거로 밀고 쓰기 | PASS |
| `LAYOUT_NOT_PUBLISHED` | 게시본 없는 부스의 방문자 경로 | PASS |
| `WALLET_NOT_FOUND` | 지갑 없는 회원(user 900002)이 보상 설문 제출 | PASS |

### PUT 검증 13 · 제출 검증 8 — `errors[0].field` 까지 대조

```text
PUT   title 공백/201자 · description 2001자 · rewardCoin -1 · closesAt 과거 · questions []
      type 미지값 · prompt 공백 · options 1개 · 비선택형 options · RATING scale 없음
      비RATING scale 있음 · 선택지 label 공백
제출  미지 questionId · 필수 누락 · 유형 불일치 키 · 타 문항 optionId
      SINGLE 2개 · MULTIPLE 중복 · rating 범위 밖 · text 201자
```

전부 `400 VALIDATION_FAILED` 이고 `field` 가 계약이 적은 자리를 정확히 가리켰다
(`questions[0].options`·`answers[1].rating` 등).

### 이번 어댑터의 핵심 계약 2개

- **T-97 키 부재 보존** — `rewardCoin` 키를 안 보내면 저장본 값이 유지된다. DB 값을 저장 전후로 읽어 대조. **PASS**
- **`getDraft` 404 분기** — `SURVEY_NOT_FOUND` 와 `BOOTH_NOT_FOUND` 가 같은 404 인데 code 로 갈린다. **PASS**

---

## 2. Survey 브라우저 E2E (실 Chrome · 실 BE)

### PASS

| 항목 | 근거 |
|---|---|
| Run 6유형 렌더 | radio 2 · checkbox 3 · 별점 1~5 · 단답 · 장문 · 지원서(`지원 내용을 입력하세요`) |
| 필수 미답 차단 | `0 / 6 답변` · `필수 문항 2개가 남았습니다` · 제출 버튼 disabled |
| **C-2 `questionId` 보존**(`-531`) | 주관식이 `문항 12`·`문항 13`·`문항 14` 로 구분된다. `items: string[]` 이었다면 불가능 |
| **C-3 응답 시각 2필드**(`-531`) | `최초 26. 9. 8. 오후 1:56 · 최근 … 오후 1:56` (KST 변환) |
| 분모 `answeredCount` | `선택 분포 응답 2건` · `별점 4.0 평균 · 응답 2건` |
| 0응답 결과 화면 | `아직 응답이 없습니다` — `perQuestion` 이 6문항을 실어 와도 빈 상태로 잡힌다(`-528` 이 `totalResponses === 0` 으로 바꾼 자리) |
| **빈 라벨 거부**(`-531`) | 선택지를 공백으로 만들면 `설문 저장` 이 잠기고, 되돌리면 풀린다 |
| 구조 잠금 → 해제 후 저장 | 응답 있을 때 구조 변경 실패 → 응답 삭제 후 `저장했습니다` + dirty 해제 |
| 게스트 × 보상 사전 안내 | `코인이 걸린 설문이라 로그인해야 참여할 수 있습니다` + 제출 DISABLED, 문항은 그대로 보인다 |
| 게스트 정상 제출 | `respondent_guest_key=guest:33ae5075…` · 원장 없음(보상 0) · **V23 `respondent_session_expires_at` 채워짐**(`-523` 신규 동작) |
| 회원 보상 지급 | `7 코인을 받았습니다` · 원장 `REWARD 7 · balance_after 307 · SURVEY_REWARD:1:1` · wallet 300→307 |

### FAIL

#### F-1. 서버 오류 코드·문구를 버리고 일반 문구만 낸다 — **제출·저장 양쪽**

| 경로 | 서버 | 화면 |
|---|---|---|
| 제출 | `409 SURVEY_ALREADY_RESPONDED` / `이미 응답한 설문입니다.` | **`제출하지 못했습니다. 다시 시도해 주세요.`** |
| 저장 | `409 SURVEY_LOCKED` / `응답이 있는 설문은 문항을 바꿀 수 없습니다.` | **`저장하지 못했습니다.`** |

`run.ts:117` 과 `builder.ts` 의 `catch {}` 가 오류를 통째로 버리고, `SurveyOverlay.tsx:133` 이 문구 하나만 낸다.
**서버는 사용자에게 그대로 보여줄 한국어 문구를 이미 주고 있다.**

특히 `다시 시도해 주세요` 는 **재시도해도 절대 성공하지 않는 오류에 재시도를 권한다.**
`-528` 계획의 *"오류 문구를 코드별로 가른다 — `SURVEY_CLOSED`·`SURVEY_ALREADY_RESPONDED`·`MEMBER_ONLY`·`SURVEY_LOCKED`"*
가 구현되지 않았다. 내 누락이다.

#### F-2. 마감된 설문에서 문항이 사라진다 — **계약 §5 위반**

계약: *"`closed: true` 면 문항은 그대로 싣는다 — 화면이 '마감된 설문입니다' 를 보여주되 **무엇을 물었는지는 남는다**"*

실측: 서버는 6문항을 싣는데(계약 G1 PASS) 화면의 `li` 개수가 **0**, 입력 요소 **0**.
`SurveyOverlay.tsx` 가 문항을 `run.status === 'ready'` 일 때만 렌더하고 `closed` 는 `OverlayEmpty` 만 그린다.

#### F-3. 마감 상태인데 "참여하면 N 코인을 받습니다" 를 동시에 띄운다

```
마감된 설문입니다 / 응답을 더 받지 않습니다.
참여하면 5 코인을 받습니다 · Esc 로 월드로 돌아갑니다     ← 상충
```

상단 안내 체인(`SurveyOverlay.tsx:130-142`)에 `closed` 분기가 없다.

#### F-4. 제출 성공 뒤에도 "참여하면 N 코인을 받습니다" 가 남는다

F-3 과 같은 뿌리 — 그 체인에 `submitted` 분기도 없다.

```
응답을 제출했습니다 / 7 코인을 받았습니다. 참여해 주셔서 감사합니다.
참여하면 7 코인을 받습니다 · Esc 로 월드로 돌아갑니다     ← 이미 받았는데 또 권한다
```

#### F-5. 코인 내역에 `SURVEY_REWARD` 한글 라벨이 없다

`/app/profile` 거래내역이 다른 행은 `일일 지급`·`가입 지급` 인데 설문 보상만 **`SURVEY_REWARD`** 로 보인다.

```text
entities/wallet/types.ts:36  REASON_LABELS = { INITIAL_GRANT, DAILY_GRANT, ADMIN_ADJUSTMENT, LEASE_PAYMENT }
BE CoinReason                { INITIAL_GRANT, DAILY_GRANT, ADMIN_ADJUSTMENT, PURCHASE, SURVEY_REWARD }
                             + LEASE_PAYMENT (BoothLeaseService.LEASE_REASON)
→ 누락 2건: SURVEY_REWARD · PURCHASE
```

fallback(`?? reasonType`)이 항목을 숨기지 않는 것은 **의도된 설계**(SC-005)라 데이터 유실은 없다.
다만 이 경로는 `-528` 이 실 어댑터를 붙이면서 **처음 도달 가능해졌다** — 그래서 지금 드러났다.

### NOT_EXECUTED

- **C-1 저장 중 부스 전환 가드**(`-531`) — 브라우저에서 "저장 응답이 도착하기 전에 부스를 옮긴다" 를
  손으로 재현할 창이 너무 짧다. `builder.test.ts` 의 회귀 테스트가 응답 promise 를 붙잡아 이미 잠그고
  있고, 그 테스트는 가드를 되돌리면 실제로 red 가 되는 것을 확인하고 넣은 것이다.

---

## 3. route 12 + 오버레이 회귀

각 화면을 `ENTRY / AUTH / DATA / RENDER / INTERACTION / CONSOLE / NETWORK` 로 봤다.
`CONSOLE` 은 에러 0, `NETWORK` 는 의도치 않은 4xx/5xx 0 을 뜻한다.

| route | 판정 | 근거 |
|---|---|---|
| `/` | PASS | Landing 렌더 |
| `/login` | PASS | SSAFY·Google·Kakao·게스트 4버튼 + 개발자 진입(`.dev-entry-btn`) |
| `/auth/callback` | PASS(경로 도달) | SSAFY 관통에서 실제로 도달했다 — §4 참조 |
| `/app/home` | PASS | `<Navigate to="/app/world" replace />` — 라우터에 명시된 의도된 리다이렉트 |
| `/app/profile` | PASS | `users/me`·`wallets/me`·`transactions` 200 · 307 코인 · 내역 4행 |
| `/app/booths` | PASS | `booth-slots` 200 · 내 부스 F11-R01 · 임대 가능 9곳 |
| `/app/booths/1/project` | PASS | 실 BE · 프로젝트 0건 빈 상태 |
| `/app/booths/1/survey` | PASS/FAIL | §2 |
| `/app/booths/1/consultation` | PASS(mock) | **8081 호출 0건** — §4 BLOCKED 참조 |
| `/app/studio/1` | PASS | `layouts/draft` 200 · `공개 1회차` · `1 / 12` · 팔레트·3D 미리보기 |
| `/app/world` | PASS | mock 월드 + DEV 상호작용 바 6버튼 / 실 Unity WebGL(§5) |
| `/app/games/1/edit` | PASS | Game Studio · scene 3개 · 팔레트 |
| `/app/games/1/play` | PASS | published 게임 구동(`열쇠와 문` · ♥3/3 · INVENTORY) |

오버레이: `SURVEY`(§2) · `PROJECT`·`LAPTOP`·`AI_CHAT`·`GAME` 진입 버튼 노출 확인,
`CONSULTATION` 은 mock 렌더까지.

### 관측 1건 — 재현 안 됨

`GET /api/v1/games/1/published` 가 페이지 로드 중 **503 한 번** 뒤 200. curl 6회 재시도 전부 200.
Spring 로그를 확인하려 했으나 내가 그 프로세스를 `| tail -400` 으로 띄워 **버퍼링 때문에 0바이트**라
읽을 수 없었다(내 실수). **원인 미규명**으로 남긴다 — 결함이라 단정하지 않는다.

### 접근성 소견 1건

Builder 의 문항·선택지 입력이 `<label>`·`aria-label` 없이 **placeholder 를 유일한 접근명으로 쓴다**
(`textbox "질문을 입력하세요" placeholder="질문을 입력하세요"`). 값은 정상이다 — 접근성 문제다.
`#73` 편집기 접근성 잔여와 같은 묶음.

---

## 4. 관통 시도 — 막힌 지점

### BLOCKED_AT_PROVIDER_LOGIN_CREDENTIAL_REQUIRED — Google OAuth

```text
1. 재현        http://localhost:8080/oauth2/authorization/google
2. 마지막 성공  accounts.google.com/v3/signin/accountchooser 도달.
               앱 이름 "SSAFESTA" 표시 — client_id 298980694069-… 가 정상 등록돼 있다.
               **Google 이 redirect_uri_mismatch 를 내지 않았다** → 8080 콜백이 콘솔에 등록돼 있다는 실측 근거
3. 최초 실패    계정 선택 화면에 로그인된 Google 계정이 없다
4. 원문        (오류 없음 — 로그인 요구 화면)
5. 관련        backend application.yml `spring.security.oauth2.client.registration.google`
6. 다음 조건    사람이 Chrome 에 Google 계정으로 로그인해야 한다.
               나는 자격증명을 입력하지 않는다. OAuth 동의 승인도 사용자 결정 사항이다
```

### PASS — SSAFY OAuth (`#114`) · **회귀 당일 늦게 관통했다**

> 이 항목은 회귀 시점에 `BLOCKED_AT_LOCAL_SESSION` 이었다. **같은 날 16:45 에 관통해 PASS 로 바꾼다** —
> 낡은 BLOCKED 를 남겨 두면 다음 사람이 그것을 근거로 쓴다(오늘 `#136`·`#131` 에서 겪은 자리다).

BE 회신(`#114`, 09-08 16:31)이 **host 를 바꾸지 않고 판정하는 절차**를 줬다
(`docs/25_트러블슈팅.md:703`, T-104). 그대로 따랐다.

```text
1. http://localhost:8080/oauth2/authorization/ssafy
2. SSAFY SSO 통과 → 콜백 → dev.localhost:5174/auth/callback ("입장 정보가 만료되었어요")
   ← 이 시점에 handoff 쿠키는 이미 localhost 에 있다
3. 같은 오리진 http://localhost:8080/swagger-ui/index.html 로 이동
4. POST /api/v1/auth/oauth/complete

   HTTP 200   { "status": "NICKNAME_REQUIRED", "accessToken": null }
```

```text
SSAFY provider SSO         PASS
Spring callback            PASS
TOKEN_EXCHANGE             PASS
POST /auth/oauth/complete  PASS   200 NICKNAME_REQUIRED
남은 것                     닉네임 등록 한 단계 — 계정 생성이라 하지 않았다
```

`users = 1` · `oauth_identities = 0` 그대로이고 handoff 는 `REGISTRATION|SSAFY|<subject>` 로 남아 있다
(`peekRegistration` 이라 complete 를 여러 번 불러도 소비되지 않는다 — 설계대로다).

**따라서 `400 OAUTH_HANDOFF_MISSING` 은 OAuth 경로의 결함이 아니라 host 하나였다.**

```text
등록된 redirect_uri   http://localhost:8080/login/oauth2/code/ssafy    host = localhost
handoff 쿠키          host-only (Domain 속성 없음) → localhost 에만 붙는다
타 세션 FE/API base   dev.localhost:5174 / dev.localhost:8080          host = dev.localhost
→ 쿠키가 host 를 건너가지 못한다 → complete 가 handoff 없이 도착 → 400
```

#### 회귀 시점 판정에서 두 가지가 좁았다 — BE 지적

1. **쿠키 host-only 는 설계다.** 저장소의 쿠키 5곳 전부 `.domain()` 이 없어, 같은 조건이면
   `refresh_token` 도 실리지 않아 게스트 로그인과 `/auth/refresh` 까지 막힌다.
   `handoff` 만의 문제로 좁혀 본 것이 내 오독이었다.
2. **`dev.localhost` 는 저장소 어디에도 없다**(코드·`.env.example`·`infra` 0건). 커밋된 규약이 아니라
   로컬 설정 값이다. 그래서 **A 안(로컬 API base 를 `localhost:8080`)이면 코드 변경 0** 이고,
   `.domain()` 추가는 배포에서 쿠키 범위가 조용히 넓어져 하지 않는 편이 맞다.

`*_REDIRECT_URI` 는 끝까지 바꾸지 않았다 — 등록되지 않은 값이 되어 **새 mismatch 를 만드는 쪽**이다.
`FRONTEND_BASE_URL` 도 파일을 건드리지 않고 기동 인자로만 지정했다 — 그 값은 CORS 허용 오리진
(`SecurityConfiguration:116`)과 Origin 검증 정본(`GuestAuthController:136`)을 겸한다.

#### 남은 FE 몫

`CallbackPage.tsx:138` 이 **400(handoff 누락)과 410(만료·재사용)을 같은 `phase='restart'` 로 합친다.**
BE 는 두 경우를 의도적으로 다른 코드로 내는데 화면에서 구별이 사라진다 — 이번 진단에서도 화면만
보고는 "400 또는 410" 으로밖에 못 적었고 서버를 직접 불러서야 `OAUTH_HANDOFF_MISSING` 을 확인했다.
BE 가 FE 소관으로 넘겼고 별도 티켓으로 분리한다.

### BLOCKED_AT_SERVER_BOOT — AI FastAPI

기동을 **실제로 시도했다**: `python -m venv` → `pip install -e .` (fastapi 0.141.1 설치 성공) →
`uvicorn app.main:app --port 8010`.

```text
1. 재현        festa-ai/.venv/Scripts/python.exe -m uvicorn app.main:app --port 8010
2. 마지막 성공  의존 설치 완료 · import 통과
3. 최초 실패    pydantic_core ValidationError: 13 validation errors for Settings
4. 원문        JWT_SECRET · REDIS_URL · spring_internal_base_url ·
               INTERNAL_SPRING_TO_AI_TOKENS · INTERNAL_AI_TO_SPRING_TOKENS ·
               r2_endpoint · r2_bucket · r2_access_key_id · r2_secret_access_key ·
               minio_endpoint · minio_bucket · minio_access_key_id · minio_secret_access_key
               (전부 "Field required [type=missing]")
5. 관련        festa-ai/app/core/config.py · festa-ai/.env.example (키 이름이 전부 여기 있다)
6. 다음 조건    R2·MinIO 자격증명과 내부 서비스 토큰. **외부 자격증명이라 내가 얻거나 넣지 않는다**
```

**재측정 (같은 날 16:40, BE 가 새 `.env` 를 넘겨준 뒤)** — `13 → 12`. `JWT_SECRET` 하나가 해소됐고
나머지 12는 그대로다. 넘겨받은 `.env` 는 **backend 용**이라(`POSTGRES_*`·`REDIS_PORT`·`JWT_SECRET`·
OAuth 3종·`CONNECTION_TOKEN_SECRET` 16키) **AI 계열 키가 하나도 없다.**

```text
여전히 없는 것 (12)
  REDIS_URL · spring_internal_base_url
  INTERNAL_SPRING_TO_AI_TOKENS · INTERNAL_AI_TO_SPRING_TOKENS
  r2_{endpoint,bucket,access_key_id,secret_access_key}
  minio_{endpoint,bucket,access_key_id,secret_access_key}
```

`GitLab #150` 으로 발급했고 요청은 **값이 아니라 주입 경로와 최소 실행 레시피**다.

Spring 쪽 `/agents`·`/documents` 는 8081 에 살아 있으므로 에이전트 설정 경로는 FastAPI 와 무관하게 검증 가능하다.

> **부수 발견 — 제기하고 해소까지 확인했다.** 16:30 판 `.env` 의 `KAKAO_REDIRECT_URI` 가
> **다음 줄을 삼킨 채**였다(그 줄만 122자, Google 46 · SSAFY 45). `docs/25` T-62 가 09-07 에 지적한
> 파손이 그 파일에도 남아 있었다. `CONNECTION_TOKEN_SECRET` 은 별도 줄에도 정상값이 있어
> **월드 서버는 멀쩡하고 증상이 없다** — 그래서 안 보인다. 값 파일이라 고치지 않고 `#114` 로
> 통보했고 **BE 가 17:09 판에서 고쳤다**(45자, `CONNECTION_TOKEN_SECRET` 60자로 분리).
> **JVM 인자를 빼고** 8081 을 재기동해 Google·Kakao·SSAFY 세 authorize URL 의 `redirect_uri` 가
> 전부 정상임을 확인했다 — 이제 파일만으로 맞는다.

### BLOCKED_AT_TAB_NOT_VISIBLE — `#73` 활성 탭 성능

```text
1. 재현        실 Unity WebGL 로드 후 rAF 프레임 수를 3초간 센다
2. 마지막 성공  Unity WebGL 2.0 컨텍스트 생성 · OpenGL ES 3.0 · 스플래시 렌더 · 백버퍼 2561x1347
3. 최초 실패    rAF 프레임 0개 / 벽시계 3468ms
4. 원문        {"visibility":"hidden","focus":true,"rafFramesIn3s":0,"verdict":"rAF 완전 정지"}
5. 관련        측정 전제 — visibilityState==='visible' && document.hasFocus()
6. 다음 조건    사람이 Chrome 창을 전면으로 올려야 한다. 클릭으로 focus 는 얻었지만
               visibility 는 못 바꿨다(창이 가려져 있거나 최소화 상태)
```

> **이것 자체가 `-518` 판별 축의 실증이다.** 숨은 탭에서는 프레임이 0이라 "프레임 시간" 이라는 값이
> 성립하지 않는다 — 그래서 판별 기준이 프레임 시간이 아니라 **draws·tris 증분**이어야 한다.

### BLOCKED_AT_PREVIEW_CWD_PINNED — 도구 제약 (기록용)

`preview_start` 가 세션 최초 cwd 의 `.claude/launch.json` 을 쓴다. `change_directory` 로 cwd 를 옮겨도
`previewId` 가 그대로라 **3회 연속 다른 워크트리(`ssafesta`, 브랜치 `front`)를 5173 에 띄웠다.**
브라우저가 `dev.localhost:8080` 을 부르는 것으로 드러났다. **T-63 과 같은 부류 — 경로가 브랜치를
보장하지 않는다.** 검증 대상이 틀린 상태의 관측은 전부 폐기하고, 이 한 건만 예외로 Vite 를 직접
띄운 뒤 서빙 프로세스의 cwd 를 실측해 게이트 1을 통과시켰다.

### NOT_EXECUTED — `#73` 5인 사용성

**자동 대체하지 않는다.** 설계만 확정해 남긴다.

```text
필요 참가자   5명 (첫 사용자, 서비스 사전 지식 없음)
시나리오      Game Studio 첫 진입 → 게임 1개 생성 → 저장 → 게시 → 플레이 (20분)
측정 항목     완료율 · 완료 시간 · 이탈 지점 · 도움 요청 횟수 · SUS
불가 사유     모집·일정이 팀 조율 사안이다. 내가 혼자 만들 수 없다
```

### BLOCKED_AT_REAL_ADAPTER_ABSENT — Consultation

```text
1. 재현        /app/booths/1/consultation 진입
2. 마지막 성공  mock 렌더 정상 — "대기 중인 요청 2건 · 방문자A · 방문자B · 수락"
3. 최초 실패    실 서버 호출이 **0건**이다 (8081 요청 0)
4. 원문        entities/consultation/channel.select.ts:5
               "Consultation Port 선택 지점 — real 어댑터는 아직 없다(BE -137 미착수, WS·STOMP shape 미확정)"
               → consultationChannel = consultationVisitorMock 을 직접 export. channel.real.ts 파일 자체가 없다
5. 관련        channel.select.ts · channel.port.ts · 8081 OpenAPI 46 paths 에 consultation 계열 0건(실측)
6. 다음 조건    BE S15P21A604-136·-137 구현 + WS/STOMP 계약 확정
```

---

## 5. Unity WebGL 실측

계획 시점에 8000 을 서빙하던 프로세스가 세션 도중 죽어 있어 같은 산출물
(`ssafesta-external/share/festa-webgl-dev`)을 CORS 헤더를 붙여 다시 띄웠다.
처음엔 단일 스레드 서버로 띄워 로더가 pending 으로 멈췄고, `ThreadingTCPServer` 로 고쳐 해결했다(내 실수).

**실 Unity 가 구동됐다** — `Creating WebGL 2.0 context` · `OpenGL ES 3.0 (Chromium)` · Unity 스플래시.

### `-524` 렌더 해상도 상한 — **PASS**

```text
window.devicePixelRatio      1.5
canvas CSS 크기               1707 × 898     (1.533 MP)
canvas backing buffer        2561 × 1347    (3.450 MP)
effectiveDprX = w/clientW    1.5003
effectiveDprY = h/clientH    1.5000
판정 (effectiveDpr ≤ 1.5)     PASS
```

> **이 화면의 DPR 이 상한과 같아 실측만으로는 "상한이 걸렸다" 와 "걸릴 필요가 없었다" 가 구별되지 않는다.**
> 그래서 상한 함수를 직접 갈랐다 — `resolveDevicePixelRatio()` 에 DPR 을 주입:
>
> ```text
> 0.75→0.75  1→1  1.25→1.25  1.5→1.5  1.75→1.5  2→1.5  3→1.5
> 0→1  -1→1  NaN→1
> ```
>
> **1.5 초과에서 실제로 구속한다.** 게임 파트 09-08 실측(백버퍼 4.08MP → 2.30MP, 실효 DPR 2.0 → 1.5,
> 픽셀 −44%)과 방향이 일치한다.

### `#73` 성능 — 런타임과 에디터를 섞지 않는다

- **게임 런타임**: `BLOCKED_AT_TAB_NOT_VISIBLE`(§4). 전제(`visible` + `hasFocus`)를 만들 수 없었다.
- **에디터 interaction p95**: 런타임 FPS 와 **다른 지표**다. 런타임이 막힌 것과 무관하게 별도로 재야 하며
  이번 회차에서는 시간상 착수하지 않았다 — `NOT_EXECUTED`.

---

## 6. 원복

**삭제 대상은 DB 에 삽입한 테스트 row 뿐이다.** `docs/LJH/verify/fe-full-regression-0908-seed.sql`
파일은 산출물로 남는다.

### 시작 / 종료 대조 — **의도한 1건을 빼고 완전 일치**

```text
                      시작   종료
users                  1      1
booths                 1      1
booth_leases           1      1
wallets                1      1
surveys                1      1
survey_questions       1      1
survey_options         2      2
survey_responses       0      0
survey_answers         0      0
coin_ledger_entries    3      4   ← 유일한 차이
booth_slots            AVAILABLE 11 / OCCUPIED 1  (시작과 같다)
```

`coin_ledger_entries` 의 1건은 **`SURVEY_REWARD 7 · balance_after 307 · SURVEY_REWARD:1:1`** 이다.
**지우지 않는다** — 실제 지급 경로를 통과해 생긴 원장이고, 손으로 지우면 `wallets.balance = 307` 과
어긋나 T-60 이 경고한 "스키마는 만족하는데 도메인이 깨진" 상태가 된다.

survey 1 은 API `PUT` 으로 시작 모양(제목 `실왕복 검증 설문 v2` · `SINGLE_CHOICE` 1문항 · 선택지 2개 ·
`rewardCoin` 5)으로 되돌렸다. **booth 1 의 게시본(`published_layout_version=1`)은 남긴다** — 없으면
방문자 경로가 다시 전부 404 가 되고, 그것은 시작 상태의 결함이지 보존할 상태가 아니다.

`.env.local` 은 3줄 원본으로 되돌렸다(`*.local` 이 gitignore 라 커밋 대상이 아니다).
8000 의 Unity 정적 서버는 **띄운 채로 둔다** — 원래 있던 것이 세션 도중 죽은 것이고, 되살려 둔 편이 시작 상태에 가깝다.

### 종료 검증

```text
npm run build     ✓ built            (= tsc -b && vite build)
npx vitest run    3회: 919/919 green → 2 failed → 3 failed
```

마지막 두 번의 실패는 전부 `src/app/router/__tests__/unit/rootRoute.test.tsx` 이고,
**그 파일만 단독으로 돌리면 3회 모두 4/4 통과**다. T-67(간헐 flake)이며 **오늘 나온 signature 가
09-07 것과 다른 테스트**라 그 증거를 T-67 에 붙였다(`docs/LJH/25_트러블슈팅.md`).

```text
2026-09-07  비로그인 방문자가 …          Unable to find an element with the text: 게스트로 둘러보기
2026-09-08  /app/home 은 … 호환 경로다   Unable to find a label with the text of: 조작 안내
```

> **그 증거로 자동 면책을 넓히지는 않는다.** 한 번 "면책 기준을 파일로 넓히자" 고 적었다가
> 되돌렸다 — 그러면 이 파일의 진짜 회귀까지 자동 면책된다. **매번 절차로 판정한다**:
> 전체 실행 실패 → 단독 반복 → 동일 HEAD baseline 비교 → order-dependent/flaky 판정.

이번 건은 그 절차를 거쳐 판정했다 — 같은 커밋에서 결과가 세 번 다 달랐고 단독은 3/3 통과다.
이 회차의 코드 변경은 `-539` 핫픽스 하나뿐이고 그것은 919/919 green 을 낸 적이 있다.

### 검증 중 booth 1 / survey 1 에 가한 변경 (API·DB 양쪽)

```text
booth 1     published_layout_version = 1 을 새로 만들었다 (게시본이 없어 방문자 경로가 전부 404 였다)
            layouts/draft 에 objectId=e2e-kiosk (SURVEY_KIOSK) 1개
survey 1    제목·6문항 구조로 교체 · reward_coin 을 0↔5↔7 로 여러 번 바꿈 · ends_at 을 과거↔NULL 로 토글
survey_responses  회원·게스트 응답을 넣고 지우기를 반복
wallets     user 1 잔액 300 → 307 (SURVEY_REWARD 원장 1건) — **되돌리지 않는다**.
            실제 지급 경로를 통과한 결과이고 손으로 지우면 원장과 잔액이 어긋난다
booth_slots 11·12 를 OCCUPIED 로 (시드)
```
