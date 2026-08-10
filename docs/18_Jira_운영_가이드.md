# SSAFY FESTA Jira 작성 및 운영 가이드

> **목적**: Jira를 문서 저장소가 아니라 **누가 무엇을 하고 있으며 어떤 조건에서 완료되는지** 관리하는 도구로 사용한다.

---

## 1. Jira 관리 대상

- Sprint 작업
- 담당자
- 우선순위
- Story Point
- 진행 상태
- Dependency / Blocker
- 완료 조건
- Bug / Regression

긴 기획서·API 명세·ERD·아키텍처 전문은 별도 문서로 관리하고 Jira에 링크한다.

---

## 2. Issue 구조

```text
Epic
└─ Story
   ├─ Task / Sub-task
   ├─ Task / Sub-task
   └─ Task / Sub-task

Bug는 별도 Bug Type
```

---

## 3. Epic 기준

기술 파트가 아니라 **사용자 기능 또는 시스템 영역**으로 나눈다.

### 권장 Epic

```text
EPIC-01 Auth & User
EPIC-02 Unity Multiplayer
EPIC-03 Booth Rental
EPIC-04 Booth Studio
EPIC-05 Booth Runtime
EPIC-06 AI Agent
EPIC-07 Survey
EPIC-08 Staff & Consultation
EPIC-09 Economy
EPIC-10 Inventory & Decoration
EPIC-11 Minigame
EPIC-12 Infrastructure
EPIC-13 Admin
EPIC-14 Extension
```

나쁜 예:

```text
프론트엔드 개발
백엔드 개발
```

---

## 4. Story 기준

형식:

```text
[사용자]는 [목적]을 위해 [기능]을 사용할 수 있다.
```

예:

```text
운영자는 자신의 부스에 오브젝트를 배치할 수 있다.
사용자는 빈 부스를 확인하고 코인을 사용해 임대할 수 있다.
운영자는 프로젝트 문서를 등록해 AI 직원이 해당 문서를 기반으로 답변하게 할 수 있다.
```

---

## 5. Task 기준

하나의 Story를 실제 담당자가 독립적으로 완료할 수 있는 작업으로 분해한다.

예:

```text
Story: 사용자는 빈 부스를 임대할 수 있다.

[BE] BoothSlot 조회 API
[BE] Lease 생성 API
[BE] 동시 임대 Lock
[FE] Booth Slot UI
[FE] Lease Confirm Modal
[FE] Lease API 연동
[TEST] 동시 임대 시나리오
```

---

## 6. 제목 규칙

```text
[영역] 작업 내용
```

예:

```text
[BE] Booth Lease API 구현
[FE] Booth Studio Properties 패널 구현
[UNITY] Player Spawn/Despawn 구현
[AI] Booth별 RAG 검색 격리 구현
[INFRA] Unity Dedicated Server Dockerize
[TEST] Booth Publish 통합 테스트
```

Bug:

```text
[BUG][UNITY] 재접속 시 Player가 중복 Spawn되는 문제
```

---

## 7. Prefix

| Prefix | 영역 |
|---|---|
| `[FE]` | React |
| `[BE]` | Spring Boot |
| `[UNITY]` | Unity Client / Server |
| `[AI]` | FastAPI / RAG |
| `[INFRA]` | AWS / Docker / CI/CD |
| `[DB]` | Database |
| `[TEST]` | Test / QA |
| `[DOCS]` | 문서 |
| `[BUG]` | Bug |

복합 작업을 `[FE/BE/UNITY/AI] 전체 구현`처럼 한 Issue로 만들지 않는다.

---

## 8. 개발 Issue Template

```markdown
## 작업 목적

-

## 작업 내용

-
-
-

## 완료 조건

- [ ]
- [ ]
- [ ]

## 선행 작업 / Dependency

- 없음

## 참고

- 기획서:
- 기능 명세:
- API:
- Figma:
- 관련 Issue:

## 테스트 방법

1.
2.
3.
```

---

## 9. Backend 예시

### 제목

```text
[BE] Booth Lease 생성 API 구현
```

### 완료 조건

- [ ] 빈 USER_RENTAL Slot을 임대할 수 있다.
- [ ] 100 Coin이 차감된다.
- [ ] Coin Ledger가 생성된다.
- [ ] 이미 임대된 Slot은 실패한다.
- [ ] Coin 부족 시 Lease가 생성되지 않는다.
- [ ] 동시에 같은 Slot을 요청해도 하나만 성공한다.
- [ ] API 문서/Swagger에서 확인 가능하다.

---

## 10. Frontend 예시

```text
[FE] Booth Studio Object 배치 기능 구현
```

완료 조건:

- [ ] Object List 선택 가능
- [ ] Canvas에 생성
- [ ] 이동
- [ ] 회전
- [ ] 삭제
- [ ] Editor State 반영
- [ ] Draft 저장 직렬화 가능

---

## 11. Unity 예시

```text
[UNITY] Published Booth Layout Runtime 생성
```

완료 조건:

- [ ] Published Layout 조회
- [ ] DTO 역직렬화
- [ ] AI_AGENT Prefab 생성
- [ ] VIDEO_SCREEN Prefab 생성
- [ ] Transform 동일
- [ ] 미지원 Type이 전체 Booth를 중단하지 않음

---

## 12. AI 예시

```text
[AI] Booth별 RAG 검색 격리 구현
```

완료 조건:

- [ ] boothId Filter
- [ ] agentId Filter
- [ ] READY/ACTIVE Document만 검색
- [ ] Source metadata 반환
- [ ] Booth A가 Booth B 문서를 검색하지 않음

---

## 13. Infra 예시

```text
[INFRA] Unity Dedicated Server Docker 이미지 생성
```

완료 조건:

- [ ] Linux Server Build
- [ ] Docker Build
- [ ] Local Container 실행
- [ ] Web Client 연결
- [ ] ECR Push

---

## 14. Bug Template

```markdown
## 발생 환경

- Branch:
- Build:
- Browser / OS:
- Account:

## 재현 방법

1.
2.
3.

## 기대 결과

-

## 실제 결과

-

## 로그 / Screenshot

-

## 관련 Issue

-

## 완료 조건

- [ ] 재현 절차에서 문제가 발생하지 않는다.
- [ ] 관련 기능 Regression이 없다.
```

---

## 15. Issue 크기

권장:

> **한 사람이 약 0.5~2일에 완료할 수 있는 크기**

2~3일 이상 예상되면 다시 분리 가능한지 검토한다.

너무 큼:

```text
Booth Studio 구현
AI 구현
멀티플레이 구현
```

적절:

```text
Booth Object 이동 구현
Draft Layout 저장 API 연동
Player Movement Sync
RAG Vector Search
```

---

## 16. Story Point

권장:

```text
1 / 2 / 3 / 5 / 8
```

| Point | 기준 |
|---:|---|
| 1 | 매우 단순 |
| 2 | 단순 구현 |
| 3 | 일반 개발 작업 |
| 5 | 연동/작업량 큼 |
| 8 | 불확실성 높음 |

8 초과면 분리를 우선한다.

---

## 17. Priority

| Priority | 기준 |
|---|---|
| Highest | Sprint Goal 또는 Demo가 막힘 |
| High | 핵심 기능 |
| Medium | 일반 기능 |
| Low | 개선 |
| Lowest | 여유 있을 때 |

### Highest 예

- Unity Web 실행 불가
- Dedicated Server 연결 불가
- Booth Publish 불가
- 로그인 불가
- AI Agent 호출 불가

---

## 18. Label

```text
frontend
backend
unity
ai
infra
mvp1
mvp2
extension
booth
multiplayer
economy
survey
consultation
event
bug
tech-debt
performance
```

Label을 기능명과 개인명으로 무한 증가시키지 않는다.

---

## 19. Workflow

```text
TO DO
→ IN PROGRESS
→ REVIEW
→ DONE
```

필요하면:

```text
BLOCKED
```

### REVIEW

- MR Review
- QA
- API 연동 확인
- 담당자 확인

중 하나를 기다리는 상태.

---

## 20. Definition of Done

- [ ] 구현 완료
- [ ] 로컬 실행 확인
- [ ] 관련 테스트 완료
- [ ] Regression 확인
- [ ] API/환경변수/문서 반영
- [ ] MR 생성 및 Merge
- [ ] 개발/배포 환경 확인
- [ ] Issue 완료 조건 충족

POC는 별도 DoD를 쓸 수 있다.

---

## 21. Blocked

```text
BLOCKED BY: FESTA-123

필요한 것:
Published Layout API Schema 확정

해결되면:
Unity LayoutLoader 연동 진행
```

`백엔드 기다리는 중`처럼 모호하게 쓰지 않는다.

---

## 22. Issue Link

예:

```text
[BE] Published Layout API
  blocks
[UNITY] Published Layout Runtime 생성
```

```text
[AI] Document Processing
  blocks
[FE] Agent Document Status 표시
```

---

## 23. 1주 Sprint 예시

```text
Sprint 1: 기획 + 핵심 POC
Sprint 2: 기반 시스템
Sprint 3: 1차 MVP
Sprint 4: 콘텐츠 기능
Sprint 5: 운영 기능
Sprint 6: 2차 MVP
Sprint 7: 안정화 / 확장
Sprint 8: 발표 준비
```

---

## 24. Sprint Goal 예

### Sprint 1

```text
브라우저 두 개가 Unity Dedicated Server에 접속하고,
Booth Layout이 React → Spring → Unity까지 전달되는 것을 검증한다.
```

### Sprint 3

```text
사용자가 Booth를 임대하고 Studio에서 만든 부스를
다른 사용자가 Unity에서 방문할 수 있는 1차 MVP를 완성한다.
```

### Sprint 6

```text
AI·Survey·Staff·Economy·Minigame을 통합해
발표 가능한 2차 MVP를 완성한다.
```

---

## 25. Sprint Planning

1. Sprint Goal 확인
2. Story 선택
3. Task 분해
4. 담당자 지정
5. Story Point
6. Dependency
7. 범위 확정

“될 것 같은 일 전부 넣기”를 하지 않는다.

---

## 26. Daily

Jira를 보며 세 가지 확인:

```text
1. 어제 완료 Issue
2. 오늘 진행 Issue
3. Blocker
```

기술 설명 회의로 길어지지 않게 한다.

---

## 27. Sprint 종료

1. DONE 확인
2. 미완료 확인
3. 미완료 이유 확인
4. 다음 Sprint 이동 여부
5. Sprint Goal 달성 판단

필요가 사라진 Issue는 무조건 이월하지 않는다.

---

## 28. Week 1 권장 Issue

### Unity

```text
[UNITY] 프로젝트 기본 구조
[UNITY] NGO Bootstrap
[UNITY] Dedicated Server Build 검증
[UNITY] 2 Client Spawn/Despawn
[UNITY] Player Movement Sync
[UNITY] Mock Booth Layout Runtime 생성
```

### Frontend

```text
[FE] Booth Studio 기본 Layout
[FE] Object List
[FE] Editor Canvas
[FE] Mock Layout JSON 생성
```

### Backend

```text
[BE] Auth 기본 API
[BE] BoothSlot Domain
[BE] Booth Domain
[BE] Lease Domain
[BE] Layout 저장 API Prototype
```

### AI

```text
[AI] FastAPI 구조
[AI] Agent Prompt Prototype
[AI] PDF Parsing
[AI] RAG Search Prototype
```

### Infra

```text
[INFRA] AWS 개발환경
[INFRA] Unity Server Dockerfile
[INFRA] Local Container 연결
```

---

## 29. Jira에 넣지 않을 전문

- 전체 기획서
- 전체 API 명세
- 전체 ERD
- Git 규칙 전문
- 긴 회의록

대신 링크:

```text
기획: [문서]
API: [문서/Swagger]
Design: [Figma]
```

---

## 30. 핵심 원칙

1. 공부가 아니라 결과를 Issue로 쓴다.
2. 완료 여부가 검증 가능해야 한다.
3. 한 Issue는 한 명이 책임질 수 있어야 한다.
4. Blocker를 숨기지 않는다.
5. 발표 가능한 핵심 Flow를 먼저 보호한다.
