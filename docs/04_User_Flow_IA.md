# SSAFY FESTA User Flow + IA

> **문서 목적**: 사용자가 어떤 경로로 기능에 도달하는지와 Web / Unity의 정보 구조를 정의한다.  
> 디자인 스타일과 컴포넌트 규칙은 UI/UX 디자인 가이드에서 별도 관리한다.

---

## 1. 서비스 진입 구조

```text
SSAFY FESTA Web
├─ 로그인 / 회원가입
├─ 홈
├─ 메타버스 입장
├─ 내 부스
│  ├─ 임대 상태
│  ├─ Booth Studio
│  ├─ AI Agent
│  ├─ Project
│  ├─ Survey
│  ├─ Staff
│  └─ Dashboard
├─ 마이페이지
└─ Admin (권한 사용자)

Unity Web
├─ SSAFY World
├─ Booth Zone
│  └─ Functional Object Interaction
├─ Minigame
└─ Event / Competition (P2)
```

---

## 2. Web IA

### 2.1 Public

```text
/
├─ /login
└─ /signup
```

### 2.2 Authenticated

```text
/app
├─ /home
├─ /world
├─ /booths
│  ├─ /slots
│  └─ /mine
├─ /studio/:boothId
├─ /agents
│  └─ /:agentId
├─ /projects
├─ /surveys
│  ├─ /new
│  └─ /:surveyId
├─ /staff
├─ /consultations
├─ /dashboard
└─ /mypage
```

실제 Route 명칭은 Frontend 구현 시 변경할 수 있지만 기능 소유 관계는 유지한다.

### 2.3 Admin

```text
/admin
├─ /users
├─ /booths
├─ /economy
├─ /events
├─ /competition
└─ /service-status
```

P2 기능은 구현 범위에 따라 숨길 수 있다.

---

## 3. Unity IA

```text
Unity Root
├─ World View
├─ HUD
│  ├─ User / Nickname
│  ├─ Coin
│  ├─ Current Location / Booth
│  └─ Channel (필요한 경우)
├─ Interaction Prompt
├─ Booth Interaction Panel
│  ├─ AI Chat
│  ├─ Project / Video
│  ├─ Survey
│  └─ Consultation
├─ Minigame UI
└─ System Modal
   ├─ Loading
   ├─ Connection Error
   └─ Confirm / Exit
```

---

## 4. 핵심 User Flow

### 4.1 방문자 — 프로젝트 탐색

```text
로그인
→ 홈
→ 메타버스 입장
→ World 접속
→ 다른 사용자와 이동
→ 관심 Booth 발견
→ 외부 Booth 간판·운영 상태 확인
→ 문에서 `부스 내부로 이동하시겠습니까?` 확인
→ Dedicated Server가 활성 Lease 확인
→ 같은 Scene의 해당 Interior Anchor로 이동
→ Unity Client가 Published Layout 로드
→ 프로젝트 패널 / 영상 확인
→ AI 직원 질문
→ 설문 참여 또는 사람 상담
→ 출구로 이동해 외부 Booth 앞 복귀
→ 다른 Booth 탐색
```

### 4.2 Booth Owner — 최초 부스 생성

```text
로그인
→ 내 부스
→ 빈 Booth Slot 조회
→ 임대 버튼
→ 비용 확인
→ 임대 요청
→ 성공
→ Booth Studio 진입
→ Template 선택
→ Object 배치
→ Properties 설정
→ Draft 저장
→ Preview
→ Publish
→ Unity 월드에서 반영 확인
```

### 4.2-A 임대 만료 — 슬롯 비우기

```text
Lease 만료
→ Backend가 Slot ↔ Booth 연결 해제
→ 신규 입장 차단
→ 내부 방문자에게 종료 안내
→ Dedicated Server가 방문자를 외부 복귀 지점으로 이동
→ Unity Client가 Interior Layout 제거
→ 외부 Slot을 기본 빈 간판 상태로 표시
→ 기존 Owner의 Layout·AI·문서·설문·프로젝트는 Draft로 보존
```

새 임차인은 이전 Owner의 외부·내부 구성을 물려받지 않고 기본 Facade와 빈 내부 템플릿에서 시작한다.

### 4.3 Booth Owner — AI 직원 생성

```text
내 부스
→ AI Agent 관리
→ 새 Agent 생성
→ 이름 / 역할 / 말투 / Prompt 입력
→ 참고 문서 업로드
→ 문서 PROCESSING
→ READY 확인
→ Agent 저장
→ Booth Studio에서 AI_AGENT Object에 agentId 연결
→ Publish
```

### 4.4 Booth Owner — 프로젝트 홍보관 구성

```text
Studio
→ Video Screen 추가
→ Project Panel 추가
→ AI NPC 추가
→ Survey Kiosk 추가
→ 각 config 연결
→ Preview
→ Publish
```

### 4.5 방문자 — AI → 사람 상담

```text
AI NPC 상호작용
→ AI Conversation 시작
→ 질문 / 답변
→ 사람 상담 요청
→ 상담 가능한 Staff 확인
→ 상담 요청 생성
→ Staff 수락 대기
→ AI Summary 생성/전달
→ 상담 연결
→ 실시간 텍스트 상담
→ 상담 종료
```

### 4.6 Staff — 상담 처리

```text
로그인
→ Staff 관리 / 상담 상태
→ AVAILABLE 설정
→ 상담 요청 알림
→ 요청 상세 + AI Summary 확인
→ Accept
→ 텍스트 상담
→ 종료
→ AVAILABLE 복귀
```

### 4.7 Survey

```text
Visitor가 Survey Kiosk 선택
→ 설문 설명 확인
→ 문항 응답
→ 제출
→ 중복/마감 검증
→ 저장
→ 보상형이면 Coin 처리
→ 완료 UI
```

### 4.8 Dashboard

```text
Owner 로그인
→ Dashboard
→ 기간 / Booth 선택
→ 방문 / AI 이용 / Survey / 상담 / 수익 확인
→ 필요 시 각 상세 화면으로 이동
```

---

## 5. 월드 입장 Flow

```text
Web 인증 완료
→ Unity Web 로드
→ 인증/세션 정보 전달
→ World Session 요청
→ Channel / Server 접속 정보 획득
→ Unity Dedicated Server 연결
→ 인증 확인
→ Player Spawn
→ World 활성화
```

### 실패 분기

```text
Unity 로드 실패 → 재시도 / 오류 안내
Session 발급 실패 → Web 인증 상태 재확인
Server 연결 실패 → 재접속 / 홈으로 이동
Channel Full → 다른 Channel 재배정(P2 자동화)
```

---

## 6. Booth Publish Flow

```text
Editor State
→ Validation
→ Draft 저장
→ Preview
→ Publish 요청
→ Spring이 새 Published Version 생성
→ Unity Client가 최신 Published Version 조회
→ 기존 Local Booth Object 정리
→ Registry / Factory로 Prefab 생성
→ Object별 Config 연결
```

### Publish 전 검증 후보

- 지원되지 않는 Object Type 여부
- Booth 경계 밖 Transform 여부
- 필수 configId 누락 여부
- 권한 여부

구체 Validation 규칙은 Frontend / Unity / Backend 설계서에서 계약한다.

---

## 7. 정보 우선순위

### 7.1 Visitor

1. 월드에 입장하는 방법
2. 현재 위치와 상호작용 대상
3. Booth가 무엇을 제공하는지
4. 비용이 있는지
5. AI / 설문 / 상담을 어떻게 시작하는지

### 7.2 Owner

1. Booth 임대 상태
2. 현재 Draft / Published 상태
3. 편집·저장·Publish
4. AI / Project / Survey 연결 상태
5. 방문·수익·응답 결과

### 7.3 Staff

1. 현재 상담 가능 상태
2. 새 상담 요청
3. AI Summary
4. 진행 중 상담

---

## 8. Navigation 원칙

- `Booth Studio`, `AI Agent`, `Survey`, `Staff`, `Dashboard`는 같은 Booth 운영 맥락에서 서로 이동할 수 있어야 한다.
- Unity 내 상호작용에서 복잡한 설정 편집을 강제하지 않는다.
- 관리 기능은 Web, 실시간 경험은 Unity가 담당한다.
- 외부 링크는 사용자가 현재 메타버스 세션을 잃지 않도록 새 창 등 UX를 검토한다.
- P2 기능은 IA에 자리만 예약하고 MVP에서 비활성화할 수 있다.

---

## 9. 화면 목록

### Web P0

- Login / Signup
- Home
- World Entry
- Booth Slot List
- My Booth
- Booth Studio
- AI Agent List / Editor
- Project Editor
- My Wallet / Transactions 기본

### Web P1

- Survey Builder / Results
- Staff Management
- Consultation Panel
- Inventory / Decoration
- Dashboard

### Unity P0

- Loading / Connection
- World HUD
- Booth Interaction
- AI Chat
- Project / Video Interaction

### Unity P1

- Survey UI
- Consultation UI
- Minigame UI
- Avatar / Emote UI

### P2

- Competition
- Event / QR
- Advanced Analytics
- Booth Template Community
