# 에디터 설정 체크리스트 (상세판)

> 코드는 전부 작성돼 있음. 아래는 Unity 에디터 안에서만 가능한 클릭 작업을
> 처음 하는 사람 기준으로 풀어쓴 것. 순서대로 진행.
> 막히면 **Console 창의 에러를 우클릭 > Copy** 해서 전달할 것.

## ✅ 진행 현황 (2026-08-08 기준)

| 단계 | 상태 |
|---|---|
| Step 1 열기/패키지 | ✅ 완료 (NGO 2.4.3 정상) |
| Step 2 Main 씬 구성 | ✅ 완료 |
| Step 3 Player Prefab | ✅ 완료 (+PlayerCameraFollow 추가됨) |
| Step 4 POC B/D (Booth+AI Mock) | ✅ 완료 — 4개 오브젝트 생성, AI Mock 응답 확인 |
| Step 5 POC A (멀티플레이) | ✅ 완료 — 2클라 상호 스폰/이동/Despawn 확인 |
| Step 6 Web/Linux Server 빌드 | 🔄 진행 중 — 모듈 설치 완료, Web 빌드 단계 |

---

## Step 0. 용어/창 위치 미리 확인 (1분)

- **Hierarchy**: 왼쪽의 씬 오브젝트 트리
- **Inspector**: 오른쪽의 선택한 오브젝트 속성 패널
- **Project**: 아래쪽의 파일 탐색기 (Assets 폴더)
- **Console**: 아래쪽 탭 (없으면 `Ctrl+Shift+C`)
- **Add Component**: Inspector 맨 아래 큰 버튼. 누르면 검색창이 뜸 → **이름 몇 글자 치고 Enter**가 제일 빠름

---

## Step 1. 열기 & 패키지 확인 (5~10분)

1. Unity Hub → Projects → **festa-unity** 클릭해서 열기
   - 처음 열 때 패키지 다운로드 + 컴파일 때문에 몇 분 걸림. 기다릴 것
   - "Enter Safe Mode?" 팝업이 뜨면 → **Ignore** 누르지 말고 **Enter Safe Mode** 선택 후 Console 에러를 복사해서 전달
2. 열리면 Console(`Ctrl+Shift+C`) 확인:
   - 빨간 에러 ❌ 있으면 → 멈추고 에러 전문 공유
   - 노란 경고 ⚠️ 는 무시해도 됨
3. 메뉴 **Window > Package Manager** 열기:
   - 왼쪽 상단 드롭다운을 **In Project**로 변경
   - 목록에서 확인: **Netcode for GameObjects** (2.4.3), **Multiplayer Play Mode** (1.5.0)
   - 둘 다 보이면 통과. 안 보이면 알려줄 것

---

## Step 2. Main 씬 구성 (15분)

### 2-1. 씬 생성/저장

1. 메뉴 **File > New Scene** → 템플릿 선택창이 뜨면 **Basic (URP)** 선택 → Create
2. `Ctrl+S` → 저장 위치를 `Assets/_Project/Scenes` 폴더로 이동
   - `Scenes` 폴더가 없으면 저장 대화상자 안에서 폴더 생성 가능
   - 파일 이름: `Main` → 저장

### 2-2. 바닥 만들기

1. Hierarchy 빈 공간 **우클릭 > 3D Object > Plane**
2. 생성된 Plane 선택 → Inspector의 **Transform**에서:
   - Position: X `0`, Y `0`, Z `0`
   - Scale: X `5`, Y `1`, Z `5`

### 2-3. @GameBootstrap 오브젝트

1. Hierarchy 빈 공간 **우클릭 > Create Empty** → 이름을 `@GameBootstrap`으로 변경 (F2 키)
2. 선택한 채로 Inspector 맨 아래 **Add Component** 클릭
3. 검색창에 `GameBootstrap` 입력 → 목록에서 **Game Bootstrap** 클릭
4. Inspector에 나타난 Game Bootstrap 컴포넌트 확인:
   - **Use Mock Api**: ✅ 체크된 상태 그대로 둠 (아직 Spring 서버가 없으므로)
   - Spring Base Url / Ai Base Url: 그대로 둠

### 2-4. @Network 오브젝트 (제일 중요)

1. Hierarchy **우클릭 > Create Empty** → 이름 `@Network`
2. **Add Component**를 4번 반복해서 아래 4개를 순서대로 추가:

   | 검색어 | 추가되는 컴포넌트 | 비고 |
   |---|---|---|
   | `NetworkManager` | Network Manager | NGO 패키지 것. 아이콘 있는 쪽 |
   | `NetworkBootstrap` | Network Bootstrap | 우리가 만든 것 |
   | `ConnectionManager` | Connection Manager | 우리가 만든 것 (Festa.Network) |
   | `DevConnectionHud` | Dev Connection Hud | 우리가 만든 것 |

   ⚠️ NetworkManager를 추가하면 "NetworkManager cannot be a child..." 같은 안내가 뜰 수 있는데, @Network가 루트 오브젝트면 문제없음.

### 2-5. NetworkManager 설정 (여기서 틀리면 접속 안 됨)

@Network를 선택한 상태에서 Inspector의 **Network Manager** 컴포넌트를 펼친다:

1. **Network Transport** 항목:
   - "Select transport..." 라고 써있는 드롭다운 클릭 → **UnityTransport** 선택
   - 선택하면 같은 오브젝트에 **Unity Transport** 컴포넌트가 자동으로 추가됨 (정상)
2. **Connection Approval** 체크박스: **✅ ON**
   - Network Manager 컴포넌트 안에서 스크롤해서 찾을 것 (Network Settings 영역)
   - ❗ 이거 안 켜면 우리 코드의 토큰 검증이 실행되지 않고 접속이 전부 거부됨
3. **Player Prefab** 슬롯: 지금은 비워둠 → Step 3에서 채움
4. 자동 추가된 **Unity Transport** 컴포넌트는 **아무것도 건드리지 않음**
   - Address/Port가 127.0.0.1:7777로 보이면 그대로 둠
   - Use WebSockets 체크박스가 보여도 손대지 말 것 — 코드(NetworkBootstrap)가 실행 시 자동으로 켬

### 2-6. 씬을 빌드 목록에 추가

Unity 6는 메뉴 이름이 예전과 다름:

1. 메뉴 **File > Build Profiles** 클릭
2. 왼쪽에서 **Scene List** 클릭 (또는 창에 바로 Scene List가 보임)
3. **Add Open Scenes** 버튼 클릭 → `_Project/Scenes/Main`이 목록에 들어가면 됨
4. 창 닫기 → `Ctrl+S`로 씬 저장

---

## Step 3. Player Prefab 만들기 (10분)

### 3-1. 캡슐 생성 + 컴포넌트

1. Hierarchy **우클릭 > 3D Object > Capsule** → 이름 `PlayerAvatar`
2. Transform Position: X `0`, Y `1`, Z `0` (바닥에 반쯤 안 묻히게)
3. **Add Component**로 4개 추가:

   | 검색어 | 컴포넌트 |
   |---|---|
   | `NetworkObject` | Network Object |
   | `NetworkPlayer` | Network Player (우리 것) |
   | `PlayerMovement` | Player Movement (우리 것) |
   | `ClientAuthoritative` | Client Authoritative Network Transform (우리 것) |

   ⚠️ 검색할 때 그냥 `NetworkTransform`을 추가하면 안 됨 — 반드시 **Client Authoritative Network Transform** (우리가 만든 서브클래스)이어야 Owner 권위 이동이 됨.

4. **Client Authoritative Network Transform** 컴포넌트에서:
   - **Syncing** 섹션의 **Scale** 줄에 있는 **체크박스** X/Y/Z 해제 (Position/Rotation 줄은 체크 유지)
   - ⚠️ **Transform 컴포넌트의 Scale 값(숫자)을 0으로 바꾸는 게 아님!**
     Transform Scale은 반드시 (1, 1, 1) 유지. 실제로 여기서 (0,1,0)을 입력해
     캡슐이 종잇장처럼 납작해지는 사고가 있었음
5. **Add Component** → `PlayerCameraFollow` 추가 (Owner 캡슐을 카메라가 따라감)

### 3-2. 프리팹으로 저장

1. Project 창에서 `Assets/_Project/Prefabs/Player` 폴더로 이동
   - 폴더가 없으면: Project 창에서 `_Project` 우클릭 > Create > Folder로 `Prefabs` 만들고, 그 안에 `Player` 생성
2. Hierarchy의 `PlayerAvatar`를 **Project 창의 Player 폴더로 드래그** → 파랗게 변하면 프리팹화 성공
3. Hierarchy에 남아있는 `PlayerAvatar`는 **선택 후 Delete** (씬에 미리 놓으면 안 됨 — 접속 시 서버가 스폰함)

### 3-3. NetworkManager에 연결

1. Hierarchy에서 `@Network` 선택
2. Network Manager 컴포넌트의 **Player Prefab** 슬롯에
   Project 창의 `PlayerAvatar` 프리팹을 **드래그해서 넣기**
3. `Ctrl+S`

---

## Step 4. Booth Runtime 검증 — POC B (5분)

1. Project 창에서 `Assets/_Project` 우클릭 > Create > Folder → `ScriptableObjects`
2. 그 폴더 안에서 **우클릭 > Create > FESTA > Booth Object Registry**
   - `FESTA` 메뉴가 안 보이면 컴파일이 안 끝난 것 → Console 확인
   - 이름은 `BoothObjectRegistry` 그대로
   - **Entries는 비워둬도 됨** — 프리팹이 없으면 색깔 있는 placeholder 도형이 자동 생성되게 해둠
3. Hierarchy **우클릭 > Create Empty** → 이름 `BoothSlot_7`, Position X `5`, Y `0`, Z `5`
4. **Add Component** → `BoothRuntime` 검색해서 추가:
   - **Booth Id**: `7`
   - **Registry**: 방금 만든 `BoothObjectRegistry` asset을 드래그
   - **Load On Start**: ✅ 그대로
5. **▶ Play** 누르고 확인:
   - BoothSlot_7 위치에 4개 오브젝트 생성: 검은 판(VIDEO_SCREEN), 파란 캡슐(AI_AGENT), 베이지 판(PROJECT_PANEL), 초록 기둥(SURVEY) — 각각 위에 타입 이름표
   - Console에 `[BoothRuntime] Booth 7 built: 4 objects` 로그
6. **Game 뷰에서 파란 캡슐 클릭** → 1초쯤 후 Console에
   `[AiNpc] booth=7 agent=78 answer: 이 부스는 SSAFY FESTA...` 로그가 찍히면 **POC B + D 동시 성공**
7. Play 정지(▶ 다시 클릭)

> Play 중엔 씬 변경이 저장 안 되므로, 설정 바꿀 땐 반드시 정지 상태에서.

---

## Step 5. 멀티플레이 테스트 — POC A (15분)

### 방법 A — Multiplayer Play Mode (권장, 빌드 불필요)

1. 메뉴 **Window > Multiplayer > Multiplayer Play Mode** 창 열기
2. **Player 2** 항목의 체크박스 활성화 → 처음엔 Virtual Player 준비에 1~2분 걸림
   - 상태가 준비되면 Player 2용 미니 에디터 창이 따로 뜸
3. 메인 에디터에서 **▶ Play**
4. 메인 에디터 Game 뷰 좌상단 HUD에서 **"Start Server (로컬 테스트용)"** 클릭
   - HUD가 `SERVER — clients: 0`으로 바뀜
5. **Player 2 창**으로 전환 → 같은 HUD에서:
   - Name을 `PlayerB` 등으로 바꾸고
   - **"Connect as Client"** 클릭
6. 확인:
   - 서버 HUD가 `clients: 1`
   - Player 2 화면에 노란 이름표 캡슐(자기 자신) 등장
7. 여유가 되면 **Player 3**도 켜서 Client 2명 접속 → 서로의 캡슐이 보이는지 확인
   - Player 2에서 WASD로 움직이면 → Player 3 화면에서 그 캡슐이 움직여야 함

> 참고: 메인 에디터를 Server로 쓰면 Server 화면에는 카메라 조작이 없어서 캡슐이 잘 안 보일 수 있음. Scene 뷰 탭으로 전환해서 보면 됨.
> 클라이언트끼리 서로 보이는지가 핵심이므로 **Player 2 ↔ Player 3 상호 확인**이 정확한 판정.

### 성공 판정 (전부 체크되면 POC A 완료)

- [ ] Client 2명이 서로의 캡슐을 봄 (자신=노란 이름표, 상대=흰 이름표)
- [ ] WASD 이동이 상대 화면에 실시간 반영 (양방향)
- [ ] 한쪽 Virtual Player를 끄면 상대 화면에서 캡슐이 사라짐 (Despawn)
- [ ] 서버 Console에 `[ConnectionManager] Approved client=... nickname=...` 로그

### 방법 B — 실제 빌드 2개 (방법 A가 안 될 때)

1. File > Build Profiles > Windows → **Build** → 폴더 지정(예: `Builds/win`)
2. 빌드된 exe를 2개 실행 + 에디터 Play까지 총 3개 인스턴스
3. 하나는 Start Server, 나머지 둘은 Connect as Client

---

## Step 6. (다음 단계) Web 빌드 준비 — 지금은 안 해도 됨

Week 1의 진짜 관문. Step 5까지 성공하면 진행:

1. **Unity Hub > Installs > 6000.0.78f1 톱니바퀴 > Add Modules**:
   - ✅ **WebGL Build Support**
   - ✅ **Linux Build Support (Dedicated Server)** — "Dedicated Server" 표기 확인
2. Web 빌드 → 로컬 http 서버로 서빙 → `ws://` 접속 확인
3. Linux Server 빌드 → Docker → AWS + ALB → **HTTPS 페이지에서 `wss://` 접속** ← ADR 결정 1의 최종 검증

---

## 자주 나는 문제 미리보기

| 증상 | 원인 | 해결 |
|---|---|---|
| Connect 눌러도 아무 반응 없다가 잠시 후 실패 | 서버를 먼저 안 켬 / 포트 불일치 | Start Server 먼저, 포트 7777 확인 |
| 접속은 되는데 바로 끊김 + `Denied: INVALID_TOKEN` | Connection Approval은 켜졌는데 payload 문제 | 정상 동작임 — HUD의 Connect 버튼을 쓰면 더미 토큰이 자동으로 들어감. 다른 경로로 접속했는지 확인 |
| 접속 자체가 거부되는데 Console에 Approved/Denied 로그가 없음 | **Connection Approval 체크 안 켬** | Step 2-5의 2번 확인 |
| 캡슐이 스폰되는데 움직여도 상대에게 안 보임 | NetworkTransform을 잘못 추가 (기본 NetworkTransform은 서버 권위) | 프리팹에서 제거하고 **Client Authoritative Network Transform**으로 교체 |
| 캡슐이 둘 다 같은 자리에 겹침 | 정상 아님 — Approval의 스폰 분산이 안 탄 것 | Connection Approval 체크 확인 |
| `FESTA` Create 메뉴가 없음 | 컴파일 에러로 스크립트가 로드 안 됨 | Console 에러 공유 |
| Multiplayer Play Mode에서 Player 2가 계속 로딩 | 첫 준비가 오래 걸림 | 2~3분 대기, 안 되면 에디터 재시작 |
| 캡슐/오브젝트가 납작한 원반·종잇장으로 보임 | **Transform Scale에 0이 들어감** (프리팹 또는 부모 오브젝트) | 해당 오브젝트와 부모의 Transform Scale을 (1,1,1)로 복원. "Scale 체크 해제"는 NetworkTransform의 Syncing 체크박스 얘기지 Transform 값이 아님 |
| 파란/알록달록 소용돌이 얼룩이 떠다님 | 런타임 생성 TextMesh에 폰트 미지정 (Unity 6는 자동 지정 안 됨) 또는 비균등 스케일 부모의 자식이라 글자가 찌그러짐 | 코드에서 `Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf")` 지정 + 라벨에 역스케일 보정 (BoothObjectFactory에 반영됨) |
| 오브젝트가 절반쯤 바닥에 묻힘 | Unity primitive는 피벗이 중심이라 Y=0 스폰 시 절반이 지면 아래로 감 | 스폰 위치에 절반 높이를 더함 (ConnectionManager 스폰 Y=1, BoothObjectFactory groundLift로 반영됨) |
| 코드를 고쳤는데 실행 결과가 그대로 | 에디터가 Play 중이거나 포커스를 안 받아 재컴파일 안 됨 | Play 정지 → Unity 창 클릭 → Ctrl+R → 우측 하단 스피너 끝난 후 Play |

## 알려진 제약 (미리 인지)

- Mock의 `Task.Delay`는 **WebGL 빌드에서 동작하지 않을 수 있음** — Web 검증 단계에서 `Awaitable.WaitForSecondsAsync`로 교체 예정. 에디터/데스크톱 POC는 문제없음
- DevConnectionHud는 IMGUI라 못생김 — POC 전용, 정식 UI에서 제거
