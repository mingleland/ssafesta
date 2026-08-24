# JIRA 백로그 설계 (S15P21A604)

## ⚡ v2 개정 (2026-08-24 반영 완료) — 이 절이 아래 원안보다 우선한다

사용자 지시: 개발 마감 **09-18**·다음 주 발표 준비 / **09-07 13:00부터 매주 1회 배포** /
용어 통일 / 이슈 최소 단위 분할 / 전 이슈 현업 템플릿(docs/18 §8).

- **일정**: Sprint 6 = 09-14~**09-18 (개발 마감)**, Sprint 7 = 09-21~09-25 **발표 준비**, Sprint 8 삭제.
- **릴리즈 = 주간 배포 트레인 (매주 월 13:00)**: v0.1.0-mvp1(09-07) → v0.2.0(09-14) →
  v0.3.0-mvp2(09-21, 09-18 동결본) → v1.0.0(09-25 발표). 배포 절차 이슈 신설(주간 배포 체크리스트).
- **용어 통일 규칙**: 계약·도메인 명사는 영문 원어(Booth, BoothSlot, Lease, Layout, Draft,
  Publish/Published, Facade, Wallet, Coin, Ledger, Avatar, World Session, Connection Token,
  AI Agent, Document, Conversation, SSE, Game Studio) + 한국어 서술어. "파사드/퍼블리시/지갑" 등
  혼용 표기 제거 — 제목·설명 전수 적용, 완료 이슈 제목 15건도 소급 통일.
- **분할**: 잔여 5·8SP 이슈 전수 분할 → 갱신 108건 + 신규 47건, 활성 스프린트 이슈는 전부 1~3SP.
  백로그(컷라인 검토분)만 원 SP 유지 — 편성 시 분할한다.
- **컷라인**: Staff & Consultation 4건(22SP)은 09-18 마감 기준 백로그로 이동, `cutline-review`
  라벨 — 편성 여부는 팀 결정.
- **스프린트 부하(개정 후)**: S3 73SP / S4 90 / S5 89 / S6 48(5일) / S7 18(발표) / 백로그 47.
- **전 이슈 설명 템플릿**: 작업 목적 / 작업 내용 / 완료 조건(체크박스) / 선행 작업·Dependency /
  참고(spec·GitLab) / 테스트 방법 — docs/18 §8. Blocks 링크 14건 배선.
- 실행 정본: `jira_v2_meta.json` + `jira_v2_updates_{a_s3,b_s4,c_s5up}.json` + `jira_v2_creates.json`
  + `jira_seed_v2.mjs`. 결과: 총 214건(에픽 15·이슈 199), Done 59, Sprint 3 활성 32건.

---

> 작성: 2026-08-24 (Week 3 월요일) · 기준 문서: `docs/18_Jira_운영_가이드.md`(팀 컨벤션 확정본),
> `docs/01_프로젝트_기획서.md` §11 일정, 각 브랜치 specs/001~016, `docs/sdd/파트별_할일.md`
> 이 문서는 Jira 일괄 생성의 **원본 설계**다. 생성 후 어긋나면 Jira가 정본, 이 문서는 이력.

## 1. 운영 원칙 (사용자 확정 + docs/18)

- **주 단위 스프린트, 매주 월요일 시작** — 월요일에 이슈 등록 후 스프린트 개시. 중간 추가 허용.
- 번다운 차트는 **회고 도구**다 — 평가 대상 아님.
- dev/main 에 머지되는 모든 변경은 **JIRA 이슈 필수** (커밋/MR에 이슈 키 표기).
- 담당자는 **비워 둔다** (추후 팀 배정). Story Point·우선순위·컴포넌트·에픽은 전부 채운다.
- 이슈 구조: Epic → Story → Task, Bug 는 별도 타입 (docs/18 §2).
- 제목: `[영역] 내용` prefix — FE/BE/UNITY/AI/INFRA/DB/TEST/DOCS/BUG (docs/18 §6~7).
- SP 척도: 1/2/3/5/8 (docs/18 §16). 이슈 크기 0.5~2일 (§15).

## 2. 스프린트 계획 (프로젝트 시작 2026-08-10, 8주)

| 스프린트 | 기간 | 상태 | 골 (기획서 §11) |
|---|---|---|---|
| Sprint 1 — 기획+POC | 08-10 ~ 08-16 | **closed** (소급) | 기획 확정 + Multiplayer/Booth/AI/Deployment POC |
| Sprint 2 — 기반 시스템 | 08-17 ~ 08-23 | **closed** (소급) | World, Auth, Booth, Lease, Coin, RAG 기반 |
| Sprint 3 — 1차 MVP | 08-24 ~ 08-30 | **active** (오늘 시작) | Booth Studio → Spring → Unity 1차 MVP 관통 |
| Sprint 4 — 콘텐츠 기능 | 08-31 ~ 09-06 | future | AI Agent, Project, Survey, Functional Object |
| Sprint 5 — 운영 기능 | 09-07 ~ 09-13 | future | Staff, Consultation, Economy, Inventory |
| Sprint 6 — 2차 MVP 동결 | 09-14 ~ 09-20 | future | Minigame, 멀티 고도화, 통합, 아키텍처 동결 |
| Sprint 7 — 안정화 | 09-21 ~ 09-27 | future | QA, Load Test, 최적화 |
| Sprint 8 — 발표 준비 | 09-28 ~ 10-04 | future | 발표·영상·README·리허설 |

- 소급 스프린트 1·2에는 **완료(Done) 이슈만** 배치 — 기록·회고용.
- 이슈 배치: Sprint 3 는 오늘 확정. Sprint 4~5 는 잠정 배치(월요일마다 재확정). Sprint 6+ 은 백로그 유지.

## 3. 릴리즈 (fixVersion)

| 버전 | 날짜 | 상태 | 내용 |
|---|---|---|---|
| v0.0.1-poc | 2026-08-16 | released | POC — Multiplayer + Booth Runtime (git 태그와 일치, 기준선 동결) |
| v0.1.0-mvp1 | 2026-08-30 | unreleased | 1차 MVP — 임대→Studio→Publish→Unity 방문 관통 |
| v0.2.0-mvp2 | 2026-09-20 | unreleased | 2차 MVP — AI·Survey·Staff·Economy·Minigame 통합, 동결 |
| v1.0.0 | 2026-10-04 | unreleased | 최종 발표본 |

릴리즈 노트는 각 버전 릴리즈 시점에 해당 fixVersion 의 Done 이슈로 자동 구성 (Jira 릴리즈 페이지).

## 4. 컴포넌트

`Frontend (React)` · `Backend (Spring)` · `Unity Client/Server` · `AI (FastAPI/RAG)` · `Infra (AWS/Docker)` · `Docs/Process`
— 에픽은 기능 축, 컴포넌트는 기술 파트 축 (교차 조회용, docs/18 §3의 "파트별 에픽 금지" 원칙과 상보).

## 5. 에픽 (docs/18 §3 그대로 + 프로젝트 실상 반영)

| 키 예정 | 에픽 | 비고 |
|---|---|---|
| EPIC-01 | Auth & User | specs/001 |
| EPIC-02 | Unity Multiplayer & World | specs/002·012·016, 페스티벌 존 포함 |
| EPIC-03 | Booth Rental | specs/004 (Lease) |
| EPIC-04 | Booth Studio | specs/005 |
| EPIC-05 | Booth Runtime | specs/006·016 |
| EPIC-06 | AI Agent | specs/007·008 |
| EPIC-07 | Survey | specs/010 |
| EPIC-08 | Staff & Consultation | specs/011 |
| EPIC-09 | Economy | specs/003 (Wallet/Coin) |
| EPIC-10 | Avatar & Customization | specs/013 — 가이드의 Inventory&Decoration 을 실제 구현 축으로 치환 |
| EPIC-11 | Game Studio & Minigame | game-studio (specs/009 포함) |
| EPIC-12 | Infrastructure | 배포·wss·Docker·CI |
| EPIC-13 | Docs & Process | 컨벤션·정본화·회고 |

## 6. JQL 필터 (보드·회고용)

- 내 파트 백로그: `project = S15P21A604 AND component = "Unity Client/Server" AND statusCategory != Done ORDER BY priority DESC, cf[SP] DESC`
- 이번 스프린트 남은 것: `project = S15P21A604 AND sprint in openSprints() AND statusCategory != Done`
- 스프린트 회고(완료율): `project = S15P21A604 AND sprint in closedSprints() ORDER BY resolved DESC`
- 블로커: `project = S15P21A604 AND (labels = blocked OR status = BLOCKED)`
- 릴리즈 노트 초안: `project = S15P21A604 AND fixVersion = "v0.1.0-mvp1" AND statusCategory = Done ORDER BY component`
- 이슈 없는 머지 감시(수동 대조용): GitLab MR 목록 ↔ `project = S15P21A604 AND issuetype != Epic AND updated >= -7d`

## 7. 이슈 목록

> 표기: `제목 | 타입 | SP | 에픽 | 컴포넌트 | 스프린트(또는 백로그) | 우선순위`
> Done 표시는 소급 스프린트에 완료로 등록.

### 7.1 게임(Unity) 파트 — 세션 실지식 + docs/KHS/26 기준

**완료 (Sprint 1 소급, v0.0.1-poc):**
- [UNITY] NGO 부트스트랩·2클라이언트 Spawn/Despawn POC | 작업 | 5 | Unity Multiplayer | Done
- [UNITY] Player Movement Sync (client-authoritative) | 작업 | 3 | Unity Multiplayer | Done
- [UNITY] Mock Booth Layout Runtime 생성 POC | 작업 | 5 | Booth Runtime | Done
- [UNITY] WebGL 빌드 ws:// 로컬 멀티플레이 실증 | 작업 | 3 | Unity Multiplayer | Done

**완료 (Sprint 2 소급):**
- [UNITY] 허공 스폰 수정 — 서버 배정 위치 강제 (T-177) | 작업 | 3 | Unity Multiplayer | Done
- [UNITY] 모듈형 아바타 조립·외형 동기화 (FixedString4096) | 스토리 | 8 | Avatar & Customization | Done
- [UNITY] CharacterLobby 커스터마이징 UI (docs/29) | 스토리 | 5 | Avatar & Customization | Done
- [UNITY] 페스티벌 존 — 복도 전환·야시장·담장·불꽃놀이 | 스토리 | 8 | Unity Multiplayer | Done
- [UNITY] 내부 부스 12실 + F 상호작용 포털·하이라이트 | 스토리 | 5 | Booth Runtime | Done
- [UNITY] 구역 BGM 크로스페이드·카메라 하드클램프 | 작업 | 3 | Unity Multiplayer | Done
- [UNITY] 에디터·런타임 실측 최적화 (렌더러 병합·텍스처 캡·오클루전) | 작업 | 5 | Unity Multiplayer | Done
- [UNITY] 부스 규격 계약 공지 — boothId 1~12·앵커 40 (#62) | 작업 | 2 | Booth Runtime | Done
- [UNITY] 플레이어 실측 스케일업 ×1.25 + 점프 리터치 | 작업 | 3 | Unity Multiplayer | Done

**Sprint 3 (이번 주):**
- [UNITY] 아바타 외형 Spring 영구 저장 연동 — HttpUserApiClient (P0-1) | 스토리 | 5 | Avatar & Customization | High
- [UNITY] 멀티클라이언트 외형 E2E — 2클라 실측 (P0-2) | 작업 | 3 | Avatar & Customization | High
- [UNITY] AI_AGENT_INTERACT 송신부 — 브리지 일반화·Mock 제거 | 작업 | 3 | AI Agent | High
- [UNITY] BoothSlot_7 위치 확정·스폰 검증 실기 | 작업 | 2 | Booth Runtime | Medium
- [TEST] 이동 애니메이션·상태 동기화 마감 검증 (P0-3) | 작업 | 2 | Unity Multiplayer | Medium

**Sprint 4~ / 백로그:**
- [UNITY] Published Layout 실서버 연동 — loadOnStart 전환 | 스토리 | 5 | Booth Runtime | Sprint 4 | High
- [UNITY] 카탈로그 기본 자산 정책 (T-148) — assetCode fallback 규칙 | 작업 | 2 | Booth Runtime | Sprint 4 | Medium
- [UNITY] 라이트맵 베이크 전환 — 실시간 조명 127개 정리 | 작업 | 5 | Unity Multiplayer | Sprint 6 | Medium
- [UNITY] 복도·축제 존 프리팹화 | 작업 | 3 | Unity Multiplayer | Sprint 6 | Low
- [UNITY] Grand 불꽃놀이 인터랙션 연결 | 작업 | 2 | Unity Multiplayer | 백로그 | Low
- [UNITY] 30~40명 부하 프레임·메모리 실측 | 작업 | 5 | Unity Multiplayer | Sprint 7 | High

### 7.2 인프라 — docs/22 §1·2, #52, #30 기준

**Sprint 3:**
- [INFRA] Linux Dedicated Server 빌드 + Docker 이미지 | 작업 | 3 | Infrastructure | High
- [INFRA] EC2 + Nginx wss 종단 실측 — world.<domain> (#52) | 스토리 | 5 | Infrastructure | Highest
- [INFRA] Web 빌드 정적 서빙 — demo.<domain>, wasm MIME | 작업 | 2 | Infrastructure | High

**백로그:**
- [INFRA] CI 파이프라인 — 빌드·테스트 자동화 (GitLab CI) | 작업 | 5 | Infrastructure | Sprint 4 | Medium
- [INFRA] 60초 무입력 연결 유지·타임아웃 튜닝 검증 | 작업 | 1 | Infrastructure | Sprint 3 | Medium

### 7.3 BE / 7.4 FE / 7.5 AI

> 각 브랜치 specs(001~020)·tasks.md 체크 상태·실제 코드(backend/src 130여 파일, festa-frontend/src
> 132파일, origin/ai 는 스펙만·코드 0)·커밋 로그를 교차 대조해 도출했다.
> **전체 152건의 확정 목록은 [`jira_backlog.json`](jira_backlog.json) 이 정본이다** (생성 스크립트 입력).
> 요점: BE 는 001/003/004/005/013a/020 구현 완료(Done 21건), 002 world-sessions 가 미착수 최우선.
> FE 는 001/005/013a/016 완료(Done 15건), Lease·Wallet UI 와 실서버 결선이 착수 가능 상태.
> AI 는 스펙·계약만 완료(코드 0) — Sprint 3 스캐폴드·스파이크부터 시작해 007→008 순서로 구현.

### 7.5-1 스프린트별 집계 (152건, 총 585SP)

| 스프린트 | 건수 | SP | 비고 |
|---|---|---|---|
| Sprint 1 (소급) | 7 | 29 | 전부 Done — POC·설계 문서 |
| Sprint 2 (소급) | 52 | 213 | 전부 Done — 기반 시스템 실적 |
| Sprint 3 (오늘 시작) | 28 | 74 | 6인 기준 ≈12SP/인 |
| Sprint 4 | 27 | 116 | **과적재 — 8/31 플래닝에서 일부를 5·6으로 이월할 것** |
| Sprint 5 | 25 | 98 | AI 안정화 집중 |
| Sprint 6 | 9 | 41 | + Sprint 4 이월분 수용 여력 |
| Sprint 7 | 1 | 5 | QA 이슈는 6주차 결과로 그때 등록 (회고적 성격) |
| 백로그 | 3 | 9 | Low 3건 |

### 7.6 GitLab 열린 이슈 → Jira 전환 대상

| GitLab | Jira 제목 | 타입 | SP | 에픽 | 스프린트 |
|---|---|---|---|---|---|
| #81 | [BE/FE] Published 플레이 세션·Coin 차감 규약 확정 | 작업 | 3 | Game Studio & Minigame | Sprint 3 |
| #78 | [FE] GameProject v1.1 타이머·점수·승리 규약 구현 | 스토리 | 5 | Game Studio & Minigame | Sprint 4 |
| #73 | [TEST] Game Studio 첫 사용자 20분 사용성 검증 | 작업 | 3 | Game Studio & Minigame | Sprint 5 |
| #69 | [BE] 사용자 Asset 업로드·stable asset:// | 스토리 | 5 | Game Studio & Minigame | Sprint 4 |
| #62 | [UNITY] 부스 규격 계약 유지 관리 | 작업 | 1 | Booth Runtime | (Done 처리 — 공지 완료) |
| #60 | [FE/UNITY] 013a WebGL Host — 빌드 URL·credential 정본 | 작업 | 3 | Unity Multiplayer | Sprint 3 |
| #59 | [DOCS] spec 상태 서술 정본화 규칙 | 작업 | 2 | Docs & Process | Sprint 3 |
| #56 | [BE/FE] GAME_PORTAL Binding·Booth Host 수직 구현 | 스토리 | 5 | Game Studio & Minigame | Sprint 4 |
| #55 | [FE] Published Web Runtime 진입·오류 격리 | 스토리 | 5 | Game Studio & Minigame | Sprint 4 |
| #52 | (7.2 의 wss 실측과 동일 — 병합) | | | | |
| #51 | [DOCS] spec 007 저장소 표기 R2 로 정정 | 작업 | 1 | Docs & Process | Sprint 3 |
| #48 | [BE] Draft/Publish·Published Query MVP | 스토리 | 5 | Game Studio & Minigame | Sprint 3 |

GitLab `gh:PR` 라벨(이관된 PR 기록)은 Jira 로 옮기지 않는다 — MR 은 GitLab 정본.
전환 시 Jira 이슈 설명에 GitLab 이슈 URL 을 남기고, GitLab 이슈에 Jira 키 코멘트를 남긴다 (양방향 링크).

## 8. 생성 절차 (자동화)

1. 컴포넌트 6종 생성 → 2. 버전 4종 → 3. 에픽 13종 → 4. 이슈 일괄 생성 (SP=Story Points 필드,
   컴포넌트·에픽 링크·라벨) → 5. 스프린트 8개 생성 (보드 15273) → 6. 이슈 스프린트 배치 →
7. 소급 스프린트 1·2 는 start→complete 처리, Done 이슈 resolution 지정 → 8. Sprint 3 start.

접근: Atlassian REST v3 + Agile 1.0. 인증은 사용자 API 토큰(Basic, gudtnslwkd@naver.com).
