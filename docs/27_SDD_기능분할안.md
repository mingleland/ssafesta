# SDD 기능 분할안 (spec 단위 설계)

> **목적**: `docs/02_서비스_기능_명세서`의 기능 ID를 spec-kit의 spec 단위(`specs/001-…`)로 묶는다.
> 분할 기준: ① 하나의 spec = 독립적으로 명세·구현·검증 가능한 수직 조각 ② 파트 간 계약(API/JSON)이 spec 경계와 일치 ③ P0 → P1 순서
> **상태**: v1.1 (2026-08-12) — 팀 결정 반영: 캐릭터 커스터마이징 P0 승격, 016~018 신설, 마피아 P2 기록. spec-kit 설치 후 이 순서대로 `/speckit.specify` 진행 (파트별 권장 브리프: `docs/sdd/parts/`)

---

## 1차 MVP (P0) — specs 001~009 + 013a + 016

| Spec | 이름 | 포함 기능 ID | 주 담당 파트 | 선행 spec | 비고 |
|---|---|---|---|---|---|
| **001** | auth-user | AUTH-01~04 | BE + FE | — | 모든 보호 기능의 전제. Token 구조(ADR 결정5) 반영 |
| **002** | world-session-multiplayer | WORLD-01~03 | Unity + BE | 001 | **POC 소급 spec** — 검증된 구현 존재. world-sessions API·connection token만 신규 |
| **003** | wallet-coin | ECON-01~04 | BE + FE | 001 | Ledger 불변식 핵심. 임대 차감은 004와 계약 공유 |
| **004** | booth-slot-lease | BOOTH-01~03, 05, 06 | BE + FE + Unity | 001, 003 | 동시 임대 Lock (doc 01 리스크) |
| **005** | booth-studio-layout | STUDIO-01~09 | FE + BE | 004 | **Layout JSON 계약의 Source spec** — React/Spring/Unity 3파트 합의 지점 |
| **006** | booth-runtime | RUNTIME-01~04 | Unity | 005 계약 | **POC 소급 spec** — 구현 완료, Published 연동만 신규 |
| **007** | ai-agent-document | AI-01, AI-02 | AI + BE + FE | 001, 004 | Document Pipeline + 상태 머신 (doc 13 §5~6) |
| **008** | ai-conversation-rag | AI-03~05 | AI + FE | 007 | 격리 Critical Test 포함. SSE + React 오버레이(ADR 결정4) |
| **009** | project-exhibition | PROJECT-01~04 | BE + FE + Unity | 004, 005 | RUNTIME-05 중 Video/Panel 상호작용 일부 포함 |
| **013a** | avatar-customization | WORLD-04 | Unity + BE | 001, 002 | **P0 승격** — Rukha93 모듈 파츠·정식 Unity `CharacterLobby` UI·실시간 동기화 완료. 2026-08-16 React 교체 요구 폐기. 신규 잔여: Spring 저장(`PATCH /users/me/avatar`), 재접속 복원, 멀티클라이언트 E2E |
| **016** | booth-laptop-homepage | RUNTIME-06, PROJECT-05 | FE + Unity + BE | 004, 005 계약 | **신설 (2026-08-12)** — Studio에서 홈페이지 URL 등록 → 부스 노트북 오브젝트 클릭 → React 오버레이에서 해당 페이지 열람·웹서핑. Unity는 상호작용 트리거만 (Article V). iframe 차단(X-Frame-Options) 시 새 탭 fallback 필수 |

**1차 MVP 완료 판정** = doc 02 §5.1의 10단계 연속 흐름 (001~009 전부) + 캐릭터 커스텀(013a) + 노트북 홈페이지(016)
**1차 월드 구성** = **11층 단일 존** (층 구조는 018/2차)

## 2차 MVP (P1) — specs 010~015, 017~018

| Spec | 이름 | 포함 기능 ID | 선행 | 비고 |
|---|---|---|---|---|
| **010** | survey | SURVEY-01~08 | 004, 005 | 보상(SURVEY-07)은 003 계약 사용 |
| **011** | staff-consultation | STAFF-01~03, CONSULT-01~05, AI-06 | 001, 008 | WebSocket 상담 + Presence + Handoff Summary |
| **012** | economy-extended | ECON-05~08 | 003 | AI 결제·수익, Inventory, 장식 구매 |
| **013b** | avatar-presence | WORLD-05~06 | 002, 013a | Emote·Presence·닉네임 표시 등 커스터마이징 제외 잔여분 (커스터마이징은 013a로 P0 이동) |
| **014** | minigame | GAME-01~03 | 002, 003 | 1종만. 정산은 Spring. **P2 확장 후보: 마피아 게임(GAME-05)** — 접속자 대상 역할 부여 + 연출("지직" 글리치). spec은 P0/P1 안정 후 판단 |
| **015** | dashboard | DASH-01~05 | 007~012 | 집계 지표 — 데이터 소스 spec들 이후 |
| **017** | proximity-voice | WORLD-09 | Unity + FE + Infra | 002 | **거리 기반 음성채팅** — 거리별 볼륨 감쇠. 권장: WebRTC SFU(LiveKit 등) 별도 채널 + Unity가 위치 기반 게인 계산. NGO로 음성을 실어 나르지 않는다 |
| **018** | world-floors | WORLD-10 | Unity | 002 | **1층+11층 + 엘리베이터 전환** — 1층 축제 부스·포토존, 엘리베이터 진입 → 내부 연출/로딩 → 층수 변화 → 11층 도착. 1차 MVP는 11층 단일이므로 2차에서 존 분리·세션 이동 설계 |

**P2 (WORLD-07/08, AI-07/08, EVENT, COMP, STUDIO-14 등)는 spec을 만들지 않는다** — doc 01 Cut Line대로 P0/P1 안정 후 판단.

## 의존 그래프 (착수 가능 시점 기준)

```text
001 auth ─┬─ 002 world-session (Unity 소급)
          ├─ 003 wallet ── 004 lease ─┬─ 005 studio-layout ── 006 booth-runtime (Unity 소급)
          │                           ├─ 007 ai-document ── 008 ai-conversation
          │                           └─ 009 project / 010 survey
          └─ 011 staff-consult (008 이후)
```

## 파트별 병렬 트랙 (Week 2~3 제안)

- **BE**: 001 → 003 → 004 (직렬 — 서로 의존)
- **FE**: 001(로그인 UI) → 005(Studio) 착수 — 005는 Mock API로 004와 병렬 가능
- **Unity**: 013 정식 UI 완료 → Spring 저장/재접속 복원 E2E → 002 wss·006 Published 연동 검증
- **AI**: 007 스파이크(이미 계획됨) → 007/008 spec

## POC 소급 spec 처리 (docs/23 원칙)

- **002, 006**: 구현이 spec보다 먼저 존재하는 케이스. spec 작성 시 "현재 구현이 만족하는 요구사항"을 역으로 명세하고, 부족분(world-sessions API 연동, Published 연동, 재접속)을 requirement로 추가한다. **구현을 spec에 맞춰 재작성하지 않는다** — spec이 현실을 기록하고 다음 증분을 정의한다

## 미정 항목 → clarify 매핑

doc 02 §6의 미정 항목이 어느 spec의 `/speckit.clarify`에서 풀리는지:

| 미정 항목 | 해결 spec |
|---|---|
| 탈퇴 데이터 보존 | 001 |
| 임대 갱신·연장 정책 | 004 |
| 부스 최대 오브젝트 수 | 005 |
| 수수료율 | 012 |
| Survey 보상 상한 | 010 |
| 재접속 UX | 002 |
| 친구 기능 여부 | 013 (또는 미포함 확정) |
| 모바일 Web 지원 범위 | 002 |
