# SSAFY FESTA Frontend 설계서

> **대상**: React + TypeScript Web Client  
> **핵심 책임**: 관리·제작 UI, Booth Studio, Agent/Survey/Staff/Dashboard, Unity Web 진입  
> 특정 상태관리 라이브러리는 현재 자료에서 확정되지 않았으므로 설계 원칙만 정의한다.

---

## 1. Frontend 책임

### 담당

- 로그인 / 계정 UI
- Home
- Booth Slot / Lease UI
- My Booth
- Booth Studio
- AI Agent Builder
- Document Upload 상태
- Project 관리
- Survey Builder
- Staff / Consultation 운영 UI
- Wallet / Inventory
- Dashboard
- Admin
- Unity Web 실행 및 세션 연결 시작

### 담당하지 않음

- Coin 계산의 최종 판정
- Booth 임대 동시성 제어
- AI Vector Search
- Player Multiplayer authoritative state

---

## 2. 권장 애플리케이션 구조

```text
src/
├─ app/
│  ├─ router/
│  ├─ providers/
│  └─ config/
├─ pages/
├─ features/
│  ├─ auth/
│  ├─ booth/
│  ├─ studio/
│  ├─ agent/
│  ├─ project/
│  ├─ survey/
│  ├─ staff/
│  ├─ consultation/
│  ├─ wallet/
│  ├─ inventory/
│  └─ dashboard/
├─ entities/
│  ├─ user/
│  ├─ booth/
│  ├─ layout/
│  └─ agent/
├─ shared/
│  ├─ api/
│  ├─ ui/
│  ├─ hooks/
│  ├─ utils/
│  ├─ types/
│  └─ constants/
└─ unity/
   ├─ loader/
   └─ bridge/
```

폴더 구조는 팀 선호에 맞게 단순화할 수 있지만 기능별 책임 분리는 유지한다.

---

## 3. Route 설계

```text
/login
/signup
/auth/callback
/app/home
/app/world
/app/booths/slots
/app/booths/mine
/app/studio/:boothId
/app/games/:gameId/edit
/app/games/:gameId/play
/app/agents
/app/agents/:agentId
/app/projects
/app/surveys
/app/surveys/:surveyId
/app/staff
/app/consultations
/app/dashboard
/app/mypage
/admin/*
```

### Route Guard

- 인증 필요
- Booth Owner/Staff 권한 필요
- Admin 권한 필요

UI에서 메뉴를 숨겨도 서버 인가를 대체하지 않는다.

---

## 4. 상태 분리 원칙

### 4.1 Server State

API에서 가져오는 데이터:

- User
- Booth
- Layout Version
- Agent
- Document Status
- Survey
- Staff
- Wallet
- Dashboard

Caching/Fetching 도구는 팀이 선택하되 서버 상태를 임의로 여러 Store에 복제하지 않는다.

### 4.2 Local UI State

- Modal open
- 현재 Tab
- Dropdown
- Form dirty state
- Booth Studio 선택 Object
- Canvas zoom

### 4.3 Editor State

Booth Studio는 일반 Form과 별도 취급한다.

```text
EditorState
- boothId
- template
- objects[]
- selectedObjectId
- dirty
- saveStatus
- history(P1)

FacadeEditorState
- themeCode
- primaryColor
- signText
- logoUrl
```

MVP에서 `EditorState`는 내부 Booth Layout 전용이다. 외부는 자유 오브젝트 배치를 제공하지 않고 `FacadeEditorState`의 제한된 값만 편집한다.

---

## 5. API Client

### 공통

- Base URL 환경변수
- Authorization Header
- Error normalization
- requestId 표시 가능성
- 401 처리
- timeout 구분

### Error Type

```ts
type ApiError = {
  code: string;
  message: string;
  requestId?: string;
};
```

사용자 메시지와 개발 로그를 분리한다.

---

## 6. Booth Studio 아키텍처

```text
ObjectPalette
      │
      ▼
EditorCanvas ────── Selection
      │                │
      │                ▼
      │          PropertiesPanel
      │                │
      └────────┬───────┘
               ▼
          EditorState
               │
        ┌──────┴──────┐
        ▼             ▼
   Draft Save      Preview/Publish
        │             │
        └──── API ────┘
```

편집 화면은 `외부 설정`과 `내부 꾸미기`를 분리한다.

- 외부 설정: 간판·로고·대표색·허용된 Facade 테마
- 내부 꾸미기: Unity가 제공한 Object Palette의 위치·회전·콘텐츠 연결
- 외부와 내부 모두 Lease가 활성일 때만 월드에 노출되며, 만료 후 데이터는 Owner 계정에 보존한다.
- 일반 방문 예약 UI는 만들지 않는다. 상담·비공개 행사처럼 독점이 필요한 기능에서만 별도 예약 UI를 추가한다.

---

## 7. Layout Type

```ts
type Vector3Dto = {
  x: number;
  y: number;
  z: number;
};

type BoothObjectType =
  | 'AI_AGENT'
  | 'VIDEO_SCREEN'
  | 'PROJECT_PANEL'
  | 'SURVEY_KIOSK'
  | 'RECRUITMENT_BOARD'
  | 'CONSULTATION_DESK'
  | 'LIKE_VOTE'
  | 'LAPTOP'
  | 'DECORATION'
  | 'FURNITURE';

type BoothObjectDto = {
  objectId: string;
  type: BoothObjectType;
  position: Vector3Dto;
  rotationY: number;
  configId?: number;
  assetCode?: string;
};

type BoothLayoutDto = {
  boothId: number;
  version: number;
  template: string;
  objects: BoothObjectDto[];
};

type BoothFacadeDto = {
  themeCode?: string;
  primaryColor?: string;
  signText?: string;
  logoUrl?: string;
};
```

Layout Object 식별자는 `objectId`, 공개 조회 경로는 `/booths/{boothId}/layouts/published`로 통일한다. 신규 Object Type은 Backend 기능 명세의 canonical 문자열을 사용한다.

---

## 8. Editor Validation

Publish 전에 Frontend에서 빠른 검증을 하되 최종 검증은 Backend가 수행한다.

### 후보

- objectId 중복
- 지원하지 않는 Object Type
- Functional Object의 configId 누락
- 숫자가 아닌 Transform
- Booth 경계 밖 위치
- 기능 수 제한 정책 위반(TBD)

---

## 9. Save / Publish 정책

### Draft Save

- 편집 내용을 저장하지만 방문자에게 반영하지 않는다.
- 성공/실패 상태를 명확히 표시한다.

### Publish

- 현재 Draft를 공개 Version으로 전환한다.
- Publish 전에 Validation을 실행한다.
- Published Version 번호를 UI에 표시한다.

### Autosave — P1

- 사용자가 입력 중일 때 과도한 요청을 만들지 않도록 debounce를 적용한다.
- 마지막 저장 실패를 숨기지 않는다.

---

## 10. Undo / Redo — P1

권장 방식:

```text
Command 또는 Snapshot History
```

MVP 범위가 빡빡하면 Object add/move/rotate/delete 정도부터 지원한다.

Editor State 전체를 매 프레임 저장하지 않는다.

---

## 11. AI Agent Builder

### Form 영역

- 기본 정보
- System Prompt
- 참고 문서
- 서비스 가격
- 금지 주제
- Handoff

### Document Upload

```text
파일 선택
→ Spring Upload URL
→ S3 Upload
→ complete/process 연계
→ QUEUED
→ PROCESSING
→ READY / FAILED
```

Polling 또는 Realtime 상태 전달 방식은 구현 선택 가능하다. P0은 단순 Polling도 허용한다.

---

## 12. Survey Builder

Data Model 예시:

```ts
type SurveyQuestion = {
  id?: number;
  tempId: string;
  type: 'SINGLE_CHOICE' | 'MULTIPLE_CHOICE' | 'RATING' | 'SHORT_TEXT' | 'LONG_TEXT' | 'APPLICATION';
  title: string;
  required: boolean;
  options?: { tempId: string; label: string }[];
};
```

Builder는 질문 순서 변경, 추가, 삭제가 가능한 구조로 만든다. Drag sort는 P1 범위와 일정에 따라 결정한다.

---

## 13. Staff / Consultation

### Staff 관리

- 목록
- 초대
- Role 변경
- Presence 표시

### Realtime 상담

WebSocket 연결 모듈과 화면 상태를 분리한다.

```text
Socket Event
→ Consultation Store/State
→ Notification
→ Request Drawer
→ Chat Panel
```

새 상담 요청을 페이지마다 별도 Socket으로 만들지 않고 상위 Provider/Manager에서 관리한다.

---

## 14. Unity Web Integration

### 진입

```text
React
→ World Session 요청
→ Unity Loader
→ Build Load
→ Unity에 Session/Connection 정보 전달
```

### Bridge 후보

- JavaScript → Unity `SendMessage`
- Unity → JavaScript Callback
- URL/Global object 기반 초기 설정

정확한 방식은 Unity Web POC 후 확정한다.

### 전달 금지

장기 유효 Secret을 JS 전역에 노출하지 않는다. Unity 연결용 정보는 짧은 수명의 Token을 권장한다.

---

## 15. UI 공통 상태

모든 주요 비동기 화면은 다음을 지원한다.

```text
idle
loading
success
empty
error
```

특히:

- Booth Slot 없음
- Coin 부족
- Document PROCESSING
- Publish 실패
- Staff 없음
- Consultation 연결 실패

을 별도 UX로 정의한다.

---

## 16. Error Handling

### 401

- 인증 갱신 가능 여부 확인
- 실패 시 로그인으로 이동

### 403

- 권한 부족 안내
- 화면이 잘못 노출됐더라도 작업 실행 금지

### 409

동시성 충돌에 사용하기 적합한 사례:

- 이미 임대된 Booth
- 이미 Accept된 Consultation
- Version 충돌

### 5xx

- 재시도 가능 안내
- requestId 제공 가능하면 표시

---

## 17. 성능

- Unity Web Build는 Lazy entry로 분리한다.
- Dashboard 등 무거운 기능은 필요한 시점에 로드한다.
- 큰 이미지/영상은 Frontend bundle에 포함하지 않는다.
- Booth Editor에서 Object 이동 중 서버 요청을 발생시키지 않는다.
- Autosave는 debounce한다.

---

## 18. 테스트 전략

### Unit

- Layout 변환
- Validation
- Coin 표시 formatter
- Survey validation

### Component

- Booth Slot
- Properties Panel
- Document Status
- Consultation Request

### Integration

- Lease API
- Draft Save / Publish
- Document Upload
- Survey Submit

### E2E

- Login → Lease → Studio → Publish
- AI Agent 설정
- Staff 상담 요청 수락

---

## 19. 환경변수 후보

```text
VITE_API_BASE_URL
VITE_AI_API_BASE_URL
VITE_UNITY_BUILD_URL
VITE_REALTIME_URL
```

Secret 값은 Frontend 환경변수에 넣지 않는다.

---

## 19A. Game Studio P2 내부 lazy 모듈

Game Studio는 Unity WebGL 위에 그리는 UI가 아니라 React 계층의 독립 제작기와 2D Runtime이다.
배포와 origin은 기존 `festa-frontend`를 사용하되 코드 소유권은 `src/game-studio/`로 분리한다.
기존 인증·`shared/api/client.ts`·오버레이 Shell을 재사용하고 lazy route로 초기 번들을 격리한다.

- 제작: `/app/games/:gameId/edit`
- 플레이: `/app/games/:gameId/play`
- Preview: 같은 origin의 내부 Runtime iframe. Token은 `postMessage`에 넣지 않는다.

Frontend 책임:

- Scene 목록 + Object/Asset palette + Map/Dialogue 작업 공간 + Properties/Event inspector 편집 흐름
- TOP_DOWN과 OVERLAY/FULL_SCREEN DIALOGUE 편집, 제한형 Component/Event 조합 UI
- Tile/Object 배치와 Asset reference 분리, preset 편의 입력의 공통 Event recipe 변환
- 공통 GameProject 타입과 Schema 기반 client validation
- Draft autosave/revision 충돌 UI, Preview, Publish 요청
- Published Version의 Canvas 기반 2D 실행
- 독립 게임 URL과 FESTA 오버레이 진입 경로
- 기존 `window.FestaUnity.onBoothInteract` 이벤트를 Portal Resolver에 연결
- `OnOverlayStateChanged({ state: OPENED|CLOSED|FAILED, overlay: GAME })`로 Unity 입력 lifecycle 전달

Frontend 비책임:

- Published Version의 최종 유효성·권한 판정
- 보상·랭킹·영구 결과 계산
- Unity 안에서 GameProject를 실행하는 기능
- AI 응답 없이는 저장할 수 없는 제작 흐름

확정 경계는 [`specs/019-game-studio/contracts/part-boundaries.md`](../specs/019-game-studio/contracts/part-boundaries.md)다.
Host 계약 #20은 반영했으며 Studio 내부 선택은 [#35](https://github.com/kanghyunsoon/ssafesta/issues/35),
Portal 타입은 [#34](https://github.com/kanghyunsoon/ssafesta/issues/34)의 signed Int32·BE-first whitelist 계약을 따른다.

신규 실행은 route/overlay 진입 시 REST 조회로 판정하고 전용 게임 socket은 만들지 않는다. 비공개 전환 전
이미 로드된 무보상 세션은 종료까지 허용한다. FE의 `configId`는 정수 `number`이며
`1..2147483647` 밖의 값과 0은 API 호출 전에 거부한다.

---

## 20. 확정 필요 사항

- 상태관리 / Server State 라이브러리
- Form 라이브러리
- Unity Bridge 방식
- Autosave 주기
- Layout 충돌 처리
- 모바일 Booth Studio 지원 범위
- WebSocket 인증 갱신 방식
- Game Runtime renderer와 후속 PLATFORMER physics adapter (#35)
- Game Studio 실제 반응형 패널 배치, Preview 표시 형태와 iframe sandbox/CSP (#35)
- builtin Asset resolver 내부 구조 (#35)
- 사용자 Asset upload 도입 시 Runtime resolver와 cache 정책 (별도 Asset spec)
