# FE 계약 경계 — G-6 · G-7 · G-8

- 문서 종류: **FE 확정 계약 + Cross-part 요청**. 작성 2026-09-05 (`S15P21A604-413`), 기준 `origin/develop` `c0a468a4`.
- 목적: "G-6·G-7·G-8 전체가 미정" 이라는 뭉뚱그린 상태를 없앤다. **FE 가 혼자 정할 수 있는 것은 여기서 확정하고**, 타 파트 합의가 필요한 것만 남겨 owner 를 붙인다.
- `decision-queue.md` 는 Unity 합의 대기 목록이고, 이 문서는 그중 FE 가 먼저 닫은 부분을 담는다.

---

## G-6 Survey — FE 확정 완료, BE endpoint 대기

**FE Port 는 이미 코드로 확정돼 있다**: `src/entities/survey/api.port.ts`.

```
SurveyPort
  getRun(surveyId)        → { status: 'open'|'closed', questions }
  submitAnswers(surveyId, answers)
  getResult(surveyId)     → { perQuestion, textAnswers }
  getTextAnswers(surveyId, page)
  getDraft() / saveDraft(draft)
```

FE 가 확정한 것

| | 결정 |
|---|---|
| MVP 결합도 | **부스당 활성 설문 1개.** Unity 는 `{ boothId, objectId }` 만 보내고 FE 가 부스 기준으로 활성 설문을 연다 |
| `objectId → surveyId` binding | **지금 필요 없다.** 한 부스에 설문이 여러 개 놓일 때 필요해지며, 그때 Unity payload 가 아니라 **BE 조회로** 푼다 |
| `status: 'closed'` | 질문이 있어도 제출 불가. 마감은 서버 판정이고 FE 는 표시만 |
| 주관식 페이지네이션 | `getTextAnswers(surveyId, page)` — 결과 화면 첫 페이지는 `getResult` 에 포함 |

**BE 에 필요한 것 (endpoint·DTO)** — 위 6개 호출에 대응하는 실 endpoint. 도착하면 FE 는 `api.select.ts` 에서 mock → real 어댑터 교체만 한다. FE 는 endpoint 를 발명하지 않는다.

> Assignee: **황덕(@ejraks1548)** · Jira `-130`·`-131`·`-132`·`-190`·`-192`·`-193`
> 완료조건: 위 6개 호출에 대응하는 endpoint·DTO 확정 + `specs/010` 계약 문서 반영

---

## G-7 Consultation Transport — FE 확정 완료, STOMP 계약 대기

**FE Port 도 코드로 확정돼 있다**: `src/entities/consultation/channel.port.ts` · `features/consultation/model/startContext.ts`.

```
ConsultationStartContext { boothId, source: 'AI_HANDOFF'|'CONSULTATION_DESK', handoffSummary? }

ConsultationChannelPort   requestConsultation(boothId) → { expiresInSeconds }
                          cancelRequest()
                          onVisitorEvent(cb)   accepted | expired | ended

ConsultationStaffPort     getQueue() → ConsultationRequestCard[]
                          accept(requestId) → ConsultationActiveSession
                          end()
```

FE 가 확정한 것

| | 결정 |
|---|---|
| 상태 | `idle → requesting → waiting → active → ended`, 별도 `expired` (C-01 서버 기준 만료) |
| `close != cancel` | 오버레이 닫기는 요청을 취소하지 않는다. 재진입하면 카운트다운이 이어지고, 명시적 취소 후에야 비활성. E2E 로 회귀 고정 (`-416`) |
| 만료 | `CONSULTATION_EXPIRY_SECONDS = 600` — **정본은 서버**, FE 값은 잔여 시간 안내용 |
| 게스트 | C-04 — 진입점에서 미노출. FE 가 호출 자체를 하지 않는다 |
| 직원 동시 1건 | C-06 — FE 가 `accept` 호출 전에 게이트하되 **서버 거부가 정본** |
| `handoffSummary` | Context 에는 실려 있으나 **아직 채널로 넘기지 않는다** — 요청 프레임 schema 가 transport 소관이라 FE 가 필드를 발명하지 않았다 (`visitor.ts:112` 주석) |

**BE 에 필요한 것 (transport 계약)**

```
WS endpoint · WS Token 발급 경로
STOMP destination (요청 / 방문자 이벤트 / 직원 대기열)
요청 프레임 schema — handoffSummary 를 어느 필드로 싣는가
재연결·중복 요청 처리
```

도착하면 FE 는 real 어댑터가 Port 를 구현하는 것으로 끝난다(STOMP 는 어댑터 내부 상세). **현재 close 정책 판정은 mock adapter 기준이라 transport 도달 시 재확인이 필요하다.**

> Assignee: **황덕(@ejraks1548)** · Jira `-137`(WS 채널·WS Token) · 관련 `-416`
> 완료조건: 위 4항 확정 + `specs/011` 계약 반영

---

## G-8 Game Client Input Foundation — FE 층 확정, Unity 계약 대기

여기가 실제로 FE 결정이 남아 있던 유일한 항목이라 아래를 **FE 정본으로 확정한다.** Unity 합의가 필요한 것은 §G-8-2 뿐이다.

### G-8-1. FE 확정 (Unity 합의 불요)

**계층** — 위가 이긴다.

```
1. Overlay (OverlayHost · role="dialog" aria-modal)
2. World HUD (Consultation Quick Access · GameMenu)
3. Unity canvas (World 입력)
```

**Overlay 는 동시에 하나다.** `shared/types/overlay.ts` 의 Overlay Bus 는 `OverlayRequest | null` 하나만 들고 있고 스택이 아니다. 새 요청이 오면 이전 것을 **교체**한다 — 이 단일성을 유지한다. 스택이 필요해지는 화면이 나오면 그때 Bus 를 고치고, 그 전까지 오버레이 위에 오버레이를 얹지 않는다.

**ESC 우선순위**

| 상태 | ESC 동작 |
|---|---|
| Overlay 열림 | 그 Overlay 를 닫는다. **여기서 소비하고 아래로 내리지 않는다** |
| Overlay 없음 · World | GameMenu 토글 |
| Game Studio 편집기 | 편집기 자체 규칙(`GameStudioShell`) — World 계층 밖 |

`GameOverlay` 만 `keydown` 을 **capture 단계**(`addEventListener(..., true)`)로 듣고 나머지는 bubble 이다. 게임 런타임이 자체 키 핸들러를 갖기 때문이고, 이 비대칭은 의도된 것이다.

**focus ownership**

- Overlay 가 열리면 `OverlayFrame` 이 자기 루트(`tabIndex={-1}`)에 focus 한다. 이미 구현돼 있다.
- Overlay 가 닫히면 **focus 를 Unity canvas(`#unity-canvas`)로 돌려준다.** 지금은 이 복구가 없어 닫은 뒤 body 에 focus 가 남는다 — WASD 가 Unity 로 안 가는 원인이 될 수 있다. **이건 FE 단독으로 고칠 수 있고 §G-8-2 의 Unity API 없이도 유효하다.**
- Unity canvas 는 `tabIndex=-1` 이라 Tab 순서에 없다(`-421`). a11y 개선으로 `0` 을 검토할 수 있으나 그때 Unity 가 Tab 을 삼키지 않는지 함께 봐야 한다.

**용어** — `gameClientUi`(World 위 상주 React 층: HUD·Overlay 호스트)와 Overlay Bus(요청 전달)는 다른 것이다. Bus 는 "무엇을 열지" 만 알고, 그리는 곳은 `OverlayHost` 하나다.

### G-8-2. Unity 합의 필요 (cross-part)

```
① WebGLInput.captureAllKeyboardInput = false 설정 시점·주체
   현재 저장소 전체에서 captureAllKeyboardInput 0건. Unity 가 기본값(true)이면
   canvas 밖 입력까지 Unity 가 먹어 React 입력 필드가 죽는다.

② Unity input lock / unlock 브리지 API
   React: Overlay open  → Unity 입력 정지
          Overlay close → Unity 입력 재개
   FestaUnity 네임스페이스에 함수 2개면 된다. 이름·시그니처는 Unity 몫.

③ cursor / pointer ownership
   Unity 가 pointer lock 을 잡는 구간과 Overlay 가 마우스를 쓰는 구간의 경계.
```

FE 는 ①②③ 없이도 §G-8-1 을 먼저 구현할 수 있다(ESC·focus 복구·계층). ②가 오면 `OverlayHost` 의 open/close 지점에서 부르는 것으로 끝난다.

> Assignee: **강형순(@gudtnslwkd)** · `decision-queue.md` Unity 항목 4번
> 완료조건: ①의 설정 주체 확정 · ②의 함수 이름·시그니처 확정 · ③의 경계 서술

---

## 이 문서가 하지 않는 것

- UI/UX polish, Visual Direction, 컴포넌트 구현 — 계약 경계만 다룬다.
- Booth 2.5D 에셋(R3F·GLB) — `boothAssetBase` seam 만 열려 있고 구현은 DEFERRED (`05_technical-spikes/`).
- GAME(G-5) — `#56` ⓑⓒ 기획 확정과 BE endpoint 선행.
