# SSAFY FESTA FE 파트 역할 분담 확정안

> **문서 목적**  
> SSAFY FESTA의 Frontend 개발 책임을 명확히 구분하고, Frontend 내부 공통 규약과 Unity·Spring·FastAPI 등 타 파트와의 Contract 결정 방식을 정의한다.
>
> **적용 대상**  
> - 이정헌 — Frontend Main / Platform & Booth  
> - 김가현 — AI / Conversation Vertical
>
> **운영 원칙**  
> 본 문서는 단순 페이지 분배표가 아니라 각 담당자의 **책임 영역·결정 권한·협업 경계**를 정의하는 기준 문서로 사용한다.

---

## 1. 역할 분담 원칙

### 1.1 Frontend Main Owner

이정헌은 Frontend Main Owner로서 FE 내부 Architecture와 Convention을 관리한다.

Frontend 내부에서 공통으로 사용하는 구조와 규칙은 한 명의 Owner가 일관성 있게 관리하며, 기능별 담당자는 해당 규약을 따른다.

주요 대상:

- 프로젝트 / 폴더 구조
- Naming Convention
- 상태 관리 기준
- API Client 사용 방식
- Shared UI
- Overlay Platform 구현 방식
- Auth / Routing
- Loading / Error / Empty 처리
- 공통 타입 및 DTO 작성 규칙
- Frontend 코드 구조와 기본 개발 규약

> **Owner는 모든 코드를 직접 작성한다는 의미가 아니다.**  
> 해당 영역의 구조적 일관성, 기준 수립, 변경 관리에 대한 최종 책임을 의미한다.

### 1.2 Cross-system Contract

React 외부의 다른 시스템이 함께 소비하는 Contract는 Frontend Main이 단독으로 확정하지 않는다.

다음 항목은 관련 파트 담당자가 함께 합의한다.

- React ↔ Unity Bridge Event
- Booth Layout JSON
- Spring ↔ React DTO
- React ↔ FastAPI DTO
- 인증 / Token 전달 방식
- 실시간 통신 Event / Payload
- 파트 간 공통 ID 및 상태 표현

이정헌은 **Frontend 대표 및 FE 측 관리 Owner**로 참여하고, 실제 Contract는 Spring·Unity·AI 등 관련 담당자와 공동 확정한다.

---

## 2. 이정헌 — Frontend Main / Platform & Booth

### 2.1 역할 정의

> **FESTA Frontend의 공통 구조를 설계하고, Booth Studio와 React↔Unity 연결을 중심으로 사용자 제작·운영 플랫폼을 담당한다.**

이정헌은 특정 기능 하나보다 FESTA 웹 클라이언트 전체의 기반과 연결 구조를 소유한다.

### 2.2 Frontend Governance

다음 FE 공통 영역의 Main Owner를 담당한다.

- Frontend Architecture
- 폴더 / Feature 구조
- Naming Convention
- Shared UI
- API Client
- Auth
- Routing
- 공통 상태 관리 기준
- Error / Loading / Empty 정책
- 공통 Overlay 구조
- 공통 타입 / DTO 작성 규칙

### 2.3 Platform / Unity Integration

Unity와 React 사이의 Web Platform 영역을 담당한다.

- Unity Web Loader
- World Entry
- Unity 로딩 / 실패 / 재시도 UX
- React ↔ Unity Bridge
- Interaction Dispatcher
- Unity 상호작용 → React Feature 호출 구조
- Overlay Platform
- Overlay lifecycle 및 공통 호출 방식

예시:

```text
Unity
  ↓
AI_AGENT_INTERACT
  ↓
React Bridge
  ↓
Interaction Dispatcher
  ↓
해당 Feature 호출
```

Unity와 공유하는 Event Payload 자체는 Unity 담당자와 공동 확정한다.

### 2.4 Booth / Creator

사용자가 자신의 부스를 제작하고 공개하는 핵심 UGC 영역을 담당한다.

- Booth Studio
- 2D Booth Editor
- Object Palette
- 오브젝트 선택 / 배치 / 이동 / 회전 / 삭제
- Properties Panel
- Editor State
- Validation
- Draft / Save
- Publish
- Booth Layout 생성 및 관리
- Booth 임대 / 내 부스 관리

Booth Studio는 Unity 내부에서 직접 3D 건축을 수행하는 방식이 아니라, **React에서 부스 배치 데이터를 작성하고 Unity가 이를 3D Runtime으로 해석하는 구조**를 기준으로 한다.

### 2.5 일반 서비스 Feature

AI / Conversation Vertical을 제외한 다음 서비스 영역을 기본 담당한다.

- Project 관리 / Viewer
- Survey Builder / Viewer
- Booth 운영 관련 UI
- 일반 Dashboard
- 기타 Creator / Operation 기능

단, 일정과 구현 부하에 따라 독립적인 CRUD 기능은 Flex Backlog로 재배정할 수 있다.

---

## 3. 김가현 — AI / Conversation Vertical

### 3.1 역할 정의

> **AI 직원 생성부터 방문자의 AI 상담, 필요 시 실제 사람 상담으로 전환되는 사용자 경험을 React에서 FastAPI까지 수직으로 담당한다.**

Frontend AI 기능과 AI Backend는 구현 레이어상 분리해서 관리하되, 제품 관점에서는 하나의 AI / Conversation Vertical로 소유한다.

### 3.2 Frontend AI / Conversation

#### AI Product UI

- Agent Builder
- Agent 설정 UI
- Prompt / 역할 설정
- Document 업로드 UI
- Document 처리 상태 UI
- Agent Test 관련 UI
- AI 관련 관리 화면

#### AI Chat

- AI Chat Overlay
- AI 질문 / 답변 UI
- Source 표시
- SSE Client
- Streaming 답변 출력
- AI Error UX
- Retry UX
- AI 관련 Loading / 상태 표현

#### Human Handoff / Consultation

P1에서 다음 사용자 경험을 담당한다.

- AI → 사람 상담 전환 UI
- 상담 요청
- 상담 대기 상태
- 상담 수락 상태
- Consultation UI
- 사람 상담 채팅 화면
- 상담 종료 / 재연결 UX

사람 상담의 서버 측 상담방·Presence·WebSocket 자체는 Spring / Realtime 담당 영역으로 분리한다.

### 3.3 AI Backend

다음 AI Backend 영역을 담당한다.

- FastAPI
- PDF Parsing
- Chunking
- Embedding
- PostgreSQL + pgvector
- Booth / Agent별 RAG 검색
- LLM 호출
- 답변 생성
- Source 생성
- Conversation API
- SSE Server
- Handoff Summary
- AI 품질 테스트
- Booth / Agent 데이터 격리 테스트

AI Backend는 AI 처리와 추론을 담당하며, 일반 서비스 영속 데이터와 권한의 Source of Truth는 Spring 영역으로 유지한다.

---

## 4. 타 파트 책임 영역

FE / AI Vertical과 혼동하지 않도록 다음 영역은 별도 파트의 책임으로 둔다.

| 영역 | 주 담당 |
|---|---|
| Agent 영구 저장 / 권한 | Spring |
| Document Metadata | Spring |
| 파일 원본 / S3 | Spring / Infra |
| Booth 영속 데이터 | Spring |
| Coin / Ledger | Spring |
| 상담방 생성 / 상태 | Spring / Realtime |
| Staff Presence | Spring / Realtime |
| 사람 간 WebSocket Server | Spring / Realtime |
| 3D Prefab / World 표현 | Unity |
| Character / Movement | Unity |
| Multiplayer 내부 처리 | Unity |
| Dedicated Server | Unity / Infra |

---

## 5. 주요 기능별 책임 경계

### 5.1 Booth Studio ↔ AI Agent

```text
[이정헌]
Booth Studio
AI 블록 추가
배치 / 이동 / 회전 / 삭제
Layout 저장 / Publish
        │
        │ agentId 연결
        ▼
[공동 Contract]
Booth Feature Contract
        │
        ▼
[김가현]
Agent Builder
Prompt / Document
AI 설정 / 테스트
```

책임 기준:

- **AI 블록을 공간에 어떻게 배치하는가** → 이정헌
- **그 블록에 연결된 AI가 무엇을 하는가** → 김가현
- `agentId` 등 연결 데이터 형식 → 관련 담당자 공동 Contract

### 5.2 Unity ↔ React Overlay

```text
Unity
  ↓
Interaction Event
  ↓
React Bridge
  ↓
Interaction Dispatcher
  ↓
Overlay Platform
  ↓
Feature Overlay
```

책임 기준:

- Unity Loader / Bridge / Dispatcher → 이정헌
- Overlay Platform → 이정헌
- `AIChatOverlay` → 김가현
- Unity Event Payload → 이정헌 + Unity 담당 공동 확정
- AI Feature Input / Output → 이정헌 + 김가현 공동 확정

### 5.3 AI ↔ Spring

```text
Spring
- Agent 영구 저장
- 권한
- Document Metadata
- S3
- 상담방 / Presence

        ↕ Contract

FastAPI
- Parsing
- Embedding
- RAG
- LLM
- AI Conversation
- Summary
```

일반 서비스 데이터의 Source of Truth와 AI 처리 책임을 분리한다.

### 5.4 AI → Human Consultation

```text
AI Chat
  ↓
사람 상담 요청
  ↓
AI Handoff Summary
  ↓
Spring / Realtime 상담방
  ↓
Staff 수락
  ↓
Consultation UI
```

- AI 대화 / Handoff Summary → 김가현
- 상담 전환 / 채팅 FE → 김가현
- 상담방 / Presence / WebSocket Server → Spring / Realtime

---

## 6. MVP 단계별 담당 범위

### 6.1 P0 — 핵심 End-to-End 연결

#### 이정헌

- Frontend 기본 Architecture
- 공통 Convention
- API / Auth / Routing 기반
- Overlay Platform 기본 구조
- Booth Studio 기본 편집
- Layout / Draft / Publish
- Unity Loader
- React ↔ Unity Bridge 기본 연결
- Interaction Dispatcher
- Project 기본 기능

#### 김가현

- Agent Builder
- Document UI
- AI Chat UI
- FastAPI
- Parsing / Chunking / Embedding
- RAG
- LLM
- 기본 SSE Streaming

P0 SSE 최소 Event:

```text
start
token
done
error
```

P0의 목표는 기능을 많이 만드는 것이 아니라 다음 End-to-End 흐름을 완성하는 것이다.

```text
Booth 제작
→ Publish
→ Unity 반영
→ AI NPC 상호작용
→ AI Chat
→ FastAPI / RAG / LLM
→ Streaming 답변
```

### 6.2 P1 — 운영 및 안정화

#### 이정헌

- Booth Studio 고도화
- Undo / Redo
- Autosave
- Survey
- Creator / Operation 기능 고도화
- 일반 운영 UI

#### 김가현

- Human Handoff
- Consultation
- SSE 중단 처리
- 부분 실패 대응
- Retry / 재연결
- AI Chat UX 고도화
- Conversation 안정화

---

## 7. Flex Backlog 운영 원칙

다음과 같이 핵심 Architecture와 결합도가 낮은 독립 기능은 특정 담당자에게 영구 고정하지 않는다.

- Home
- MyPage
- Wallet
- Inventory
- Admin
- 단순 Dashboard / CRUD 화면

운영 원칙:

1. 각 담당자의 핵심 책임 영역을 우선한다.
2. 스프린트별 실제 부하를 확인한다.
3. 여유가 있는 담당자가 Flex Backlog를 가져간다.
4. Flex 업무 재배정으로 핵심 Feature Ownership을 변경하지 않는다.

업무량을 정확한 고정 비율로 관리하지 않는다.

다만 Frontend 전체 책임 구조상 **이정헌이 Frontend Main으로 더 넓은 FE 책임 범위를 가지며**, 김가현은 상대적으로 좁은 FE 범위 대신 AI Backend까지 깊게 수직 소유한다.

---

## 8. 착수 전 공동 확정 항목

각 영역을 병렬 개발하기 전에 아래 공통 Interface부터 맞춘다.

### 8.1 Frontend 구조

- AI 관련 Feature 폴더 위치
- Shared / Feature 경계
- 공통 타입 위치
- API DTO 관리 방식

### 8.2 Overlay

- Overlay Platform
- `AIChatOverlay` 등록 방식
- Overlay open / close lifecycle
- Feature Payload 전달 방식

예시:

```ts
openOverlay(type, payload)
```

구체적인 타입과 Payload는 실제 Spec 단계에서 확정한다.

### 8.3 Unity Interaction

- Unity → React Event 구조
- `AI_AGENT_INTERACT`
- objectId / agentId / boothId 등 Payload 기준
- Error / Unknown Interaction 처리

### 8.4 DTO Convention

- Agent DTO
- Conversation DTO
- Request / Response 분리 기준
- ID 타입
- 날짜 표현
- Nullable 정책
- Error Response
- SSE Event 타입

---

## 9. 최종 책임 요약

### 이정헌 — Frontend Main / Platform & Booth

**핵심 책임**

- Frontend Architecture / Convention
- Shared Platform
- Booth Studio
- Booth Layout / Publish
- Unity Loader
- React ↔ Unity Bridge
- Interaction Dispatcher
- Overlay Platform
- Project / Survey
- FE 대표 및 Cross-system Contract 관리

한 줄 정의:

> **FESTA Frontend의 공통 구조를 설계하고, Booth 제작과 Unity 연결을 중심으로 웹 플랫폼 전체의 일관성을 책임한다.**

### 김가현 — AI / Conversation Vertical

**핵심 책임**

- Agent Builder
- Document UI
- AI Chat
- SSE Client
- Human Handoff
- Consultation UI
- FastAPI
- Parsing / Embedding
- pgvector / RAG
- LLM
- Conversation / SSE Server
- AI 품질 및 격리

한 줄 정의:

> **AI 직원 생성부터 AI 상담, 사람 상담 전환까지의 사용자 경험을 React에서 FastAPI·RAG·LLM까지 수직으로 책임한다.**

---

## 10. 개발 진행 원칙

최종 역할 분담 확정 이후에는 다음 순서로 진행한다.

```text
Frontend Architecture / Convention 확정
        ↓
공통 Overlay / Feature 구조 확정
        ↓
Cross-system Contract 최소 합의
        ↓
각 담당 영역 병렬 개발
        ↓
Contract Test / Integration
        ↓
P0 End-to-End 검증
```

핵심 원칙:

> **공통 Interface를 먼저 맞추고, 구현은 각 Owner가 독립적으로 병렬 진행한다.**

> **Frontend 내부 규약은 이정헌이 Main Owner로 관리하고, 다른 파트가 소비하는 Contract는 관련 담당자가 공동 확정한다.**

> **기능별 Owner는 해당 영역의 설계·일관성·완료 책임을 가지되, 모든 구현을 단독 수행한다는 의미는 아니다.**
