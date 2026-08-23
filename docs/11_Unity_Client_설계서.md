# SSAFY FESTA Unity Client 설계서

> **대상**: Unity 6 Web Client  
> **네트워크**: Netcode for GameObjects 기반 Dedicated Server 연결  
> **핵심 책임**: 월드 렌더링, Player 표현, Booth Runtime, 기능 오브젝트 상호작용, Minigame Client

---

## 1. 설계 목표

1. Unity Client는 실시간 경험과 3D 표현에 집중한다.
2. Booth 콘텐츠는 Scene에 하드코딩하지 않고 Published Layout 데이터로 생성한다.
3. 정적 Booth Object는 네트워크 동기화를 하지 않는다.
4. 서버 authoritative 영역과 Client-only 표현 영역을 분리한다.
5. Web 환경에서 로딩·메모리·렌더링 비용을 지속적으로 측정한다.

---

## 2. Client 책임

### 담당

- SSAFY 11F World 렌더링
- 입력 처리
- Player Camera
- Network 연결
- Remote Player 표현
- Booth Layout 조회 및 Runtime Spawn
- Functional Object Interaction
- AI / Project / Survey / Consultation UI
- Minigame Client
- Audio / Animation / Emote 표현

### 담당하지 않음

- Booth Lease 최종 판정
- Coin Ledger 수정
- Staff 권한 최종 판정
- AI RAG / LLM
- Booth Layout 영구 저장

---

## 3. Scene 구성 제안

```text
Bootstrap
└─ 초기 설정 / 인증 전달 / 서비스 초기화

World_11F
├─ Static Environment
├─ Booth Exterior Slots (고정 외부 건물·간판·입구)
├─ Booth Interior Anchors (시야 밖 원거리, 물리 임대 슬롯별 1개)
├─ Player Spawn Points
├─ Interaction Zones
└─ UI Root

Minigame_<Type>
└─ 필요 시 Additive 또는 별도 Scene
```

Scene 수를 지나치게 늘리지 않고 MVP에서는 `Bootstrap + World` 중심으로 시작한다.

---

## 4. Assembly / Folder 구조 제안

```text
Assets/
├─ _Project/
│  ├─ Scripts/
│  │  ├─ Core/
│  │  ├─ Network/
│  │  ├─ Player/
│  │  ├─ World/
│  │  ├─ Booth/
│  │  ├─ Interaction/
│  │  ├─ UI/
│  │  ├─ Minigame/
│  │  └─ Infrastructure/
│  ├─ Prefabs/
│  │  ├─ Player/
│  │  ├─ BoothObjects/
│  │  └─ UI/
│  ├─ ScriptableObjects/
│  ├─ Materials/
│  ├─ Animations/
│  └─ Scenes/
└─ ThirdParty/
```

Unity 패키지와 프로젝트 코드를 물리적으로 분리한다.

---

## 5. Bootstrap 구조

```text
GameBootstrap
├─ AppConfigLoader
├─ AuthSessionProvider
├─ ApiClient
├─ WorldSessionClient
├─ NetworkLauncher
├─ SceneLoader
└─ GlobalUI
```

### 초기 Flow

```text
Unity Web Build Load
→ Web에서 전달된 인증/환경 정보 확인
→ World Session 요청
→ Network Endpoint 획득
→ Dedicated Server 연결
→ 연결 성공
→ World Scene 활성화
→ Player Spawn 대기
```

---

## 6. Player Client 구조

```text
PlayerRoot
├─ NetworkObject
├─ NetworkTransform 또는 프로젝트 선택 동기화 컴포넌트
├─ PlayerInputController   # Owner only
├─ PlayerMotor            # 입력/이동 구조는 서버 설계와 합의
├─ PlayerAnimator
├─ AvatarView
├─ NicknameView
└─ InteractionDetector
```

### Local Player

- 입력 처리
- Camera target
- Interaction 탐지
- Local UI 연결

### Remote Player

- 입력 처리 없음
- 서버에서 동기화된 상태 표현
- Nickname / Avatar / Animation 표시

---

## 7. Player Sync 데이터

최소 동기화 후보:

```text
Position
Rotation
Animation State
Emote
Nickname / Avatar identifier
Current Booth / Zone
```

모든 Animator Parameter를 네트워크에 그대로 올리지 않고 필요한 의미 상태만 전송한다.

---

## 8. Booth Runtime 구조

```text
BoothRuntimeController
      │
      ├─ BoothLayoutLoader
      │      └─ Spring Published Layout API
      │
      ├─ BoothObjectRegistry
      │      └─ ObjectType → Prefab Definition
      │
      ├─ BoothObjectFactory
      │      └─ Instantiate
      │
      └─ BoothObjectBinder
             └─ configId 연결
```

---

## 9. BoothLayoutDto

```csharp
[Serializable]
public class BoothLayoutDto
{
    public long boothId;
    public int version;
    public string template;
    public BoothObjectDto[] objects;
}

[Serializable]
public class BoothObjectDto
{
    public string objectId;
    public string type;
    public Vector3Dto position;
    public float rotationY;
    public long? configId;
    public string assetCode;
}
```

DTO 명칭과 JSON Field는 Backend/Frontend와 동일 계약을 사용한다.

---

## 10. BoothObjectRegistry

Registry는 `type`을 실제 Prefab으로 매핑한다.

```text
AI_AGENT           → AiNpcPrefab
VIDEO_SCREEN        → VideoScreenPrefab
PROJECT_PANEL       → ProjectPanelPrefab
SURVEY_KIOSK        → SurveyKioskPrefab
RECRUITMENT_BOARD   → RecruitmentBoardPrefab
CONSULTATION_DESK   → ConsultationDeskPrefab
LIKE_VOTE           → LikeVotePrefab
DECORATION          → assetCode 기반 Prefab
```

### 구현 원칙

- 거대한 `switch`가 프로젝트 곳곳에 퍼지지 않게 Registry 한 곳에서 관리한다.
- 지원하지 않는 Type은 Error Placeholder 또는 Skip 처리한다.
- 하나의 Object 실패가 전체 Booth 로딩을 막지 않는다.

---

## 11. Booth Object Base

```text
BoothObjectView
├─ objectId
├─ type
├─ configId
└─ Initialize(context)
```

기능형 Object는 필요한 Service만 의존한다.

예:

```text
AiNpcView
→ AiInteractionService

SurveyKioskView
→ SurveyService
```

Prefab가 API Client를 직접 생성하지 않는다.

---

## 12. 정적 Object와 NetworkObject 구분

### NetworkObject 아님

- Desk
- Chair
- Sofa
- Plant
- Light
- Wall / Floor
- Decoration
- Static Sign
- Video Screen의 물리 Transform
- AI NPC의 정적 배치 Transform

### NetworkObject 후보

- Player
- Minigame Actor
- 서버 권위가 필요한 공동 Interaction

기능 Object가 API를 호출한다고 해서 NetworkObject일 필요는 없다.

---

## 13. Booth 입장 / Zone

1차 MVP는 사용자별 Scene이나 서버를 만들지 않고 같은 `World_11F` Scene 안의 내부 슬롯 풀을 사용한다.

```text
ExteriorSlot(slotNo)
→ 입장 확인 UI
→ Dedicated Server에 boothId 입장 요청
→ 활성 Lease·입장 가능 상태 확인
→ InteriorAnchor(slotNo)로 서버 권한 이동
→ Local Player Client가 Published Layout 조회
→ 해당 Anchor 아래 Local Prefab Spawn
```

내부 앵커는 사용자 수가 아니라 물리 임대 슬롯 수만큼만 둔다. 사용자 임대 슬롯이 12개면 내부 앵커도 12개다 (#62, 2026-08-23 — 축제 존 12부스·내부 홀 12실과 대응). 각 Client는 자신이 입장한 내부 Layout만 로드하고, 다른 내부의 정적 오브젝트는 생성하지 않는다.

```text
Exit
→ UI 종료
→ Dedicated Server가 외부 복귀 지점으로 이동
→ BoothRuntime.Clear()
→ CurrentBooth 해제
```

외부는 고정 Prefab과 제한된 Facade 설정만 사용하고, 내부만 자유 Layout으로 꾸민다. 임대 만료 시 신규 입장을 막고 내부 플레이어를 먼저 외부로 이동한 뒤 Runtime Object를 제거한다. 기존 Owner 데이터는 Spring에 보존하며 새 임차인의 내부에는 사용하지 않는다.

일반 방문은 공유 입장이다. 상담·비공개 행사 등 독점이 필요한 기능은 추후 별도 세션/예약으로 분리한다. P2 Booth Instance 전환은 Server/Realtime 문서에서 확장한다.

---

## 14. Interaction 구조

```text
InteractionDetector
→ IInteractable 후보 탐지
→ InteractionPrompt 표시
→ Input
→ Interact()
```

Interface 후보:

```csharp
public interface IInteractable
{
    string InteractionLabel { get; }
    bool CanInteract(PlayerContext context);
    void Interact(PlayerContext context);
}
```

상호작용 키는 한 곳에서 통일한다.

---

## 15. AI NPC Client Flow

```text
Player Interact
→ Agent 정보 조회
→ 필요 시 Spring 이용 승인
→ FastAPI Conversation 생성
→ Chat UI Open
→ Message Send
→ SSE/Streaming Bridge
→ Token 표시
→ Handoff 요청 시 Spring Consultation
```

Unity가 AI 답변을 Dedicated Server를 통해 중계하지 않는다.

---

## 16. Video Screen

MVP에서는 영상 URL과 Web 환경 호환성을 먼저 검증한다.

고려:

- Unity VideoPlayer로 직접 재생 가능한지
- 외부 URL/CORS 정책
- Web 페이지 overlay가 더 안정적인지

최종 방식은 POC 후 결정한다. 영상 기능 때문에 전체 Booth Runtime 구조를 변경하지 않는다.

---

## 17. Survey Kiosk

```text
Interact
→ Survey Metadata 조회
→ Unity Survey UI 표시
→ Response Submit
→ 성공/보상 결과 표시
```

복잡한 Survey Builder는 React에서만 제공한다.

---

## 18. Consultation Desk

```text
Interact
→ Booth Staff 상태 조회
→ 상담 요청
→ REQUESTED UI
→ Realtime Event 수신
→ ACCEPTED
→ Chat UI
```

AI Chat에서 바로 Handoff하는 Flow와 같은 Consultation 모듈을 재사용한다.

---

## 19. Minigame Client

게임 규칙 자체는 별도 게임 기획 확정 후 구현한다.

Client 책임:

- 입력
- 표현
- UI
- 서버에서 승인된 결과 표시

보상 Coin을 Client가 직접 증가시키지 않는다.

---

## 20. UI 계층

```text
Canvas / UI Root
├─ HUD
├─ InteractionPrompt
├─ BoothPanel
├─ AIChatPanel
├─ SurveyPanel
├─ ConsultationPanel
├─ MinigamePanel
├─ LoadingOverlay
└─ ErrorModal
```

UI 간 직접 참조를 줄이고 Navigation/Panel Manager를 둔다.

---

## 21. API Client

Unity에서 필요한 REST 호출:

- Published Layout
- Booth Metadata
- Project
- Survey
- AI 사용 승인
- Consultation
- World Session 초기 정보

공통 처리:

- Authorization
- timeout
- JSON deserialize
- 오류 코드
- 401/403
- retry 가능 여부

---

## 22. Web ↔ Unity Bridge

후보:

```text
React JS
→ Unity SendMessage

Unity
→ JavaScript function / jslib
```

전달 후보:

- 짧은 수명의 Unity connection token
- API base url
- 사용자 UI 식별정보

장기 Access Token을 불필요하게 JS Global에 복제하지 않는 구조를 검토한다.

---

## 23. Addressables / Asset 관리

기존 기획에서 필수 기술로 확정된 것은 아니므로 P0 강제사항이 아니다.

다만 Decoration 수가 증가하면:

- Prefab Registry
- AssetCode
- Build Size

관점에서 Addressables 도입을 검토할 수 있다.

초기에는 구조만 `assetCode → Prefab`으로 분리해 향후 교체 가능하게 한다.

---

## 24. Web 성능 기준

측정 대상:

- 초기 Build download
- Scene load
- Peak memory
- Draw Call
- Avatar SkinnedMesh
- Animator cost
- Network serialization
- Booth Object count

30명 목표는 실제 측정 결과에 따라 조정한다.

---

## 25. 오류 처리

### Published Layout 실패

- Booth 기본 구조는 유지
- 재시도 버튼 또는 일정 횟수 재요청
- 전체 World 종료 금지

### 기능 API 실패

- 해당 Object UI만 Error
- 다른 기능 이용 가능

### Network Disconnect

- Reconnect UX 또는 홈 이동을 제공
- 정확한 재접속 정책은 Server 문서에서 확정

---

## 26. 테스트

### EditMode

- DTO parsing
- Registry mapping
- Layout validation
- Object Factory

### PlayMode

- Booth Load
- Interaction
- Player UI

### Multiplayer

- 2 Client Spawn
- Movement
- Disconnect
- Remote Avatar

### Browser

- 실제 Web Build에서 API / Network / Video / Input 테스트

---

## 27. P0 구현 순서

1. Bootstrap / Web Build
2. Dedicated Server 연결
3. Player Spawn / Movement
4. World Prototype
5. BoothLayoutDto
6. Mock Layout Runtime Spawn
7. Spring Published Layout 연결
8. Functional Object 기본 Interface
9. AI NPC
10. Project / Video
11. Booth E2E

---

## 28. 확정 필요 사항

- Unity Transport / WebSocket 최종 방식
- 이동 권위 수준
- NetworkTransform 세부 설정
- Video 재생 방식
- Unity ↔ Web 인증 전달
- Addressables 도입 시점
- World 재접속 정책
- Mobile Web 지원 범위
