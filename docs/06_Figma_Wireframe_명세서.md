# SSAFY FESTA Figma / Wireframe 명세서

> **문서 목적**: Figma 작업자가 바로 Frame을 생성할 수 있도록 화면 목록, 레이아웃, 상태, 연결 관계를 정의한다.  
> 실제 색상·폰트·이미지 스타일은 디자인 가이드 확정 후 적용한다.

---

## 1. Figma 파일 권장 구조

```text
00_Cover
01_Foundation
02_Components
03_Web_Auth
04_Web_Home
05_Web_Booth
06_Web_Studio
07_Web_AI
08_Web_Survey
09_Web_Staff
10_Web_Dashboard
11_Unity_HUD
12_Unity_Interaction
13_Admin
14_Prototype_Flow
```

### Page 규칙

- Foundation: Color / Typography / Spacing / Radius / Grid
- Components: 상태별 공통 컴포넌트
- 기능 Page: 실제 화면 Frame
- Prototype: 주요 Demo Flow만 연결

---

## 2. Frame Naming

```text
[Surface]_[Feature]_[State]_[Breakpoint]
```

예:

```text
WEB_Login_Default_Desktop
WEB_Studio_ObjectSelected_Desktop
WEB_AI_DocumentProcessing_Desktop
UNITY_AIChat_Streaming_Desktop
WEB_Dashboard_Empty_Desktop
```

State는 최소 `Default / Loading / Empty / Error / Disabled`를 고려한다.

---

## 3. 핵심 Prototype Flow

Figma Prototype은 전체 기능을 모두 연결하기보다 발표·개발 기준 핵심 흐름을 우선한다.

```text
Login
→ Home
→ Booth Slot
→ Lease Confirm
→ My Booth
→ Booth Studio
→ AI Agent 설정
→ Publish
→ World Entry
→ Booth Visit
→ AI Chat
→ Human Handoff
→ Survey
→ Dashboard
```

---

## 4. Web — Login

### 목적

사용자가 서비스에 인증하고 메인으로 진입한다.

### Wireframe

```text
┌─────────────────────────────────────────────┐
│                 SSAFY FESTA                 │
│                                             │
│              [ 로그인 Card ]               │
│                                             │
│  ID / Email   [________________________]    │
│  Password     [________________________]    │
│                                             │
│              [   로그인   ]                │
│                                             │
│          회원가입 / 도움말                  │
└─────────────────────────────────────────────┘
```

### States

- Default
- Validation Error
- Login Loading
- Login Failed

---

## 5. Web — Home

### 목적

월드 진입과 내 Booth 운영 상태를 빠르게 확인한다.

### Wireframe

```text
┌────────────────────────────────────────────────────────────┐
│ Header | FESTA | Coin | Profile                           │
├────────────────────────────────────────────────────────────┤
│ [ Unity SSAFY FESTA 입장 ]                                │
│                                                            │
│ 내 부스                                                    │
│ ┌──────────────────────┐  임대/운영 상태                  │
│ │ Booth Card           │  [관리하기] [Studio]             │
│ └──────────────────────┘                                  │
│                                                            │
│ 운영 중 Booth / Event                                      │
│ [Card] [Card] [Card]                                       │
└────────────────────────────────────────────────────────────┘
```

### 정보

- 보유 Coin
- 내 Booth 상태
- World 입장 CTA
- 운영 중 콘텐츠
- 진행 중 이벤트(P2)

---

## 6. Web — Booth Slot List

### 목적

사용자가 빈 Booth를 확인하고 임대한다.

```text
┌────────────────────────────────────────────────────────────┐
│ Booth 임대                                                  │
│ Coin: 250                                                   │
│                                                            │
│ [#1 ADMIN] [#2 ADMIN] [#3 ADMIN]                           │
│ [#4 사용중] [#5 빈 슬롯] [#6 사용중] [#7 빈 슬롯] ...     │
│                                                            │
│ 선택: Booth #5                                             │
│ 비용: 100 Coin / 1일                                       │
│                                      [ 임대하기 ]           │
└────────────────────────────────────────────────────────────┘
```

### Modal

```text
Booth #5를 100 Coin으로 임대하시겠습니까?
현재 잔액 250 → 예상 잔액 150
[취소] [임대]
```

### States

- 빈 슬롯 없음
- Coin 부족
- 동시 임대로 선택 슬롯 사용 불가
- 임대 성공

---

## 7. Web — My Booth

### 목적

Booth 운영 기능의 허브 역할을 한다.

```text
┌────────────────────────────────────────────────────────────┐
│ Booth #5  | PUBLISHED v3 | 임대 만료: YYYY-MM-DD           │
├────────────────────────────────────────────────────────────┤
│ [Booth Studio] [AI 직원] [Project] [Survey] [Staff]        │
│ [Dashboard]                                                 │
│                                                            │
│ 현재 상태                                                   │
│ - Published Version                                         │
│ - AI Document READY 2 / 2                                  │
│ - Staff AVAILABLE 1                                        │
│ - 오늘 방문자                                               │
└────────────────────────────────────────────────────────────┘
```

---

## 8. Web — Booth Studio

### Desktop 기준

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│ Booth #5 | [외부 설정] [내부 꾸미기] | Draft Saved 14:21 | [Preview] [Publish]│
├──────────────┬──────────────────────────────────────┬───────────────────────┤
│ OBJECTS      │              EDITOR                  │ PROPERTIES            │
│              │                                      │                       │
│ Decoration   │      ┌────────────────────────┐      │ Object: AI_AGENT      │
│ - Desk       │      │                        │      │ Position X [  ]       │
│ - Chair      │      │ [AI]          [TV]     │      │ Position Z [  ]       │
│ - Plant      │      │                        │      │ Rotation   [  ]       │
│              │      │      [SOFA]            │      │ Agent      [select]   │
│ Functional   │      │                        │      │                       │
│ - AI NPC     │      └────────────────────────┘      │ [Delete]              │
│ - Video      │                                      │                       │
│ - Survey     │                                      │                       │
├──────────────┴──────────────────────────────────────┴───────────────────────┤
│ Undo | Redo(P1) | Zoom | Grid Snap | Save State                            │
└─────────────────────────────────────────────────────────────────────────────┘
```

`외부 설정` 탭은 간판 문구, 로고, 대표색, 허용된 Facade 테마, 영업 상태만 표시한다. 위 3단 편집기는 `내부 꾸미기` 탭에서만 노출해 두 편집 문맥을 한 화면에 섞지 않는다.

### 필수 Interaction

- Object List 선택/Drag
- Canvas Object 선택
- Transform 변경
- Config 연결
- Delete
- Save
- Preview
- Publish

### States

- No Selection
- Object Selected
- Saving
- Save Failed
- Draft differs from Published
- Publish Confirm
- Publish Failed

---

## 9. Web — AI Agent Builder

```text
┌────────────────────────────────────────────────────────────┐
│ AI 직원 편집                                               │
├────────────────────────────────────────────────────────────┤
│ 기본 정보                                                   │
│ 이름 [________________] 역할 [________________]             │
│ 말투 [select] 전문 분야 [________________]                  │
│                                                            │
│ System Prompt                                               │
│ [____________________________________________________]      │
│ [____________________________________________________]      │
│                                                            │
│ 참고 문서                                                   │
│ [Upload]                                                    │
│ project.pdf   PROCESSING                                    │
│ faq.md        READY                                         │
│                                                            │
│ 서비스 설정                                                 │
│ 이용 가격 [ ]  Handoff [on/off]                            │
│ 금지 주제 [________________]                                │
│                                      [Save]                 │
└────────────────────────────────────────────────────────────┘
```

### 핵심 UX

- 파일 업로드 완료와 RAG READY를 구분한다.
- FAILED 문서는 재처리 또는 제거 행동을 제공한다.
- Agent 저장 여부와 문서 처리 상태를 독립적으로 보여준다.

---

## 10. Web — Survey Builder

```text
┌────────────────────────────────────────────────────────────┐
│ Survey Builder                                      [Save] │
├──────────────────────┬─────────────────────────────────────┤
│ Question Types       │ Survey                              │
│ - Single Choice      │ 제목 [________________________]     │
│ - Multiple Choice    │                                     │
│ - Rating             │ Q1 [__________________________]     │
│ - Short Text         │   ( ) 선택지 1                      │
│ - Long Text          │   ( ) 선택지 2                      │
│ - Application        │                                     │
│                      │ [+ Question]                        │
├──────────────────────┴─────────────────────────────────────┤
│ 익명 [ ]  1인1응답 [ ]  마감 [date]  보상 [coin]           │
└────────────────────────────────────────────────────────────┘
```

---

## 11. Web — Staff Management

```text
┌────────────────────────────────────────────────────────────┐
│ Staff                                          [직원 초대] │
├────────────────────────────────────────────────────────────┤
│ 사용자     권한              Presence       Actions        │
│ User A     OWNER             AVAILABLE       -              │
│ User B     CONSULTANT        OFFLINE         [변경] [삭제]  │
└────────────────────────────────────────────────────────────┘
```

### Consultation Request Drawer

```text
새 상담 요청
Visitor: User C
AI Agent: 프로젝트 도슨트
Summary: ...
[거절] [수락]
```

---

## 12. Web — Dashboard

```text
┌────────────────────────────────────────────────────────────┐
│ Dashboard | 기간 [7일]                                     │
├────────────────────────────────────────────────────────────┤
│ [방문 120] [AI 이용 43] [상담 8] [수익 320 Coin]           │
│                                                            │
│ Survey 응답                                                 │
│ ┌────────────────────────────────────────────────────────┐ │
│ │ 결과 요약 / Table                                     │ │
│ └────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────┘
```

고급 전환율·체류 시간은 P2로 분리한다.

---

## 13. Unity — Loading / Connection

```text
┌─────────────────────────────────────────────┐
│                 SSAFY FESTA                 │
│                                             │
│          월드에 연결하는 중...              │
│               [ Progress ]                  │
│                                             │
│ 문제가 지속되면 [다시 시도] [홈으로]       │
└─────────────────────────────────────────────┘
```

상태:

- WebGL Loading
- Session Request
- Server Connecting
- Connected
- Failed

---

## 14. Unity — World HUD

```text
┌────────────────────────────────────────────────────────────┐
│ User A | Coin 150                              11F / CH-01 │
│                                                            │
│                                                            │
│                      WORLD VIEW                            │
│                                                            │
│                                                            │
│                  [E] Booth #5 입장                         │
└────────────────────────────────────────────────────────────┘
```

Channel 표시는 실제 사용자 UX에 필요할 때만 노출한다.

입장 키를 누르면 즉시 좌표를 바꾸지 않고 다음 확인 모달을 표시한다.

```text
┌─────────────────────────────────────────────┐
│ Booth #5                                    │
│ 부스 내부로 이동하시겠습니까?               │
│                           [취소] [이동]      │
└─────────────────────────────────────────────┘
```

`이동` 선택 후 Server가 활성 임대와 입장 가능 상태를 검증한다. 실패하면 사유를 Toast로 알리고, 성공하면 내부 슬롯으로 이동한 뒤 Published Layout을 표시한다. 임대 만료 중인 방문자는 안내 후 외부로 이동한다.

---

## 15. Unity — AI Chat

```text
┌────────────────────────────────────────────────────────────┐
│ 프로젝트 도슨트                                    [X]    │
│ 등록된 프로젝트 자료를 기반으로 답변합니다.                │
├────────────────────────────────────────────────────────────┤
│ AI: 안녕하세요...                                          │
│ User: 이 프로젝트의 핵심 기술은?                           │
│ AI: Booth Studio와 ...▌                                    │
│                                                            │
│ [메시지 입력________________________] [전송]                │
│ [사람 직원에게 상담 요청]                                  │
└────────────────────────────────────────────────────────────┘
```

### States

- Creating Conversation
- Idle
- Streaming
- LLM Error
- Handoff Requested
- Staff Waiting
- Staff Connected

---

## 16. Unity — Survey

```text
┌─────────────────────────────────────────────┐
│ 프로젝트 만족도 설문                        │
│ 2 / 5                                       │
│                                             │
│ 가장 유용했던 기능은?                       │
│ [ ] AI 도슨트                               │
│ [ ] Booth Studio                            │
│ [ ] 실시간 상담                             │
│                                             │
│                      [이전] [다음]          │
└─────────────────────────────────────────────┘
```

---

## 17. Unity — Consultation

```text
┌─────────────────────────────────────────────┐
│ 사람 상담                                   │
│ 상태: 직원 연결됨                           │
├─────────────────────────────────────────────┤
│ Staff: 안녕하세요. 어떤 점이 궁금하신가요?  │
│ User: ...                                   │
│                                             │
│ [입력__________________________] [전송]      │
│                                  [종료]      │
└─────────────────────────────────────────────┘
```

---

## 18. Admin 기본 Wireframe

```text
┌───────────────┬────────────────────────────────────────────┐
│ Admin Menu    │ 검색 [________]  Filter [Status]          │
│ - Users       │                                            │
│ - Booths      │ Table                                      │
│ - Economy     │ -----------------------------------------  │
│ - Events      │                                            │
│ - Status      │                                            │
└───────────────┴────────────────────────────────────────────┘
```

---

## 19. Prototype에서 반드시 연결할 화면

1. Login → Home
2. Home → Booth Slot → Lease Success
3. My Booth → Studio
4. Studio → AI Agent Builder
5. Studio → Publish Success
6. Home → World Loading → World HUD
7. World → Booth → AI Chat
8. AI Chat → Handoff Waiting → Consultation
9. Booth → Survey → Submit Success
10. My Booth → Dashboard

---

## 20. 개발 인계 전 체크리스트

- [ ] Desktop 기준 Frame 완료
- [ ] 주요 Loading / Empty / Error 완료
- [ ] 컴포넌트 이름과 개발 명칭 일치
- [ ] Draft / Published 상태 구분
- [ ] AI Document READY/FAILED 상태 포함
- [ ] Booth Studio Object 선택 상태 포함
- [ ] Unity Interaction Prompt 포함
- [ ] 부스 입장 확인 / 임대 만료 / 빈 슬롯 상태 포함
- [ ] 상담 REQUESTED / ACTIVE 상태 포함
- [ ] Prototype 핵심 Flow 연결
- [ ] 개발자가 확인할 Annotation 추가
