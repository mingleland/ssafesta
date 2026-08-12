# festa-unity Architecture

> Unity 파트의 책임 경계와 구조. 전체 시스템은 `docs/07_전체_시스템_아키텍처.md`,
> 착수 전 기술 결정은 `docs/21_착수전_기술결정_ADR.md` 참조.

## 1. Unity 책임 / 비책임

**담당**: World 렌더링, Avatar, Multiplayer(NGO), Presence 표현, Booth Entry,
Booth Runtime(Layout → Local Prefab), 상호작용 트리거, 관리자 Minigame

**담당하지 않음** (Spring/FastAPI 중복 구현 금지):
Coin/Lease/Layout 영구 저장, AI 추론/RAG, Staff 권한 판정, Survey 결과 저장

**텍스트 입력 UI 원칙**: AI 채팅·상담·설문 입력은 React 오버레이가 담당.
Unity는 상호작용 트리거만 발생시킨다 (한글 IME 문제 회피, ADR 결정 4).

## 2. 네트워크 구조

```
Browser (Unity Web, wss://) → ALB (TLS 종료) → Unity Dedicated Server (ws, ECS Task)
```

- Transport: **WebSocket 강제** (`NetworkBootstrap`이 UseWebSockets=true 설정).
  브라우저는 UDP 불가 → 모든 환경(에디터 포함)에서 WebSocket으로 통일해 검증 환경 차이를 없앤다.
- 접속 흐름: `IUserApiClient.CreateWorldSessionAsync()` → endpoint/connectionToken 획득
  → `ConnectionManager.StartClient()` → 서버 Approval(토큰 검증) → Player Spawn.
  **endpoint 하드코딩 금지** — 채널 확장 시 서버 로직만 바꾸면 되게 유지.
- 이동 권위: Owner(Client) 권위 + 서버 검증 여지 (doc 12 §10 A안).
  `ClientAuthoritativeNetworkTransform` 사용.
- 동기화 최소화: Player 상태(위치/회전/AnimState/Emote/CurrentBooth)만 동기화.
  **Booth 정적 오브젝트는 NetworkObject가 아님** — 각 클라이언트 Local Spawn.

## 3. Booth Runtime 데이터 흐름

```
Spring Published Layout (JSON)
  → IBoothApiClient.GetPublishedLayoutAsync()
  → BoothLayoutParser (DTO)
  → BoothRuntime (부스 앵커)
  → BoothObjectFactory (+ BoothObjectRegistry: type→prefab)
  → Local Prefab Spawn (+ BoothRuntimeObject로 출처 보관)
  → 타입별 Content 컴포넌트 부착 (AiNpcInteractable 등)
```

- Registry에 프리팹이 없으면 placeholder primitive 생성 → 아트 없이 로직 검증 가능
- Unknown type은 스킵 (구버전 클라이언트가 신규 타입에 깨지지 않게)
- Layout JSON 스키마는 **Draft** — React/Spring/Unity 3파트 합의로만 변경

## 3-1. 아바타 (Booth Runtime과 같은 원칙)

```
커스터마이징 창(현재 Unity HUD, 향후 React 오버레이)
  → PlayerAppearanceController.RequestChange()  [Owner]
  → ServerRpc → 서버가 NetworkPlayer.AvatarCode에 기록
  → 전원 전파 → 각 클라이언트 PlayerAvatarVisual이 로컬 외형 재생성
```

- **문자열 하나(`avatarCode`)만 동기화**한다. 3D 모델은 NetworkObject가 아니며 각자 로컬 생성
- 포맷: `sk_01` 또는 `sk_01|c=E85D5D` (최대 29자, 미지원 세그먼트는 무시 → forward compatible)
- 외형 에셋은 Synty Sidekick 프리셋(에디터에서 사전 제작). 런타임 파츠 조립 없음
- Unity는 `IAvatarVisualProvider` 뒤에 숨겨져 Sidekick에 종속되지 않는다
- 파트 간 계약 상세: **`Docs/avatar-customization-contract.md`**

## 4. Integration 경계

```
소비자 → ApiServices.{Booth|User|Ai} (인터페이스)
              ↕ (Init 한 곳에서 교체)
        Mock 구현  |  Http/SSE 구현
```

- `IBoothApiClient` / `IUserApiClient` → Spring (REST)
- `IAiAgentClient` → FastAPI (SSE 스트리밍 계약: start/token/source/done/error, doc 16 §6)
- Mock ↔ 실서버 전환은 `GameBootstrap`의 useMockApi 플래그 하나로 제어

## 5. 향후 Channel/Instance 확장 (P2 — 지금은 계약만)

- `WorldSessionDto`에 worldId/channelId 필드 이미 존재. MVP는 항상 11F-01 반환
- World Instance 추가 = ECS Task 추가 + Session 응답 분기 (Unity 코드 변경 없음)
- Booth Instance 전환 Flow는 doc 12 §14 — P0/P1 안정 후

## 6. 폴더 구조

```
Assets/_Project/Scripts/
  Core/Bootstrap/        GameBootstrap
  Network/Bootstrap/     NetworkBootstrap (WebSocket, 서버 자동기동, CLI args)
  Network/Connection/    ConnectionManager, ConnectionPayload
  Network/Session/       SessionDataStore
  Network/Player/        NetworkPlayer, PlayerMovement, ClientAuthoritativeNetworkTransform
  Network/               DevConnectionHud (POC 전용, 추후 제거)
  Booth/Layout/          BoothLayoutDto, BoothObjectType (+Parser)
  Booth/Factory/         BoothObjectRegistry(SO), BoothObjectFactory
  Booth/Runtime/         BoothRuntime, BoothRuntimeObject
  Content/AI|Video/      타입별 콘텐츠 컴포넌트
  Integration/Contracts/ 인터페이스 + DTO
  Integration/Mock/      Mock 3종
  Integration/Spring/    HttpBoothApiClient (스켈레톤)
  Integration/           ApiServices (composition root)
```

## 7. SDD 연계 노트

이 코드는 spec-kit 도입 전 스파이크/뼈대다.
**살아남는 코드는 해당 기능 spec 작성 시 소급 반영한다** (어영부영 성역화 금지).
Draft 계약(Layout JSON, endpoint 경로, 토큰 형식)은 constitution/각 spec에서 확정한다.
