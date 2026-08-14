# Feature Specification: Booth Studio / Layout 계약

**Spec**: `005-booth-studio-layout`
**Created**: 2026-08-12
**Status**: Draft — **FE + BE 검토 대기**
**주 담당**: Frontend(이정헌) + Backend
**선행 spec**: 004 (임대) — Mock API로 병행 착수 가능
**근거 문서**: docs/02 §2.4(STUDIO-01~13), docs/sdd/constitution.md Article I·21

> ⚠️ **이 spec은 프로젝트 최대의 파트 간 계약(Layout JSON)의 원본이다.**
> React가 만들고 Spring이 저장하고 Unity가 해석한다. 세 파트 중 하나라도 다르게 이해하면
> 통합 시점에 크게 터진다. 그래서 리드가 초안을 쓰되 **3파트 합동 확정**이 필요하다 (헌법 21조).
> Unity 측 소비자 구현(spec 006)은 이미 존재하므로 §계약 초안은 실제 동작하는 형태다.

---

## User Scenarios & Testing

### User Story 1 — 부스를 꾸며서 공개한다 (Priority: P0)

부스를 임대한 사용자가 웹 편집기에서 오브젝트를 배치하고, 저장했다가, 준비되면 공개한다.

**Why this priority**: 이 서비스의 UGC 핵심. "사용자가 Unity 없이 3D 공간을 만든다"는 프로젝트 정체성 그 자체.

**Independent Test**: 빈 부스 → 오브젝트 3개 배치 → 저장 → 새로고침 후에도 배치가 남아있음 → 공개 → 다른 사용자가 그 부스를 방문하면 배치가 보임.

**Acceptance Scenarios**:

1. **Given** 부스를 임대한 사용자, **When** 편집기를 열면, **Then** 배치 가능한 오브젝트 목록과 부스 영역이 보인다.
2. **Given** 편집기, **When** 오브젝트를 추가하고 위치·회전을 조정하면, **Then** 화면에 즉시 반영된다.
3. **Given** 편집 중인 배치, **When** 임시 저장하면, **Then** 아직 방문자에게는 보이지 않는다.
4. **Given** 임시 저장한 배치, **When** 다시 편집기를 열면, **Then** 마지막 저장 상태가 복원된다.
5. **Given** 임시 저장한 배치, **When** 공개하면, **Then** 방문자가 월드에서 그 배치를 볼 수 있다.
6. **Given** 공개된 배치가 있는 상태, **When** 새로 편집하고 저장만 하면, **Then** 방문자에게는 **여전히 이전 공개본**이 보인다.

### User Story 2 — 기능 오브젝트에 내용을 연결한다 (Priority: P0)

배치한 오브젝트(AI 직원, 영상, 설문 등)에 실제 콘텐츠를 연결한다.

**Independent Test**: AI 오브젝트 배치 → 특정 AI 직원 연결 → 공개 → 월드에서 그 오브젝트와 상호작용 시 연결한 AI가 응답.

**Acceptance Scenarios**:

1. **Given** 배치된 기능 오브젝트, **When** 속성 패널에서 콘텐츠를 연결하면, **Then** 연결이 배치 정보에 저장된다.
2. **Given** 콘텐츠가 연결되지 않은 기능 오브젝트, **When** 공개를 시도하면, **Then** 무엇이 비어 있는지 안내된다. [NEEDS CLARIFICATION: 공개를 막을지, 경고만 할지]

### User Story 3 — 실수로 공개되지 않게 한다 (Priority: P1)

**Acceptance Scenarios**:

1. **Given** 임대가 만료된 뒤 다시 임대한 부스, **When** 스튜디오에 진입하면, **Then** 이전 콘텐츠가 보존되어 있되 **자동으로 공개되지는 않는다**.
2. **Given** 유효하지 않은 배치(영역 이탈, 개수 초과), **When** 공개를 시도하면, **Then** 사유가 안내되고 공개되지 않는다.

### Edge Cases

- 두 사람(Owner와 Staff)이 같은 부스를 동시에 편집하는 경우 [NEEDS CLARIFICATION: 동시 편집 정책 — 잠금 / 마지막 저장 우선 / 금지]
- 오브젝트를 100개 배치하는 경우 → 월드 성능에 직접 영향 (헌법 4조)
- Unity가 모르는 새 오브젝트 타입이 배치된 경우 → **부스 전체가 실패하면 안 된다** (006 FR 참조)
- 공개 직후 임대가 만료되는 경우
- 편집 중 브라우저가 닫힌 경우 (Autosave는 P1)

---

## Requirements

### Functional Requirements

- **FR-001**: 부스 소유자는 오브젝트를 추가·이동·회전·삭제할 수 있어야 한다.
- **FR-002**: 시스템은 제공된 템플릿에서 부스 기본 구성을 선택할 수 있어야 한다.
- **FR-003**: 시스템은 규칙적 배치를 돕는 정렬(스냅) 수단을 제공해야 한다.
- **FR-004**: 시스템은 오브젝트별 속성을 편집하고 콘텐츠를 연결할 수 있어야 한다.
- **FR-005**: 시스템은 **작업본(Draft)과 공개본(Published)을 분리**해 저장해야 한다.
- **FR-006**: 방문자에게는 **공개본만** 노출되어야 한다.
- **FR-007**: 공개 전 유효성 검사를 수행하고, 실패 시 사유를 사용자에게 알려야 한다.
- **FR-008**: 배치 정보는 **버전을 가져야** 한다 (Unity가 스키마 변화에 대응할 수 있도록).
- **FR-009**: 배치 정보는 3D 자산이 아니라 **데이터(오브젝트 종류·위치·회전·연결 ID)** 여야 한다 (헌법 4조).
- **FR-010**: 부스당 배치 가능한 오브젝트 수에 상한이 있어야 한다. [NEEDS CLARIFICATION: 구체 수치]
- **FR-011**: 재임대 시 보존된 콘텐츠는 **작업본 상태로 시작**해야 한다 (자동 공개 금지).
- **FR-012**: 소유자·권한 있는 Staff 외에는 편집할 수 없어야 한다.
- **FR-013**: 배치 정보 스키마 변경은 **React·Spring·Unity 3파트 합의로만** 가능하다 (헌법 21조).

### Key Entities

- **Booth Layout**: 한 부스의 배치. 부스 식별자, 템플릿, 버전, 오브젝트 목록, 상태(작업본/공개본)
- **Layout Object**: 배치된 오브젝트 하나. 식별자, 종류, 위치, 회전, 연결된 콘텐츠 식별자
- **Object Type**: 배치 가능한 오브젝트 종류. 기능형(AI/영상/설문/상담데스크/프로젝트패널/노트북)과 장식형

---

## 공통 계약 기준 — Layout JSON

> 아래 필드명과 Object Type은 Backend/Frontend/Unity 공통 기준이며 Unity 파싱을 검증했다. 좌표 원점·오브젝트 상한·스케일 등 아래 미결정 항목은 3파트 합의 후 확정한다.

```json
{
  "boothId": 7,
  "template": "PROJECT_EXHIBITION",
  "version": 2,
  "objects": [
    { "objectId": "screen-1", "type": "VIDEO_SCREEN",
      "position": { "x": 2.1, "y": 0, "z": 3.4 }, "rotationY": 90, "configId": 152 },
    { "objectId": "ai-1", "type": "AI_AGENT",
      "position": { "x": 1.2, "y": 0, "z": 1.5 }, "rotationY": 0, "configId": 78 }
  ]
}
```

**공통 canonical type 문자열**: `AI_AGENT`, `VIDEO_SCREEN`, `PROJECT_PANEL`, `SURVEY_KIOSK`, `RECRUITMENT_BOARD`, `CONSULTATION_DESK`, `LAPTOP`, `LIKE_VOTE`, `FURNITURE`, `DECORATION`

Unity는 v0.0.1 POC 데이터 하위 호환을 위해 `SURVEY`, `CONSULT_DESK`도 읽지만 Frontend와 Backend는 신규 Layout에 canonical 문자열만 저장한다.

**확정이 필요한 지점**:

| 항목 | 현재 초안 | 논점 |
|---|---|---|
| 좌표계 | y=0 바닥 기준, 부스 로컬 좌표 | 원점이 부스 중앙인가 모서리인가 — **반드시 합의** |
| 단위 | Unity 단위(1 = 1m) | React 편집기의 픽셀↔미터 환산 규칙 |
| 회전 | `rotationY`만 (수평 회전) | X/Z 회전 필요한 오브젝트가 있는가 |
| `configId` | 정수 1개 | 오브젝트별로 여러 설정이 필요해지면? (예: 노트북 URL은 정수 ID가 아님 → 016 참조) |
| 스케일 | 없음 | 크기 조절을 허용할 것인가 |
| 버전 | 정수 증가 | 스키마 버전과 콘텐츠 버전을 구분할 것인가 |

---

## Success Criteria

- **SC-001**: 사용자가 편집기 사용법을 안내받지 않고도 오브젝트 3개를 배치해 공개까지 완료할 수 있다.
- **SC-002**: 공개한 배치가 월드에 반영되기까지 걸리는 시간이 사용자가 기다릴 만한 수준이다. [NEEDS CLARIFICATION: 목표 수치]
- **SC-003**: 작업 중 저장한 내용이 방문자에게 노출된 사례가 0건이다.
- **SC-004**: 같은 Layout JSON을 React 미리보기와 Unity 월드에서 열었을 때 배치가 일치한다.
- **SC-005**: Unity가 모르는 오브젝트가 포함된 배치에서도 나머지 오브젝트가 정상 표시된다.

---

## Clarifications

| # | 질문 | 담당 | 메모 |
|---|---|---|---|
| C-01 | 부스 최대 오브젝트 수는? | 기획 + Unity | 30~40명 월드 성능과 직결. docs/26 ②에 등록 |
| C-02 | 좌표 원점과 단위 규칙은? | **3파트 합동** | 이것이 어긋나면 배치가 통째로 틀어진다 |
| C-03 | 오브젝트 크기 조절을 허용하는가? | 기획 + Unity | 허용 시 계약에 scale 추가 필요 |
| C-04 | 콘텐츠 미연결 오브젝트의 공개를 막는가? | FE + 기획 | |
| C-05 | Owner와 Staff의 동시 편집 정책은? | BE + FE | |
| C-06 | 템플릿은 몇 종이며 무엇이 다른가? | 기획 | |
| C-07 | 공개 이력(과거 버전)을 보관하고 되돌릴 수 있는가? | BE | 재임대 미리보기와 연관 |

---

## Out of Scope (이 spec에서 다루지 않음)

- Undo/Redo, Autosave, 복제 (P1 — STUDIO-10~12)
- 템플릿 저장·공유 (P2 — STUDIO-14)
- Unity 내부 3D 표현 방식 → spec 006
- 노트북 홈페이지 열람 동작 → spec 016 (단, `LAPTOP` 오브젝트의 계약 필드는 여기서 함께 확정)

---

## 리뷰 (FE·BE 담당이 채운다 — 3칸 모두 채워야 확정)

| 항목 | 내용 | 완료 |
|---|---|---|
| ① Clarification 답변 (C-01~C-07) | | ☐ |
| ② 틀렸거나 과한 요구사항 지적 | | ☐ |
| ③ 빠진 요구사항 추가 | | ☐ |
| ④ **Layout JSON 계약 합의** (3파트 서명) | FE ____ / BE ____ / Unity ____ | ☐ |

검토자: __________ / 검토일: __________
