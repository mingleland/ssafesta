# SSAFY FESTA Test / QA 계획서

> **목적**: P0/P1 기능이 시연 환경에서 안정적으로 동작하는지 검증하고, 멀티플레이·AI·경제·동시성 위험을 우선적으로 차단한다.

---

## 1. 테스트 목표

1. 핵심 E2E Flow가 끊기지 않는다.
2. Booth Publish 데이터가 Web과 Unity에서 동일하다.
3. 2명 이상의 멀티플레이가 안정적으로 동작한다.
4. AI가 다른 Booth 문서를 검색하지 않는다.
5. Coin·Lease·Reward의 중복 처리가 없다.
6. AI 장애가 World 접속을 중단시키지 않는다.
7. Week 6 이후 Regression을 최소화한다.

---

## 2. 테스트 레벨

| 레벨 | 대상 |
|---|---|
| Unit | Validation, Service Logic, DTO, RAG Scope |
| Component | React Component, Unity Module |
| API Integration | Spring/FastAPI Endpoint |
| DB/Concurrency | Lease, Coin, Consultation Accept |
| Multiplayer | Dedicated Server + 여러 Client |
| E2E | Login → Booth → AI → 상담 → Survey |
| Performance | World 10/20/30/40~50명 단계 |
| Security | Auth/Permission/Data Isolation |
| Demo Rehearsal | 실제 발표 Flow |

---

## 3. 테스트 환경

### Local

개별 개발 및 Unit/Integration.

### Dev

파트 간 연동 확인.

### Demo/Staging

실제 발표에 가까운 환경.

권장:

- 별도 DB
- 실제 Unity Web Build
- 실제 Dedicated Server
- 실제 S3 문서
- 실제 LLM Provider 또는 발표용 동일 설정

---

## 4. Test Data

최소 계정:

```text
Owner A
Visitor B
Staff C
Admin D
```

최소 Booth:

```text
Booth A: AI + Project + Survey
Booth B: 다른 문서 Agent
```

AI 격리 검증을 위해 Booth A/B 문서 내용은 명확히 구분한다.

예:

```text
Booth A 문서에는 CODEWORD_ALPHA
Booth B 문서에는 CODEWORD_BRAVO
```

A Agent 질문에서 BRAVO가 검색되지 않아야 한다.

---

## 5. P0 Critical E2E

### E2E-001 Booth 생성

```text
Owner 로그인
→ 빈 Slot 조회
→ Lease
→ Coin 차감
→ My Booth 생성
```

Expected:

- Lease 1건
- Coin -100
- Ledger 1건
- Slot 사용 중

### E2E-002 Booth Publish

```text
Studio
→ AI NPC + Video Screen 배치
→ Draft 저장
→ Publish
→ Unity 외부 Slot 방문
→ 입장 확인
→ 같은 Scene의 Interior Anchor로 이동
→ 내부 Published Layout 확인
→ 외부로 퇴장
```

Expected:

- Published Version 증가
- 외부 Slot의 Booth명·Facade·입장 가능 상태 일치
- Unity Object Type/Transform 동일
- 퇴장 후 내부 Local Object 정리

### E2E-002-A Lease 만료·재임대

```text
Owner A Booth Publish
→ 방문자 내부 입장
→ Lease 만료 처리
→ 신규 입장 차단
→ 기존 방문자 외부 이동
→ 외부 Slot 빈 상태 확인
→ Owner B가 같은 Slot 임대
```

Expected:

- Owner A의 Layout·AI·문서 데이터는 Draft로 보존
- Slot과 Owner A Booth 연결 해제
- 내부 Runtime Object 제거
- 외부 Facade 기본 빈 상태 복원
- Owner B에게 Owner A의 외부·내부 구성이 노출되지 않음

### E2E-003 Multiplayer

```text
A/B Browser
→ 같은 Dedicated Server
→ Spawn
→ 이동
→ A 종료
```

Expected:

- 상호 Player 확인
- Movement 반영
- A Despawn
- B 연결 유지

### E2E-004 AI RAG

```text
문서 Upload
→ READY
→ Visitor 질문
```

Expected:

- 현재 Agent 문서 Chunk 검색
- 답변 성공
- Source Metadata

---

## 6. P1 Critical E2E

### E2E-101 Handoff

```text
Visitor AI 상담
→ 사람 상담 요청
→ Staff Notify
→ Accept
→ Summary 확인
→ Text Chat
→ End
```

### E2E-102 Survey Reward

```text
Visitor Survey Submit
→ Response 저장
→ Reward
→ 다시 제출
```

Expected:

- 첫 제출만 성공
- 보상 1회

### E2E-103 AI Service Payment

```text
Visitor AI 유료 서비스 시작
→ Coin 처리
→ AI Conversation
```

AI 실패 시 환불 정책이 확정되면 해당 케이스를 추가한다.

### E2E-104 Minigame

```text
Start
→ Play
→ Result
→ Reward
→ 일일 상한 초과 재시도
```

Expected:

- 정책 이상 지급 없음

---

## 7. Backend API Test

### Auth

- 정상 Login
- 잘못된 Token
- 만료 Token
- 권한 없는 Booth 수정

### Booth

- 빈 Slot
- 사용 중 Slot
- Admin/Event Slot 임대 거부
- Coin 부족
- 이미 Booth가 있는 사용자 정책(TBD)

### Layout

- 정상 JSON
- unknown Object Type
- duplicate objectId
- invalid Transform
- 다른 Owner 수정 시도

### Survey

- 마감
- 중복 응답
- 필수 문항 누락
- 잘못된 Option

---

## 8. DB / Concurrency Test

### CON-001 Booth 동시 임대

같은 Slot에 10개 요청 동시 실행.

Expected:

- 성공 1개
- Active Lease 1개
- Coin 차감도 성공 사용자 1회

### CON-002 Daily Reward 중복

동일 User, 동일 날짜 병렬 요청.

Expected:

- 지급 1회

### CON-003 Consultation Accept

Staff 3명이 동시에 Accept.

Expected:

- Staff 1명만 성공

### CON-004 Item Purchase

잔액이 1개 구매만 가능한 상황에서 병렬 구매.

Expected:

- 음수 잔액 없음

---

## 9. Coin Ledger 검증

각 거래 후:

```text
previousBalance + amount = balanceAfter
```

### 확인

- Wallet balance와 최신 balanceAfter 일치
- idempotency 중복 없음
- referenceId 추적 가능
- 실패 Transaction에서 일부 반영 없음

---

## 10. AI Test

### AI-TEST-001 Document Process

- 지원 파일
- 빈 파일
- 손상 파일
- 매우 큰 파일(TBD limit)

### AI-TEST-002 Scope Isolation

Booth A 질문에서 Booth B Chunk가 0건이어야 한다.

### AI-TEST-003 Disabled Document

DISABLED 문서가 Retrieval에 포함되지 않는다.

### AI-TEST-004 No Document

문서가 없을 때 정의된 Fallback으로 동작한다.

### AI-TEST-005 LLM Timeout

- 사용자 오류 UI
- Unity World 연결 유지

### AI-TEST-006 Streaming Disconnect

- 부분 응답 UI 정상
- 재질문 가능

---

## 11. AI 품질 평가 세트

Agent마다 최소 10~20개 대표 질문을 만들어 수동/자동 평가한다.

분류:

- 문서에 직접 답 존재
- 여러 Chunk 필요
- 문서에 없음
- 다른 Booth에만 답 존재
- Prompt 금지 주제

기록:

```text
Question
Expected Source
Retrieved Source
Answer Pass/Fail
Issue
```

---

## 12. Unity Client Test

### Booth Runtime

- AI_AGENT
- VIDEO_SCREEN
- SURVEY_KIOSK
- CONSULTATION_DESK
- LAPTOP
- 미지원 Type
- configId 누락
- 빈 Layout
- Backend canonical `objectId` 파싱
- `/booths/{boothId}/layouts/published` 조회
- ExteriorSlot → InteriorAnchor 이동
- 퇴장·임대 만료 후 `BoothRuntime.Clear()`

### Interaction

- 거리 밖
- 거리 안
- UI 열기/닫기
- 다른 Panel 열려 있을 때

### Web Build

Editor에서만 테스트하지 않고 브라우저 Build에서 확인한다.

---

## 13. Multiplayer Functional Test

| ID | 시나리오 |
|---|---|
| MP-01 | 2 Client Connect |
| MP-02 | Spawn/Despawn |
| MP-03 | Movement Sync |
| MP-04 | Remote Nickname |
| MP-05 | Emote(P1) |
| MP-06 | Client 강제 종료 |
| MP-07 | Server 종료 |
| MP-08 | 재접속 |
| MP-09 | Booth Enter/Exit |
| MP-10 | 2 Client 동시에 Booth 이용 |

---

## 14. Load Test

단계:

```text
2 → 10 → 20 → 30 → 40 → 50
```

각 단계 기록:

### Server

- CPU
- Memory
- Network
- Server frame/tick
- Disconnect

### Browser Client

- FPS
- Memory
- Main thread
- Remote Avatar rendering

### 판정

30~40명 목표가 실제로 불가능하면 수치를 숨기지 않고 운영 목표를 조정한다.

---

## 15. Web Frontend Test

브라우저 후보는 팀/SSAFY 사용 환경에 맞춰 확정하되 최소 Chromium 기반 브라우저를 기준으로 한다.

확인:

- Login
- Studio Drag/Move
- Upload
- Unity Web 실행
- SSE
- WebSocket
- 외부 Video

모바일은 지원 범위가 확정된 기능만 QA한다.

---

## 16. Security Test

### Authorization

- 다른 User의 boothId로 Draft 수정
- 다른 Agent 조회/수정
- 다른 Survey 결과 조회
- Admin API 일반 User 호출

Expected: 403/404 정책에 맞게 차단.

### AI Isolation

Prompt로 다른 Booth 정보를 요청해도 Retrieval Scope가 확장되지 않아야 한다.

### Upload

- 허용되지 않은 확장자
- 위장 MIME
- 경로 문자열 조작

---

## 17. Failure / Degradation Test

### FastAPI Down

Expected:

- AI Error
- World 계속 사용
- Project/Survey 이용 가능

### Spring Down

Expected:

- 신규 Booth/Survey 등 실패 안내
- 이미 열린 Unity 화면이 즉시 crash하지 않음

### Redis Down

Presence/상담에 영향이 발생하므로 오류가 명확해야 함.

### Unity Server Down

Client에게 Connection Error와 재시도/홈 이동 제공.

---

## 18. Regression Suite

Week 6 이후 매 배포마다 최소:

1. Login
2. Lease
3. Studio Save
4. Publish
5. Unity Load
6. 2 Client Connect
7. AI Question
8. Survey Submit
9. Handoff
10. Coin Ledger

을 재검증한다.

---

## 19. Bug Severity

| Severity | 기준 |
|---|---|
| S0 Blocker | Demo 핵심 Flow 불가 / 데이터 손상 |
| S1 Critical | 핵심 기능 사용 불가 |
| S2 Major | 우회 가능하지만 큰 문제 |
| S3 Minor | UI/경미한 기능 |

Week 8 진입 조건:

- S0 = 0
- 핵심 Demo 영역 S1 = 0

---

## 20. Bug Report 필수 정보

- 환경
- Build/Commit
- Browser/OS
- Account
- 재현 절차
- 기대 결과
- 실제 결과
- 로그
- Screenshot/Video
- 관련 Issue

---

## 21. MVP1 QA Exit Criteria

- [ ] 2 Client Multiplayer 성공
- [ ] Lease Transaction 정상
- [ ] Booth Studio Draft/Publish
- [ ] Unity Runtime Layout
- [ ] AI RAG
- [ ] Booth 간 AI 격리
- [ ] S0/S1 없음

---

## 22. MVP2 QA Exit Criteria

- [ ] Survey
- [ ] Staff Permission
- [ ] Presence
- [ ] Handoff Summary
- [ ] Realtime Consultation
- [ ] Economy 중복 처리
- [ ] Minigame 보상
- [ ] Dashboard
- [ ] 30명 수준 Multiplayer Test 결과 확보
- [ ] Demo 전체 3회 이상 연속 성공 권장

---

## 23. 발표 직전 체크

- [ ] Demo 계정 로그인 확인
- [ ] Booth Published
- [ ] AI 문서 READY
- [ ] Staff AVAILABLE
- [ ] Coin 충분
- [ ] Survey 응답 초기화/준비
- [ ] Unity Server 실행
- [ ] FastAPI Health
- [ ] Spring Health
- [ ] Browser Cache/Build 확인
- [ ] Backup 영상
- [ ] 안정 Commit/Tag
