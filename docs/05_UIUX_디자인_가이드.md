# SSAFY FESTA UI/UX 디자인 가이드

> **문서 목적**: Web, Unity, Admin 화면에서 공통으로 사용하는 UI/UX 원칙과 디자인 시스템 기준을 정의한다.  
> 현재 색상·폰트·세부 수치·캐릭터 스타일은 미확정이며 팀 디자인 협의 후 확정한다.

---

## 1. 적용 영역

| 영역 | 목적 |
|---|---|
| Web UI | 로그인, 부스 관리, Booth Studio, AI·설문 설정, Dashboard |
| Unity UI | 월드 탐색, 부스 이용, 실시간 상호작용, 미니게임 |
| Admin UI | 사용자·부스·이벤트·운영 관리 |

각 영역의 화면 목적은 다르지만 상태 표현과 용어는 최대한 일치시킨다.

---

## 2. 전체 UI 원칙

### 2.1 기능 우선

- Booth Studio와 운영 화면은 장식보다 작업 효율을 우선한다.
- Unity에서는 World View를 가리는 상시 UI를 최소화한다.
- 한 화면에서 사용자의 다음 행동이 명확해야 한다.

### 2.2 일관성

- 같은 기능은 같은 명칭을 사용한다.
- Button, Input, Modal, Badge, Toast 등의 상태 체계를 공통화한다.
- Web의 `Draft / Published`, Staff의 `AVAILABLE / BUSY` 등 핵심 상태 명칭을 임의 번역·변형하지 않는다.

### 2.3 정보 우선순위

1. 현재 화면 목적 / 위치
2. 주요 행동
3. 현재 상태
4. 상세 정보
5. 장식 요소

### 2.4 접근성

- 색상만으로 성공·경고·오류를 표현하지 않는다.
- 상태 텍스트 또는 아이콘을 함께 제공한다.
- 주요 텍스트와 배경의 대비를 확보한다.
- 키보드 접근이 가능한 Web 인터랙션을 우선한다.

---

## 3. 디자인 토큰

구현 이름을 먼저 고정하고 실제 값은 Figma 확정 후 채운다.

### 3.1 Color

```text
background
surface
surfaceMuted
textPrimary
textSecondary
border
primary
secondary
success
warning
danger
info
```

### 3.2 Spacing

```text
xs / sm / md / lg / xl
```

### 3.3 Radius

```text
small / medium / large / round
```

### 3.4 Shadow

```text
none / soft / default / modal
```

### 3.5 Z-Index

최소 다음 계층을 구분한다.

```text
base
sticky
popover
drawer
modal
toast
```

---

## 4. 타이포그래피

```text
Display
PageTitle
SectionTitle
CardTitle
Body
Caption
Label
ButtonText
```

### 규칙

- 제목은 짧고 기능 중심으로 작성한다.
- Button은 행동형 문구를 사용한다. 예: `저장`, `Publish`, `상담 요청`.
- 긴 안내는 Tooltip, Help, 상세 설명 영역으로 분리한다.
- 에러는 원인과 가능한 다음 행동을 함께 제공한다.

---

## 5. 공통 컴포넌트

- Button
- Input / Textarea
- Select
- Checkbox / Radio
- Card
- Modal
- Drawer
- Tabs
- Badge / Status Chip
- Tooltip
- Toast
- Loading
- Empty State
- Error State
- Confirm Dialog
- Pagination / Table Controls

### Button 계층

- Primary: 화면의 핵심 행동 하나
- Secondary: 보조 행동
- Ghost/Text: 낮은 우선순위
- Danger: 삭제·철회 등 위험 작업

한 화면에서 Primary 버튼이 과도하게 여러 개 보이지 않도록 한다.

---

## 6. 상태 표현

| 상태 | 표현 |
|---|---|
| Default | 기본 형태 |
| Hover / Focus | 상호작용 가능함을 명확히 표시 |
| Selected | Border / Surface 강조 |
| Loading | Skeleton 또는 Spinner + 작업 문구 |
| Success | 상태 색상 + 성공 문구 |
| Warning | 경고 색상 + 영향 설명 |
| Error | 오류 색상 + 해결 행동 |
| Disabled | 시각적 비활성 + 필요 시 사유 |
| Empty | 빈 이유 + 다음 행동 |

---

## 7. Web UI 가이드

### 7.1 기본 레이아웃

```text
Page
├─ Header
├─ Page Title / Breadcrumb
├─ Main Content
│  ├─ Primary Area
│  └─ Secondary Area
└─ Page Actions
```

### 7.2 운영 화면

- 테이블/리스트는 검색·필터·상태를 우선한다.
- Save와 Publish를 명확히 구분한다.
- 비동기 처리 중 상태를 숨기지 않는다.
- 설정 항목이 많으면 Section 또는 Step으로 나눈다.

---

## 8. Booth Studio UI 가이드

### 8.1 기본 구조

```text
┌──────────────┬────────────────────────┬───────────────┐
│ Object List  │      Booth Editor      │ Properties    │
│              │                        │               │
│ Decoration   │       Canvas           │ Transform     │
│ Functional   │                        │ Config        │
└──────────────┴────────────────────────┴───────────────┘
```

### 8.2 필수 표시

- 현재 Booth / Template
- Object List
- Editor Canvas
- 선택 Object
- Properties
- Save / Autosave 상태
- Preview
- Draft / Published 상태
- Publish 버튼

### 8.3 편집 UX

- 선택된 Object를 시각적으로 명확히 표시한다.
- 이동/회전 중 현재 값을 확인할 수 있어야 한다.
- 삭제는 즉시 위험성이 낮으면 Undo로 복구하고, 중요한 Config 제거는 확인 절차를 검토한다.
- 저장 실패와 Publish 실패를 서로 구분한다.

---

## 9. AI Agent Builder 가이드

### 기본 Section

1. 기본 정보: 이름, 역할, 말투
2. Prompt / 전문 분야
3. 참고 문서
4. 서비스 설정: 가격, 금지 주제, Handoff
5. 테스트(P2)

### 문서 상태

- Uploading
- QUEUED
- PROCESSING
- READY
- FAILED
- DISABLED

사용자가 `업로드 성공`과 `RAG 사용 가능`을 혼동하지 않도록 READY 여부를 별도로 표시한다.

---

## 10. Unity UI 가이드

### 10.1 기본 HUD

- 닉네임 / 사용자 정보
- Coin
- 현재 위치 또는 Booth
- Interaction 안내
- 필요 시 Channel 정보

### 10.2 상황형 UI

항상 보일 필요가 없는 UI는 상호작용 시에만 노출한다.

- AI Chat
- Video / Project
- Survey
- Consultation
- Minigame

### 10.3 Interaction

- 상호작용 가능한 오브젝트는 World에서 구분 가능해야 한다.
- 조작 키 또는 클릭 행동을 일관되게 표시한다.
- 멀티플레이 연결 상태 문제는 사용자가 이해할 수 있는 문구로 제공한다.

---

## 11. Booth 이용 UI

최소 표시 정보:

- Booth 이름
- 운영자
- 서비스 설명
- 이용 가격
- 운영/상담 상태

### AI

- AI 이름 / 역할
- 대화 영역
- 입력
- Streaming 상태
- 사람 상담 요청

### Project / Video

- 영상
- 프로젝트 요약
- 외부 링크

### Survey

- 진행 상태
- 질문 / 응답
- 제출
- 보상 여부

### Consultation

- Staff 가능 여부
- 요청 / 대기 / 연결 / 종료 상태

---

## 12. Admin UI

- 검색·필터·Table 중심
- 위험 작업은 Confirm 단계 제공
- 상태와 최근 변경을 한 화면에서 확인 가능하게 구성
- 사용자-facing 디자인보다 정보 밀도와 정확성을 우선한다.

---

## 13. 반응형 원칙

### PC

- 전체 기능 제공
- Booth Studio 정밀 편집 기준 환경

### Tablet

- 다중 Column 축소
- 관리 화면의 핵심 기능 유지

### Mobile Web

우선 검토:

- 월드 탐색
- Booth 방문
- AI 상담
- 영상
- Survey
- 투표
- 상담

Booth Studio 정밀 편집은 PC 사용을 권장한다.

---

## 14. 브랜드 / 비주얼 미확정 항목

- 로고
- 대표 색상
- 보조 색상
- 폰트
- 캐릭터 / Avatar 스타일
- 아이콘 스타일
- 2D / 3D UI 표현
- 일러스트 방향
- 애니메이션
- 사운드 피드백

디자인 확정 전 개발 코드에 임의 상수로 흩어놓지 않고 Token으로 관리한다.

---

## 15. 화면 디자인 인계 템플릿

```text
화면명:
목적:
주요 사용자:
진입 경로:
필수 정보:
Primary Action:
Secondary Action:
레이아웃:
컴포넌트:
상태:
- Default
- Loading
- Empty
- Error
- Disabled
반응형:
개발 참고:
```

---

## 16. 디자인 완료 체크리스트

- [ ] 브랜드 컬러
- [ ] 폰트
- [ ] 로고
- [ ] Button / Input / Card / Modal
- [ ] 상태 색상
- [ ] Booth Studio
- [ ] AI Agent Builder
- [ ] Survey Builder
- [ ] Dashboard
- [ ] Unity HUD
- [ ] Booth Interaction UI
- [ ] Admin UI
- [ ] 반응형 Breakpoint
- [ ] Loading / Empty / Error
- [ ] 접근성 점검
