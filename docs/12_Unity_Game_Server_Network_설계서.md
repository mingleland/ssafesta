# SSAFY FESTA Unity Game Server / Network 설계서

> **대상**: Unity 6 Dedicated Server + Netcode for GameObjects  
> **목표**: 하나의 월드에 모든 사용자를 몰아넣지 않고, 제한된 World Instance를 수평 확장 가능한 구조로 설계한다.  
> Transport 세부 방식은 Web Client POC 결과에 따라 확정한다.

---

## 1. 네트워크 목표

- MVP 2 Client 안정 연결
- 11F World 한 채널 30~40명 운영 목표
- 최대 50명 수준 부하 테스트
- 전체 서비스는 다중 World Instance로 100명+ 확장 가능한 구조
- Booth Instance는 P2 확장 시 8~12명 목표

위 숫자는 설계 목표이며 성능 보장치가 아니다.

---

## 2. 서버 권위 원칙

Dedicated Server는 다음 실시간 상태의 기준이다.

- Player 연결 상태
- Spawn / Despawn
- Position / Rotation
- 필요한 Animation 상태
- Current Zone / Booth
- Minigame 핵심 상태
- 서버 검증이 필요한 공동 Interaction

다음은 Game Server의 Source of Truth가 아니다.

- Coin
- Lease
- Booth Layout
- Survey
- AI Conversation
- Staff Permission

---

## 3. 서버 실행 단위

```text
World Instance
- instanceId
- worldId = 11F
- channelId
- maxPlayers
- connectedPlayers
- status
```

예:

```text
11F-01 : 32명
11F-02 : 30명
11F-03 : 28명
```

각 Instance는 동일한 Published Booth 데이터를 Spring에서 조회하는 Client들과 연결된다.

---

## 4. MVP 구조

```text
Browser Client A ─┐
Browser Client B ─┼─> Unity Dedicated Server : 11F-01
Browser Client C ─┘
```

MVP에서는 자동 Channel Manager보다 한 Instance의 안정적 접속을 먼저 검증한다.

---

## 5. 확장 구조

```text
Client
  │
  ▼
World Session Service
  │
  ├─ 11F-01 Endpoint
  ├─ 11F-02 Endpoint
  └─ 11F-03 Endpoint
```

자동 분배는 P2지만 Session API는 처음부터 확장 가능한 필드를 가진다.

---

## 6. Channel 배정 기준

기획상 우선순위:

1. 같은 팀
2. 친구
3. 같은 Booth Staff
4. 여유가 있는 Channel

단, 친구 기능 자체는 현재 MVP 확정 기능이 아니므로 P2 구현 시 실제 데이터 유무에 맞게 단순화한다.

---

## 7. Connection Flow

```text
1. Client가 Spring World Session 요청
2. channelId / endpoint / short-lived connection token 획득
3. Unity NetworkManager 연결
4. Server Connection Approval
5. token / user identity 검증
6. 승인
7. Player NetworkObject Spawn
8. 접속 상태 Redis/Session Service 갱신
```

Connection Approval에서 검증할 정확한 Token 형식은 Backend/Infra와 합의한다.

---

## 8. Player Lifecycle

### Connect

```text
CONNECTING
→ APPROVED
→ SPAWNED
→ ACTIVE
```

### Disconnect

```text
ACTIVE
→ DISCONNECTED
→ Player Despawn
→ Presence 정리
```

### 비정상 종료

- Network timeout 감지
- Player Despawn
- Session TTL 정리
- Staff Presence 등 별도 서비스 상태는 Spring/Redis 정책에 따라 복구

---

## 9. 재접속 정책

업로드된 기획에서는 최종 정책이 확정되지 않았다.

MVP 권장 우선순위:

1. 복잡한 World state 복구보다 안전한 재접속
2. Player 중복 Spawn 방지
3. 영구 상태는 Spring에서 다시 조회

Minigame 중 재접속 복구 여부는 게임 규칙 확정 후 별도 결정한다.

---

## 10. Movement 동기화

### 데이터

- Position
- Rotation
- 이동에 필요한 최소 상태

### 권위 수준

정확한 Server-authoritative movement 구현 깊이는 Week 1 POC 후 결정한다.

가능한 단계:

```text
A. Client owner movement + server relay/validation
B. Server authoritative simulation
```

6~8주 프로젝트에서는 완전한 경쟁 게임 수준의 복잡한 예측·보정이 핵심 요구사항이 아니다. 다만 순간이동 등 명백한 비정상 값은 서버에서 제한하는 방향이 적절하다.

---

## 11. Animation Sync

전체 Animator Parameter를 전송하지 않는다.

의미 상태 예:

```text
Idle
Walk
Run (사용 시)
EmoteId
```

Client가 Position 변화로 계산 가능한 상태는 네트워크 변수로 중복 전송하지 않는 것도 검토한다.

---

## 12. Nickname / Avatar

영구 프로필 자체는 Spring에 있다.

연결 시 서버가 승인한 식별 정보만 Network Player에 적용한다.

```text
userId
nickname
avatarCode
```

클라이언트가 임의로 다른 사용자의 userId를 주장하지 못하도록 한다.

---

## 13. Booth 상태와 네트워크

### Local Runtime

```text
Published Layout
→ 각 Client가 동일 데이터 조회
→ Local Prefab Spawn
```

Booth Decoration은 Dedicated Server가 NetworkObject로 Spawn하지 않는다.

### MVP 내부 슬롯 이동

Dedicated Server는 같은 World Scene 안에 물리 임대 슬롯별 `InteriorAnchor`와 외부 복귀 지점을 가진다. 정적 내부 오브젝트는 서버가 생성하지 않지만 플레이어 이동과 현재 Booth 상태는 서버 권한으로 처리한다.

```text
Client 입장 요청(boothId)
→ Server가 활성 Lease/slotNo/입장 가능 상태 확인
→ Player.currentBoothId 설정
→ InteriorAnchor(slotNo)로 Teleport
→ 해당 Client가 Published Layout을 Local Spawn
```

퇴장 시 서버가 외부 복귀 지점으로 이동시킨 뒤 `currentBoothId`를 해제한다. 임대가 만료되면 신규 입장을 거부하고 현재 방문자를 외부로 이동시킨 다음 각 Client가 로컬 내부 Layout을 제거한다.

### Server가 아는 Booth 정보

필요한 최소 상태:

```text
Player.currentBoothId
Player.currentBoothSlotNo
Zone occupancy (필요한 경우)
Shared interaction state (필요한 경우)
```

일반 Booth는 공유 입장이며 전체 공간 예약·독점 상태를 네트워크 기본 모델에 넣지 않는다. 독점이 필요한 상담·행사는 별도 세션 상태로 확장한다.

---

## 14. Booth Instance — P2

MVP의 같은 Scene 내부 슬롯 풀과 Booth Instance는 다른 개념이다. 내부 슬롯 풀은 동일 World Server 안에서 좌표만 분리하고, Booth Instance는 별도 서버 프로세스/연결로 분리하는 P2 확장이다.

```text
11F World
  │
  ├─ Booth A Instance (8~12명 목표)
  └─ Booth B Instance
```

### 전환 Flow 후보

```text
Player Booth Entry
→ Session Service에 Instance 요청
→ Booth Instance Endpoint 반환
→ World Server 연결 해제/전환
→ Booth Instance 연결
→ Booth Published Layout Load
```

구현 복잡도가 높으므로 P0/P1 안정 후 진행한다.

---

## 15. Visibility / AOI — P2

World 내 사용자가 증가하면:

```text
가까운 Player → 정상 Sync
먼 Player → 저빈도 Sync 후보
다른 Zone → Visibility 제외 후보
다른 Booth Instance → Sync 없음
```

NGO의 실제 지원 방식과 프로젝트 구조에 맞게 구현한다. 성능 문제가 확인되기 전 과도하게 선행 구현하지 않는다.

---

## 16. Minigame Network

Minigame은 1종만 안정적으로 완성하는 것이 우선이다.

Server 책임 후보:

- 시작 승인
- 플레이어 참가 상태
- 점수 판정에 필요한 핵심 이벤트
- 종료
- 결과 검증용 데이터

Coin 지급은 Spring에 결과를 전달한 뒤 Spring이 처리한다.

---

## 17. Server ↔ Spring 연계

Dedicated Server가 직접 모든 비즈니스 API를 호출할 필요는 없다.

필요한 후보:

- Connection approval / session validate
- Minigame result
- World instance heartbeat

Booth Layout / AI / Survey는 주로 Client가 서비스 API를 사용한다.

---

## 18. Instance Heartbeat

P2 자동 Channel을 위해 Instance 상태를 등록할 수 있다.

```text
instanceId
channelId
status
currentPlayers
maxPlayers
startedAt
lastHeartbeatAt
```

저장 위치는 Redis가 적합하다.

---

## 19. 서버 상태

```text
STARTING
READY
DRAINING
STOPPING
FAILED
```

### DRAINING

새 Player를 받지 않고 기존 Player만 유지한 뒤 종료하는 운영 상태 후보.

ECS 자동 확장 구현 시 유용하지만 MVP에서 필수는 아니다.

---

## 20. Network Error 정책

### Connection Rejected

원인 코드 예:

```text
INVALID_TOKEN
SESSION_EXPIRED
SERVER_FULL
SERVER_NOT_READY
```

### Client UX

- 인증 문제: Web으로 복귀
- Server Full: 다른 Channel 요청(P2)
- 일시 오류: 재시도

---

## 21. 보안

- Connection Approval에서 사용자 식별 검증
- Client가 보낸 Nickname/Role을 무조건 신뢰하지 않음
- 이동 속도/좌표의 비정상 값 검증 후보
- Minigame 결과를 Client 값 하나만으로 정산하지 않음
- Server 내부 관리 Endpoint는 Public에 노출하지 않음

---

## 22. 성능 측정 항목

### Server

- CPU
- Memory
- Tick/Frame time
- Network send/receive
- Connected Players
- Spawn/Despawn cost

### Client 영향

- Remote Avatar Renderer
- Animator
- SkinnedMesh
- Network serialization

월드 인원 한계는 Server뿐 아니라 Web Client 렌더링 비용으로 결정될 수 있다.

---

## 23. Load Test 단계

### Stage 1

2 Client 기능 검증.

### Stage 2

10 Client.

### Stage 3

20 Client.

### Stage 4

30 Client 목표.

### Stage 5

40~50 Client 부하 확인.

각 단계에서 기능 성공 여부와 성능 수치를 함께 기록한다.

---

## 24. 로그

최소:

```text
instanceId
channelId
userId
connectionId
connect/disconnect reason
currentPlayers
network error
server exception
```

민감 Token 원문은 로그에 남기지 않는다.

---

## 25. Week 1 POC 완료 조건

- [ ] Linux Dedicated Server Build 성공
- [ ] 브라우저 Client A/B 연결
- [ ] Connection Approval
- [ ] A/B Spawn
- [ ] 이동 동기화
- [ ] 한 Client 종료 시 Despawn
- [ ] Web 환경에서 재현 가능

이 POC가 실패하면 World 기능 개발보다 먼저 원인을 해결한다.

---

## 26. 확정 필요 사항

- NGO Transport / Web 호환 방식
- Network tick / send rate
- Movement authority 수준
- Reconnect 상세 정책
- Channel 자동 분배 시점
- Booth Instance 실제 구현 방식
- AOI/Visibility 적용 방식
- Server autoscaling trigger
