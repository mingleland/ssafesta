# 전 영역 QA 실측 매트릭스 — 2026-09-08 (S15P21A604-508)

> 릴리스 빌드를 뽑기 전에 **사용자가 불편을 느낄 수 있는 지점을 전부 모은다.**
> 하나 찾고 고치고 빌드하면 빌드마다 35분이 든다. 여기 모아 두고 한 번에 고친 뒤 한 번만 빌드한다.

조사 방법: 8개 축 동시 조사 + 적대적 검증 (에이전트 92, 완료 44).
**검증 단계가 사용량 한도로 48건 끊겼다 — 그 항목은 `미검증` 이고, 내가 에디터에서 직접 확인한다.**
`반박됨` 은 검증자가 근거를 들어 기각한 것이다. 되살리려면 새 근거가 있어야 한다.

## 집계

| 심각도 | 검증통과 | 미검증 | 반박됨 | 합계 |
|---|---:|---:|---:|---:|
| 높음 | 8 | 16 | 4 | 28 |
| 중간 | 9 | 22 | 12 | 43 |
| 낮음 | 2 | 10 | 1 | 13 |
| **합계** | **19** | **48** | **17** | **84** |

> **처리 현황 (2026-09-09 14:20, 각 항목의 `처리` 칸이 정본)** — 89건: ✅ 완료 47 · 🟡 부분 7 · ⏸ 보류(설계·자산·계약 필요) 12 · ➡ 타 파트 소관 11 · 반박됨·조치 불필요 12. `⬜ 미착수` 0. 09-09 오후 실측 추가 #86~#89(워밍업 제거·팔레트 별칭·404 재시도·runInBackground), #29/#43 은 프리웜 철회로 ⏸.
> 높음 28: ✅ 21 · 🟡 3(#3 초기 힙 데이터 감량, #6 비콘은 보고 줄로 대체, #21 FE 호출 대기) · ⏸ 2(#2 정적 배칭 A/B, #5 조립 분산) · ➡ 2(#8·#9) · 반박 1(#23) — #10·#11 은 오늘 완료.
> 빌드로만 확인되는 항목은 `inbox/build-verify-checklist.md` R-3~R-13. 검증은 문서상 `검증통과` 가 아니라 **에디터·빌드 실측** 으로 갱신한다.

## 축별

| 축 | 높음 | 중간 | 낮음 |
|---|---:|---:|---:|
| 릴리스 관측성 | 3 | 5 | 1 |
| 오디오·BGM | 4 | 4 | 2 |
| 아바타·캐릭터 | 4 | 4 | 0 |
| 네트워킹·재접속 | 2 | 8 | 1 |
| 상호작용·미니게임 | 4 | 5 | 1 |
| 부스스튜디오→런타임 | 4 | 5 | 2 |
| 남은 성능 병목 | 3 | 4 | 4 |
| UI·입력·접근성 | 4 | 8 | 2 |

---

# 심각도: 높음

## 1. 부스 내부를 껐는데도 축제장에서 동쪽을 보면 여전히 끊긴다 — 컬링이 부스 내용물을 놓친다

| | |
|---|---|
| 축 | 남은 성능 병목 |
| 상태 | **미검증** · 플레이 모드 필요 |
| 위치 | `festa-unity/Assets/_Project/Scripts/World/Festival/BoothInteriorCulling.cs:52` |
| 처리 | ✅ 수정 `93842c94` — `WorldBoothPublishedBootstrap.BoothsRebuilt` 이벤트로 집기 스폰 뒤 렌더러 재스캔(+5초 안전 재스캔) |

**사용자가 겪는 일** — 축제장 서쪽에서 동쪽을 볼 때 프레임이 떨어진다. 오늘 '부스 내부 12실 거리 컬링' 을 넣고 '렌더러 -39%' 를 봤지만, 실제로 사라진 것은 빈 방의 벽·바닥·천장(값싼 31개)뿐이고 진짜 무거운 부스 집기·간판·소품(90~101개)은 그대로 그려지고 있다. 사용자 표현으로는 '축제벽 끝쪽에서 끊긴다' 가 고쳐졌다고 보고됐지만 절반만 고쳐진 상태다.

**내용** — BoothInteriorCulling.Awake() 가 방마다 c.GetComponentsInChildren<Renderer>(true) 로 렌더러 배열을 **한 번만** 캐시한다(BoothInteriorCulling.cs:52-66). 그런데 부스 안의 실제 내용물은 BoothRuntime.Start() 가 async void 로 ApplyFacadeAsync()·GetPublishedLayoutAsync() 를 await 한 뒤 Rebuild() 에서 생성한다(BoothRuntime.cs:35-56). 즉 캐시가 잡힐 때 BoothSlot_N 아래는 비어 있다. 결과적으로 방 껍데기 31개만 캐시되고, 나중에 생긴 90~101개는 배열에 없어 Apply() 가 절대 건드리지 못한다. Rebuild 가 재호출되면(레이아웃 재발행) 캐시는 다시 낡는다. Light 배열도 같은 방식으로 캐시되므로 부스 내용물에 붙은 광원도 똑같이 샌다.

**근거** — 플레이 모드 실측(카메라 (2100,60,950)): - 12개 방 전부 Visible=False 로 판정됨(전부 250u 밖) — 판정 자체는 옳다 - 그런데 @BoothInteriors 실제 상태: enabled=541 / disabled=377 (총 918) - 리플렉션으로 읽은 _rooms[i].Renderers.Length = 12개 방 모두 **31** (실제 방 렌더러는 122 또는 31) - 켜진 541개 중 BoothSlot* 하위 = 541개 전부 - 방별: Interior_01 dist=1693u on=90 off=32 / Interior_07 dist=257u on=91 off=31 … 고정 포즈 (-754,173,0) yaw=85 far=4000 에서: - 아직 켜진 @BoothInteriors 렌더러 중 절두체 안 = **541개 전부**, 서브메시(드로우콜) 703, 삼각형 121,140 - 같은 포즈 전체(현재 enabled 상태 반영, 오클루전 미적용) = 렌더러 1031 / 서브메시 1440 - → 부스 내부 누수가 그 포즈 드로우콜의 **49%** 지금은 목 레이아웃이 6개 방만 채워 541개다. 12개 방이 다 차면 약 1,080개가 된다.

**제안 수정** — Awake 캐시를 버리고, 방이 꺼짐↔켜짐으로 전이하는 순간에만 GetComponentsInChildren 을 다시 뜨거나(전이는 드물다), BoothRuntime.Rebuild() 완료 시 해당 방의 캐시를 무효화하는 콜백을 두어라. 가장 단순하고 확실한 방법은 Room 에 int _cachedChildCount 를 두고 Apply() 에서 room.Root 하위 구조가 바뀌었는지(예: 전이 시점에만) 확인해 재수집하는 것이다. 매 프레임 재수집은 금물 — 방당 122개 GetComponentsInChildren 이 된다.


## 2. WebGL 빌드만 정적 배칭이 꺼져 있다 — 316개 오브젝트에 붙인 BatchingStatic 표시가 통째로 무효

| | |
|---|---|
| 축 | 남은 성능 병목 |
| 상태 | **미검증** |
| 위치 | `festa-unity/ProjectSettings/ProjectSettings.asset:525` |
| 처리 | ⏸ 보류 — 켜면 `.data` 가 커지므로 빌드 A/B(드로우콜 vs 크기)가 필요. 오늘 #85 소품 병합으로 정적 배칭 대상의 대부분(204 렌더러)이 이미 합쳐졌다 |

**사용자가 겪는 일** — 축제장·11층 어디서든 드로우콜이 필요보다 많다. 배포 빌드에서만 그렇고 에디터(Win64로 열면)에서는 배칭이 돼 보이므로, '에디터에선 괜찮은데 웹에서만 무겁다' 로 나타난다.

**내용** — PlayerSettings 의 플랫폼별 배칭 설정에서 WebGL 만 staticBatching=0 이다. 나머지 5개 플랫폼(Win64·Linux64·Android·iOS)은 전부 1 이다. ProjectSettings.asset 의 m_BuildTargetBatching 은 빈 배열이라 눈으로는 보이지 않고, Player Settings UI 를 WebGL 탭에서 열어야만 드러난다. 씬에는 BatchingStatic 이 붙은 MeshRenderer 가 316개 있는데 이 표시가 WebGL 빌드에서 아무 일도 하지 않는다. 실제 플레이(활성 빌드 타깃 WebGL) 에서 staticBatches=0, staticBatchedDrawCalls=0 이다. URP SRP Batcher(m_UseSRPBatcher: 1)와 정적 배칭은 배타적이지 않다 — 정적 배칭이 먼저 메시를 합쳐 드로우콜 수를 줄이고, 그 다음 SRP Batcher 가 머티리얼 상태를 묶는다.

**근거** — PlayerSettings.GetBatchingForPlatform 리플렉션 실측:   WebGL : staticBatching=0 dynamicBatching=1   <-- 유일하게 0   StandaloneWindows64 : staticBatching=1 dynamicBatching=1   StandaloneLinux64 : staticBatching=1 dynamicBatching=1   Android : staticBatching=1 dynamicBatching=1   iOS : staticBatching=1 dynamicBatching=1 EditorUserBuildSettings.activeBuildTarget = WebGL 씬 실측: BatchingStatic MeshRenderer = 316개 UnityStats(플레이 중, 활성 타깃 WebGL): drawCalls=579 batches=579 setPassCalls=53 staticBatches=0 staticBatchedDrawCalls=0 ProjectSettings/ProjectSettings.asset:525  m_BuildTargetBatching: []  (UI 로만 보이는 값)

**제안 수정** — Player Settings > WebGL > Other Settings > Static Batching 체크. 단, 공짜가 아니다 — 정적 배칭은 합쳐진 메시 사본을 만들어 .data 를 키운다. 이 프로젝트는 로딩 시간도 문제(결함 3)이므로, 켠 뒤 .data.unityweb 증가분과 드로우콜 감소분을 같이 재고 판단해라. 316개 중 Festival_Trees(92, LODGroup 소속이라 정적 배칭 대상 아님)를 빼면 실제 이득 대상은 더 적다.


## 3. 로비에서 월드 들어가는 데 50~84초 — 배포본 데이터가 압축 후에도 134.8 MiB 다

| | |
|---|---|
| 축 | 남은 성능 병목 |
| 상태 | **미검증** |
| 위치 | `festa-unity/ProjectSettings/ProjectSettings.asset:817` |
| 처리 | 🟡 부분 (2026-09-09) — `webGLInitialMemorySize` 32 → **256 MB** 로 올려 grow 복사 반복을 없앴다. 데이터 줄이기(2048 텍스처·TGA)는 미착수. 효과는 릴리스 빌드에서 진입 시간으로 확인한다(체크리스트 R-3) |

**사용자가 겪는 일** — '입장' 을 누르고 50~84초 검은 화면을 본다(GitLab #129). 진행률 표시가 멈춘 것처럼 보이는 구간이 힙 grow 복사 구간이다. 새로고침해도 no-store 라 캐시가 안 먹어 매번 처음부터다.

**내용** — 배포 빌드(Builds/web-release)의 .data.unityweb 이 141,315,254 B = 134.8 MiB 다. wasm 은 9,217,473 B = 8.8 MiB 로 작으니 로딩 시간은 사실상 전부 데이터 파일이다. 씬이 참조하는 텍스처 런타임 메모리만 203 MB 이고, .data 압축률이 30% 밖에 안 되는 이유가 그것이다 — 이미 DXT 로 압축된 텍스처는 Brotli 가 더 줄이지 못한다. 여기에 WebGL 힙 설정이 겹친다: webGLInitialMemorySize=32 MB, webGLMemoryGrowthMode=2(기하급수), 증가폭 0.2, 단계 상한 96 MB, 최대 2048 MB. 32 MB 에서 시작해 200 MB 이상까지 20% 씩 키우면 grow 이벤트가 열몇 번 나고, 매번 WASM 선형 메모리 전체를 재할당·복사한다. 로컬 서버는 Content-Encoding: br 을 제대로 붙이므로(확인함) 브라우저 네이티브 디코드가 돈다. 배포 호스트가 이 헤더를 안 붙이면 webGLDecompressionFallback=1 때문에 **자바스크립트 Brotli 디코더가 메인 스레드에서** 134.8 MiB 를 푼다 — 그때는 50~84초가 아니라 몇 배가 된다. 로딩 시간을 잴 때 이 헤더부터 확인해야 한다.

**근거** — $ ls -la festa-unity/Builds/web-release/Build/   782aa66d…data.unityweb   141,315,254 B  (134.8 MiB)   ba58f61a…wasm.unityweb      9,217,473 B  (  8.8 MiB)   06da9278…framework.js.unityweb 78,478 B 씬 참조 텍스처 런타임 메모리 실측 = 203 MB (distinct textures 121장, 2MB 초과 36장) ProjectSettings.asset:817-823   webGLInitialMemorySize: 32 / webGLMaximumMemorySize: 2048   webGLMemoryGrowthMode: 2 / webGLMemoryGeometricGrowthStep: 0.2 / webGLMemoryGeometricGrowthCap: 96 $ curl -I -H 'Accept-Encoding: br' http://localhost:8000/Build/f8f837f8….data.unityweb   Content-Encoding: br      <-- 로컬은 정상   Cache-Control: no-store   <-- 매 새로고침마다 134 MiB 재다운로드

**제안 수정** — 두 갈래다. (a) 데이터 줄이기 — 2048px 텍스처 7장(ArcadeMachine_* 4장, Slots, Screens_1, Lines)을 1024 로 내리면 약 25 MB, 비압축 TGA 3장(결함 8) 약 21 MB. (b) 초기 힙을 실제 사용량 근처(256 MB)로 올려 grow 반복을 없앤다 — webGLInitialMemorySize 를 키우는 것은 시작 지연이 아니라 grow 복사 제거라 순이득이다. 어느 쪽이든 재기 전에 배포 호스트의 Content-Encoding: br 를 먼저 확인해라.


## 4. 닉네임이 한글 10자 이상인 회원은 월드에 들어가도 아바타가 안 뜨고 움직이지도 않는다

| | |
|---|---|
| 축 | 네트워킹·재접속 |
| 상태 | **미검증** |
| 위치 | `festa-unity/Assets/_Project/Scripts/Network/Player/NetworkPlayer.cs:97` |
| 처리 | ✅ 수정 `93842c94` — `FitFixedString32`(UTF-8 ≤29 B, 글자 경계에서 자름·경고) 로 닉네임·avatarCode 를 바이트 기준으로 맞춤. 스폰 중단 없음 |

**사용자가 겪는 일** — 닉네임을 길게 지은 회원(한글 10자 이상, 예: '싸피구미이반박성준입니다')이 월드에 입장하면 승인은 통과하는데 캐릭터가 조작되지 않고 아바타·이름표도 안 뜬다. 다른 사람 화면에도 안 나온다. 게스트는 닉네임이 '게스트-XXXX'(14바이트)라 절대 안 걸리고, 짧은 닉네임 회원도 안 걸린다 — 그래서 지금까지의 게스트 위주 통합 실측에서 한 번도 드러나지 않았다. 시연 당일 참가자 한 명만 닉네임이 길어도 그 사람은 아무것도 못 한다.

**내용** — 서버의 NetworkPlayer.OnNetworkSpawn 이 `Nickname.Value = session.nickname ?? "Unknown"` 으로 가드 없이 대입한다. NetworkVariable<FixedString32Bytes> 의 실제 용량은 UTF-8 29바이트다(com.unity.collections FixedString.gen.cs:1550, utf8MaxLengthInBytes = 29). 백엔드는 닉네임을 30 코드포인트까지 허용하고(NicknamePolicy.java:14 MAX_LENGTH=30, V1__initial_schema.sql:6 VARCHAR(30)) 그 값을 grant 클레임에 그대로 싣는다(WorldEntryTokenIssuer.java:65 .claim("nickname", identity.nickname())). 한글은 3바이트라 10자면 30바이트 — 용량 초과다. 초과하면 조용히 잘리는 게 아니라 ArgumentException 을 던진다. NGO 는 OnNetworkSpawn 호출을 try/catch 로 감싸지 않고(NetworkObject.cs:2539-2553 InvokeBehaviourNetworkSpawn), NetworkPlayer 는 PlayerAvatar.prefab 의 0번 NetworkBehaviour 라 그 뒤 9개(PlayerMovement · ClientAuthoritativeNetworkTransform · PlayerCameraFollow · PlayerAvatarVisual · PlayerAppearanceController · PlayerEmoteController · PortalInteractor · AvatarLook · PlayerNameplate)가 전부 OnNetworkSpawn 을 못 받는다. 바로 아래 주석이 avatarCode 에 대해 "FixedString overflow aborts the spawn initialization" 이라고 같은 교훈을 이미 적어 뒀는데 닉네임 줄만 안 고쳐졌다.

**근거** — NetworkPlayer.cs:97  Nickname.Value = session.nickname ?? "Unknown";   (길이 검사 없음) NetworkPlayer.cs:104 AvatarCode.Value = ... legacyAvatarCode.Length <= 31 ...  (이쪽만 가드) MCP execute_code 실행 결과 (에디터, 2026-09-08):   len= 9 utf8=27B  OK  -> '싸피구미이반박성준'   len=12 utf8=36B  THROW ArgumentException: FixedString32Bytes: Truncation while copying "싸피구미이반박성준입니다"   len=30 utf8=30B  THROW ArgumentException: ... MCP execute_code 실행 결과 (프리팹 NetworkBehaviour 순서):   [0] NetworkPlayer  [1] PlayerMovement  [2] ClientAuthoritativeNetworkTransform   [3] PlayerCameraFollow  [4] PlayerAvatarVisual  [5] PlayerAppearanceController   [6] PlayerEmoteController  [7] PortalInteractor  [8] AvatarLook  [9] PlayerNameplate backend/src/main/java/com/example/ssafesta/user/NicknamePolicy.java:14  MAX_LENGTH = 30 backend/src/main/resources/db/migration/V1__initial_schema.sql:6  nickname VARCHAR(30) NOT NULL UNIQUE Library/

**제안 수정** — 대입 전에 UTF-8 바이트 기준으로 자른다. 문자 길이가 아니라 바이트 길이여야 한다.   static string FitFixed32(string s) { if (string.IsNullOrEmpty(s)) return s; var b = System.Text.Encoding.UTF8.GetBytes(s); if (b.Length <= 29) return s; int n = 29; while (n > 0 && (b[n] & 0xC0) == 0x80) n--; return System.Text.Encoding.UTF8.GetString(b, 0, n); }   Nickname.Value = FitFixed32(session.nickname) ?? "Unknown"; 자르는 것이 싫으면 Nickname 을 FixedString64Bytes(61바이트, 한글 20자)로 올린다 — NetworkVariable 타입 변경이라 서버·클라 동시 배포가 필요하다. 어느 쪽이든 잘렸을 때 Debug.LogWarning 을 남겨 조용히 넘어가지 않게 한다(T-24 원칙).


## 5. 사람이 많은 월드에 들어가면 들어가는 순간 화면이 수백 ms 얼어붙는다

| | |
|---|---|
| 축 | 네트워킹·재접속 |
| 상태 | **반박됨** · 플레이 모드 필요 |
| 위치 | `festa-unity/Assets/_Project/Scripts/World/Avatar/PlayerAvatarVisual.cs:75` |
| 처리 | ⏸ 설계 필요 — 조립 큐·외형 캐시는 별건. #37 로 입장 1명당 조립 2회→1회는 반영됐다 |

**사용자가 겪는 일** — 축제가 한창일 때(사람이 가장 많을 때) 들어온 사람이 가장 나쁜 첫인상을 받는다 — 로딩이 끝나고 화면이 뜬 직후 0.1~0.5초 정지한다. 그리고 새 사람이 들어올 때마다 이미 안에 있던 사람들의 화면이 한 번씩 튄다. 시연처럼 여러 명이 동시에 입장하는 순간에는 그게 겹쳐 몇 초짜리 멈춤이 된다.

**내용** — 원격 아바타 조립이 완전 동기다. PlayerAvatarVisual.OnNetworkSpawn(:75) 이 곧바로 Rebuild → CatalogAvatarVisualProvider.CreateVisual 을 부르고, 그 안에서 파츠 인스턴스화·머티리얼 변환·스킨메시 결합(AvatarMeshMerge)이 한 호출에 끝난다. NGO 는 새 클라이언트가 붙을 때 이미 존재하는 모든 NetworkObject 의 스폰 메시지를 동기화 버스트로 한 번에 보내므로, 40명이 있는 월드에 들어가면 한 프레임 안에서 40회 조립이 돈다. 프레임 분산·코루틴·풀링이 어디에도 없다. 우리 자체 실측 조립 비용은 재질 공유 최적화 이후 2.4 ms/기(docs/KHS/28 §9)이므로 40기면 최소 ~96 ms, 스킨메시 결합(§10)까지 포함하면 그 이상이다. 반대 방향도 같다 — 한 사람이 새로 들어오면 이미 안에 있던 40명 전원의 화면에서 조립 1회가 동시에 돈다.

**근거** — PlayerAvatarVisual.cs:75   Rebuild(_appearance.Encoded.Value.ToString());   // OnNetworkSpawn 안에서 동기 호출 PlayerAvatarVisual.cs:93-129  Rebuild(): Destroy → _provider.CreateVisual → FitVisualToWorld → GroundToCurrentPose(BakeMesh) → EnsureAnimatorController  (전부 같은 프레임) docs/KHS/28_최적화_기법_정리.md:294  "고유 재질 480 → 14, SetPass 835 → 613(−26%), 아바타 조립 시간 20.7 → 2.4 ms/기(8.5배)" docs/KHS/28_최적화_기법_정리.md:300-309  §10 스킨메시 결합 — 조립 뒤 재질별 결합(추가 비용) festa-unity/Assets/_Project/Scripts/Network/Bootstrap/NetworkBootstrap.cs:19  _maxPlayers = 40

**제안 수정** — 조립을 프레임에 분산한다. ① Rebuild 를 즉시 실행 대신 전역 조립 큐에 넣고(우선순위 = 카메라 거리), 한 프레임당 1~2기만 처리한다. 큐 대기 중인 아바타는 저비용 임시 표현(캡슐 실루엣 또는 이미 만들어 둔 기본 프리팹 1기 공유)으로 세워 둔다 — 안 보이는 것보다 낫고, 사람이 거기 있다는 사실은 이름표로 이미 전달된다. ② 조립 결과를 인코딩 문자열 키로 캐시해 같은 외형이 여러 번 조립되지 않게 한다(시연에서는 기본 외형이 많이 겹친다). 릴리스 빌드 하나로 검증하려면 이 두 가지를 이번에 같이 넣어야 한다.


## 6. 릴리스에서 "느리다"는 신고를 재현할 수 없다 — 프레임·힛치·GC 계측이 전부 꺼진다

| | |
|---|---|
| 축 | 릴리스 관측성 |
| 상태 | **검증통과** |
| 위치 | `festa-unity/Assets/_Project/Scripts/Diagnostics/PerfHud.cs:126` |
| 처리 | 🟡 부분 (2026-09-09) — 별도 비콘 대신 릴리스에서도 살아 있는 `DisplayRefreshAdapter` 30초 보고 줄에 힛치(>50ms) 수·카메라 위치를 추가(`[Festa/프레임] … 힛치 N │ 위치 (x,y,z) yawN`). 릴리스 빌드 `ef7e5f41` 크롬 콘솔에서 p50/p95/p99/최대/GC 줄 수신 확인. 진단 6종은 릴리스에서 꺼진 채 유지(실사용자 소환 위험) |

**사용자가 겪는 일** — 사용자가 "축제장이 느리다", "걸을 때 툭툭 끊긴다" 고 말해도 우리에게는 숫자가 하나도 없다. 재현하려면 개발 빌드를 따로 뽑아 그 사용자 환경을 흉내내야 하는데 빌드가 35분이고, 개발 빌드는 릴리스와 성능 특성이 다르다. 결국 "내 PC 에서는 괜찮은데요" 로 끝난다.

**내용** — 프레임 백분위·힛치·드로우콜·GC 를 재는 6종(PerfHud·HitchLogger·RenderCostProbe·FixedPoseBenchmark·AvatarStressSpawner·LoadTestBot)이 씬의 `@Diagnostics` 게임오브젝트 하나에 전부 붙어 있다. 컴포넌트는 릴리스 빌드에도 그대로 실려 나가지만 OnEnable 첫 줄에서 `PerfHud.ToolsEnabled`(= `Application.isEditor || Debug.isDebugBuild`)를 보고 `enabled = false` 로 자살한다. 즉 릴리스에는 코드가 있는데 값이 없다. 릴리스에서 살아 있는 관측은 이벤트 로그 뿐이다 — `[ApiServices] Init`(ApiServices.cs:78), `[WorldLoadTimeline]` 단계·요약(WorldLoadTimeline.cs:56,77), `[WorldEntryGate] 개방`(WorldEntryGate.cs:311), `[PublishedLayoutLoader] N슬롯 …`(PublishedLayoutLoader.cs:42), `[WorldReconnector]` 접속 전이, `[DisplayRefreshAdapter]` 2줄. 성능 수치는 단 하나도 없다. 설계 제안 — `ReleaseFrameBeacon`(가칭). DisplayRefreshAdapter 와 **똑같은 구조**로 붙인다: 씬에 배치하지 않고 `[RuntimeInitializeOnLoadMethod(AfterSceneLoad)]` + `#if UNITY_WEBGL && !UNITY_EDITOR && !UNITY_SERVER`(DisplayRefreshAdapter.cs:63-68). 이 전처리기 조건이 곧 게이트다 — `Debug.isDebugBuild` 를 쓰지 않으므로 릴리스에서 산다. 서버 빌드에도 안 들어간다. `public static bool Enabled`(DisplayRefreshAdapter.cs:42) 킬 스위치를 두어 SendMessage 로 끌 수 있게 한다(35분짜리 재빌드 없이). 무엇을 내는가 — 세 종류뿐이다. ① 부팅 1회 '조건 각인': Application.version, Debug.isDebugBuild, 품질 레벨, vSyncCount, Screen 크기, devicePixelRatio, graphicsDeviceType/Version, 오클루전 컬링 on/off, 그리고 **ProfilerRecorder.Valid 자기 신고**(아래 F7 을 첫 릴리스 빌드에서 결판낸다). ② 10초 창마다 1줄: p50/p95/p99/max 프레임 ms, 33ms 초과 횟수, 100ms 초과 횟수, GC 횟수 증분, GC 힙 MB, wasm 힙 MB, 플레이어 위치(정수 반올림)·시야 각. 1000ms 초과 프레임은 '정지'로 분류해 통계에서 뺀다 — HitchLogger.cs:41-47 이 이미 그 규칙과 이유(탭 백그라운드 rAF 정지가 42,583ms 로 잡혔다)를 확립했다. ③ 상태 전이 시 1줄: 접속 전이·부스 레이아웃 로드 결과(이미 있는 로그를 브리지로도 한 번). 비용 — 프레임당 하는 일은 `Time.unscaledDeltaTime` 읽기 1, 비교 2, 미리 잡아 둔 `float[1200]` 에 저장 1, int 증가 2. 문자열을 만들지 않는다. 10초 경계에서만 600개 제자리 정렬(≈5,500 비교, 0.1ms 미만) + 200바이트 문자열 1개 = **초당 20바이트 할당**. 절대 하지 말아야 할 것은 이미 이 저장소가 한 번 밟았다: PerfHud.cs:103-106 주석 — "OnGUI 가 매 프레임 AppendFormat 십여 번 + ToString() 을 돌려서, 계측 도구가 스스로 GC 쓰레기를 만들고 그 GC 를 자기가 표시했다."

**근거** — MCP execute_code 출력: "AvatarStressSpawner on '@Diagnostics' … HitchLogger on '@Diagnostics' … FixedPoseBenchmark … LoadTestBot … PerfHud … RenderCostProbe … 총 6개" (전부 enabled=True activeInHierarchy=True — 씬에 살아서 빌드에 들어간다). PerfHud.cs:125-126 `public static bool ToolsEnabled => Application.isEditor || Debug.isDebugBuild;` HitchLogger.cs:57 `if (!PerfHud.ToolsEnabled) { enabled = false; return; }` GUID 대조: PerfHud guid=9340731847a71be4fb8923ae1311d458 → main.unity 에 1회 등장 (6종 모두 동일). DisplayRefreshAdapter.cs:29-30 "진단 도구가 아니라 상시 동작이므로 개발 빌드 게이트를 두지 않는다" — 게이트 없이 도는 유일한 선례.

**제안 수정** — `Assets/_Project/Scripts/Core/Bootstrap/ReleaseFrameBeacon.cs` 를 새로 만든다. DisplayRefreshAdapter.cs 를 그대로 본떠 `[RuntimeInitializeOnLoadMethod(AfterSceneLoad)]` + `#if UNITY_WEBGL && !UNITY_EDITOR && !UNITY_SERVER` + `static bool Enabled` 킬 스위치. 기존 6종은 건드리지 않는다 — 그것들은 에디터·개발 빌드용으로 그대로 두고, 릴리스용은 별도 최소 세트로 새로 만든다. 두 벌을 유지하는 대가보다 계측 도구를 릴리스에 여는 위험(실사용자가 아바타 40기를 소환)이 크다.


## 7. 모든 로그가 스택트레이스를 뜬다 — 관측을 붙이기 전에 이걸 먼저 내려야 한다

| | |
|---|---|
| 축 | 릴리스 관측성 |
| 상태 | **반박됨** |
| 위치 | `festa-unity/ProjectSettings/ProjectSettings.asset:58` |
| 처리 | ✅ 수정 `93842c94` — Log 스택트레이스 None(Warning/Error 는 ScriptOnly 유지). 반박 사유(비용 미실측)와 무관하게 콘솔 가독성 때문에 적용 |

**사용자가 겪는 일** — 사용자가 부스가 비어 있는 축제장에 들어가면 슬롯마다 로그가 쏟아지고, 그 로그 한 줄 한 줄이 스택을 걷는다. 로딩 중·이동 중에 몰려 나오면 그 자체가 프레임 끊김이 된다 — 그리고 그 끊김의 원인이 '문제를 알리려고 넣은 로그' 라는 것을 아무도 모른다.

**내용** — ProjectSettings.asset:58 `m_StackTraceTypes: 010000000100000001000000010000000100000001000000` — 6개 int 전부 1(ScriptOnly)이다. 런타임 확인 결과 Error·Assert·Warning·**Log**·Exception 다섯 종이 모두 ScriptOnly 다. 즉 `Debug.Log` 한 줄마다 관리 스택을 걷어 문자열로 만든다. 저장소 어디에도 `Application.SetStackTraceLogType` 호출이 없어(전수 grep 0건) 릴리스에서도 이 설정 그대로다. 에디터에서 100회씩 A/B 한 결과 로그 1회당 ScriptOnly 0.727ms vs None 0.046ms — **16.0배**다. (에디터 절대값에는 콘솔 창 비용이 섞여 있으므로 WebGL 절대값으로 옮겨 읽으면 안 된다. 두 조건의 차이가 스택트레이스 하나뿐이라는 점에서 비율만 유효하다.) 실제로 이 비용을 내는 양이 얼마인가 — fe-dev.log 에 남은 한 세션에서 warn 465 + error 168 = 633줄이다. 그중 231줄이 `[HttpBoothApiClient] Slot N: published layout 없음`, 63줄이 같은 슬롯 반복이다. 부스 슬롯이 안 채워진 상태로 월드에 들어가면 슬롯 하나당 21회씩 스택을 걷는다.

**근거** — MCP execute_code 출력: "Error → ScriptOnly / Assert → ScriptOnly / Warning → ScriptOnly / Log → ScriptOnly / Exception → ScriptOnly". festa-unity/ProjectSettings/ProjectSettings.asset:58 `m_StackTraceTypes: 0100…`(6×1). MCP execute_code A/B 출력: "ScriptOnly : 0.727 ms / None : 0.046 ms / 배수 : 16.0x". `grep -rn "SetStackTraceLogType\|Debug.unityLogger" festa-unity/Assets --include=*.cs` → 0건. fe-dev.log 집계: `console.warn` 465 + `console.error` 168, 그중 `[HttpBoothApiClient]` warn 231 / error 57.

**제안 수정** — 부트에서 한 번 낮춘다: `Application.SetStackTraceLogType(LogType.Log, StackTraceLogType.None)` · `(LogType.Warning, None)` 를 `#if UNITY_WEBGL && !UNITY_EDITOR` 아래 `[RuntimeInitializeOnLoadMethod(BeforeSceneLoad)]` 에서. **Error·Exception 은 ScriptOnly 로 남긴다** — 진짜 고장은 스택이 있어야 잡는다. 조용히 지우는 게 아니라 등급으로 가른다. ProjectSettings 를 고치는 대신 코드로 거는 이유: 에디터 작업 편의(에디터에서는 스택이 필요하다)를 해치지 않고 WebGL 플레이어에서만 내려가기 때문. 별도로 `[HttpBoothApiClient] published layout 없음` 은 슬롯별 반복이 아니라 로더 단에서 한 줄로 묶어야 한다(PublishedLayoutLoader.cs:42 가 이미 요약 줄을 낸다 — 개별 warn 은 그 요약과 중복이다).


## 8. 월드가 죽어도 아무도 모른다 — FE 가 Unity 의 print/printErr/errorHandler 를 하나도 걸지 않는다

| | |
|---|---|
| 축 | 릴리스 관측성 |
| 상태 | **반박됨** |
| 위치 | `festa-frontend/src/unity/host/loader.ts:52` |
| 처리 | ➡ FE 소관 — 로더 `print/printErr/errorHandler` 훅. 게임 쪽은 `[Festa/프레임]`·`[WorldLoadTimeline]` 콘솔 줄을 그대로 낸다 |

**사용자가 겪는 일** — 축제장을 돌아다니다 화면이 그대로 멈춘다. 캐릭터도 다른 사람도 안 움직이는데 오류 메시지는 없고 FE 는 여전히 월드 화면이다. 사용자는 자기 인터넷을 의심하며 한참 기다리다 새로고침하고, 우리는 그 사람이 왜 죽었는지 영원히 모른다.

**내용** — `loader.ts` 가 `createUnityInstance` 에 넘기는 config 는 `dataUrl`·`frameworkUrl`·`codeUrl`·`devicePixelRatio` **넷뿐**이다. Unity WebGL 로더가 받아 주는 `print`·`printErr`·`showBanner`·`errorHandler` 가 전부 비어 있다. 릴리스 산출물의 기본 `index.html` 에는 `errorHandler` 가 아예 주석 처리돼 있고(Builds/web-release/index.html), FE 임베드는 그 index.html 을 쓰지도 않는다. 결과가 둘이다. ① **abort/OOM 무보고.** wasm abort 나 힙 OOM(webGLMemoryGrowthMode: 2, initial 32MB → max 2048MB, ProjectSettings.asset:817-822)이 나면 `createUnityInstance` 는 이미 resolve 된 뒤라 `loadUnityBuild` 의 예외 경로에 걸리지 않는다. 캔버스만 얼고 FE 는 계속 정상 화면을 보여 준다. ② **공짜로 얻을 수 있는 캡처를 안 쓴다.** `print`/`printErr` 를 걸면 Unity 의 **모든** Debug 출력이 Unity 코드 변경 0줄로 FE 손에 들어온다. 등급을 FE 가 다시 정할 수 있으므로 F3 의 `console.log` 유실 문제도 여기서 함께 풀린다.

**근거** — festa-frontend/src/unity/host/loader.ts:52-60 — config 객체에 `dataUrl, frameworkUrl, codeUrl, devicePixelRatio` 4개만 있다. festa-unity/Builds/web-release/index.html — `// errorHandler: function(err, url, line) {` 로 주석 처리된 기본 템플릿 그대로. festa-unity/Builds/web/probe.html — probe 도 print/printErr/errorHandler 를 걸지 않는다. fe-dev.log: `[vite] (client) [console.warn] Config option "companyName" is missing or empty…` — 로더가 config 를 최소로만 받고 있다는 부수 증거. ProjectSettings.asset:817-822 `webGLInitialMemorySize: 32 / webGLMaximumMemorySize: 2048 / webGLMemoryGrowthMode: 2`.

**제안 수정** — `loadUnityBuild` 의 config 에 셋을 추가한다. ① `printErr: (msg) => { console.error('[unity]', msg); }` — Unity 의 stderr(예외·abort 사유)를 FE 가 잡는다. ② `errorHandler: (err, url, line) => { onUnityFatal(err, url, line); return true; }` — 치명 오류를 FE 상태로 올려 "축제장이 중단됐습니다 · 새로고침" 을 띄운다. 조용히 얼지 않는다(T-24 원칙). ③ `print: (msg) => { … }` 는 등급 판정을 FE 가 하도록 두되, 기본은 `console.debug` 로 흘려 보내 개발 중 노이즈를 만들지 않는다. Unity 재빌드가 필요 없다 — FE 배포만으로 오늘 붙는다. 이게 이 축에서 **가장 비용 대비 효과가 큰 한 수**다.


## 9. 스튜디오에서 왼쪽에 둔 것이 월드에서는 오른쪽에 있다 — 미리보기가 좌우 거울상

| | |
|---|---|
| 축 | 부스스튜디오→런타임 |
| 상태 | **미검증** |
| 위치 | `festa-frontend/src/features/studio/ui/canvas/TemporaryIsoRenderer.tsx:22` |
| 처리 | ➡ 팀 결정 — colosair 검증(거울상 아님, 시점 규약 문제). `docs/26` 에 결정 항목 등록(2026-09-09) |

**사용자가 겪는 일** — 부스 오너가 스튜디오에서 'AI 직원은 입구 왼쪽, 프로젝트 패널은 오른쪽'으로 배치하고 게시한다. 방문객이 부스에 들어가면 좌우가 뒤바뀌어 있다. 배치가 대칭이면 눈치채지 못하고, 비대칭일수록 '내가 만든 게 아닌데' 로 보인다. 좌표는 정확하므로 로그·검증에서는 절대 잡히지 않는다.

**내용** — 레이아웃 좌표 자체는 완벽하게 옮겨진다. 깨지는 건 '그 좌표를 사람이 어느 쪽으로 보는가'다. three.js 는 오른손 좌표계, Unity 는 왼손 좌표계인데 계약에 축 방향 규약이 없어 양쪽이 같은 숫자를 반대 방향으로 그린다. SVG 렌더러(TemporaryIsoRenderer.tsx:22)는 화면 x = (x - z)·C 라 +X 가 오른쪽·+Z 가 왼쪽이고, R3F 렌더러도 같은 (1,1,1) 시점을 쓴다. 유니티에서 똑같은 카메라를 세워 실측하니 camera.right 가 정반대였다. 부스 셸의 벽 위치(-x, -z)는 양쪽이 같으므로 '벽 기준 반대편'은 유지되지만, 방문객이 정면(+z)에서 걸어 들어올 때의 좌우가 뒤집힌다.

**근거** — festa-frontend/src/features/studio/ui/canvas/TemporaryIsoRenderer.tsx:22 — `const proj = (x, y, z) => ({ x: (x - z) * C, ... })` (+X 오른쪽, +Z 왼쪽) festa-frontend/src/features/studio/ui/canvas/isoCamera.ts:11,16 — ISO_DIRECTION = [1,1,1], isoCameraPosition() = (13.86, 13.86, 13.86) festa-frontend/src/features/studio/ui/canvas/R3FBoothRenderer.tsx:449 — camera={{ position: isoCameraPosition(), ... }} three.js Matrix4.lookAt(eye=(1,1,1), target=0, up=+Y) → x축 = (+0.7071, 0, -0.7071) Unity 에디터 실측(execute_code, 같은 eye/target):   Unity camera.right = (-0.7071, 0.0000, 0.7071)   ← 정확히 반대   world (2,0,0) -> Unity viewport x=0.382 (화면 왼쪽)   world (0,0,2) -> Unity viewport x=0.618 (화면 오른쪽) FE 는 같은 시점에서 (2,0,0) 이 0.618(오른쪽), (0,0,2) 가 0.382(왼쪽) 이 된다.

**제안 수정** — 계약(contracts/layout-api.md)에 축 방향을 못박고 한쪽에서 변환한다. 가장 싼 쪽은 FE 렌더러에서 x 를 부호 반전해 화면 매핑만 유니티에 맞추는 것(SVG: proj 의 (x - z) → (-x - z), R3F: 카메라를 (-1,1,1) 로). Unity 쪽 좌표를 건드리면 이미 게시된 데이터가 전부 뒤집히므로 하지 마라. 어느 쪽으로 정하든 '방문객이 정면에서 볼 때 왼쪽/오른쪽' 을 스크린샷 2장으로 붙여 확정할 것.


## 10. 화분을 놓으면 진열장이, 진열 선반을 놓으면 원형 테이블이 나온다 — 장식·가구 외형 선택이 전부 무시된다

| | |
|---|---|
| 축 | 부스스튜디오→런타임 |
| 상태 | **미검증** |
| 위치 | `festa-unity/Assets/_Project/ScriptableObjects/BoothObjectRegistry.asset:40` |
| 처리 | ✅ 수정 (2026-09-09) — colosair 정본 28행(#146) 전부 등록. ExpoKit 래퍼 18종(`Prefabs/Booth/Decor`), typeDefault 빈 엔트리 2개, 레거시 2개 예약. Mock 픽스처로 플레이 실측: `FURN_CHAIR_01_BLUE`→파란 의자, `STRUCT_PANEL_01`→패널(세움·바닥 피벗), 경고 0. 첫 생성에서 원본 -90° 회전을 지워 눕혀졌던 것을 고쳤다. `verify/booth-decor-2026-09-09/` |

**사용자가 겪는 일** — 오너가 팔레트에서 '화분'·'기본 패널'·'진열 선반'·'그래픽 카운터'를 골라 부스를 꾸민다. 스튜디오 썸네일과 미리보기는 각각 다르게 보인다. 게시하고 월드에 들어가면 장식은 전부 똑같은 유리 진열장, 가구는 전부 똑같은 원형 테이블이다. '내가 고른 게 안 나온다' 는 신고가 그대로 나온다.

**내용** — FE 팔레트는 장식형에 assetCode 를 실어 보낸다(WALL_PLAIN·PLANT·COUNTER_GRAPHIC·SHELF, 그리고 잠금 상태인 TRUSS_* 3종). 유니티 레지스트리에 등록된 코드는 FURNITURE_DEFAULT·DECORATION_DEFAULT 둘뿐이다. GetPrefab 은 모르는 코드에 대해 경고 한 줄을 남기고 '타입 기본 자산' 으로 대체하는데, 그 타입의 후보가 하나뿐이라 무조건 그 하나가 나온다. 결과적으로 5가지 장식이 전부 같은 진열장, 2가지 가구가 전부 같은 원형테이블+의자 3개가 된다. 조용히 넘어가지는 않지만(콘솔 경고) 방문객·오너 누구도 콘솔을 보지 않는다.

**근거** — festa-frontend/src/features/studio/model/visualAssets.ts:32,42,43,50,51,52,70 — assetCode 'WALL_PLAIN','COUNTER_GRAPHIC','SHELF','TRUSS_BEAM','TRUSS_PILLAR','TRUSS_GATE','PLANT' festa-frontend/src/features/studio/model/editorReducer.ts:79-80 — 팔레트의 assetCode 를 그대로 계약 필드로 실어 보낸다 backend/.../LayoutJson.java:133 — 서버는 assetCode 를 검증하지 않고 그대로 저장·게시한다 (grep: 백엔드에 assetCode 화이트리스트 0건) festa-unity/Assets/_Project/ScriptableObjects/BoothObjectRegistry.asset:40-45 — type 9(Furniture)=FURNITURE_DEFAULT, type 10(Decoration)=DECORATION_DEFAULT 뿐 에디터 실측(execute_code, registry.GetPrefab):   Decoration + 'WALL_PLAIN'      -> Decoration   Decoration + 'PLANT'           -> Decoration   Furniture  + 'COUNTER_GRAPHIC' -> Furniture   Furniture  + 'SHELF'           -> Furniture 콘솔: "[BoothObjectRegistry] Unknown assetCode 'PLANT' for type Decoration — 타입 기본 자산으로 대체" (7건) 프리팹 실물 메시 — Decoration = DisplayBox01

**제안 수정** — 둘 중 하나. (a) 레지스트리에 4개 코드(WALL_PLAIN·PLANT·COUNTER_GRAPHIC·SHELF)의 엔트리를 추가하고 타입 기본은 assetCode 를 비운 엔트리로 명시한다. (b) 릴리스 전까지 자산이 없다면 FE 팔레트를 실제 있는 것만 남기고 나머지를 locked 처리한다 — 고를 수 있는데 안 나오는 것이 가장 나쁘다. 어느 쪽이든 서버가 assetCode 를 화이트리스트로 검증하도록 붙여 다음에 또 조용히 갈라지지 않게 할 것.


## 11. 배치를 고쳐 게시해도 월드는 그대로다 — 탭을 새로고침해야 바뀐다

| | |
|---|---|
| 축 | 부스스튜디오→런타임 |
| 상태 | **미검증** |
| 위치 | `festa-unity/Assets/_Project/Scripts/Booth/Runtime/WorldBoothPublishedBootstrap.cs:60` |
| 처리 | ✅ 수정 (2026-09-09) — 제안 (a)+(b) 둘 다. `RequestReload(slotId)` 가 한 슬롯만 재조회해 서명(version+오브젝트 열)이 바뀌면 Rebuild. 포털 입장 시 자동, FE 는 `SendMessage('BoothLayoutBridge','ReloadBoothSlot', slotId)` 로 즉시 반영(FE 호출은 #146 회신으로 요청). 플레이 실측: 동일 서명 무동작 / 변경 시 1회 재빌드·중복 없음 |

**사용자가 겪는 일** — 오너가 스튜디오에서 배치를 바꾸고 '게시' 를 누른 뒤, 같은 탭에서 월드로 넘어가 부스에 들어간다. 예전 배치가 그대로 있다. 게시가 실패한 줄 알고 다시 게시한다. 시연 중이라면 그 자리에서 막힌다. 페이지를 통째로 새로고침해야만 반영된다.

**내용** — Published Layout 조회는 씬 로드 시점에 딱 한 번 돈다. WorldBoothPublishedBootstrap 이 RuntimeInitializeOnLoadMethod + sceneLoaded 로만 걸려 있고, 이미 채워진 방은 IsLoaded 로 건너뛴다. 저장소 전체에서 BoothRuntime.Rebuild 를 부르는 곳은 이 한 군데뿐이고, FE→Unity 방향으로 '게시됐다' 를 알리는 브리지 메시지도 없다. 월드 서버가 알려 주지도 않는다 — 부스 오브젝트는 NetworkObject 가 아니라 클라이언트별 Local Spawn 이라 네트워크 경로 자체가 없다.

**근거** — festa-unity/Assets/_Project/Scripts/Booth/Runtime/WorldBoothPublishedBootstrap.cs:36-41 — [RuntimeInitializeOnLoadMethod(AfterSceneLoad)] + SceneManager.sceneLoaded 만 festa-unity/.../WorldBoothPublishedBootstrap.cs:60 — `if (r.IsLoaded || bySlot.ContainsKey(r.BoothId)) continue;` grep -rn "\.Rebuild(\|LoadAndBuildAsync" Assets/_Project/Scripts → 호출처는 WorldBoothPublishedBootstrap.cs:75 와 BoothRuntime 자기 자신(_loadOnStart, 인테리어 빌더가 false 로 꺼 둠)뿐 grep -rn "LAYOUT_PUBLISHED\|RefreshBooth\|ReloadLayout\|BOOTH_REFRESH" --include=*.cs --include=*.jslib → 0건

**제안 수정** — 릴리스 전에 최소한 두 가지 중 하나. (a) 부스 방에 재진입할 때(PortalInteractor 로 Interior_NN 에 들어갈 때) 그 슬롯 하나만 다시 조회해 Rebuild. 12실 전체가 아니라 1실이라 비용은 무시할 수 있다. (b) FE 가 게시 성공 시 SendMessage 로 BOOTH_LAYOUT_PUBLISHED{slotId} 를 던지고 Unity 가 해당 슬롯만 Rebuild. 어느 쪽이든 Rebuild 전에 BoothInteractionTarget.InvalidateBounds 가 도는지 확인할 것(Clear→Destroy 경로라 자동이지만 캐시가 있다).


## 12. 게시한 부스인데 '아직 준비 중' 이 뜨며 입장이 막힌다 — 조회가 한 번 실패하면 세션 내내 잠긴다

| | |
|---|---|
| 축 | 부스스튜디오→런타임 |
| 상태 | **미검증** · 플레이 모드 필요 |
| 위치 | `festa-unity/Assets/_Project/Scripts/World/Festival/PortalInteractor.cs:94` |
| 처리 | ✅ 수정 `bf0b36e6` — 조회 실패한 방은 `RetryLoopAsync`(20초 간격, 최대 6회)로 다시 채운다. 세션 내내 잠기지 않음 |

**사용자가 겪는 일** — 오너가 방금 게시한 부스 앞에서 F 를 눌러도 '이 부스는 아직 준비 중이에요' 만 뜬다. 게시가 안 된 줄 알고 스튜디오로 돌아가 다시 게시한다. 실제로는 12실 동시 조회 중 하나가 타임아웃 났을 뿐이고, 새로고침하면 멀쩡히 들어가진다. 시연에서 가장 나쁜 종류의 실패다.

**내용** — HttpBoothApiClient 는 404(미게시)·타임아웃·CORS·5xx 를 전부 null 로 돌려준다. WorldBoothPublishedBootstrap 은 null 이면 Rebuild 를 부르지 않으므로 그 방의 BoothRuntime.IsLoaded 는 false 로 남는다. PortalInteractor.IsEnterable 은 그 플래그만 보고 입장을 막고 '이 부스는 아직 준비 중이에요' 를 띄운다. 즉 '미게시' 와 '조회 실패' 가 방문객에게 완전히 같은 화면이다. 그리고 재시도 경로가 없다(F3 과 같은 원인) — 한 번 실패하면 새로고침 전까지 그 부스는 영영 못 들어간다. 12실을 Task.WhenAll 로 동시에 때리므로 서버가 잠깐 느리면 여러 실이 한꺼번에 이 상태가 된다.

**근거** — festa-unity/Assets/_Project/Scripts/Integration/Spring/HttpBoothApiClient.cs:97-108 — 404·ProtocolError·ConnectionError/timeout 전부 return null festa-unity/.../WorldBoothPublishedBootstrap.cs:72 — `if (layout == null) continue;   // 미게시 — 기본 프레임 유지` festa-unity/Assets/_Project/Scripts/World/Festival/PortalInteractor.cs:88-96 — `if (runtime == null || runtime.IsLoaded) return true; reason = "이 부스는 아직 준비 중이에요 — 게시된 부스만 들어갈 수 있어요";` festa-unity/Assets/_Project/Scripts/Booth/Layout/PublishedLayoutLoader.cs:32 — 12슬롯 Task.WhenAll 동시 조회 재시도 없음: TryLoad 는 s_Loading 가드 + IsLoaded 스킵이라 두 번째 기회가 없다

**제안 수정** — IsLoaded(bool) 을 3상태로 나눈다 — 미조회/미게시/조회실패. 미게시는 지금 문구 그대로, 조회실패는 '부스를 불러오지 못했어요 — 다시 시도할게요' 로 구분하고 포털 상호작용 시 그 슬롯만 1회 재조회한다. 최소 조치만 한다면 PublishedLayoutLoader.LoadOneGuardedAsync 에 1회 재시도(0.5초 후)를 넣는 것만으로도 동시 12건 타임아웃 대부분을 흡수한다.


## 13. 광장 게임기에 F 를 누르면 화면이 확 들어가고 조작이 막히는데 게임은 안 열린다

| | |
|---|---|
| 축 | 상호작용·미니게임 |
| 상태 | **미검증** · 플레이 모드 필요 |
| 위치 | `festa-unity/Assets/_Project/Scripts/Content/Arcade/ArcadeMachineInteractable.cs:39` |
| 처리 | ✅ 수정 `803dfb49` — 호스트가 3초 안에 잠금 주인으로 나타나지 않으면 초점 해제 + "이 게임기는 아직 열 수 없어요" 토스트 |

**사용자가 겪는 일** — 광장 아케이드 앞에서 [F] 게임기 플레이 프롬프트를 보고 F 를 누르면, 카메라가 게임기 화면으로 확 빨려 들어가고 캐릭터가 안 움직인다. 게임 화면은 영원히 안 뜬다. Esc 를 아는 사람만 빠져나온다 — 그마저도 그 직전에 React UI 를 한 번 클릭해 캔버스가 focus 를 잃었으면 Esc 가 브라우저로 가서 Unity 에 도달하지 않는다. 그 경우 새로고침 말고는 방법이 없다.

**내용** — ArcadeMachineInteractable.Interact() 는 ① InteractionFocusCamera.Focus() 로 카메라를 기계 앞으로 당기고 월드 입력을 잠근 뒤 ② WORLD_ARCADE_INTERACT {machineId} 를 호스트로 보낸다. 그런데 FE 의 유일한 라우팅 지점인 features/interaction/dispatcher.ts 에 그 type 의 case 가 없다 — default: return 으로 조용히 버려진다. events.ts 의 UnityInteractEvent union 에도 없다. 즉 잠금은 걸리는데 열릴 화면이 존재하지 않는다. 게다가 FE 임베드 빌드에서는 BoothInteractionInput.OnBridgeSent 의 안내 토스트가 HostProvidesUi 게이트로 억제되므로 'Esc 로 나가기' 라는 문구조차 화면에 안 뜬다. 씬에 이 상태의 게임기가 2대(plaza-arcade-01 z=161, plaza-arcade-02 z=175) 있다. docs/26 117행에 '이벤트 이름 FE 확정 필요(🟡)' 로 등록돼 있으나, 사용자 관점에서는 미정이 아니라 '눌리는데 고장난 기계' 다.

**근거** — festa-unity/Assets/_Project/Scripts/Content/Arcade/ArcadeMachineInteractable.cs:39-44 → `if (InteractionFocusCamera.IsFocused) return; InteractionFocusCamera.Focus(...); BoothInteractBridge.SendArcadeInteract(_machineId);` festa-unity/.../Integration/Bridge/BoothInteractBridge.cs:56 → `public const string ArcadeInteract = "WORLD_ARCADE_INTERACT";` (주석: "FE 수신부 확정 전") festa-frontend/src/features/interaction/dispatcher.ts:13-41 → case 는 LAPTOP·PROJECT·AI_AGENT·GAME·SURVEY·MANAGEMENT 6종뿐, `default: return`. grep 결과 저장소 전체 FE 소스에 WORLD_ARCADE_INTERACT 문자열 0건. festa-unity/.../Content/BoothInteractionInput.cs:267 → `if (Festa.World.UI.ControlsHintHud.HostProvidesUi) return;` (임베드에서는 토스트 없음) Unity 에디터 실측: `ARCADE Arcade_04_Cabinet_02 machineId=plaza-arcade-02 pos=(-905,0,175)` / `ARCADE Arcade_03_Cabinet_01 machineId=plaza-arcade-01 pos=(-905,0,161)` docs/26_팀_결정_필요사항.md:117 → "🟡 FE 확정 필요 — Un

**제안 수정** — 둘 중 하나를 릴리스 전에 고른다. (A) FE dispatcher.ts 에 WORLD_ARCADE_INTERACT case 를 추가하고 events.ts union 에 넣는다(GET /api/v1/arcade-machines/{machineId} 는 아직 404 이므로 FE 가 '준비 중' 빈 상태를 띄워야 한다). (B) FE 수신부가 이번 릴리스에 못 들어오면 Unity 쪽에서 Focus/잠금을 걸지 않고 BoothInteractionInput.Toast("이 게임기는 아직 준비 중이에요") 만 띄운다 — GamePortalInteractable.cs:40-45 가 이미 쓰는 패턴이다. 어느 쪽이든 '잠그기 전에 열릴 곳이 있는지 확인' 순서로 바꾼다.


## 14. 미니게임·초점 화면이 떠 있는데 F 를 누르면 캐릭터가 다른 부스로 순간이동한다

| | |
|---|---|
| 축 | 상호작용·미니게임 |
| 상태 | **미검증** · 플레이 모드 필요 |
| 위치 | `festa-unity/Assets/_Project/Scripts/World/Festival/PortalInteractor.cs:51` |
| 처리 | ✅ 수정 `803dfb49` — `InputBridge.IsLocked || InteractionFocusCamera.IsFocused` 이면 Update 조기 반환(하이라이트·`_nearest` 해제) |

**사용자가 겪는 일** — 타이밍 스톱 게임 중 Space 대신 F 를 잘못 누르거나, 부스 안에서 노트북을 들여다보는 중(초점 카메라) F 를 한 번 더 누르면, 화면은 그대로인데 캐릭터만 다른 부스로 순간이동한다. Esc 로 나오면 엉뚱한 곳에 서 있다. 그 전에 이미 화면에는 '[F] 타이밍 스톱 게임' 과 '[F] 12번 부스 입장' 두 알약이 겹쳐 그려져 무슨 키인지 알 수 없다.

**내용** — InputBridge 는 '잠금은 읽는 쪽이 지킨다' 는 규약이고 주석에 읽는 곳을 PlayerMovement·BoothInteractionInput·PlayerEmoteController 셋으로 적어 놨다. 그런데 F 를 읽는 컴포넌트는 넷이다 — PortalInteractor 가 빠져 있다. PortalInteractor.Update() 는 잠금 여부를 보지 않고 kb.fKey.wasPressedThisFrame 을 그대로 읽어 텔레포트한다. OnGUI 도 마찬가지라 잠금 중에도 포털 프롬프트를 계속 그린다 — BoothInteractionInput 은 -437 때문에 잠금 중 프롬프트를 명시적으로 끄는데(62-67행) 포털만 안 끈다. 두 프롬프트는 InteractPromptUI 안에서 정확히 같은 좌표(Screen.height*0.52)에 그려지므로 겹칠 때 알약 두 장이 포개진다. 에디터 실측으로 '한 지점에서 F 가 둘 다 먹는' 조합이 실제로 존재함을 확인했다: @AdminGameBooth(타이밍 스톱, r=35) ↔ Portal_Ext_12(r=23) 표면거리 31.8 (< 35+23). 부스 내부에서는 더 흔하다 — 노트북·AI 초점 중에 F 를 또 누르면 출구 포털이 먹는다.

**근거** — festa-unity/.../World/Festival/PortalInteractor.cs:51-52 → `var kb = Keyboard.current; if (kb == null || !kb.fKey.wasPressedThisFrame) return;` — 앞뒤 어디에도 InputBridge.IsLocked 검사 없음 festa-unity/.../World/Festival/PortalInteractor.cs:127-133 → OnGUI 가 무조건 DrawPrompt festa-unity/.../Integration/InputBridge.cs:12-14 → "IsLocked 를 보는 곳은 PlayerMovement·BoothInteractionInput·PlayerEmoteController 셋이다" grep -rn "IsLocked" 결과: PortalInteractor.cs 0건 (BoothInteractionInput.cs:62,191 / PlayerMovement.cs:504,518,529 / PlayerEmoteController.cs:51 만 존재) festa-unity/.../World/Interaction/InteractPromptUI.cs:42 와 :66 → 둘 다 `y = Screen.height * 0.52f` Unity 에디터 실측: "겹침: @AdminGameBooth(r=35) ↔ Portal_Ext_12(r=23) 표면거리=31.8"

**제안 수정** — PortalInteractor.Update() 맨 앞과 OnGUI() 맨 앞에 `if (Festa.Integration.InputBridge.IsLocked) { _ring.Hide(); return; }` 를 넣는다. 겸사겸사 InputBridge.cs:12-14 주석의 '읽는 곳 셋' 목록을 넷으로 고쳐, 다음에 F 를 읽는 컴포넌트가 생겼을 때 같은 사고가 반복되지 않게 한다. 프롬프트 y 좌표 충돌(포털 vs 부스 오브젝트)도 별건으로 남는다 — 한쪽만 그리도록 우선순위를 정해야 한다.


## 15. 타이밍 스톱은 서버가 없어도 그냥 돌아가고 '기록되었습니다' 라고 말한다 — 슬롯머신에만 체험판 배지가 있다

| | |
|---|---|
| 축 | 상호작용·미니게임 |
| 상태 | **미검증** |
| 위치 | `festa-unity/Assets/_Project/Scripts/Minigame/TimerStopGameHud.cs:190` |
| 처리 | ✅ 수정 `803dfb49` — `GameResultAckDto.simulated`(Mock 경로면 true) → HUD 에 "체험판 — 기록·보상이 남지 않습니다" 표시 |

**사용자가 겪는 일** — 타이밍 스톱을 하면 목표 시간이 뜨고, 멈추면 오차가 나오고, '기록되었습니다' 라는 문구까지 뜬다. 사용자는 자기 기록이 남았다고 믿는다. 실제로는 목표 시간을 브라우저가 지어냈고 서버에는 아무것도 안 갔다. 같은 화면 언어를 쓰는 슬롯머신에는 '체험판 · 코인 미반영' 이 붙어 있어서, 배지가 없는 타이밍 스톱은 오히려 '이건 진짜' 로 읽힌다.

**내용** — 미니게임 판정 2종은 둘 다 ServerFirst(404면 Mock) 구조인데, 사용자에게 그 사실이 드러나는 방식이 다르다. 슬롯머신은 SlotSpinResultDto.simulated 를 타고 올라와 SlotMachineHud 가 '체험판 · 코인 미반영' 칩을 켠다. 타이밍 스톱은 IGameResultClient 자체에 simulated 개념이 없고, ServerFirstGameResultClient 의 _lastWasMock 은 private 이며 공개 표면이 LastError 하나인데 그마저 Mock 일 때 null 을 돌려준다(=실패 아님으로 위장). TimerStopGameHud 에는 배지 UI 가 아예 없다. 그 결과 BE 404 상황에서 목표 시간을 클라이언트가 지어내고(FR-008 미성립), 결과 문구는 MockGameResultClient 가 만든 '기록되었습니다 (보상 연동 전)' 가 그대로 뜬다 — 아무 데도 기록되지 않았는데 '기록되었습니다' 다. 콘솔 LogWarning 은 남지만 사용자는 콘솔을 안 본다. 헌법/T-24 의 '조용한 대체 금지' 위반이다. 로컬 스택 실측으로 이 경로가 지금 바로 발생함을 확인했다.

**근거** — 로컬 Spring 실측(게스트 토큰 첨부):   POST /api/v1/minigames/timer-stop/sessions -> 404   POST /api/v1/minigames/slot-machines/plaza-slot-01/spins -> 404   GET  /api/v1/wallets/me -> 403 (게스트) / 컨트롤러 존재   (토큰 없이 호출하면 넷 다 401 — Security 가 먼저 막는다) backend/src/main/java: minigames·timer-stop·slot-machines·arcade-machines 문자열 grep 0건 (컨트롤러 미존재 확정) festa-unity/.../Integration/Spring/HttpGameResultClient.cs:147-189 → ServerFirstGameResultClient. `bool _lastWasMock;` 은 private, `public string LastError => _lastWasMock ? null : _server.LastError;` festa-unity/.../Minigame/TimerStopGameHud.cs — 전체에 badge/simulated/체험판 문자열 0건 festa-unity/.../Minigame/Slot/SlotMachineHud.cs:73 → `_badge = FestaUiKit.Chip(cr, "체험판 · 코인 미반영", ...)`, :107 → `_badge.SetActive(_session.Simulated);` festa-unity/.../Integration/MockGameResultClient.cs:80 → `message = "기록되었습니다 (보상 연동 전)"` festa-unity/.../Minigame/TimerStopGameHud.cs:

**제안 수정** — IGameResultClient 에 `bool LastWasSimulated { get; }` 를 추가하고(ServerFirstSlotMachineClient.LastWasSimulated 와 같은 모양) ServerFirstGameResultClient 가 _lastWasMock 을 그대로 노출한다. TimerStopGameHud 에 SlotMachineHud.cs:73 과 동일한 Chip('체험판 · 기록 미반영')을 달고 Verdict.message 가 Mock 산 문구일 때는 '기록되었습니다' 대신 '체험판이라 기록되지 않았습니다' 로 바꾼다. MockGameResultClient.cs:80 의 문구 자체도 '기록되었습니다' 로 시작하지 않게 고친다.


## 16. FE 패널을 열었다 닫으면 돌아가던 슬롯머신이 저절로 꺼진다 (입력 잠금에 주인이 없다)

| | |
|---|---|
| 축 | 상호작용·미니게임 |
| 상태 | **미검증** · 플레이 모드 필요 |
| 위치 | `festa-unity/Assets/_Project/Scripts/Integration/InputBridge.cs:64` |
| 처리 | ✅ 수정 `803dfb49` — 잠금을 주인(owner) 집합으로 관리(`SetLocked(bool, owner)`). 호스트 패널이 닫혀도 미니게임 잠금은 남는다 |

**사용자가 겪는 일** — 슬롯머신에 10코인을 넣고 릴이 도는 중에 화면 위 React 버튼(내 부스 관리 등)을 열었다 닫으면, 슬롯 화면이 저절로 사라지고 카메라가 튕겨 나온다. 사용자 입장에서는 '베팅하다 게임이 꺼졌다' 다. 반대로 웹 오버레이가 떠 있는데 캐릭터가 그 뒤에서 걸어다니는 상태도 같은 원인에서 나온다.

**내용** — InputBridge.IsLocked 는 소유자 없는 static bool 하나다. 소스 주석은 '여러 곳이 겹쳐 잠그는 경우는 없다(화면은 동시에 하나)' 를 전제하지만, 실제로는 두 주인이 같은 값을 쓴다. ① Unity 내부: InteractionFocusCamera/TimerStopGameHud 가 SetLocked(true/false). ② FE: UnityHost 의 effect 가 `syncInputLock(instance, screen !== 'world')` 를 screen 이 바뀔 때마다 무조건 밀어 넣는다. FE 는 Unity 안에서 슬롯머신이 돌고 있는지 모르므로, screen 이 world→(다른 화면)→world 로 한 바퀴 돌면 마지막에 SetInputLocked('0') 이 날아간다. Unity 쪽에서 그 '0' 은 LockedChanged(false) → InteractionFocusCamera.EndFocus() → Released 이벤트 → SlotMachineHud.Close() 로 이어진다. Unity 캔버스 위에 떠 있는 React WorldHud 버튼은 잠금과 무관하게 마우스로 눌리므로 이 시퀀스는 아주 쉽게 만들어진다. 역방향도 성립한다 — FE 오버레이가 열려 있는데 Unity HUD 가 닫히면 SetLocked(false) 가 나가 오버레이 뒤에서 캐릭터가 걸어 다닌다(TimerStopGameHud.cs:51-53 주석이 이미 그 계열 사고를 기록하고 있다).

**근거** — festa-frontend/src/unity/host/UnityHost.tsx:160-164 → `useEffect(() => { ... syncInputLock(instance, screen !== 'world'); }, [instanceReady, screen]);` festa-unity/.../Integration/InputBridge.cs:64-66 → "열릴 때 true, 닫힐 때 false. 여러 곳이 겹쳐 잠그는 경우는 없다(화면은 동시에 하나)." ← 전제가 깨져 있다 festa-unity/.../Integration/InputBridge.cs:54-61 → SetInputLocked 는 값이 바뀔 때만 LockedChanged 발화 festa-unity/.../World/Interaction/InteractionFocusCamera.cs:164-168 → `void OnLockedChanged(bool locked) { if (!locked && _active) EndFocus(); }` festa-unity/.../Minigame/Slot/SlotMachineHud.cs:40 → `InteractionFocusCamera.Released += hud.Close;` festa-unity/.../Minigame/TimerStopGameHud.cs:49-54 주석 → "Close 뒤 프레임 끝의 OnDestroy 가 다시 풀면 그 사이 다른 상호작용(게임기 초점 등)이 잡은 잠금까지 풀어 버린다(2026-09-06 실측)"

**제안 수정** — 잠금에 소유자를 붙인다. `SetLocked(owner, bool)` 형태의 다중 소유(참조 카운트 또는 소유자 집합)로 바꾸고, 호스트(SetInputLocked)는 'host' 슬롯만, Unity 내부 HUD·초점 카메라는 각자 자기 슬롯만 건드리게 한다. IsLocked 는 슬롯이 하나라도 켜져 있으면 true. 그러면 FE 의 screen 전이가 Unity 내부 게임을 끄지 못하고, Unity HUD 가 닫혀도 FE 오버레이가 열려 있는 동안은 잠금이 유지된다.


## 17. 로비에 들어가면 옷이 전부 자물쇠로 잠겨 있고, 새로고침해도 그대로다

| | |
|---|---|
| 축 | 아바타·캐릭터 |
| 상태 | **검증통과** |
| 위치 | `festa-unity/Assets/_Project/Scripts/World/Avatar/Lobby/CharacterLobbyController.cs:194` |
| 처리 | ✅ 수정 `4874eb7b` — 토큰이 늦게 와도 `OnAuthTokenChanged` 에서 소유권 판정을 다시 돈다(`LoadOwnershipAsync` 재시도) |

**사용자가 겪는 일** — 회원으로 로그인해 캐릭터 로비에 들어가면 머리·옷·신발이 전부 자물쇠로 표시되고 아무것도 갈아입을 수 없다. 화면에는 "파츠 보유 정보를 불러오지 못했습니다. 새로고침 후 다시 시도해 주세요." 가 뜨는데, 새로고침해도 같은 순서로 401 이 나서 똑같이 잠긴다 — 사용자가 시킨 대로 해도 안 낫는 안내다.

**내용** — CharacterLobbyController.Awake 가 곧바로 InitializeFromServerAsync() 를 부르는데(190행), 그 시점에는 React 호스트가 아직 SendMessage('AuthBridge','SetAccessToken') 를 보내지 않았다. FE 는 createUnityInstance 의 .then 에서 setInstanceReady(true) 를 하고(UnityHost.tsx:101-107), 그 상태 변화를 본 useEffect 가 syncAccessToken 을 부른다(UnityHost.tsx:152-156) — Unity 첫 씬의 Awake 는 그보다 앞선다. 토큰 없이 나간 GET /catalog/items 는 401 이고 AvatarOwnership.MarkFailed 로 떨어진다. 문제는 회복 경로가 없다는 것이다: 토큰이 도착했을 때 도는 핸들러가 `void OnAuthTokenChanged() => TryEnterWorldAsGuest();` 하나뿐이라(194행) 회원이면 아무 일도 일어나지 않는다. LoadOwnershipAsync 의 호출자는 저장소 전체에서 InitializeFromServerAsync 한 곳뿐이다. 게스트는 토큰 도착 즉시 월드로 빠져나가므로 이 증상을 안 겪는다 — 회원만 겪는다. 새로고침해도 순서가 같아 매번 재현된다.

**근거** — CharacterLobbyController.cs:190 `InitializeFromServerAsync();` / :194 `void OnAuthTokenChanged() => TryEnterWorldAsGuest();` / :247 `AvatarOwnership.MarkFailed("GET /catalog/items 응답을 받지 못했다")`. 재시도 없음: `grep -rn "LoadOwnershipAsync\|InitializeFromServerAsync" Assets` → 정의 1 + 호출 1. FE: UnityHost.tsx:101 `.then((instance) => { ... setInstanceReady(true)` , :152-156 `useEffect(... syncAccessToken(instance), [instanceReady, ...])`. HostRuntimeConfig 에는 토큰 통로가 없어(ApiBaseUrl 하나) 부팅 시점에 토큰을 읽을 방법이 없다.

**제안 수정** — OnAuthTokenChanged 에서 게스트가 아니고 AvatarOwnership.JudgementReady 가 false 면 InitializeFromServerAsync() 를 다시 돌린다(중복 실행 가드 포함). 또는 로비 진입을 AuthBridge.HasToken 이 참이 될 때까지(혹은 짧은 타임아웃까지) 기다렸다가 첫 호출을 낸다. 상태 문구도 "로그인 정보를 기다리는 중" 과 "조회 실패" 를 구분해야 한다.


## 18. 저장해 둔 아바타가 안 뜨는데 아무 말도 없고, 그대로 입장하면 랜덤 아바타가 서버 저장본을 덮어쓴다

| | |
|---|---|
| 축 | 아바타·캐릭터 |
| 상태 | **검증통과** |
| 위치 | `festa-unity/Assets/_Project/Scripts/World/Avatar/Lobby/CharacterLobbyController.cs:310` |
| 처리 | ✅ 수정 `4874eb7b` — 저장 실패는 LogError + 토스트("아바타를 저장하지 못했어요. 이번 접속에만 적용됩니다")로 드러낸다. 저장본 재조회는 #17 과 같은 경로 |

**사용자가 겪는 일** — 어제 30분 걸려 꾸민 아바타로 다시 접속했는데 처음 보는 랜덤 캐릭터가 서 있다. 오류 메시지도 로딩 표시도 없어서 "내가 저장을 안 눌렀나" 싶을 뿐이다. 그대로 월드에 들어가는 순간 그 랜덤 캐릭터가 계정에 저장되어, 다시 접속해도 원래 아바타는 영영 돌아오지 않는다.

**내용** — LoadPersistedAppearanceAsync 는 GET /users/me 가 null 을 돌려주면(401·네트워크 오류 등) `Decode(null)` → 프리셋 기본값 → `!appearance.IsModular` 조건에 걸려 **아무 말 없이 return** 한다(310행). 예외 경로도 Debug.LogWarning 뿐이다(320행) — 릴리스 빌드에는 콘솔이 없으니 사용자에게는 아무 신호도 없다. 화면에는 Awake 가 만든 CreateRecommendedRandomConfig 결과(무작위 추천 외형)가 남아 있어서, 사용자는 이것이 자기 아바타인 줄 안다. 여기서 '월드 입장' 을 누르면 EnterWorld → AvatarSceneHandoff.Save → main 씬의 PlayerAppearanceController.TryApplySceneHandoff 가 토큰이 있으면 진입당 1회 SaveToProfileAsync 를 부른다(96-99행). 즉 **불러오기에 실패해 만들어진 랜덤 외형이 PUT /users/me/avatar 로 서버에 기록되어 진짜 저장본을 지운다.** F1(토큰 지연 401)과 겹치면 이 경로가 정상 흐름이 된다 — 조회는 토큰 전이라 실패하고, 저장은 토큰이 도착한 뒤라 성공한다.

**근거** — CharacterLobbyController.cs:303-321 LoadPersistedAppearanceAsync — :310 `if (!appearance.IsModular || !IsUsableAppearance(appearance.ModularConfig)) return;` (SetStatus 없음), :320 `Debug.LogWarning($"[CharacterLobby] 저장 외형을 불러오지 못해 최초 추천 외형을 유지합니다: ...")`. HttpUserApiClient.cs:38-46 GetMyProfileAsync 는 실패 시 null. AvatarAppearance.Decode(null) → Default(PresetCode="sk_01", IsModular=false). PlayerAppearanceController.cs:96-99 `if (!_handoffSaved && AuthBridge.HasToken && !AuthBridge.IsGuest) { _handoffSaved = true; SaveToProfileAsync(_sceneHandoffEncoded); }`.

**제안 수정** — ① 프로필 조회가 실패(null)한 것과 '저장한 적이 없다'(avatarCode == null, 서버가 기본값을 만들지 않는 정상 상태)를 구분해 실패는 SetStatus 로 드러낸다. ② 조회에 실패한 세션에서는 자동 저장(TryApplySceneHandoff 의 SaveToProfileAsync)을 하지 않는다 — 읽지 못한 값을 쓰지 않는다. ③ 로비에 명시적 '저장' 결과 표시를 둔다.


## 19. 공용 PC 에서 앞사람 아바타가 내 계정으로 저장된다

| | |
|---|---|
| 축 | 아바타·캐릭터 |
| 상태 | **검증통과** |
| 위치 | `festa-unity/Assets/_Project/Scripts/World/Avatar/AvatarSceneHandoff.cs:15` |
| 처리 | ✅ 수정 `4874eb7b` — 핸드오프 키를 JWT `sub` 해시별로 분리(`AuthBridge.SubjectKey`), 주체가 바뀌면 메모리 복사본 즉시 폐기 |

**사용자가 겪는 일** — 교육장 공용 PC 에서 앞사람이 쓰고 간 뒤 내가 로그인하면, 캐릭터 로비에 **앞사람이 만든 아바타**가 떠 있다. 내가 저장해 둔 아바타는 조회조차 되지 않는다. 그대로 월드에 들어가면 앞사람 외형이 내 계정에 저장되어, 집에 가서 내 노트북으로 접속해도 그 외형이 나온다.

**내용** — AvatarSceneHandoff 는 로비→월드 핸드오프 값을 PlayerPrefs 키 "festa.avatar.scene-handoff" 하나에 저장한다(15행) — **사용자 식별자가 키에 없다.** 그리고 로비 Awake 는 서버 프로필보다 이 핸드오프를 먼저 본다: TryGetLiveAppearance → TryGetSceneHandoffAppearance → 무작위 추천 순이고(178-184행), TryGetSceneHandoffAppearance 는 성공하면 `_restoredExistingAppearance = true` 를 세운다(299행). 그러면 InitializeFromServerAsync 의 `if (!_restoredExistingAppearance) await LoadPersistedAppearanceAsync();`(222행) 가 통째로 건너뛰어져 **서버에 내 아바타가 무엇인지 아예 묻지 않는다.** PlayerPrefs 는 브라우저 오리진 단위로 남고 로그아웃 시 지워지지 않는다(AuthBridge.ClearAccessToken 은 PlayerPrefs 를 건드리지 않는다). 여기에 F2 의 진입 시 자동 저장이 붙으면, 앞사람 외형이 뒷사람 계정에 PUT 된다. SSAFY 교육장 공용 PC·같은 브라우저 프로필을 여러 명이 쓰는 상황이 정확히 이 조건이다.

**근거** — AvatarSceneHandoff.cs:15 `const string AppearanceKey = "festa.avatar.scene-handoff";` (사용자 스코프 없음), :28-30 PlayerPrefs.SetString + Save. CharacterLobbyController.cs:178-184 핸드오프가 서버 조회보다 우선, :299 `_restoredExistingAppearance = true;`, :222 `if (!_restoredExistingAppearance) await LoadPersistedAppearanceAsync();`. AuthBridge.cs ClearAccessToken 에 PlayerPrefs 정리 없음. PlayerAppearanceController.cs:96-99 진입 시 프로필 저장.

**제안 수정** — 핸드오프 키에 사용자 식별자를 붙이거나(예: `festa.avatar.scene-handoff.{sub}`), AuthBridge.ClearAccessToken·토큰 변경 시 PlayerPrefs 핸드오프를 지운다. 더 안전한 쪽은 우선순위 뒤집기 — 회원 세션이면 **서버 프로필이 정본**이고 PlayerPrefs 는 같은 세션 안 씬 전환용(정적 s_runtimeAppearance)으로만 쓴다.


## 20. 닉네임이 한글 10자를 넘으면 이름표가 잘리거나 월드 스폰이 깨진다

| | |
|---|---|
| 축 | 아바타·캐릭터 |
| 상태 | **검증통과** |
| 위치 | `festa-unity/Assets/_Project/Scripts/Network/Player/NetworkPlayer.cs:78` |
| 처리 | ✅ 수정 `93842c94` — #4 와 같은 `FitFixedString32`. 이름표는 잘린 닉네임을 그대로 표시(말없이 깨지지 않음) |

**사용자가 겪는 일** — 닉네임을 10자 이상 한글로 지은 사람은 월드에 들어가도 머리 위 이름표가 9자에서 잘려 나온다(릴리스 서버). 에디터·개발 서버로 테스트하면 그 사람의 스폰 초기화가 예외로 중단돼 아바타 코드가 설정되지 않고, 로그에는 원인과 무관해 보이는 ArgumentException 만 남는다. 본인은 왜 자기 이름만 이상한지 알 수 없다.

**내용** — BE NicknamePolicy 는 2~30 **코드포인트**를 허용하는데, Unity 의 NetworkPlayer.Nickname 은 FixedString32Bytes 다 — UTF-8 기준 **29바이트**(FixedString.gen.cs:1550 utf8MaxLengthInBytes = 29). 한글은 3바이트라 10자면 30바이트로 넘친다. 대입부에는 길이 가드가 없다: NetworkPlayer.cs:78 `Nickname.Value = session.nickname ?? "Unknown";`. 바로 아래 avatarCode 대입에는 `legacyAvatarCode.Length <= 31` 가드가 붙어 있고 주석이 그 이유를 "FixedString overflow aborts the spawn initialization" 이라고 적어 두었다 — 같은 위험을 닉네임에만 빼먹었다. 실측: 에디터/개발 빌드(ENABLE_UNITY_COLLECTIONS_CHECKS)는 ArgumentException 을 던져 서버의 OnNetworkSpawn 이 중단되고, CiBuild 가 BuildOptions.None 으로 뽑는 릴리스 Linux 서버에서는 체크가 컴파일 제거되어 조용히 9자로 잘린다(CopyFromTruncated). 순수 ASCII 도 30자면 같다 — BE 상한과 정확히 1 코드포인트 어긋난다.

**근거** — 실행 결과(에디터, mcp execute_code): `코드포인트  9 / UTF8 27B "싸피일일층불꽃축제" → OK`, `코드포인트 10 / UTF8 30B "싸피일일층불꽃축제요" → ArgumentException: FixedString32Bytes: Truncation while copying`, `코드포인트 30 / UTF8 30B "abcdefghijklmnopqrstuvwxyzABCD" → ArgumentException`. 코드: NetworkPlayer.cs:55 `NetworkVariable<FixedString32Bytes> Nickname`, :78 가드 없는 대입, :84-87 avatarCode 는 `<= 31` 가드 + "FixedString overflow aborts the spawn initialization" 주석. backend NicknamePolicy.java:14 `MAX_LENGTH = 30` (codePointCount). Library/PackageCache/com.unity.collections@31e96307/Unity.Collections/FixedString.gen.cs:1550 `utf8MaxLengthInBytes = 29`, :2505 CheckCopyError 는 `[Conditional("ENABLE_UNITY_COLLECTIONS_CHECKS")]`. CiBuild.cs:154 `options = BuildOptions.None` (Linux 서버).

**제안 수정** — 둘 중 하나로 맞춘다. (a) Nickname 을 FixedString64Bytes(61B, 한글 20자) 이상으로 올리고 BE 상한(30 코드포인트 = 최대 120B)에 맞춰 FixedString128Bytes 를 쓴다 — NetworkVariable 필드 선언 변경이라 NetworkPlayer 기준선(헌법 27조) 수정 여부를 먼저 확인할 것. (b) NetworkPlayer 를 못 건드리면 승인 단계(ConnectionManager.Approve)에서 룬 경계로 안전하게 자르고 그 사실을 로그로 남긴다. 어느 쪽이든 BE NicknamePolicy 와 Unity 용량을 같은 상수로 문서화한다.


## 21. 로그인 화면에서 음악을 껐는데 월드에 들어가면 다시 나온다 — 월드 안에는 끄는 방법이 없다

| | |
|---|---|
| 축 | 오디오·BGM |
| 상태 | **검증통과** |
| 위치 | `festa-unity/Assets/_Project/Scripts/World/Festival/WorldBgm.cs:33` |
| 처리 | 🟡 게임 쪽 완료 `f7b6258f` — `AudioBridge.SetMuted/SetVolume`(SendMessage 수신, `AudioListener.volume`). FE 가 로그인 화면의 음소거 상태를 월드 진입 시 넘겨야 끝난다 |

**사용자가 겪는 일** — 사무실·강의실·심야에 쓰는 사용자가 로그인 화면에서 음악을 끄고 들어간다. 월드가 뜨는 순간 음악이 다시 나오고, 끌 수 있는 UI 가 어디에도 없어 브라우저 탭 음소거로 도망가야 한다. 탭 음소거는 나중에 필요한 소리까지 같이 죽인다.

**내용** — 음소거 선호는 `festa.settings.music.muted` 로 localStorage 에 저장되지만, 읽는 곳은 FE 의 screenAudio.ts 하나뿐이다. Unity(WorldBgm)도, jslib 브리지(FestaUnityBridge.jslib)도 이 키를 읽지 않는다. 게다가 음소거 UI(ScreenControls)는 Landing·Login 두 페이지에만 마운트된다 — /app/world 에는 없다. 월드 안 GameMenu.tsx 에도 오디오 항목이 없다.

**근거** — grep 결과 `festa.settings.music.muted` 를 참조하는 파일은 festa-frontend/src/features/audio/model/screenAudio.ts(19·98·107행)와 테스트뿐. .cs/.jslib 전체에 0건. ScreenControls 마운트: festa-frontend/src/pages/landing/LandingPage.tsx:42, festa-frontend/src/pages/login/LoginPage.tsx:138 — 이 둘뿐. `grep -n "음소거|mute|Audio|사운드|음악" festa-frontend/src/features/world/ui/GameMenu.tsx` → 출력 없음. screenAudio.ts:16 주석이 스스로 인정: "ESC Client Settings 의 Music 설정이 생기면 이 값을 그대로 승계해" (= 아직 없다).

**제안 수정** — jslib 에 `FestaGetMusicMuted()` 를 추가해 localStorage 를 읽고, WorldBgm.Awake 에서 초기값을, storage 이벤트로 변화를 받아 `AudioListener.volume` 또는 두 소스의 mute 에 반영한다. 동시에 ScreenAudioController 를 /app/world 에서도 살려 두거나 월드 HUD 에 같은 토글을 붙여 같은 키를 쓴다.


## 22. 월드에 들어가는 순간 음악이 로그인 화면보다 약 7 dB(2배) 커진다

| | |
|---|---|
| 축 | 오디오·BGM |
| 상태 | **검증통과** |
| 위치 | `festa-frontend/src/features/audio/model/screenAudio.ts:45` |
| 처리 | ➡ FE 소관 — `AudioBridge.SetVolume`(#151) 로 FE 가 로그인 화면 볼륨을 넘기면 맞출 수 있다 |

**사용자가 겪는 일** — 로그인 화면 음악에 볼륨을 맞춰 둔 사용자가 월드에 들어가는 순간 소리가 두 배로 커진다. 헤드폰이면 놀란다. FE 의 1.5초 페이드아웃은 화면 쪽만 줄일 뿐 도착 쪽 도약을 막지 못한다 — Unity 쪽은 0.36초 만에 목표 볼륨에 닿는다(_fadeSpeed 1.4, 0.5/1.4).

**내용** — FE 로그인 BGM 의 TRACK_VOLUME 0.25 는 "도착점인 11F 방 실효 출력" 에 맞춘 값인데, 그 계산의 전제가 `_morningVolume = 0.1` 이었다. 그 값은 커밋 86dc2284(2026-09-07)에서 설계값 0.5 로 복원됐다. screenAudio.ts 는 그보다 하루 전(cfb6db26, 2026-09-06)에 들어왔고 그 뒤 갱신되지 않았다 — 주석이 지금도 "main.unity 의 _morningVolume 이 … 0.1 로 오버라이드돼 있어 11F 가 매우 조용하다" 라고 말한다. 주석에 적힌 실측 라우드니스로 다시 계산하면: 11F 도착 = -16.83 + 20·log10(0.5) = -22.85 dBFS, 로그인 화면 = -17.73 + 20·log10(0.25) = -29.77 dBFS. 방향이 뒤집혀 도착점이 +6.9 dB 크다(원래 의도는 -7.2 dB, 즉 화면이 조금 크고 도착에서 잦아드는 것).

**근거** — festa-frontend/src/features/audio/model/screenAudio.ts:30-45 (실측 -16.83 / -17.24 / -17.73 dBFS, "×0.1", "0.255" 계산 전부 명시) git show 86dc2284 --stat → "[S15P21A604-471][GAME] 11층 BGM 볼륨 0.1 -> 0.5 복원", main.unity 1줄 변경 git show f837aa0c → main.unity 에서 `-  _morningVolume: 0.5` / `+  _morningVolume: 0.1` (소파 리모델 커밋에 섞여 들어간 것) 현재 씬(HEAD·워킹트리 동일): festa-unity/Assets/_Project/Scenes/main.unity:33247 `_morningVolume: 0.5`, :33248 `_circusVolume: 0.55` git log screenAudio.ts → cfb6db26 2026-09-06 하나뿐 (86dc2284 이후 갱신 없음)

**제안 수정** — 둘 중 하나로 기준선을 다시 맞춘다. (a) TRACK_VOLUME 을 재계산한다 — 두 구역 중점 -29.6 대신 새 도착점 -22.85 dBFS 기준이면 10^((-22.85+17.73)/20) ≈ 0.55. (b) 씬의 _morningVolume 을 낮춘다. 어느 쪽이든 screenAudio.ts:26-44 주석의 계산 전제를 같은 커밋에서 고쳐야 다음 사람이 또 낡은 수를 믿지 않는다. 실측 없이 값만 바꾸지 말고 아래 스크립트로 현재 값부터 확정할 것.


## 23. 월드 BGM이 페이드인 없이 툭 켜진다 — Chromium이 클립을 통째로 디코드하는 동안 볼륨 램프가 먼저 끝나기 때문

| | |
|---|---|
| 축 | 오디오·BGM |
| 상태 | **반박됨** |
| 위치 | `festa-unity/Assets/_Project/Scripts/World/Festival/WorldBgm.cs:47` |
| 처리 | — 반박됨, 조치 없음 |

**사용자가 겪는 일** — 월드가 뜨고 나서 잠깐 조용하다가 음악이 페이드 없이 최대 볼륨으로 갑자기 시작한다. 그 직전 로그인 BGM 은 1.5초에 걸쳐 사라졌기 때문에 정적 → 큰 소리 순서가 되어 도약이 더 크게 느껴진다. 또 브라우저 메모리를 130 MB 넘게 더 쓴다 — 이 개발 머신에서 강제 종료를 부르던 바로 그 예산이다.

**내용** — WorldBgm.MakeSource 는 Awake 에서 volume 0 으로 Play() 하고, Update 가 _fadeSpeed 1.4/s 로 0.36초 만에 목표(0.5)까지 올린다. 그런데 WebGL 에서 소리는 그때 안 난다 — Unity framework 가 Chromium 이면 임포트 설정과 무관하게 `decompress = 1` 로 강제해 decodeAudioData 로 전체를 풀고, 그 디코드가 끝난 뒤에야 첫 샘플이 나온다. 디코드가 끝나는 시점엔 volume 이 이미 0.5 라 페이드가 통째로 낭비되고 음악이 최대 볼륨에서 시작한다. 같은 코드가 임포트 의도도 무효화한다. 작업일지에 "임포트: 스트리밍 + Vorbis 0.7 (BGM 을 메모리에 통째로 안 올림)" 이라고 적혀 있지만 Chromium 에서는 정확히 반대로 동작한다: 7,771,831×2×4 = 62.2 MB + 8,822,822×2×4 = 70.6 MB ≈ 133 MB 의 float32 PCM 이 브라우저 오디오 메모리에 상주한다.

**근거** — festa-unity/Builds/web/Build/60ce5ca59e1fdb2dd37399772ee3a010.framework.js, _JS_Sound_Load 내부:   "// Chrome's AudioContext retains internal references ... decode into an AudioBuffer"   `if (!!window.chrome) decompress = 1`   이어서 `sound = jsAudioCreateUncompressedSoundClipFromCompressedAudio(audioData)` WorldBgm.cs:39-49 (volume=0, Play() in Awake), :60-62 (MoveTowards, _fadeSpeed 1.4) 에디터 실측(execute_code): BGM_AlgorithmicMorning len=176.23s ch=2 freq=44100 samples=7771831 loadType=Streaming preload=False / BGM_MidnightCircus len=200.06s samples=8822822, 둘 다 WebGL override=False docs/KHS/24_작업일지.md:3300 "임포트: 스트리밍 + Vorbis 0.7 (BGM 을 메모리에 통째로 안 올림)"

**제안 수정** — (1) 볼륨 램프의 시작점을 "실제로 소리가 나기 시작한 프레임" 으로 옮긴다 — `src.isPlaying && src.time > 0f` 를 확인한 뒤부터 MoveTowards 를 돌린다. (2) 클립 길이를 루프 구간만 남기게 줄이거나(176s/200s → 60~90s) 모노·22.05 kHz 로 내려 디코드 PCM 을 1/4 로 줄인다. (3) WebGL 플랫폼 오버라이드를 명시해 무엇이 적용되는지 씬이 아니라 임포터에 남긴다.


## 24. 아케이드 1번 슬롯머신은 당첨돼도 소리가 없고 2번만 난다 — 게다가 2번은 19 m 밖에서도 들린다

| | |
|---|---|
| 축 | 오디오·BGM |
| 상태 | **검증통과** |
| 위치 | `festa-unity/Assets/_Project/Scripts/Minigame/Slot/SlotMachineInteractable.cs:40` |
| 처리 | ✅ 수정 (2026-09-09, 씬) — 1번 `winAudioSource` 를 자기 AudioSource 로 배선. 두 대 모두 volume 0.45 / minDistance 20 u(1.5 m) / maxDistance 106 u(8 m) 로 통일. spin·reelStop 은 둘 다 없음(통일) |

**사용자가 겪는 일** — 두 기계 중 어느 쪽에 앉느냐에 따라 당첨 경험이 완전히 달라진다. 1번에서 이기면 화면만 번쩍이고 소리가 없어 "이긴 게 맞나" 싶고, 2번에서 이기면 소리가 난다. 반대로 2번 근처를 그냥 지나가던 사람은 19 m 밖에서도 남의 당첨 소리를 듣는다 — 자기 행동과 무관한 소리라 놀란다.

**내용** — 광장 아케이드의 두 슬롯머신은 겉보기에 같은 기계지만 오디오 배선이 정반대다. - Arcade_01_Slot_Machine_01: AudioSource 는 있는데(클립 'Ascending Win Chime' 7.43 s, volume 0.386) PresetUVSlotMachine 의 winAudioSource 가 NULL 이라 **한 번도 재생되지 않는다**. maxDistance 도 4 unit = 0.30 m 라 설령 울려도 안 들린다. - Arcade_02_Slot_Machine_02: winAudioSource 가 자기 AudioSource 로 연결돼 있고 volume 0.5, minDistance 6 unit(0.45 m), **maxDistance 250 unit = 18.85 m**. spinAudioSource·reelStopAudioSource 는 양쪽 다 NULL 이라 릴 회전음·정지음도 없다.

**근거** — execute_code 로 씬 실측:   @Festival/Festival_Arcade/Arcade_01_Slot_Machine_01 | clip=Ascending Win Chime vol=0.3860 spatialBlend=1.00 rolloff=Logarithmic min=1.0 max=4.0 playOnAwake=False loop=False   @Festival/Festival_Arcade/Arcade_02_Slot_Machine_02 | clip=Win Bonus Chimes vol=0.5000 spatialBlend=1.00 rolloff=Logarithmic min=6.0 max=250.0 playOnAwake=False loop=False   Arcade_01: winAudioSource=NULL / spinAudioSource=NULL / reelStopAudioSource=NULL   Arcade_02: winAudioSource=Arcade_02_Slot_Machine_02 / spinAudioSource=NULL / reelStopAudioSource=NULL 재생 경로: Assets/ithappy/Casino_Free/Scripts/Slot_Machine/PresetUVSlotMachine.cs:549-552 `if (winAudioSource != null) { winAudioSource.Stop(); winAudioSource.Play(); }` 플레이어 스핀도 같은 경로를 탄다: Assets/_Project/Scripts/Minigame/Slot/SlotMachineSession.cs:173 `_reels.SpinPresetByIndex(index)` → SpinRoutine → PlayResult(preset.isWin) → PlayWinFeedback(

**제안 수정** — 두 인스턴스의 AudioSource 설정과 winAudioSource 배선을 같게 맞춘다. 거리값은 미터가 아니라 unit 이므로 원하는 가청 반경 ×13.26 으로 넣는다 — 예: 들리게 하고 싶은 반경 8 m → maxDistance 106, minDistance 1.5 m → 20. spin·reelStop 소스도 붙이거나(둘 다) 안 붙이거나(둘 다) 하나로 통일한다.


## 25. 부스 안 노트북·설문에 F 를 누르면 화면이 열리면서 동시에 축제장으로 튕겨 나간다

| | |
|---|---|
| 축 | UI·입력·접근성 |
| 상태 | **미검증** |
| 위치 | `festa-unity/Assets/_Project/Scripts/World/Festival/PortalInteractor.cs:45` |
| 처리 | ✅ 수정 `803dfb49` — #14 와 같은 가드(잠금·초점 중 포털 Update 정지). 노트북·설문 F 로 화면이 열리는 프레임에 포털이 발동하지 않는다 |

**사용자가 겪는 일** — 게시된 부스 6곳 전부에서, 출입구 쪽 0.4~0.6 m 구간에 서서 노트북/설문에 F 를 누르면 상세 화면이 뜨는 동시에 몸이 축제장으로 빠져나간다. 화면을 닫으면 부스 밖에 서 있다. 미니게임 중 F 를 누르면 게임 화면만 남고 캐릭터는 밖으로 나가 있다.

**내용** — PortalInteractor 는 InputBridge.IsLocked 도 InteractionFocusCamera.IsFocused 도 보지 않는다. 형제 컴포넌트 BoothInteractionInput 은 "화면이 열려 있으면 조준·프롬프트도 멈춘다 (S15P21A604-437)" 라는 주석과 함께 잠금을 보는데, 같은 F 키를 읽는 PortalInteractor 에는 그 처리가 없다. 두 컴포넌트가 같은 프레임의 kb.fKey.wasPressedThisFrame 을 각각 읽으므로, 노트북 상호작용이 초점 카메라를 켜고 InputBridge.SetLocked(true) 를 걸어도 PortalInteractor 는 그 프레임에 그대로 TeleportTo 를 호출한다. Unity 내부 미니게임(TimerStopGameHud·SlotMachineHud)이 떠 있는 동안에도 마찬가지다 — 그때는 캔버스가 계속 DOM focus 를 쥐고 있어서 브라우저 focus 로도 막히지 않는다.

**근거** — grep -n "IsLocked|IsFocused|InputBridge|InteractionFocusCamera" World/Festival/PortalInteractor.cs → 0건. 대조: Content/BoothInteractionInput.cs:62 `if (Festa.Integration.InputBridge.IsLocked) { UpdateHover(null); ShowHint(null); return; }`. PortalInteractor.cs:51-52 `var kb = Keyboard.current; if (kb == null || !kb.fKey.wasPressedThisFrame) return;` → :67 `_movement.TeleportTo(dest.position);`. 에디터 실측(아래 스크립트): 포털 24 / Interactive 대상 48 중 발동 구역이 겹치는 조합 14건 — 예 `Portal_Int_11`(반경 19.9u=1.50m) ↔ `Booth11_laptop-1`(사거리 15.0u=1.13m) 거리 34.2u, 여유 5.7u = 0.43m. 부스 1·3·5·7·9·11 전부 동일.

**제안 수정** — PortalInteractor.Update() 첫 줄에 BoothInteractionInput.cs:62 와 같은 가드를 넣는다 — `if (Festa.Integration.InputBridge.IsLocked) { _nearest = null; _ring.Hide(); return; }`. OnGUI() 에도 같은 가드를 넣어 프롬프트·토스트를 함께 멈춘다.


## 26. 미니게임·부스 화면 한가운데에 `[F] N번 부스 입장` 알약이 겹쳐 떠서 글자가 서로 뭉갠다

| | |
|---|---|
| 축 | UI·입력·접근성 |
| 상태 | **미검증** |
| 위치 | `festa-unity/Assets/_Project/Scripts/World/Interaction/InteractPromptUI.cs:42` |
| 처리 | ✅ 수정 (2026-09-09) — 미니게임 위 겹침은 `803dfb49` 가드로, 문 앞 두 알약 겹침은 `BoothInteractionInput.PromptShowing` 이 참이면 포털 프롬프트가 양보(부스 오브젝트 > 포털) |

**사용자가 겪는 일** — 부스 출입구 근처에서 노트북을 조준하면 `[F] 노트북 열기` 와 `[F] 11번 부스 입장` 두 알약이 화면 중앙에서 겹쳐 글자가 읽히지 않는다. 미니게임을 하는 동안에도 부스 입장 안내가 게임 화면 위에 계속 떠서, 눌러도 안 되는 안내처럼 보인다.

**내용** — InteractPromptUI 의 세 그리기 함수는 모두 화면 좌표가 고정이다 — DrawPrompt 와 DrawPassivePrompt 는 x=중앙, y=Screen.height*0.52, DrawToast 는 그 바로 위. 그런데 이 함수를 부르는 곳이 둘이다: BoothInteractionInput.OnGUI(:299) 와 PortalInteractor.OnGUI(:127). 두 대상이 동시에 사거리 안이면 폭이 다른 알약 두 개가 같은 자리에 포개져 그려지고, OnGUI 실행 순서는 정의돼 있지 않아 어느 쪽이 위로 갈지도 매번 다르다. PortalInteractor 쪽은 잠금 가드가 없으므로 미니게임 HUD·초점 카메라 화면 위에도 계속 그려진다.

**근거** — World/Interaction/InteractPromptUI.cs:41-42 `float x = Mathf.Round((Screen.width - w) / 2f); float y = Mathf.Round(Screen.height * 0.52f);` — :65-66(DrawPassivePrompt), :81-82(DrawToast) 동일 좌표. 호출부: Content/BoothInteractionInput.cs:299-306, World/Festival/PortalInteractor.cs:127-133. PortalInteractor.OnGUI 에는 IsLocked/IsFocused 가드 0건. 겹침 가능 조합 14건은 앞 항목의 실측과 같다. 대조로 ControlsHintHud.cs:121 은 `if (InputBridge.IsLocked || InteractionFocusCamera.IsFocused) return;` 로 제대로 비킨다.

**제안 수정** — ① PortalInteractor.OnGUI 에 잠금·초점 가드를 넣는다. ② 한 프레임에 프롬프트는 하나만 그리도록 InteractPromptUI 에 "이번 프레임에 이미 그렸는가" 플래그(또는 우선순위 인자)를 두고, 부스 오브젝트 > 포털 순으로 양보시킨다.


## 27. 11층 안내판·벽 글자가 웹에서만 비스듬히 보면 뭉갠다 — 에디터에서는 절대 재현되지 않는다

| | |
|---|---|
| 축 | UI·입력·접근성 |
| 상태 | **미검증** |
| 위치 | `festa-unity/ProjectSettings/QualitySettings.asset:24` |
| 처리 | ✅ 수정 (2026-09-09) — 제안 ②: 글자·사진 판 텍스처 임포터에 Trilinear + aniso 8 (Mobile 품질 레벨의 Per-Texture 와 무관하게 적용). 웹 비스듬 시야 확인은 릴리스 빌드에서(체크리스트 R-4) |

**사용자가 겪는 일** — 복도를 걸으며 벽면 안내판을 비스듬히 보면 글자가 번지고, 정면으로 다가서야 읽힌다. 개발자가 에디터에서 보면 멀쩡해서 '내 쪽에서는 잘 보이는데' 로 끝난다.

**내용** — WebGL 은 품질 레벨 0=Mobile 을 쓰는데 Mobile 의 anisotropicTextures 는 1(Per Texture)이다. 그런데 MapTexture/ 25장 전부 텍스처 자체 anisoLevel 이 1(=꺼짐)이라 WebGL 에서는 이방성 필터링이 전혀 걸리지 않는다. 반면 에디터·Standalone 은 레벨 1=PC 를 쓰고 그쪽은 anisotropicTextures: 2(ForceEnable)라 Unity 가 강제로 aniso 를 올려 준다. lodBias 도 PC 1.5 / Mobile 1.0 이라 밉 전환 거리까지 다르다. 즉 '웹에서만 안내판이 뭉갠다'는 재현 조건이 품질 레벨 차이에 그대로 들어 있다.

**근거** — ProjectSettings/QualitySettings.asset — Mobile 레벨 `anisotropicTextures: 1`(:24), `lodBias: 1`(:37 부근), PC 레벨 `anisotropicTextures: 2`(:77), `lodBias: 1.5`. `m_PerPlatformDefaultQuality:` 블록에 `WebGL: 0`, `Standalone: 1`. 에디터 실측: `QualitySettings.anisotropicFiltering=ForceEnable ... level=PC`, 각 텍스처는 `history-14 1.png mip=True mipCount=10 aniso=1 filter=Bilinear`, `campus-seoul 1.png aniso=1`, `고용노동부 1.png aniso=1`, `ssafy-tv-1.png aniso=1`.

**제안 수정** — 둘 중 하나. ① Mobile 레벨의 anisotropicTextures 를 2(ForceEnable)로 올린다 — WebGL2 에서 aniso 4~8 은 사실상 공짜다. ② 글자 보드 텍스처만 임포터에서 anisoLevel 을 4~8 로 올린다(WorldModelNaming.OnPreprocessTexture 에 `ti.anisoLevel = 8;` 한 줄). 어느 쪽이든 에디터 검증은 반드시 품질 레벨을 Mobile 로 내리고 해야 한다.


## 28. 안내판 글자 획이 압축으로 무너져 있다 — 원본 대비 PSNR 28~31 dB

| | |
|---|---|
| 축 | UI·입력·접근성 |
| 상태 | **미검증** |
| 위치 | `festa-unity/Assets/_Project/Scripts/Editor/WorldModelNaming.cs:862` |
| 처리 | ✅ 수정 (2026-09-09, [T-242](../../25_트러블슈팅.md#t-242)) — 판(.png)은 crunch 끔. 작은 판은 NPOT 그대로 무압축, 큰 판은 ToLarger + DXT1 명시(2048² 상한). 에디터 전후 캡처 `verify/signboard-2026-09-09/` |

**사용자가 겪는 일** — 부스 앞이나 벽 앞에 정면으로 서도 안내판의 작은 한글이 뭉개져 읽히지 않는다. 캠퍼스 이름·연혁 같은 정보 전달이 목적인 보드가 장식으로 전락한다.

**내용** — WorldModelNaming 의 OnPreprocessTexture 가 Assets/_Project/Models/MapTexture/ 아래 전량에 crunch 압축(품질 75)을 무조건 건다. 작업일지에는 '시각 이상 없음 확인' 으로 남아 있지만 그 확인은 배경 텍스처 기준이고, 같은 폴더에 한글 글자가 찍힌 안내판 보드가 섞여 있다. .meta 의 WebGL 항목은 overridden: 0 이라 Default(crunch on)가 그대로 적용된다. 원본 PNG 를 임포트 해상도로 같은 방식으로 리샘플한 뒤 비교하면 글자 보드는 PSNR 28.0~31.6 dB, 채널 최대 오차 69~142 — 사진이라면 몰라도 얇은 획에는 치명적이다. 추가로 npotScale=ToNearest 가 비-2의거듭제곱 원본을 강제로 늘리거나 줄여(ssafy-tv-1: 1462x825 → 1024x1024, 가로 해상도 -30%) 압축 전에 이미 한 번 흐려진다.

**근거** — festa-unity/Assets/_Project/Scripts/Editor/WorldModelNaming.cs:862-869 `void OnPreprocessTexture() { ... ti.textureCompression = Compressed; ti.crunchedCompression = true; ti.compressionQuality = 75; }`. `history-14 1.png.meta` platformSettings: DefaultTexturePlatform crunchedCompression: 1 / quality 75, WebGL 항목 overridden: 0. 에디터 실측 PSNR(원본→같은 해상도 리샘플 vs 임포트 결과, sRGB 경로): history-14 29.2 dB / maxErr 109, campus-seoul 29.5 / 95, history-banks 31.2 / 69, history-bool 28.0 / 142, campus-daejun 31.6 / 111 — 전부 '글자 무너짐' 구간. 대조군(원본을 같은 경로로 왕복) PSNR=99.0 dB, maxErr=0 이라 측정 경로 자체의 손실은 없다. 원본/임포트 해상도 불일치: history-14 410x234→512x256, history-bool 266x152→256x128, 고용노동부 566x182→512x128, ssafy-tv-1 1462x825→1024x1024.

**제안 수정** — OnPreprocessTexture 에서 글자 보드는 예외 처리한다 — 파일명 화이트리스트(history-*, campus-*, 고용노동부, ssafy-tv-*, KEF)에는 `ti.crunchedCompression = false; ti.compressionQuality = 100; ti.npotScale = TextureImporterNPOTScale.None;` 를 준다. 다운로드 증가분은 이 몇 장 기준 수백 KB 수준이다. 반대로 배경(Carpet·Wood·Metal·tile)은 지금대로 둔다.


## 85. 축제장 소형 정적 소품이 한 개씩 그려진다 — 풍선 91·벽등 52·상자 28·표지판 24 가 각각 드로우콜

| | |
|---|---|
| 축 | 남은 성능 병목 |
| 상태 | **실측 확인** (2026-09-09, 사용자 지시 "관람차나 전구줄 같은 것들도 묶어서 어색하지 않은 애들끼리 다 묶는 게 좋겠다") |
| 위치 | `festa-unity/Assets/_Project/Scenes/main.unity` `@Festival/*` |
| 처리 | ✅ 병합 (2026-09-09) — `Editor/FestivalStaticCombiner.cs` 로 렌더러 204 → 30 (풍선 98→3, 벽등 52→14, 표지판 24→3, 가로등 14→8, 소품 16→2). 오클루전 재베이크. **7포즈 드로우콜: 축제중심 동향 671→523(−22%), 서향 646→498(−23%), 끝벽 동향 1,095→779(−29%), 복도 318→297, 로비 554→546.** 증적 `verify/occlusion-ab/combine-before*` / `combine-after*`. 관람차는 캐빈이 스크립트로 개별 이동해 제외, 전구줄은 이미 병합. 빌드 확인 R-9 |

**사용자가 겪는 일** — 축제장에서 동쪽을 볼 때 프레임이 가장 낮다. 화면에는 풍선·벽등·상자·표지판 같은 작은 물건이 수백 개 있고 각각이 드로우콜 하나(그림자 패스까지 두 개)다. 정적 배칭은 WebGL 에서 꺼져 있어(#2) 같은 재질이라도 묶이지 않는다.

**내용 (에디터 실측, `@Festival` 렌더러 396개 / 139만 삼각형)** —

| 그룹 | 렌더러 | 재질 | 병합 가능 | 비고 |
|---|---:|---:|---|---|
| Festival_Ambience | 102 | 2 | ✅ 풍선 91 + 막대 7 → 재질당 1 | 콜라이더 4·스크립트 1(SearchBeam 파티클 Quad 4 는 제외) |
| Festival_WallLights | 52 | 2 | ✅ 금속 39 → 1, 전구 13 → 1 | Light 13·스크립트 13 은 그대로 둔다(렌더러만 병합) |
| Festival_Clutter | 28 | 2 | ✅ 28 → 2 | 스크립트·콜라이더 없음 |
| Festival_BoothSigns | 24 | 1 | ✅ 24 → 1 | |
| Festival_Props | 18 | 3 | 🟡 풍선 13+막대 1 → 1 | 나머지는 이미 `_Combined` |
| Festival_Lamps | 14 | 2 | ✅ 기둥 7 → 1, 구 7 → 1 | Light 7 유지 |
| Festival_Backdrop(관람차) | 13 | 2 | 🟡 회전부(휠+캐빈 10)를 회전 트랜스폼 아래에서 1개로 | `RimBulbs` 는 이미 1개. 회전 스크립트 확인 후 |
| Festoon_Bulbs_Combined(전구줄) | 4 | 4 | — 이미 병합됨 | 재질 4색 → 4 |
| Festival_Trees | 92 | 2 | ✖ LODGroup 소속 | 별건 |
| Festival_Slots | 12 | 11 | ✖ 12개인데 삼각형 72만 | LOD/감량은 별건(#30) |

**제안 수정** — 그룹 안에서 (재질, 공간 셀 ≈250u) 단위로 `Mesh.CombineMeshes` 를 에디터에서 굽고(`Art/Generated/Combined/`), 원본 렌더러는 끄고 콜라이더·Light·스크립트는 그대로 둔다. Festoon 전구줄이 이미 같은 방식이다. 병합 뒤 오클루전 재베이크(T-217 가드가 있어 안전) → `Festa/측정/오클루전 A·B` 7포즈로 드로우콜 전후 비교.

# 심각도: 중간

## 29. 아바타가 처음 나타날 때·부스에 처음 마우스를 올릴 때 한 번 멈춘다 — 셰이더 프리웜이 전무하다

| | |
|---|---|
| 축 | 남은 성능 병목 |
| 상태 | **미검증** (빌드로만 확인 가능) |
| 위치 | `festa-unity/ProjectSettings/GraphicsSettings.asset:43` |
| 처리 | 🟡 1차 (2026-09-09, S15P21A604-493) — 이 에디터 세션(로비 제외 월드·부스·축제장 플레이)에서 추적된 변형을 `Resources/FestaTrackedVariants.shadervariants` 로 저장(178 셰이더/304 변형 → 에디터·Hidden·Legacy 102개 제거 후 **76 셰이더/187 변형**), `GraphicsSettings.m_PreloadedShaders` 에 등록. 로딩 시간 증가분·첫 시야 힛치 감소는 릴리스 빌드에서(체크리스트 R-10). 로비·아케이드 변형은 다음 플레이 투어 뒤 재저장 |

**사용자가 겪는 일** — 월드에 들어가 첫 아바타(자기 자신 포함)가 그려지는 순간 화면이 한 번 확 멈춘다. 부스에 처음 커서를 올릴 때 또 한 번, 첫 불꽃/컨페티에서 또 한 번. 두 번째부터는 멀쩡해서 '가끔 튄다' 로만 보고된다.

**내용** — GraphicsSettings.m_PreloadedShaders 가 빈 배열이고, 프로젝트 전체에 .shadervariants(ShaderVariantCollection) 자산이 하나도 없다. 그래서 모든 셰이더 변형이 **처음 그려지는 프레임에** GLSL 컴파일·링크된다. WebGL 에서 이 작업은 메인 스레드 동기 작업이라 그대로 프레임 정지로 보인다. 특히 위험한 것은 씬에 미리 배치돼 있지 않고 런타임에만 등장하는 재질들이다. 아바타 조립기가 Shader.Find 로 6종 커스텀 셰이더(SkinTint·FaceTint·IrisTint·MouthTint·HairTint·GarmentTint)를 찾아 재질을 새로 만든다 — 이 셰이더들은 AlwaysIncludedShaders 에는 들어 있어 빌드에서 빠지지는 않지만(그건 이미 대비돼 있다), **미리 컴파일되지는 않는다.** 부스 호버 아웃라인(Festa/Outline)도 Resources 에 있어 빌드에는 포함되나 첫 호버 때 컴파일된다. URP Lit 은 패스가 9개라 변형 수가 가장 많고, 씬 재질 125개 중 111개가 URP Lit 이다.

**근거** — ProjectSettings/GraphicsSettings.asset:43  m_PreloadedShaders: [] $ find Assets -name '*.shadervariants'  → (없음) 리플렉션 확인:   Festa/Avatar/{Skin,Face,Iris,Mouth,Hair,Garment}Tint : alwaysIncluded=True  found=True   Festa/Outline   : alwaysIncluded=False found=True (Assets/_Project/Resources/Shaders/FestaOutline.shader — Resources 라 빌드엔 포함)   Universal Render Pipeline/Lit : passes=9 런타임 Shader.Find 지점:   World/Avatar/Assembly/AvatarAssembler.cs:459,480,520,534   Booth/Interaction/BoothInteractionTarget.cs:232   World/Interaction/InteractRing.cs:46 (Mobile/Particles/Additive) 씬 재질 125개 / 셰이더 8종 (URP Lit x111, URP Unlit x4, Mobile/Particles/Additive x5, Festa/WorldText x1, TMP Distance Field x1, 그 외 3)

**제안 수정** — 플레이 모드에서 아바타 소환·부스 호버·이모트·불꽃놀이를 한 바퀴 돌린 뒤 Graphics > Shader Loading 의 'Save to asset...' 으로 ShaderVariantCollection 을 뜨고, 그것을 m_PreloadedShaders 에 넣어라. 그리고 로딩 화면이 떠 있는 동안(진행률 100% 직전) ShaderVariantCollection.WarmUp() 을 호출해 멈춤을 로딩 구간으로 옮겨라. m_PreloadShadersBatchTimeLimit(-1) 로 한 프레임에 몰리지 않게 나눌 수 있다.


## 30. 축제장 부스 12동이 삼각형 72만 개 — 전체의 41%인데 LOD 가 하나도 없다

| | |
|---|---|
| 축 | 남은 성능 병목 |
| 상태 | **미검증** |
| 위치 | `festa-unity/Assets/_Project/Scenes/main.unity` |
| 처리 | ⏸ 자산 작업 — LOD 메시 제작이 필요(모델 원작자 부재, 블렌더 감량은 별건). 사용자 테스트 뒤 |

**사용자가 겪는 일** — 축제장 안을 걸을 때 카메라가 부스 쪽을 향하면 프레임이 내려간다. 부스에서 멀어져도 정점 비용이 그대로라 '멀리 갔는데도 안 가벼워진다' 로 느껴진다. 저사양 GPU 일수록 두드러진다.

**내용** — @Festival/Festival_Slots 아래 FestivalSlot_01~12_Combined 는 각각 하나의 통합 메시이고 합계 720,128 삼각형이다. 씬 전체 활성 렌더러 삼각형 1,750,182 의 41% 를 12개 오브젝트가 차지한다. 단일 오브젝트 최대는 FestivalSlot_03_Combined 181,818, 다음이 01(158,266), 11(145,101) 이다. 이들 정적 플래그는 OccludeeStatic 뿐이라 **가려질 수는 있어도 남을 가리지는 못하고**(OccluderStatic 없음), 씬의 LODGroup 23개는 전부 Festival_Trees 의 가문비나무이고 부스에는 하나도 없다. 즉 500 m 밖에서 봐도 18만 삼각형 원본 그대로 그린다. 같은 성격으로 Festival_Decor_Combined 1개가 102,268 삼각형(서브메시 4), Festoon_Bulbs_Combined 4개가 59,904 다. 드로우콜 관점에서는 문제가 아니다 — 통합돼 있어 12드로우콜뿐이고 SRP Batcher(setPassCalls=53 / drawCalls=579)가 잘 묶고 있다. 순수하게 정점 처리량 문제다.

**근거** — @Festival 자식 그룹별 (렌더러 / 서브메시 / 삼각형 / BatchingStatic):   Festival_Slots : 97 / 114 / 827,103 / 0   Festival_Trees : 92 / 184 / 399,260 / 92   Festival_Decor : 1 / 4 / 102,268 / 0   Festoon_Bulbs_Combined : 4 / 4 / 59,904 / 0 메시별(Festival_Slots): FestivalSlot_03_Combined x1 tris=181,818 / _01 158,266 / _11 145,101 / _09 74,794 / _04 48,818 / _05 48,378 / _08 19,636 / _06 14,886 / _07 10,595 / _10 7,748 / _12 6,314 / _02 3,774  = 720,128 정적 플래그 샘플: FestivalSlot_01_Combined flags=OccludeeStatic (BatchingStatic·OccluderStatic 없음) LODGroup 23개 = 전부 @Festival/Festival_Trees/UNS_Spruce_01|02 고정 포즈 (-754,173,0) yaw=85: Festival_Slots 기여 47렌더러 / 53서브메시 / 508,865 삼각형 씬 전체 활성: 렌더러 1,155(에디트 모드) 삼각형 1,750,182

**제안 수정** — 부스 통합 메시에 LODGroup 을 붙이고 LOD1 을 데시메이트(50%)·LOD2 를 25% 로 굽는 것이 정석이지만 원본 저작자 부재 문제가 있으니, 블렌더로 Combined 메시만 데시메이트해 LOD 슬롯에 넣어라. 그 전에 값싼 확인부터: Festival_Slots 12동에 OccluderStatic 을 주고 오클루전을 다시 구우면 부스가 서로를 가려 프러스텀 뒤쪽 부스가 빠질 수 있다 — 오늘 재베이크 인프라가 이미 서 있으므로 비용이 거의 없다. QualitySettings 의 lodBias(Mobile=1) 는 손대지 마라 — 나무 LOD 전환 거리가 같이 바뀐다.


## 31. 사람이 많아질수록 조금씩 무거워진다 — 아바타마다 매 프레임 Physics.RaycastAll 을 쏘고 배열을 버린다

| | |
|---|---|
| 축 | 남은 성능 병목 |
| 상태 | **미검증** |
| 위치 | `festa-unity/Assets/_Project/Scripts/World/Avatar/PlayerAvatarVisual.cs:391` |
| 처리 | ✅ 수정 (2026-09-09) — `Physics.RaycastAll` → 공유 버퍼 `RaycastNonAlloc`(16). 프레임당 배열 할당 0 |

**사용자가 겪는 일** — 혼자일 때는 안 느껴지고 사람이 20~30명 모이면 전반적으로 무거워진다. 그리고 주기적으로 살짝 튄다(GC). 부스 앞처럼 사람이 몰리는 곳에서 가장 심하다.

**내용** — PlayerAvatarVisual.LateUpdate() 가 아바타마다 매 프레임 TryFindGroundHeight() 를 호출하고, 그 안이 Physics.RaycastAll(origin, down, 100f, ~0, Ignore) 다(PlayerAvatarVisual.cs:391). RaycastAll 은 히트 개수만큼 RaycastHit[] 를 **새로 할당해 반환한다** — 결과에서 실제로 쓰는 것은 '가장 높은 y' 하나뿐인데 배열을 통째로 버린다. 레이어 마스크가 ~0 이라 모든 콜라이더(활성 439개, 그중 MeshCollider 191개·전부 non-convex)를 상대한다. 같은 파일의 HoldFeetOnGround() → TryFindGroundBelowFeet() 도 RaycastAll 이지만 이쪽은 FootCheckInterval=0.1 로 10 Hz 스로틀이 걸려 있고 접지 중에만 돈다(PlayerAvatarVisual.cs:305). 문제는 스로틀이 없는 쪽이다. 주석(PlayerAvatarVisual.cs:499-503)은 '아바타당 레이캐스트 1회라 40명이어도 프레임당 40회로 무시할 수 있다' 고 적어 두었는데, 실제로는 단발 Raycast 가 아니라 할당형 RaycastAll 이라 4배 비싸다. WorldNameplate.LateUpdate() 도 아바타마다 돌면서 120프레임마다 GetComponentsInChildren<Renderer>(true) + new List + ToArray 를 한다(WorldNameplate.cs:311-318) — 인원수만큼 동시에 터지는 주기적 할당이다.

**근거** — 에디터 실측(씬 콜라이더 439개, 축제장 지면 (-575,145) 위 3,000회 평균):   Physics.RaycastAll(100u, ~0) = 6.23 us/call, 평균 히트 1.16개, 호출당 쓰레기 약 89 B   Physics.RaycastNonAlloc      = 1.86 us/call   Physics.Raycast(단발)         = 1.51 us/call   → RaycastAll 이 단발 대비 4.1배 부스 내부(700,0): RaycastAll 0.86us, 평균 히트 1.87개, 쓰레기 128 B/call 호출 지점: World/Avatar/PlayerAvatarVisual.cs:391 (TryFindGroundHeight, 스로틀 없음, LateUpdate:520 에서 매 프레임)             World/Avatar/PlayerAvatarVisual.cs:305 (TryFindGroundBelowFeet, 10Hz 스로틀) 산술: 30명 x 60fps x 6.23us = 11.2 ms/초(에디터 기준) + 30 x 89B x 60 = 약 160 KB/s 쓰레기 주의: 이 수치는 데스크톱 에디터 값이다. WebGL 실측이 아니다. 씬 콜라이더 내역: BoxCollider 196 / MeshCollider 191(convex 0) / CapsuleCollider 50 / CharacterController 2

**제안 수정** — 세 가지가 각각 독립적으로 효과가 있다. (a) RaycastAll → RaycastNonAlloc + 정적 버퍼(할당 0, 3.4배 빠름). (b) ~0 대신 지면 레이어만 담은 마스크를 쓰면 MeshCollider 191개 대부분을 건너뛴다. (c) 접지 그림자 위치 갱신은 매 프레임일 필요가 없다 — HoldFeetOnGround 처럼 10 Hz 로 낮추고 사이는 보간해라. 다만 T-183(스폰 직후 그림자 안 보임)이 이 매 프레임 갱신으로 고쳐진 이력이 있으니, 주기를 두려면 '바닥을 못 찾았을 때는 다음 프레임 즉시 재시도' 를 남겨라.


## 32. 축제장 NPC 12기가 화면 밖에서도 계속 애니메이션을 돌린다

| | |
|---|---|
| 축 | 남은 성능 병목 |
| 상태 | **에디터 실측 확인** — Animator 13기 중 12기 AlwaysAnimate |
| 위치 | `festa-unity/Assets/_Project/Scenes/main.unity` |
| 처리 | ✅ 수정 (2026-09-09, 씬) — 12기 `cullingMode` → `CullUpdateTransforms` (SerializedObject, 벤더 스크립트 무수정) |

**사용자가 겪는 일** — 축제장 어디에 있든, NPC 를 보고 있지 않아도 CPU 를 쓴다. 12기라 단독으로는 크지 않지만 사람이 붐빌 때 아바타 비용 위에 그대로 얹힌다.

**내용** — @Festival/Festival_Slots/FestivalSlot_NN/Staff_NN 아래 Animator 12기가 전부 cullingMode=AlwaysAnimate 다. 이 설정은 렌더러가 컬링돼도 애니메이터 그래프를 평가하고 본 트랜스폼까지 기록한다. 씬의 Animator 13기 중 12기가 이 상태이고, 유일하게 정상인 것은 @ManagementDesk/Visual_31_Cashier(CullUpdateTransforms) 다. 출처는 벤더 스크립트다 — ithappy/Casino_Free/Scripts/Slot_Machine/SlotMachineCharacterSpawner.cs:176 이 animator.cullingMode = AlwaysAnimate 를 박아 넣는다(원본 수정 금지 대상). 다만 지금 씬의 12기는 이미 씬에 배치된 인스턴스라 벤더 파일을 건드리지 않고 씬에서 덮을 수 있다. 대조군이 이미 프로젝트 안에 있다: 플레이어 아바타는 PlayerAvatarVisual.cs 에서 CullUpdateTransforms 를 쓰고 있고, 그 주석에 화면 밖 40기 기준 9.17 ms → 6.46 ms(29.5% 개선) 라는 자체 A/B 실측이 적혀 있다.

**근거** — 씬 실측 Animator 13기:   AlwaysAnimate = 12  (@Festival/Festival_Slots/FestivalSlot_01~12/Staff_NN/Visual_*, ctrl=Adult_Security_Idle_1)   CullUpdateTransforms = 1  (@ManagementDesk/Visual_31_Cashier) 활성 SkinnedMeshRenderer 91개, 삼각형 115,630, 블렌드셰이프 0 UnityStats(플레이 중) visibleSkinnedMeshes=12 출처: Assets/ithappy/Casino_Free/Scripts/Slot_Machine/SlotMachineCharacterSpawner.cs:176  animator.cullingMode = AnimatorCullingMode.AlwaysAnimate; 대조: Assets/_Project/Scripts/World/Avatar/PlayerAvatarVisual.cs — _animator.cullingMode = AnimatorCullingMode.CullUpdateTransforms (주석에 40기 9.17ms→6.46ms 실측)

**제안 수정** — 씬의 12기 Animator 를 CullUpdateTransforms 로 바꾼다(벤더 파일은 건드리지 않는다). 이 NPC 들은 제자리 Idle 만 하므로 CullCompletely 도 안전하지만, 다시 보일 때 포즈가 튈 수 있으니 CullUpdateTransforms 가 무난하다. 씬 편집이므로 조명 담당이 같은 씬을 만지는 중이면 충돌하지 않게 순서를 맞춰라.


## 33. 닉네임과 같은 결함이 avatarCode 가드에도 남아 있다 — 30·31자 코드는 가드를 통과한 뒤 던진다

| | |
|---|---|
| 축 | 네트워킹·재접속 |
| 상태 | **반박됨** |
| 위치 | `festa-unity/Assets/_Project/Scripts/Network/Player/NetworkPlayer.cs:104` |
| 처리 | — 반박됨. #4 의 `FitFixedString32` 가 avatarCode 에도 같은 바이트 기준으로 적용된다(`93842c94`) |

**사용자가 겪는 일** — 오늘은 사용자에게 보이지 않는다(클레임이 비어 있어 도달하지 않는다). 그러나 avatarCode 클레임을 되살리거나 프리셋 코드가 30자를 넘게 되는 순간, 그 외형을 가진 사람 전원이 위 항목과 같은 방식으로 월드에서 조작 불가 상태가 된다.

**내용** — NetworkPlayer.cs:104 의 가드는 `legacyAvatarCode.Length <= 31` 이다. FixedString32Bytes 의 실제 용량은 29바이트이므로 30자·31자 ASCII 코드는 가드를 통과하고 대입에서 ArgumentException 을 던진다. 결과는 위 항목과 완전히 같다 — 0번 NetworkBehaviour 에서 스폰 초기화가 중단된다. 오프바이투다. 지금은 BE 가 grant 에서 avatarCode 클레임을 빼서(WorldEntryTokenIssuer.java:79-86) 이 값이 항상 null 로 들어와 도달하지 않지만, 클레임이 되돌아오거나 프리셋 코드 명명 규칙이 길어지면 바로 터진다. 함정을 알고 가드를 달았는데 그 가드가 틀린 자리라는 점에서 위험도가 높다.

**근거** — NetworkPlayer.cs:104-106   AvatarCode.Value = !string.IsNullOrEmpty(legacyAvatarCode) && legacyAvatarCode.Length <= 31       ? legacyAvatarCode       : Festa.World.AvatarAppearance.DefaultPreset; MCP execute_code 실행 결과:   chars=30 utf8=30B  THROW ArgumentException   chars=31 utf8=31B  THROW ArgumentException (즉 가드가 허용하는 30·31 이 실제로는 초과다)

**제안 수정** — `Length <= 31` 을 `System.Text.Encoding.UTF8.GetByteCount(legacyAvatarCode) <= 29` 로 바꾼다. 위 항목의 FitFixed32 헬퍼를 만들면 두 줄이 같은 헬퍼를 쓰게 되어 다시 어긋나지 않는다. 상수 29 는 Unity.Collections 의 값이므로 코드 옆에 출처 주석을 남긴다.


## 34. 잠깐 끊겼다 돌아오면 있던 자리가 아니라 엘리베이터 앞 입구로 되돌아가 있다

| | |
|---|---|
| 축 | 네트워킹·재접속 |
| 상태 | **검증통과** |
| 위치 | `festa-unity/Assets/_Project/Scripts/Network/Connection/ConnectionManager.cs:193` |
| 처리 | ⏸ 보류 — `ConnectionManager` 는 동결 기준선(헌법 27조). 재접속 위치 복원은 서버 스폰 정책 변경이라 팀 결정 뒤 |

**사용자가 겪는 일** — 부스 안에서 상담 중이거나 축제장 안쪽에 있다가 잠깐 다른 탭을 봤다 돌아오면, 재접속은 되지만 엘리베이터 앞 입구에 서 있다. 하던 걸 이어서 하려면 다시 걸어가야 한다. 사용자 입장에서는 '끊겼다 붙었다' 가 아니라 '처음부터 다시' 로 느껴진다.

**내용** — 재접속은 위치를 복원하지 않는다. WorldReconnector 는 새 world-session 을 받아 ConnectionManager.StartClient 를 다시 걸 뿐이고, 서버의 ApprovalCheck → Approve 는 response.Position 을 항상 스폰 격자에서 계산한다(GetSpawnPosition). 게다가 슬롯은 clientId 로 새로 배정되므로(AcquireSpawnSlot — 비어 있는 가장 낮은 슬롯) 원래 있던 슬롯도 아니다. 스폰 중심은 (-12, 0, -238) = 엘리베이터 앞이다. 외형은 AvatarSceneHandoff 의 static 캐시로 복원되는데(WorldReconnector.cs:26 주석) 위치만 빠졌다. 탭을 다른 데 봤다 돌아오는 것은 정상 행동이고(T-120), 그때마다 축제장 반대편에서 입구로 끌려온다.

**근거** — ConnectionManager.cs:193   response.Position = GetSpawnPosition(slot); ConnectionManager.cs:228   static readonly Vector3 SpawnCenter = new Vector3(-12f, 0f, -238f); ConnectionManager.cs:235-244  AcquireSpawnSlot — 비어 있는 가장 낮은 슬롯 (재접속 시 원래 슬롯 보장 없음) WorldReconnector.cs:174-177  StartClient(session, new ConnectionPayload{ userId=0, nickname="reconnect", ... })  — 위치 정보 없음 WorldReconnector.cs:26  "아바타 외형은 AvatarSceneHandoff 에 남아 있어 재스폰 시 그대로 복원된다" (외형만 언급) docs/25_트러블슈팅.md:852  T-120 — 백그라운드 탭 30초 이탈은 실사용자의 정상 행동

**제안 수정** — 자발적이지 않은 끊김에 한해 마지막 위치를 복원한다. ① 클라이언트가 로컬 플레이어 위치를 AvatarSceneHandoff 같은 static 캐시에 주기적으로(예: 1초) 남긴다. ② 재접속 payload 가 아니라 — 클라이언트가 주장하는 값은 신뢰하지 않는다(헌법 16조) — 서버가 grant 의 sub 로 최근 위치를 들고 있게 한다: WorldSessionRegistry 옆에 `sub -> (position, 만료시각 60초)` 테이블을 두고, ApprovalCheck 에서 같은 sub 의 최근 위치가 있으면 response.Position 으로 쓴다. 60초를 넘겼거나 없으면 지금처럼 스폰 격자. 이러면 위치 위조도 막히고 슬롯 재배정 문제도 자연히 사라진다.


## 35. 서버가 죽어 있으면 '재접속 중…' 스피너가 5분 넘게 돌고, 최악의 경우 영원히 안 끝난다

| | |
|---|---|
| 축 | 네트워킹·재접속 |
| 상태 | **반박됨** |
| 위치 | `festa-unity/Assets/_Project/Scripts/Network/Connection/WorldReconnector.cs:144` |
| 처리 | — 반박됨, 조치 없음 |

**사용자가 겪는 일** — 서버가 재시작 중이거나 LB 뒤에서 응답이 없으면, 사용자는 아무것도 못 하는 '재접속 중…' 전면 오버레이 앞에서 몇 분을 기다린다. 끝날 때까지 취소 버튼도 없다. 상황에 따라 아예 안 끝나서 새로고침 말고는 방법이 없다. 문서와 코드 주석은 67초라고 말하고 있어서 팀도 이 시간을 모른다.

**내용** — WorldReconnector 의 백오프는 2·5·10·20·30초 합계 67초로 설계돼 있지만(WorldReconnector.cs:31), 그 시계는 '끊김 이벤트가 와야' 돈다. UnityTransport 는 m_MaxConnectAttempts 60 × m_ConnectTimeoutMS 1000 이라 응답 없는 상대에게는 한 번의 연결 시도가 최대 60초를 먹는다. 그동안 WorldReconnector 는 `nm.IsListening || nm.IsClient` 분기에서 1초마다 다시 보기만 하고 회차를 세지 않는다(:144). 그래서 5회 = 67초가 아니라 최대 67 + 300 = 6분 가까이 된다. 더 나쁜 것은 그 분기에 시간 상한이 없다는 점이다 — NGO 가 어떤 이유로든 IsListening 을 내려놓지 않으면 _attempt 가 영원히 증가하지 않아 'failed' 로 넘어가지 않고, 사용자는 '로비로 돌아가기' 버튼조차 못 본다. 재접속 상태 전체를 감싸는 벽시계 데드라인이 없다.

**근거** — festa-unity/Assets/_Project/Scenes/main.unity:48803-48804   m_ConnectTimeoutMS: 1000   m_MaxConnectAttempts: 60        → 연결 시도 1회가 최대 60,000 ms WorldReconnector.cs:31   BackoffSeconds = { 2, 5, 10, 20, 30 }   // 주석: "합계 ~67초" WorldReconnector.cs:144  if (nm == null || nm.IsListening || nm.IsClient) { _nextAttemptAt = Time.realtimeSinceStartup + 1f; return; }   // _attempt 증가 없음, 상한 없음 WorldReconnector.cs:68   if (_reconnecting && !_inFlight && Time.realtimeSinceStartup >= _nextAttemptAt) _ = TryReconnectAsync(); festa-frontend/src/unity/host/UnityHost.tsx:205-211  reconnecting 이면 '재접속 중…' 오버레이(캔버스 전면 차단), 취소 수단 없음

**제안 수정** — ① 재접속 전체에 벽시계 데드라인을 둔다: 첫 끊김 시각을 기록하고 90초를 넘기면 회차와 무관하게 Notify("failed", "TIMEOUT") 로 끝낸다. ② 폴링 분기에도 개별 시도 상한을 둔다 — 한 시도가 15초를 넘으면 NetworkManager.Shutdown() 을 걸어 전송 계층을 강제로 접고 다음 회차로 넘어간다. ③ UnityTransport 의 MaxConnectAttempts 를 60 → 10 으로 내린다(=10초). 60초는 웹 클라이언트에게 의미 없는 값이다. ④ FE 오버레이에 '지금 포기하고 로비로' 를 붙여 사용자가 기다림을 끊을 수 있게 한다.


## 36. 다 같이 입장하는 순간 서버가 딸꾹질한다 — 입장 승인마다 메인 스레드에서 디스크 동기화를 한다

| | |
|---|---|
| 축 | 네트워킹·재접속 |
| 상태 | **반박됨** |
| 위치 | `festa-unity/Assets/_Project/Scripts/Network/Auth/GrantReplayLedger.cs:282` |
| 처리 | — 반박됨, 조치 없음 |

**사용자가 겪는 일** — 시연이나 수업 시작처럼 여러 명이 동시에 들어오는 그 순간에, 이미 월드에 있는 사람들의 화면에서 다른 사람들이 순간이동하듯 끊겨 움직인다. 최악의 경우 승인 자체가 10초 버퍼를 넘겨 뒤에 붙은 사람이 이유 없이 튕긴다. 서버를 오래 켜 둘수록 원장 파일이 커져 재기동 시간도 길어진다.

**내용** — GrantReplayLedger.Append 가 입장 승인 경로 안에서 `stream.Flush(true)` 를 부른다. Flush(true) 는 OS 버퍼가 아니라 디스크까지 내려쓰는 fsync 다 — 재배포를 넘어 재사용을 막으려는 의도적 선택이고 그 이유는 타당하지만, 호출 위치가 ConnectionApprovalCallback 안, 즉 서버 메인 스레드다. 승인 1건당 fsync 1회이고 병렬화가 없다. 배포 환경의 원장 경로는 /var/lib/festa-world/used-grants.log 즉 퍼시스턴트 볼륨이라 로컬 SSD 보다 fsync 가 훨씬 비싸다. 40명이 동시에 들어오는 순간(시연 시작) 40회 fsync 가 메인 스레드에서 직렬로 돌아 그 시간만큼 서버 틱이 밀린다 — 이미 안에 있는 사람 전원의 이동이 그동안 끊긴다. 파일은 append 만 하고 압축하지 않아 계속 자란다는 점도 같이 본다.

**근거** — GrantReplayLedger.cs:272-291  Append():   using var stream = new FileStream(s_path, FileMode.Append, ...);   writer.Flush();   stream.Flush(true);      // ← fsync. 주석: "버퍼만 비우면 프로세스가 죽을 때 디스크에 안 남는다" ConnectionManager.cs:183   return GrantReplayLedger.TryConsume(grant.Jti, grant.ExpiresAtUnix, out reason);   // ApprovalCheck 안 = 서버 메인 스레드 ConnectionManager.cs:120   void ApprovalCheck(NetworkManager.ConnectionApprovalRequest request, ...) GrantReplayLedger.cs:176   const string DefaultPath = "/var/lib/festa-world/used-grants.log";   // 퍼시스턴트 볼륨 GrantReplayLedger.cs:245-259  Load() 는 File.ReadAllLines 로 전체를 읽는다(만료분은 메모리에서만 제외, 파일은 안 줄어든다) main.unity:48873  ClientConnectionBufferTimeout: 10   // 승인이 늦으면 10초 안에 실패로 끝난다

**제안 수정** — fail-closed 는 유지하되 fsync 를 승인 경로에서 뺀다. ① 파일 핸들을 한 번만 열어 두고(매 호출 FileStream 생성/파기도 비용이다) 승인 시에는 writer.Flush() 까지만, fsync 는 별도 스레드에서 200 ms 마다 묶어서 한다. 아직 fsync 되지 않은 jti 도 메모리 원장에는 이미 있으므로 프로세스가 사는 동안의 재사용 차단은 그대로다. 프로세스가 죽는 순간의 최대 손실은 200 ms 분량이고, 그 창에 걸린 grant 는 어차피 120초 TTL 안에서만 유효하다. ② 그래도 동기 보장이 필요하면 append-only 파일 대신 O_DSYNC 로 연 고정 크기 링버퍼를 검토한다. ③ Load 시 만료분을 제외한 내용으로 파일을 다시 쓴다(압축) — 지금은 메모리에서만 걸러서 파일이 무한히 자란다.


## 37. 다른 사람이 들어오면 기본 아바타로 한 번 떴다가 진짜 외형으로 바뀐다 — 조립도 그만큼 두 번 돈다

| | |
|---|---|
| 축 | 네트워킹·재접속 |
| 상태 | **검증통과** · 플레이 모드 필요 |
| 위치 | `festa-unity/Assets/_Project/Scripts/World/Avatar/PlayerAvatarVisual.cs:87` |
| 처리 | ✅ 수정 (2026-09-09) — 초기값이 기본 프리셋이면 1.5초 기다려 진짜 외형이 오면 그것만 조립, 안 오면 기본으로(게스트). 서버 초기화(제안 ①)는 건드리지 않았다 — 구 클라이언트 호환. 호스트 플레이 실측 `적용` 1회 |

**사용자가 겪는 일** — 사람이 들어올 때마다 잠깐 기본 캐릭터가 서 있다가 옷이 갈아입혀진다. 자기 자신도 월드에 들어간 직후 기본 아바타로 보였다 바뀐다 — '내가 로비에서 고른 게 반영이 안 됐나' 로 읽힌다. 그리고 앞의 조립 부하 항목이 정확히 두 배가 된다.

**내용** — GitLab #138 을 해결하면서 BE 가 grant 에서 avatarCode 클레임을 제거했다(WorldEntryTokenIssuer.java:79-86 — '외형은 여기 없고, 돌아와서도 안 된다'). 그 결과 grant.AvatarCode 가 항상 null 이고, 서버의 PlayerAppearanceController.OnNetworkSpawn 은 초기값을 DefaultPreset 으로 잡는다. 진짜 외형은 그 뒤 소유자가 RequestChangeServerRpc 로 올려야 도착한다. 모든 원격 클라이언트에서 Encoded.OnValueChanged 가 걸려 Rebuild 가 한 번 더 돈다(PlayerAvatarVisual.cs:87). 즉 사람 1명 입장 = 다른 사람 전원의 화면에서 아바타 조립 2회. 40명이면 조립 80회다. 게다가 그 사이 사용자는 남의 기본 아바타를 보게 되고, 자기 자신도 기본 아바타로 한 번 떴다 바뀐다. 소유자 쪽 handoff 는 실패하면 0.35초 간격으로 8초까지 재시도하므로(PlayerAppearanceController.cs:32-33, 152-170), 서버 왕복이 느린 실배포에서는 기본 아바타로 보이는 시간이 더 길어진다.

**근거** — backend/.../WorldEntryTokenIssuer.java:56-68  claims 빌더에 avatarCode 없음 (role/playerId/nickname/sessionId/worldId/channelId 뿐) backend/.../WorldEntryTokenIssuer.java:79-86  "It used to ride along as an avatarCode claim ... The game server already receives the appearance over the RPC that follows spawn" PlayerAppearanceController.cs:52-58  initial = SessionDataStore.Get(...)?.avatarCode (=null) → _player.AvatarCode.Value (=DefaultPreset) → Encoded.Value = DefaultPreset PlayerAvatarVisual.cs:75   OnNetworkSpawn → Rebuild(기본 외형)      ← 1회차 조립 PlayerAvatarVisual.cs:87   OnEncodedChanged → Rebuild(진짜 외형)    ← 2회차 조립 (모든 클라이언트에서) PlayerAppearanceController.cs:31-33  ServerApplyTimeout=2s / SceneHandoffRetryInterval=0.35s / SceneHandoffRetryDeadline=8s docs/KHS/28_최적화_기법_정리.md:294  조립 2.4 ms/기 → 2회면 4.8 ms/기

**제안 수정** — 기본 외형으로 한 번 조립하는 것을 아예 건너뛴다. ① 서버가 Encoded 초기값을 DefaultPreset 으로 채우지 말고 비워 둔다(PlayerAppearanceController.cs:46-59 의 초기화 제거). Rebuild 는 이미 `if (string.IsNullOrEmpty(encoded)) return;`(PlayerAvatarVisual.cs:105) 라 아무것도 안 만든다. ② 소유자의 첫 RPC 가 도착해 Encoded 가 채워질 때 딱 한 번 조립된다. ③ 그 사이의 빈 자리는 이름표(PlayerNameplate 는 Encoded 와 무관하게 이미 뜬다)와 저비용 실루엣으로 채운다 — 앞의 조립 큐 항목과 같은 임시 표현을 쓰면 된다. 이러면 조립 횟수가 절반이 되고 '옷 갈아입기' 도 사라진다.


## 38. '월드 연결이 끊어졌습니다' 의 유일한 버튼이 '로비로 돌아가기' 인데 실제로는 게임 전체를 처음부터 다시 받는다

| | |
|---|---|
| 축 | 네트워킹·재접속 |
| 상태 | **검증통과** |
| 위치 | `festa-frontend/src/unity/host/UnityHost.tsx:217` |
| 처리 | ➡ FE 소관 |

**사용자가 겪는 일** — 접속이 끊겨 당황한 사용자가 '로비로 돌아가기'(가벼워 보이는 선택지)를 누르면, 로비가 아니라 로딩 화면이 처음부터 다시 뜬다. 로비로 가고 싶었던 사람은 원치 않는 전체 재로딩을 당하고, 사실상 새로고침과 같은데 라벨만 다르다.

**내용** — UnityHost 의 실패 오버레이 버튼은 handleRetry → setAttempt(n+1) 이고, 그 attempt 변경이 boot 이펙트를 다시 돌려 restartUnitySession 으로 Unity 인스턴스를 통째로 새로 만든다. 로비 씬으로 돌아가는 것이 아니다. 로컬 릴리스 빌드 기준 .wasm 116 MB + .data 203 MB 를 다시 초기화해야 하고, 브라우저 캐시가 살아 있어도 wasm 컴파일과 씬 로드는 다시 한다. 같은 컴포넌트 안에서 boot 실패 오버레이의 버튼은 '다시 시도' 라고 정직하게 쓰여 있는데(:242), 접속 실패 쪽만 '로비로 돌아가기' 라는 다른 약속을 한다.

**근거** — festa-frontend/src/unity/host/UnityHost.tsx:212-220   {connection?.state === 'failed' && ( ... <button onClick={handleRetry}>로비로 돌아가기</button> )} festa-frontend/src/unity/host/UnityHost.tsx:187-189  function handleRetry() { setAttempt((n) => n + 1); } festa-frontend/src/unity/host/UnityHost.tsx:95   const start = attempt === 0 ? acquireUnitySession : restartUnitySession; festa-frontend/src/unity/host/UnityHost.tsx:241-247  boot 실패 쪽 같은 handleRetry 의 버튼 라벨은 '다시 시도' festa-unity/Builds/web/Build/  5d73dd74...wasm 116,130,813 B / 9a557721...data 203,112,088 B (2026-09-08 10:21)

**제안 수정** — 둘 중 하나로 정직하게 맞춘다. ① 라벨을 동작에 맞춘다 — '처음부터 다시 불러오기'. ② 라벨을 지키려면 동작을 바꾼다 — Unity 인스턴스는 살려 둔 채 SendMessage 로 Unity 쪽에 로비 복귀를 시키고(WorldReconnector.MarkUserInitiatedShutdown → Shutdown → LoadScene(CharacterLobby), 지금 DevConnectionHud.ReturnToCustomization 이 하는 일과 같다) FE 는 connection 상태만 초기화한다. 이러면 100 MB 재로딩이 없다. 어느 쪽이든 실패 오버레이에 '다시 접속 시도' 를 하나 더 두는 편이 낫다 — 지금은 서버가 돌아와도 재시도할 방법이 전체 재부팅뿐이다.


## 39. 서버 원장이 고장 나면 모든 사람에게 '로그인이 만료되었습니다. 다시 로그인해 주세요' 라고 거짓 안내한다

| | |
|---|---|
| 축 | 네트워킹·재접속 |
| 상태 | **검증통과** |
| 위치 | `festa-unity/Assets/_Project/Scripts/Network/Connection/WorldReconnector.cs:107` |
| 처리 | ⏸ 보류 — 서버 사유(`ENTRY_UNAVAILABLE`)·클라 즉시 실패·FE 문구 세 곳 동시 변경. 사용자 테스트 뒤 한 MR 로 |

**사용자가 겪는 일** — 서버 측 문제인데 사용자는 자기 계정 문제로 안내받는다. 시키는 대로 로그아웃하고 다시 로그인해도 똑같이 실패하고, 그때는 안내 문구조차 같아서 무엇이 잘못됐는지 알 길이 없다. 운영 쪽에서도 '로그인이 안 된다' 는 신고만 들어와 원장 고장을 못 찾는다.

**내용** — GrantReplayLedger 는 원장 파일을 못 쓰면 fail-closed 로 모든 입장을 거부한다(:191-196). 그 거부는 ApprovalCheck 에서 INVALID_TOKEN 하나로 뭉뚱그려 나간다 — 사유를 세분해서 알려주면 위조를 돕는다는 판단이라 서버 쪽은 옳다. 문제는 클라이언트다. WorldReconnector 가 즉시 실패로 빼는 사유는 SERVER_FULL 과 REPLACED_BY_SAME_USER 둘뿐이라(:107-113) INVALID_TOKEN 은 5회 재시도 대상이고, 매 회차마다 새 grant 를 발급받아 태운다. 5회를 다 쓴 뒤 FE 는 INVALID_TOKEN 을 '로그인이 만료되었습니다. 다시 로그인해 주세요' 로 번역한다(UnityHost.tsx:39). 원장 고장·서명키 불일치·시계 어긋남 어느 경우에도 재로그인은 아무 효과가 없다.

**근거** — GrantReplayLedger.cs:191-196  if (s_unusable) { reason = "원장을 사용할 수 없다 (fail-closed)"; return false; } GrantReplayLedger.cs:220  Debug.LogError($"[ReplayLedger] 원장을 쓸 수 없어 모든 입장이 거부된다 — {s_path}"); ConnectionManager.cs:150-155  ValidateToken 실패 → Deny(response, ReasonInvalidToken)   // 모든 원인이 INVALID_TOKEN 하나로 WorldReconnector.cs:107-113  ServerFull 과 ReplacedBySameUser 만 즉시 failed. InvalidToken 은 5회 재시도 WorldReconnector.cs:157  매 회차 CreateWorldSessionAsync() — 실패할 grant 를 5개 발급해 태운다 festa-frontend/src/unity/host/UnityHost.tsx:39  INVALID_TOKEN: '로그인이 만료되었습니다. 다시 로그인해 주세요.'

**제안 수정** — ① 서버가 '이 서버는 지금 아무도 못 받는다' 를 구분되는 사유로 내보낸다 — 위조에 도움이 되지 않는 정보다. 예: WorldDisconnectReason.EntryUnavailable = "ENTRY_UNAVAILABLE" 를 추가하고, GrantReplayLedger 가 s_unusable 일 때만 그 사유로 Deny 한다(서명·만료·재사용은 지금처럼 INVALID_TOKEN 유지). ② WorldReconnector 의 즉시-실패 목록에 ENTRY_UNAVAILABLE 을 넣는다 — 재시도해도 결과가 같으므로. ③ FE 문구를 '월드 서버가 입장을 받지 못하는 상태입니다. 운영자에게 문의해 주세요.' 로 추가한다. ④ 서버 기동 시 GrantReplayLedger.Warmup 의 LogError 가 헬스체크에 드러나도록 배선한다 — 지금은 로그에만 남는다.


## 40. 카메라 추적이 프레임률에 끌려간다 — 30fps 와 60fps 에서 조작감이 다르고, 프레임이 튀면 카메라가 순간이동한다

| | |
|---|---|
| 축 | 네트워킹·재접속 |
| 상태 | **반박됨** |
| 위치 | `festa-unity/Assets/_Project/Scripts/World/Player/PlayerCameraFollow.cs:193` |
| 처리 | — 반박됨, 조치 없음 |

**사용자가 겪는 일** — 같은 조작인데 프레임률이 흔들리면 카메라가 따라오는 느낌이 달라진다 — '무겁다/가볍다' 가 프레임마다 바뀌어 조준과 이동이 불안정하게 느껴진다. 프레임이 크게 튀는 순간(8 fps 이하 프레임)에는 카메라가 목표 위치로 순간이동해서, 프레임 하나 끊긴 것이 화면 전체가 튀는 것으로 증폭된다. vSyncCount 로 목표 60fps 를 고정한 지금은 덜 드러나지만, 사양이 낮은 관람객 노트북에서 30fps 로 떨어지면 그 사람만 조작감이 다르다.

**내용** — 조사 축 3(보간이 프레임률에 의존하는 곳)의 실물이다. PlayerCameraFollow 의 두 줄이 `Lerp(a, b, rate * Time.deltaTime)` 형태다. 이 형태는 dt 에 선형 비례하므로 프레임률이 바뀌면 감쇠 곡선이 바뀌고, dt 가 1/rate 를 넘으면 t 가 1 을 넘어 Lerp 가 클램프되어 목표로 순간이동한다. _followLerp = 8 이므로 프레임 시간 125 ms(8 fps) 이상에서 카메라가 스냅한다 — 지금 이 프로젝트가 쫓고 있는 출렁임 구간과 정확히 겹친다. 같은 저장소의 다른 감쇠들은 전부 프레임률 독립형(`1 - Exp(-rate * dt)`)으로 이미 고쳐져 있다 — AvatarLook.cs:88·96, WorldNameplate.cs:253, BalloonSway.cs:64. 이 두 줄만 안 고쳐졌다. 오늘 DisplayRefreshAdapter 로 목표 프레임률을 다루기 시작했으므로 지금 정리해야 할 자리다. (NGO 쪽 원격 보간도 같은 형태가 하나 있다 — BufferedLinearInterpolator.cs:532 `deltaTime / MaximumInterpolationTime`, 기본 0.1 이라 프레임 시간 100 ms 이상에서 원격 플레이어가 스냅한다. 패키지 코드라 우리가 고칠 것은 아니고, 프레임 시간을 100 ms 아래로 유지하는 것이 대응이다.)

**근거** — World/Player/PlayerCameraFollow.cs:20   [SerializeField] float _followLerp = 8f; World/Player/PlayerCameraFollow.cs:32   [SerializeField] float _collisionReturnLerp = 5f; World/Player/PlayerCameraFollow.cs:176  _resolvedDistance = Mathf.Lerp(_resolvedDistance, desiredDistance, _collisionReturnLerp * Time.deltaTime); World/Player/PlayerCameraFollow.cs:193  : Vector3.Lerp(_cam.transform.position, targetPos, _followLerp * Time.deltaTime); 같은 저장소의 올바른 형태(대조군):   World/Player/AvatarLook.cs:88   float follow = 1f - Mathf.Exp(-_followLerp * Time.deltaTime);   World/Player/AvatarLook.cs:96   _weight = Mathf.Lerp(_weight, wanted, 1f - Mathf.Exp(-_weightLerp * Time.deltaTime));   World/Interaction/WorldNameplate.cs:253  _smoothTopY = Mathf.Lerp(_smoothTopY, raw, 1f - Mathf.Exp(-TopFollowRate * Time.deltaTime));   World/Festival/BalloonSway.cs:64  _pushAmount = Mathf.Lerp(_pushAmount, target, 1f - 

**제안 수정** — 두 줄을 프레임률 독립형으로 바꾼다. 이 저장소가 이미 네 군데에서 쓰는 형태 그대로다.   :176  _resolvedDistance = Mathf.Lerp(_resolvedDistance, desiredDistance, 1f - Mathf.Exp(-_collisionReturnLerp * Time.deltaTime));   :193  : Vector3.Lerp(_cam.transform.position, targetPos, 1f - Mathf.Exp(-_followLerp * Time.deltaTime)); 계수 8·5 의 의미가 '초당 감쇠율' 로 바뀌므로 체감이 살짝 달라진다 — 60fps 에서 지금과 같은 느낌을 내려면 _followLerp 를 약 8.7 로 두면 된다(1-exp(-8.7/60) ≈ 0.135 ≈ 8*1/60). 카메라 충돌 즉시-당김 로직(:174-175)은 건드리지 않는다, T-190 의 이유가 그대로 유효하다.


## 41. Debug.Log 로 낸 관측치는 터미널 캡처에 한 줄도 안 잡힌다 — Vite 는 warn/error 만 전달한다

| | |
|---|---|
| 축 | 릴리스 관측성 |
| 상태 | **검증통과** |
| 위치 | `festa-frontend/fe-dev.log` |
| 처리 | ➡ FE 소관(Vite 로그 전달). 게임 관측 줄은 브라우저 콘솔에서 읽는다 |

**사용자가 겪는 일** — 직접적인 사용자 증상은 없다. 다만 이 사실을 모르고 릴리스 관측을 `Debug.Log` 로 붙이면, 빌드를 35분 들여 뽑아 놓고 "로그가 안 나온다" 로 하루를 버린다. 그리고 사용자에게 "F12 를 눌러 콘솔을 복사해 주세요" 라고 부탁하게 된다 — 그건 관측이 아니다.

**내용** — 관측이 있다: Vite dev 서버가 브라우저 콘솔을 WebSocket 으로 받아 터미널에 찍는다. 실제로 그렇다 — `festa-frontend/fe-dev.log` 에 `[vite] (client) [console.warn] [Disconnect] 서버가 사유를 보내지 않았다…` 같은 **Unity 발** 줄이 그대로 들어 있다. 그런데 전달되는 등급은 `console.warn` 과 `console.error` **뿐이다**. 633줄을 등급별로 세면 warn 465 + error 168 이고 `console.log` 는 **0건**이다. 결과: `Debug.Log` → `console.log` 로 나가는 것들 — `[ApiServices] Init`, `[WorldLoadTimeline]` 단계·요약, `[WorldEntryGate] 개방`, `[PublishedLayoutLoader]` 요약, `[DisplayRefreshAdapter]` 2줄 — 은 fe-dev.log 에 **한 줄도 없다.** 지금 우리가 릴리스 판정에 쓰기로 한 신호들이 전부 자동 캡처 밖에 있다는 뜻이다. 사람이 DevTools 를 열고 있어야만 보인다. 비용 쪽 영향도 있다: 개발 중에는 warn/error 한 줄마다 (스택트레이스 + JSON 직렬화 + WebSocket 전송)이 추가로 붙는다. 위 F2 의 231줄 반복은 개발 중 이 경로까지 함께 탄다.

**근거** — `sed 's/\x1b\[[0-9;]*m//g' fe-dev.log | grep -o 'console\.[a-z]*' | sort | uniq -c` → `168 console.error` / `465 console.warn` (console.log 0건). fe-dev.log 실제 줄: `오후 6:37:11 [vite] (client) [console.warn] [WorldReconnector] 접속 끊김 (사유 '') — 2초 뒤 재접속 시도` — Unity 의 WorldReconnector.cs:131 `Debug.LogWarning` 이 그대로 터미널에 도달했다. festa-frontend/package.json:37 `"vite": "^8.2.0"`. festa-frontend/vite.config.ts 에 콘솔 후킹 플러그인 없음 — Vite 8 기본 동작이다.

**제안 수정** — 릴리스 관측 비콘은 `Debug.Log` 를 전송 수단으로 쓰지 않는다(F9 의 브리지를 쓴다). 브리지 수신부가 없는 환경(probe·단독 실행)의 폴백만 `console.warn` 으로 둔다 — jslib 의 기존 폴백이 이미 전부 `console.warn`/`console.error` 다(FestaUnityBridge.jslib:41,56,76,88). 이 선택은 우연이 아니라 이 관측 때문에 옳다.


## 42. 릴리스 산출물에는 probe.html 이 없다 — 릴리스 검증 절차 자체가 성립하지 않는다

| | |
|---|---|
| 축 | 릴리스 관측성 |
| 상태 | **반박됨** |
| 위치 | `festa-unity/Assets/_Project/Scripts/Editor/CiBuild.cs:135` |
| 처리 | ✅ 수정 (2026-09-09) — `Tools/probe.html` 을 정본으로 추적하고 `CiBuild.BuildWeb` 이 산출물에 동봉(배포 zip 은 manifest 4종만 담으므로 실사용자에게 나가지 않음) |

**사용자가 겪는 일** — 릴리스에서만 나는 결함(T-237 의 Prod 고정, WSS 포트, 셰이더 첫 컴파일)을 최소 조건에서 재현할 수 없다. 사용자가 겪는 문제를 확인하려고 스택 전체를 세우다 보니 원인이 Unity 인지 FE 인지 서버인지 가르는 데 하루가 간다.

**내용** — 팀의 WebGL 검증 방식은 `probe.html` 로 빌드를 열고 `window.unityInstance` 에 SendMessage 를 쏘는 것이다. 그 파일은 `Builds/web`(개발 빌드)에만 있다. `Builds/web-release` 에는 `index.html`·`manifest.json`·`Build`·`TemplateData` 뿐이다. CiBuild.BuildWeb 도 만들지 않는다 — 산출물 검사 목록이 `index.html`·`Build`·`TemplateData`·`manifest.json` 넷뿐이다(CiBuild.cs:135-141). 그래서 릴리스 빌드를 검증하려면 FE 전체(Vite + Spring + 세션 발급 + 로그인)를 띄워야만 Unity 를 열 수 있다. 변수를 줄일 수 없으니 릴리스에서만 나는 문제를 격리할 방법이 없다. 그리고 F4 의 `printErr` 훅도 probe 에 넣으면 FE 없이 바로 쓸 수 있다.

**근거** — `ls Builds/web` → `bgm-probe.html  probe-bare.html  probe.html` 존재. `ls Builds/web-release` → `Build  TemplateData  index.html  manifest.json` (probe 없음). festa-unity/Assets/_Project/Scripts/Editor/CiBuild.cs:135-141 — 필수 산출물 검사 목록에 probe 계열 없음. Builds/web/probe.html — `window.FestaUnity` 4종 수신부를 console.log 로 받고 `window.unityInstance` 를 노출한다.

**제안 수정** — `FestaWebBuilder.WriteManifest` 와 같은 자리에서 probe.html 도 릴리스 출력 폴더에 쓴다(CiBuild.cs:134 `FestaWebBuilder.WriteManifest(WebOutDir);` 바로 다음). 내용은 Builds/web/probe.html 을 그대로 쓰되 **`printErr` 와 `errorHandler` 를 추가**한다 — 릴리스 probe 의 존재 이유가 "릴리스에서만 나는 것" 이므로 abort 를 잡을 수 있어야 한다. 필수 산출물 검사 목록에도 넣어 다음부터 빠지지 않게 한다.


## 43. 셰이더 프리웜이 아예 없다 — 새 구역에 처음 들어갈 때 끊기는데 그게 셰이더인지 알 방법도 없다

| | |
|---|---|
| 축 | 릴리스 관측성 |
| 상태 | **반박됨** |
| 위치 | `festa-unity/ProjectSettings/GraphicsSettings.asset:43` |
| 처리 | ⏸ 프리웜 철회 (2026-09-09) — 프리로드(T-245)·분산 워밍업(T-246) 모두 WebGL 에서 변형당 초 단위라 진입을 1~4분 굳혔다. 관측은 `[Festa/프레임] 힛치·위치` 줄로만 |

**사용자가 겪는 일** — 엘리베이터에서 내려 축제장을 처음 볼 때, 복도를 돌아 새 구역이 시야에 들어올 때, 처음 보는 아바타가 나타날 때 한 번씩 툭 끊긴다. 같은 자리를 두 번째 지날 때는 멀쩡하다 — 그래서 사용자는 "가끔 그런다" 고만 말하고 재현 조건을 못 준다.

**내용** — 브랜치 이름이 `perf/S15P21A604-493-shader-prewarm` 인데 프리웜 구현이 저장소에 **하나도 없다**. `GraphicsSettings.asset:43 m_PreloadedShaders: []`, `.shadervariants` 에셋 0개, `Shader.WarmupAllShaders`·`ShaderVariantCollection` 호출 0건. 그래서 각 셰이더 변형은 그 머티리얼이 화면에 **처음 보이는 프레임**에 컴파일된다. WebGL 에서 이건 메인 스레드 정지다. 그리고 릴리스에는 이걸 관측할 수단이 없다 — 셰이더 컴파일을 직접 세는 API 는 없고, 간접 지표(드로우콜 급증)는 F7 때문에 릴리스에서 못 읽는다. 다행히 규모는 작다. 씬 실측으로 렌더러 1,814개 / 고유 머티리얼 125개 / 고유 셰이더 8종이다(URP Lit 이 1,928 슬롯으로 압도적). 아바타 카탈로그와 부스 런타임 생성분은 여기 안 잡히므로 실제 변형 수는 더 많지만, 125개 머티리얼을 대상으로 한 프리웜은 초 단위로 끝날 규모다.

**근거** — festa-unity/ProjectSettings/GraphicsSettings.asset:43 `m_PreloadedShaders: []`. `find Assets -name "*.shadervariants"` → 0건. `grep -rln "WarmupAllShaders\|ShaderVariantCollection" Assets --include=*.cs` → 0건. MCP execute_code 출력: "씬 렌더러 1814개 / 고유 머티리얼 125개 / 고유 셰이더 8종 — 1928 Universal Render Pipeline/Lit, 125 URP/Unlit, 45 Mobile/Particles/Additive, 24 Festa/WorldText, 20 Mobile/Particles/Alpha Blended, 18 TextMeshPro/Mobile/Distance Field, 2 Particles/Standard Unlit, 1 URP/Particles/Unlit".

**제안 수정** — 두 단계로 나눈다. ① **측정 먼저**: 입장 게이트 워밍업 구간(WorldEntryGate.cs:311 이 `워밍업 N/WarmupFrames` 를 이미 세고 있다)에서 `Shader.WarmupAllShaders()` 를 Stopwatch 로 감싸 소요 ms 를 비콘으로 낸다. 이 한 줄이 "끊김의 몇 %가 셰이더인가" 를 처음으로 답한다. ② 수치가 유의미하면 ShaderVariantCollection 을 씬 머티리얼 125개 + 아바타 카탈로그 + 부스 프리팹 기준으로 만들어 `m_PreloadedShaders` 에 넣는다. **주의**: 프리웜은 로딩 시간을 늘린다 — 지금 입장이 이미 50~84초다(WorldLoadTimeline.cs:10). 프리웜을 넣으면 그 시간이 어디로 갔는지 WorldLoadTimeline 에 단계를 하나 추가해 보여야 한다.


## 44. 드로우콜은 릴리스에서 못 잰다 — 관측 후보에서 빼고, 대신 비콘이 스스로 신고하게 한다

| | |
|---|---|
| 축 | 릴리스 관측성 |
| 상태 | **검증통과** |
| 위치 | `festa-unity/Assets/_Project/Scripts/Diagnostics/HitchLogger.cs:88` |
| 처리 | ✅ 대체 — #6 보고 줄이 드로우콜 없이 힛치 수·카메라 위치를 스스로 신고한다 |

**사용자가 겪는 일** — 직접 증상은 없다. 그러나 이걸 모르고 릴리스 비콘에 드로우콜을 넣으면 35분 빌드 뒤에 전부 -1 만 찍혀 나오고, 그 빌드 한 벌이 통째로 낭비된다.

**내용** — 릴리스 관측 후보로 나온 것 중 드로우콜만 성격이 다르다. PerfHud·HitchLogger 는 `ProfilerRecorder.StartNew(ProfilerCategory.Render, "Draw Calls Count")` 로 읽는데, 이 카운터는 Unity 네이티브 프로파일러가 만드는 값이고 네이티브 프로파일러는 non-development 플레이어에서 컴파일 제외된다(`ENABLE_PROFILER`). 에디터에서는 `Valid=True` 로 확인되지만 그것은 에디터라서다. **릴리스에서 Valid 가 false 일 가능성이 높다 — 이건 확인 못 했다.** 반대로 릴리스에서 확실히 사는 것도 갈렸다. `System.GC.CollectionCount(0)` 과 `System.GC.GetTotalMemory(false)` 는 프로파일러 API 가 **아니라** .NET 런타임 API 라서 릴리스에서 그대로 동작한다. HitchLogger 가 이미 이 둘을 쓰고 있다(HitchLogger.cs:60-61). 즉 릴리스 최소 세트에서 GC 는 살리고 드로우콜은 뺀다. 드로우콜을 못 재면 대신 **드로우콜을 결정하는 입력**을 부팅 시 한 번 각인한다: 오클루전 컬링 on/off, 품질 레벨, 렌더 해상도(Screen × DPR), 부스 실렬 개수. 오늘 오클루전 재베이크로 축제중심 동향이 2,169→672 로 떨어진 것처럼, 드로우콜 회귀는 대개 '설정이 빠졌다' 로 나타나지 '조금 늘었다' 로 나타나지 않는다. 설정 각인만으로 그 부류는 전부 잡힌다.

**근거** — PerfHud.cs:139-141 · HitchLogger.cs:58-59 — `ProfilerRecorder.StartNew(ProfilerCategory.Render, "Draw Calls Count")`. HitchLogger.cs:60-61 — `_prevGc = System.GC.CollectionCount(0); _prevHeap = System.GC.GetTotalMemory(false);` (프로파일러 API 아님). HitchLogger.cs:88-89 — `long draws = _draws.Valid ? _draws.LastValue : -1;` — 코드가 이미 Valid 가 false 일 수 있다는 전제로 쓰여 있다. MCP execute_code 출력: 에디터에서 "Draw Calls Count Valid=True", "GC.CollectionCount(0)=246", "GC.GetTotalMemory(false)=983 MB". Unity 공식 ScriptReference(Unity.Profiling.ProfilerRecorder)에는 릴리스 플레이어 제약 문구가 없다 — 문서로는 결판나지 않는다.

**제안 수정** — 비콘의 부팅 1회 '조건 각인' 줄에 `profilerRecorderValid=true/false` 를 **넣는다**. 릴리스 빌드를 처음 열자마자 이 한 글자가 결판을 낸다 — 추측을 코드에 남기지 않고 빌드가 답하게 한다. false 면 드로우콜 칸을 조용히 비우고(-1 로 드러내고, 0 으로 속이지 않는다) 설정 각인만 남긴다. true 면 그때 드로우콜을 창 통계에 추가한다. 어느 쪽이든 비콘 코드는 한 번만 쓴다.


## 45. 전송 경로 판단 — 콘솔이 아니라 window.FestaUnity 브리지로 낸다

| | |
|---|---|
| 축 | 릴리스 관측성 |
| 상태 | **반박됨** |
| 위치 | `festa-unity/Assets/Plugins/WebGL/FestaUnityBridge.jslib:104` |
| 처리 | — 반박됨(콘솔 유지), 조치 없음 |

**사용자가 겪는 일** — 이 판단이 틀리면 사용자에게 "F12 를 눌러 콘솔 내용을 복사해서 보내 주세요" 라고 부탁하게 된다. 대부분은 그렇게 하지 않고, 그냥 접속을 그만둔다.

**내용** — 질문: 릴리스 관측을 브라우저 콘솔로만 낼 것인가, `window.FestaUnity` 브리지로 FE 에 넘길 것인가. **브리지다.** 근거 넷. ① 콘솔 `console.log` 는 자동 수집 밖이다 — F3 에서 실측으로 확인했다(fe-dev.log 633줄에 console.log 0건). 그렇다고 `Debug.LogWarning` 으로 격상하면 진짜 경고와 섞여 warn 채널이 못 쓰게 된다(이미 465줄이 차 있다). ② 브리지는 이미 5개가 돌고 패턴이 확립돼 있다 — `FestaHostHasUi`·`FestaHostApiBaseUrl`·`FestaNotifyBoothInteract`·`FestaNotifyWorldGateReady`·`FestaNotifyWorldConnectionState`·`FestaNotifyWorldLoadStart`(FestaUnityBridge.jslib:4-92). 새 규격을 만들지 않는다. 수신부가 없으면 `console.warn` 한 줄로 떨어지는 폴백까지 같은 패턴에 이미 들어 있다(jslib:41,56,76,88). ③ **수집 정책을 Unity 재빌드 없이 바꿀 수 있다.** 빌드가 35분이다(docs/KHS/24_작업일지.md:54 — CiBuild 경로 31.7분). Unity 는 값만 밀고, 화면에 띄울지·콘솔로 되쏠지·BE 로 POST 할지는 FE 가 정하면 FE 배포(초 단위)만으로 바뀐다. 이 비대칭이 결정적이다. ④ 비용이 콘솔보다 **싸다.** F2 대로 지금 `Debug.Log` 한 줄은 ScriptOnly 스택을 뜬다. 브리지 호출은 `[DllImport("__Internal")]` 직행이라 Unity 로거를 아예 타지 않는다 — 스택트레이스도, LogHandler 체인도, 콘솔 포맷팅도 없다. 규격 제안 — 함수 **하나만** 늘린다: `FestaNotifyDiag(kindPtr, jsonPtr)` → `window.FestaUnity.onDiag(kind, json)`. `kind` 는 `'boot'|'frame'|'stage'` 셋. JSON 은 10초에 200바이트 한 개. FE 쪽은 `events.ts:100-138` 의 기존 등록 패턴에 `onDiag` 를 하나 더 붙이고(계약 밖 kind 는 무시하고 로그로 드러낸다 — 지금 `onWorldConnectionState` 가 하는 그대로, events.ts:126-128), 개발 중에는 받은 것을 `console.warn` 으로 되쏘아 Vite 캡처(F3)에 태운다. 같이 붙일 jslib 하나 더: `FestaHeapBytes()` → `HEAPU8.length`. wasm 힙 크기는 C# 에서 읽을 방법이 없는데(webGLMemoryGrowthMode 2, 상한 2048MB) OOM 접근을 미리 보려면 이 값이 필요하다. 비용은 프로퍼티 읽기 하나다.

**근거** — festa-unity/Assets/Plugins/WebGL/FestaUnityBridge.jslib:4-135 — 기존 브리지 6종, 전부 `window.FestaUnity.<cb>` 확인 후 없으면 `console.warn` 폴백. festa-frontend/src/unity/bridge/events.ts:88-138 — `declare global { interface Window { FestaUnity?: { onBoothInteract, onWorldGateReady, onWorldLoadStart, onWorldConnectionState } } }` 와 `initUnityBridge()` 등록부. fe-dev.log 등급 집계(F3) — console.log 0건. docs/KHS/24_작업일지.md:54 — "CiBuild.BuildWeb 경로로 돌렸다 … 31.7분". festa-unity/ProjectSettings/ProjectSettings.asset:817-822 — 힙 32MB 시작, 2048MB 상한, geometric 성장.

**제안 수정** — jslib 에 `FestaNotifyDiag(kindPtr, jsonPtr)` 와 `FestaHeapBytes()` 둘을 추가한다(기존 6종 바로 아래, 같은 try/catch + console.warn 폴백 형태). events.ts 의 `Window.FestaUnity` 타입에 `onDiag?: (kind: string, json: string) => void` 를 추가하고 `initUnityBridge()` 에 등록한다. FE 수신부는 처음엔 `console.warn('[unity-diag]', kind, json)` 한 줄이면 충분하다 — Vite 가 그걸 fe-dev.log 로 옮겨 준다. BE 전송은 나중에 FE 만 고쳐서 붙인다.


## 46. 오브젝트끼리 서로 파고들어도 아무도 막지 않는다 — 지금 서빙 중인 부스에서만 3쌍이 관통한다

| | |
|---|---|
| 축 | 부스스튜디오→런타임 |
| 상태 | **미검증** |
| 위치 | `backend/src/main/java/com/example/ssafesta/booth/LayoutValidator.java:189` |
| 처리 | ➡ BE 소관 — 게임 쪽은 `WarnIfOutsideRoom` 으로 방 밖만 드러낸다 |

**사용자가 겪는 일** — 방문객이 부스에 들어가면 채용 보드와 영상 화면이 서로 파고들어 있고, 진열장이 보드와 테이블을 뚫고 나와 있다. 오너는 스튜디오에서 아무 경고도 못 받았으므로 '유니티가 이상하다' 고 본다. 지금 로컬 스택에서 보이는 부스가 정확히 이 상태다.

**내용** — 서버 LayoutValidator 는 개수 상한(12)·objectId 중복·부스 영역 이탈(회전 반영 AABB)·rotationY 범위까지 검사하지만 오브젝트 간 교차 검사는 없다. FE geometry.ts 도 rotateAABB / worldAABB / isAreaOutOfBounds 세 함수뿐이라 겹침을 계산하지 않는다. 그래서 두 오브젝트를 겹쳐 놓으면 error 도 warning 도 없이 게시된다. 로컬 스택이 실제로 서빙하는 12개짜리 픽스처를 팩토리에 통과시켜 렌더러 AABB 로 재니 3쌍이 실제로 교차했다.

**근거** — backend/.../LayoutValidator.java — grep 'OVERLAP|intersect|겹|collide' → 0건 (규칙은 OBJECT_LIMIT·DUPLICATE_OBJECT_ID·POSITION_OUT_OF_BOUNDS·AREA_OUT_OF_BOUNDS·ROTATION_OUT_OF_RANGE 뿐) festa-frontend/src/entities/layout/geometry.ts — export 는 rotateAABB(8), worldAABB(31), isAreaOutOfBounds(42) 셋뿐 에디터 실측(Tools/mock-api/api/v1/booth-slots/5/layouts/published, 12개):   board-1(RECRUITMENT_BOARD) x -2.85~0.15  ↔ screen-1(VIDEO_SCREEN) x 0.00~2.70  : x 0.00~0.15 구간 관통   board-1 ↔ deco-1(DECORATION, -2.7/-2.7)  : 진열장이 채용 보드 안에 박혀 있다   table-1(FURNITURE) ↔ deco-2(DECORATION)  : 진열장 모서리가 테이블에 박혀 있다 세 쌍 모두 서버 AREA_OUT_OF_BOUNDS 는 통과한다(각자는 ±3 m 안)

**제안 수정** — 게시 시 warning 으로 충분하다(error 로 하면 벽면 패널을 겹쳐 붙이는 정상 연출까지 막는다). checkExtent 가 이미 회전 반영 world AABB 를 만드니 그 결과를 O(n²), n<=12 로 교차 비교해 OBJECT_OVERLAP warning 을 추가하면 된다. FE 는 같은 계산을 studio 캔버스에서 미리 보여 주면 오너가 게시 전에 고친다. 그리고 서빙 픽스처 3쌍은 지금 고쳐 두어야 '겹침이 정상' 으로 굳지 않는다.


## 47. AI 직원만 미리보기보다 42% 크게 나온다 — 스튜디오에서 짠 비례가 안 맞는다

| | |
|---|---|
| 축 | 부스스튜디오→런타임 |
| 상태 | **미검증** |
| 위치 | `backend/src/main/java/com/example/ssafesta/booth/LayoutObjectType.java:23` |
| 처리 | ➡ BE·FE 소관(계약 AABB). Unity 측 실제 크기는 #146 회신에 적었다 |

**사용자가 겪는 일** — 오너는 스튜디오에서 AI 직원을 설문 키오스크(0.93 m)보다 조금 큰 상자로 보고 위치를 잡는다. 월드에서는 사람 키(1.63 m)로 서 있어 뒤의 프로젝트 패널을 가리거나 옆 오브젝트와 붙어 보인다. 벽에 바짝 붙여 놓으면 어깨가 벽을 파고든다.

**내용** — 서버·FE 가 공유하는 타입별 실측 AABB 표에서 AI_AGENT 는 0.62 x 1.15 x 0.32 m 다. 실제 프리팹(Rukha93 인간 캐릭터)을 메시 바운즈로 재면 1.34 x 1.63 x 0.34 m 다. 나머지 9종은 계약값과 오차 0.00 m 로 정확히 일치하므로, AI_AGENT 표만 옛 플레이스홀더 시절 값에서 갱신되지 않은 것이다. 높이 1.63 m 는 포즈와 무관한 값이고, 폭 1.34 m 는 바인드 포즈(팔 벌림) 기준이라 실루엣은 그보다 좁다 — 다만 서버 영역 검사와 컬링 바운즈는 이 값을 쓴다. 서버는 ±0.31 로 검사하므로 x=±2.69 까지 허용하는데 실제 바운즈는 거기서 ±0.67 로 벽을 넘는다.

**근거** — backend/src/main/java/com/example/ssafesta/booth/LayoutObjectType.java:23 — AI_AGENT(true, new LocalBounds(-0.31, 0, -0.16, 0.31, 1.15, 0.16)) festa-frontend/src/entities/layout/objectTypes.ts:57 — 같은 값의 FE 사본 에디터 실측(프리팹 메시 바운즈, 루트 로컬, m):   AiAgent          (-0.67,0.00,-0.19)~(0.67,1.63,0.15)  | 계약 (-0.31,0,-0.16)~(0.31,1.15,0.16) | 초과 -x0.36 +x0.36 +y0.48   VideoScreen      초과 0.00 (전 축)   ProjectPanel     초과 0.00   SurveyKiosk      초과 0.00   RecruitmentBoard 초과 0.00   ConsultationDesk 초과 0.00   Laptop           초과 0.00   LikeVote         초과 0.00   Furniture        초과 0.00   Decoration       초과 0.00 서빙 픽스처 런타임 실측: ai-1 AABB y 최대 1.64 m (계약 1.15)

**제안 수정** — AI_AGENT 의 LocalBounds 를 프리팹 실측으로 갱신한다(계약서 contracts/layout-api.md §10-1 → LayoutObjectType.java → objectTypes.ts 세 곳). 폭은 애니메이션 실루엣 기준으로 정하는 게 맞다 — 바인드 포즈 ±0.67 을 그대로 쓰면 벽 앞에 세울 수 없게 되니, 플레이 모드에서 idle 재생 중 SkinnedMeshRenderer.bounds 를 재서 그 값으로 정할 것. 높이 1.63 은 그대로 반영.


## 48. 영상 화면·채용 게시판·좋아요 스탠드는 F 를 눌러도 아무 일이 없다 — 스튜디오는 연결을 요구한다

| | |
|---|---|
| 축 | 부스스튜디오→런타임 |
| 상태 | **미검증** |
| 위치 | `festa-unity/Assets/_Project/Scripts/Booth/Factory/BoothObjectFactory.cs:148` |
| 처리 | 🟡 부분 (2026-09-09) — 가구·장식(동작 없음)은 조준 대상에서 제외해 '전시물 · 준비 중' 이 뜨지 않게 했다. VIDEO_SCREEN·RECRUITMENT_BOARD·CONSULTATION_DESK·LIKE_VOTE 의 F 동작(React 화면)은 FE 계약이 필요한 별건 — 스튜디오 `linksConfigId` 정리는 #146 흐름에서 FE 와 |

**사용자가 겪는 일** — 오너가 홍보 영상을 등록하고 영상 화면을 배치한다. 방문객은 화면 앞에서 F 를 눌러도 아무 일이 없고, 마우스를 정확히 올려야 '영상 화면 · 준비 중' 이 뜬다. 좋아요 스탠드도 같다 — 부스를 응원할 방법이 없다. 채용 게시판도 마찬가지. 스튜디오는 이 셋 모두 '연결하세요' 라고 안내한다.

**내용** — BoothObjectFactory.AttachContentBehaviour 가 컴포넌트를 붙이는 타입은 AI_AGENT·VIDEO_SCREEN(비상호작용)·LAPTOP·PROJECT_PANEL·SURVEY_KIOSK 다섯뿐이다. VIDEO_SCREEN 에 붙는 VideoScreenPlaceholder 는 IBoothInteractable 을 구현하지 않아 Start 에서 로그 한 줄만 찍는다. RECRUITMENT_BOARD·CONSULTATION_DESK·LIKE_VOTE 에는 아무것도 안 붙는다. 그래서 AttachCommonInteraction 이 interactive=false 로 판정하고, 방문객에게는 조준했을 때만 '영상 화면 · 준비 중' 같은 문구가 뜬다. 반대편 FE 는 이 타입들에 configId 연결 UI 를 띄우고 VIDEO_SCREEN 은 미연결 시 경고까지 낸다 — 즉 '연결하라고 시켜 놓고 연결해도 안 되는' 조합이다. FURNITURE·DECORATION 도 같은 경로라 화분을 조준하면 '전시물 · 준비 중' 이 뜬다(영원히 준비 중일 물건이다).

**근거** — festa-unity/Assets/_Project/Scripts/Booth/Factory/BoothObjectFactory.cs:148-173 — switch 에 AiAgent/VideoScreen/Laptop/ProjectPanel/SurveyKiosk 만 festa-unity/Assets/_Project/Scripts/Content/Video/VideoScreenPlaceholder.cs:11 — `public class VideoScreenPlaceholder : MonoBehaviour` (IBoothInteractable 미구현) festa-unity/.../BoothObjectFactory.cs:134,143 — `bool interactive = GetComponentInChildren<IBoothInteractable>() != null;` → Configure(15f, false) festa-unity/.../Content/BoothInteractionInput.cs:289-295 — "영상 화면 · 준비 중" / "좋아요 투표 · 준비 중" / "채용 게시판 · 준비 중" / "전시물 · 준비 중" festa-frontend/src/entities/layout/objectTypes.ts:34,37,42 — VIDEO_SCREEN warnOnMissingConfig:true, RECRUITMENT_BOARD·LIKE_VOTE linksConfigId:true 에디터 실측(팩토리로 11타입 생성):   AI_AGENT AiNpcInteractable / PROJECT_PANEL ProjectPanelInteractable / SURVEY_KIOSK SurveyKioskInteractable / LAPTOP LaptopInteractable / GAME_PORTAL Gam

**제안 수정** — 릴리스 범위 안에서 셋을 다 만들 수 없다면, 최소한 스튜디오가 거짓 기대를 만들지 않게 objectTypes.ts 의 linksConfigId·warnOnMissingConfig 를 실제 구현 상태에 맞춰 내리고, 팔레트에 '준비 중' 배지를 붙여라. 만든다면 셋 다 이미 있는 패턴 그대로다 — IBoothInteractable 구현 + InteractionFocusCamera.FocusOn + BoothInteractBridge.SendXxxInteract 세 줄이고 화면은 React 가 연다(ProjectPanelInteractable.cs 가 그 최소 예시). 장식 2종은 _passive 대상에서 빼서 '전시물 · 준비 중' 이 안 뜨게 할 것.


## 49. 노트북 테이블·상담 데스크·진열장을 몸으로 통과한다

| | |
|---|---|
| 축 | 부스스튜디오→런타임 |
| 상태 | **미검증** |
| 위치 | `festa-unity/Assets/_Project/Prefabs/Booth/Laptop.prefab` |
| 처리 | ✅ 수정 (2026-09-09, 프리팹) — 루트 BoxCollider: Laptop 0.8×0.75×0.8(테이블 전체), ConsultationDesk 렌더러 바운즈 1.86×0.92×1.16, Decoration 0.6×1.6×0.6. 벤더 원본 무수정. F 사거리(표면 20u)는 다음 플레이 확인 |

**사용자가 겪는 일** — 방문객이 상담 데스크 뒤로 걸어 들어가고, 노트북 테이블을 몸으로 관통하고, 진열장 안을 통과해 지나간다. 부스가 '세트' 가 아니라 '스티커' 처럼 느껴진다.

**내용** — 프리팹 콜라이더가 실물의 일부만 덮는다. Laptop 은 두께 0.02 m 상판 한 장(y 0.73~0.75)만 있어 렌더러 부피의 3% 다 — 테이블 다리와 노트북 본체에는 충돌이 없다. ConsultationDesk 는 콜라이더가 z -0.16~+0.16 뿐인데 실물은 z -1.00~+0.16 이라 데스크 몸통 84% 가 비어 있다(부피 28%). Decoration 은 y 1.01~1.18 에 뜬 작은 상자 하나뿐이라 진열장 아래쪽 전체가 통과된다(부피 6%). 반대로 VideoScreen·ProjectPanel·RecruitmentBoard 는 프리팹에 콜라이더가 아예 없지만 BoothInteractionTarget.EnsureCollider 가 런타임에 렌더러 AABB 박스를 붙여 주므로 이쪽은 오히려 괜찮다.

**근거** — 에디터 실측(프리팹 루트 로컬, m — 콜라이더 포락 vs 렌더러 포락):   Laptop           콜라이더 (-0.40,0.73,-0.40)~(0.40,0.75,0.40) / 렌더러 (-0.40,0,-0.40)~(0.40,0.94,0.40)  = 부피 3%   ConsultationDesk 콜라이더 (-0.93,0.00,-0.16)~(0.93,0.92,0.16) / 렌더러 (-0.93,0,-1.00)~(0.93,0.92,0.16) = 부피 28%   Decoration       콜라이더 (-0.24,1.01,-0.24)~(0.14,1.18,0.28) / 렌더러 (-0.30,0,-0.30)~(0.30,1.61,0.30)  = 부피 6%   (비교) Furniture 100%, SurveyKiosk 99%, AiAgent 86%, LikeVote 67%   VideoScreen/ProjectPanel/RecruitmentBoard 는 프리팹 콜라이더 0개 → festa-unity/Assets/_Project/Scripts/Booth/Interaction/BoothInteractionTarget.cs:178-196 EnsureCollider 가 런타임에 생성

**제안 수정** — 프리팹 3종에 몸통 BoxCollider 를 하나씩 추가한다 — Laptop 은 테이블 전체(0.8 x 0.75 x 0.8, center y 0.375), ConsultationDesk 는 z -1.00~0.16 전체, Decoration 은 진열장 몸통 0.6 x 1.6 x 0.6. 벤더 원본을 고치지 말고 우리 프리팹 루트에 붙일 것. 붙인 뒤 BoothInteractionTarget 의 표면 거리 판정(20 unit ≈ 1.5 m)이 달라지므로 F 사거리를 한 번 다시 걸어 보고 확인해야 한다.


## 50. 에디터·Mock 으로 보는 부스는 실제 게시본과 다르다 — 서버가 절대 허용하지 않는 배치다

| | |
|---|---|
| 축 | 부스스튜디오→런타임 |
| 상태 | **미검증** |
| 위치 | `festa-unity/Tools/mock-api/booth-slot-layout.json:141` |
| 처리 | ✅ 수정 (2026-09-09) — 픽스처를 서버 규칙 안으로: 12개, x·z ±2.4 이내(HALF_WIDTH 3, 넓은 것은 안쪽 열), JSON·`MockBoothApiClient` 동일. FURNITURE·DECORATION 에 실제 assetCode(`FURN_CHAIR_01_BLUE`·`STRUCT_PANEL_01`). 뺀 것: CONSULT_DESK 별칭·HOLOGRAM·미연결 포털(상한 12) |

**사용자가 겪는 일** — 개발·QA 가 Mock 으로 부스를 확인하면 통과다. 실제 게시본으로 보면 다른 그림이 나온다. 특히 장식·가구 외형 선택이 무시되는 문제(높음 건)가 목 데이터로는 재현되지 않아 릴리스까지 살아남는다.

**내용** — MockBoothApiClient 와 Tools/mock-api/booth-slot-layout.json 은 오브젝트 15개(서버 상한 12), 좌표 x=±5.5·z=10 m(서버 한계 ±3 m)를 담고 있다. 앵커 스케일 13.26 기준으로 z=10 m 는 132.6 unit 인데 인테리어 방의 앞벽은 7.0 m = 92.8 unit 이다 — 게임기 2대는 벽 너머 3 m 지점에 서 있고, z=7 m 짜리 4개는 앞벽에 박혀 있다. 그래서 에디터에서 부스를 확인할 때 보이는 그림이 실제 방문객이 볼 그림과 다르다. 반대 방향의 구멍도 있다: 실제로 서빙되는 픽스처(api/v1/booth-slots/5)는 FURNITURE·DECORATION 에 assetCode 가 하나도 없고 configId 만 달려 있어(장식형은 FE 가 configId 를 보내지 않는다) assetCode 경로를 전혀 밟지 않는다 — 이 축의 가장 큰 결함(외형 선택 무시)이 지금까지 안 보인 이유가 이것이다.

**근거** — festa-unity/Tools/mock-api/booth-slot-layout.json:141-172 — future-1 z=10, portal-1 x=-5.5 z=10, portal-unlinked x=5.5 z=10 festa-unity/Assets/_Project/Scripts/Integration/Mock/MockBoothApiClient.cs:14-50 — 같은 15건이 문자열 상수로 박혀 있다 backend/.../LayoutValidator.java:31 MAX_OBJECTS = 12, :44 MAX_HORIZONTAL = 3 festa-unity/Assets/_Project/Scripts/Editor/FestaInteriorBuilder.cs:31 — RoomFrontZ = 7.0f * M (= 92.82 unit) 에디터 실측(목 15건을 팩토리에 통과): z=10 m → 월드 z=132.6 unit (앞벽 92.8 을 39.8 unit = 3 m 초과), z=7 m 4건은 정확히 앞벽선 서빙 픽스처 실측(api/v1/booth-slots/5, 12건): table-1 FURNITURE·deco-1/deco-2 DECORATION 전부 assetCode 없음, 대신 configId 10/11/12 (FE 는 장식형에 configId 를 보내지 않는다 — objectTypes.ts:43,44 linksConfigId:false)

**제안 수정** — 목 픽스처를 서버가 실제로 받아 줄 배치로 맞춘다 — 12개 이하, x·z ±3 m 이내, 회전 반영 AABB 도 부스 안. 그리고 FURNITURE·DECORATION 엔트리에 FE 가 실제로 보내는 assetCode(PLANT·SHELF 등)를 넣고 configId 는 빼라. 미지원 타입 회귀(HOLOGRAM)와 legacy 별칭 2종은 계약 회귀 목적이 분명하니 남기되, 좌표는 부스 안으로 옮길 것. MockBoothApiClient.cs 의 문자열 상수와 Tools/mock-api/*.json 두 곳이 같은 내용이므로 함께 고쳐야 한다.


## 51. 초점 모드 중에 씬이 바뀌면 이동·F 가 영원히 죽는다

| | |
|---|---|
| 축 | 상호작용·미니게임 |
| 상태 | **미검증** · 플레이 모드 필요 |
| 위치 | `festa-unity/Assets/_Project/Scripts/World/Interaction/InteractionFocusCamera.cs:108` |
| 처리 | ✅ 수정 (2026-09-09) — `OnDestroy` 에서 `_active` 면 `EndFocus()`(우리가 건 잠금만 되돌림) 뒤 `s_instance` 해제 |

**사용자가 겪는 일** — 부스 안에서 노트북/AI 를 들여다보던 중(초점 모드) 로비로 나갔다가 다시 월드로 들어오면, 화면은 정상인데 WASD 도 F 도 안 먹는다. 사용자는 '접속은 됐는데 캐릭터가 안 움직인다' 로 신고하고, 새로고침 말고는 복구 방법이 없다.

**내용** — InteractionFocusCamera 는 '씬을 고치지 않는다' 는 이유로 최초 사용 시 new GameObject 로 만들어지는데 DontDestroyOnLoad 를 하지 않는다. 그래서 Single 씬 로드에서 파괴되는데, OnDestroy 는 s_instance 만 null 로 만들고 EndFocus 를 부르지 않는다 — 즉 자기가 걸어 둔 InputBridge.SetLocked(true) 를 풀지 않는다. InputBridge 는 DontDestroyOnLoad 이고 IsLocked 는 static 이라 그 값은 다음 씬으로 그대로 넘어간다. 새 씬에는 그 잠금을 풀어 줄 주체가 없다(FE 는 screen 이 안 바뀌면 아무것도 안 보낸다). 결과는 이동·F·이모트가 전부 죽은 월드다. SlotMachineHud/TimerStopGameHud 는 OnDestroy 에서 스스로 푸는데(각각 Close/ReleaseLock) 초점 카메라만 안 푼다. 파괴 순서가 정해져 있지 않아 SlotMachineHud.OnDestroy → OnHudClosed → InteractionFocusCamera.Release() 가 이미 파괴된 인스턴스를 만나 no-op 이 되는 경로도 함께 열려 있다.

**근거** — festa-unity/.../World/Interaction/InteractionFocusCamera.cs:98-104 → `var go = new GameObject("@InteractionFocusCamera"); s_instance = go.AddComponent<InteractionFocusCamera>();` — DontDestroyOnLoad 호출 없음 festa-unity/.../World/Interaction/InteractionFocusCamera.cs:108 → `void OnDestroy() { if (s_instance == this) s_instance = null; }` — EndFocus 없음, 잠금 해제 없음 festa-unity/.../Integration/InputBridge.cs:30 → `public static bool IsLocked { get; private set; }`, :49 → `DontDestroyOnLoad(go);` 씬 전환 경로: festa-unity/.../Network/DevConnectionHud.cs:239 `SceneManager.LoadScene(AvatarSceneHandoff.LobbySceneName)`, .../World/Avatar/Lobby/CharacterLobbyController.cs:169·731 `SceneManager.LoadScene(WorldSceneName)` 비교: TimerStopGameHud.cs:49-54 는 OnDestroy 에서 ReleaseLock 을 부른다 — 같은 처리를 초점 카메라가 안 한다

**제안 수정** — OnDestroy 를 `void OnDestroy() { if (_active) EndFocus(); if (s_instance == this) s_instance = null; }` 로 바꾼다(EndFocus 는 _lockedByUs 만 되돌리므로 남의 잠금은 안 건드린다). 추가로 InputBridge 에 sceneLoaded 훅을 달아 Single 로드 시 '내부(Unity) 잠금' 슬롯을 0 으로 리셋하면 이 계열이 통째로 막힌다 — 호스트 잠금은 FE 가 인스턴스 재기동 때 다시 밀어 넣는다(UnityHost.tsx:160-164 의 instanceReady 의존이 그 역할).


## 52. 아케이드 두 대의 판정 표면이 13 cm 간격 — 조준 대상이 흔들리고 흔들릴 때마다 외곽선을 통째로 다시 만든다

| | |
|---|---|
| 축 | 상호작용·미니게임 |
| 상태 | **미검증** · 플레이 모드 필요 |
| 위치 | `festa-unity/Assets/_Project/Scripts/Content/BoothInteractionInput.cs:108` |
| 처리 | 🟡 부분 (2026-09-09) — 제안 ① 히스테리시스: 현재 대상이 사거리 안이면 새 후보가 3u(0.23 m) 이상 더 가까울 때만 교체(`SwitchMargin`). 레이어 마스크·배치 간격·외곽선 풀링(②~④)은 미착수 |

**사용자가 겪는 일** — 게임기 두 대 사이에 서면 어느 쪽이 선택됐는지 테두리가 두 기계 사이를 왔다 갔다 하고, 프롬프트 문구도 같이 떨린다. F 를 누르는 순간 어느 기계가 잡힐지 예측할 수 없다. 테두리가 튈 때마다 오브젝트 수십 개가 생멸하므로 그 구역에서 프레임도 함께 걸린다.

**내용** — 광장 아케이드 4대(게임기 2 + 슬롯 2)가 x=-905 한 줄에 z=105/161/175/195 로 서 있다. 게임기 두 대는 중심 간 14 unit(1.06 m)이고, 각 대의 루트 BoxCollider 는 로컬 0.73×1.97×0.92 에 스케일 13.26 → 월드 z 폭 12.2 unit 이므로 두 표면 사이가 약 1.8 unit(13 cm)다. 런타임에 붙는 BoothInteractionTarget 의 기본 사거리는 20 unit(1.5 m)이라, 어느 한 대 앞에 서면 두 대가 모두 사거리 안이고 표면 거리 차이는 수 unit 수준이다. 근접 자동 조준은 표면 최근접점 거리로 최솟값을 고르므로 몇 cm 만 움직여도 승자가 바뀐다. 게다가 마우스 조준이 근접보다 우선하므로, 레이캐스트가 프레임마다 게임기와 (플레이어 몸통·바닥 등) 다른 콜라이더 사이를 오가면 승자가 프레임 단위로 튄다. 승자가 바뀔 때마다 BoothInteractionTarget 은 이전 대상의 외곽선 오브젝트를 전부 Destroy 하고 새 대상의 렌더러 개수만큼 GameObject + SkinnedMesh/MeshRenderer 복제 + Material 인스턴스를 새로 만든다 — 아케이드 캐비닛은 자식 렌더러가 10개대라 한 번 튈 때마다 수십 개 오브젝트가 생멸한다.

**근거** — Unity 에디터 실측:   Arcade_03_Cabinet_01 pos=(-905,0,161) machineId=plaza-arcade-01   Arcade_04_Cabinet_02 pos=(-905,0,175) machineId=plaza-arcade-02   Arcade_04_Cabinet_02 <-> Arcade_03_Cabinet_01 = 14.0 unit (1.06 m)   Arcade_04_Cabinet_02 <-> Arcade_02_Slot_Machine_02 = 20.0 unit (1.51 m) festa-unity/Assets/_Project/Prefabs/World/ArcadeCabinet_Plaza.prefab:932-933 → 루트 BoxCollider `m_Size: {x: 0.73, y: 1.97, z: 0.92}` (인스턴스 스케일 13.26 → 월드 z 12.2 unit) festa-unity/.../Booth/Interaction/BoothInteractionTarget.cs:13 → `[SerializeField, Min(0.5f)] float _maxDistance = 20f;` (Arcade/Slot 은 Awake 에서 AddComponent 하므로 이 기본값이 그대로 쓰인다 — ArcadeMachineInteractable.cs:34-35) festa-unity/.../Content/BoothInteractionInput.cs:108-125 → NearestInteractableInRange 는 표면 최근접 거리 최솟값 하나만 고른다(히스테리시스 없음) festa-unity/.../Content/BoothInteractionInput.cs:76-89 → 마우스 조준이 근접보다 우선, 레이캐스트는 레이어 마스크 없이 첫 콜라이더 festa-unit

**제안 수정** — ① NearestInteractableInRange 에 히스테리시스를 넣는다 — 현재 대상이 사거리 안이면 새 후보가 현재보다 일정 마진(예: 3 unit) 이상 가까울 때만 교체한다. ② 마우스 조준 레이캐스트에 상호작용 레이어 마스크와 QueryTriggerInteraction.Ignore 를 준다(지금은 플레이어 몸통·포털 트리거까지 첫 히트로 잡는다). ③ 아케이드 캐비닛 배치 간격을 최소 2 m 로 벌리거나 캐비닛의 BoothInteractionTarget 사거리를 12~15 unit 로 낮춘다. ④ 외곽선 오브젝트를 대상마다 만들어 두고 SetActive 로만 껐다 켜면 생멸 비용이 사라진다.


## 53. 슬롯머신 잔액이 실제 코인처럼 오르내리다가, 나갔다 들어오면 원래대로 돌아간다

| | |
|---|---|
| 축 | 상호작용·미니게임 |
| 상태 | **미검증** |
| 위치 | `festa-unity/Assets/_Project/Scripts/Minigame/Slot/SlotMachineHud.cs:110` |
| 처리 | ✅ 수정 (2026-09-09) — 체험판이면 잔액 숫자를 `Muted` 회색으로, 설명을 "보유 코인 (가상 · 실제 잔액에 반영되지 않음)" 으로. 실제 판정이면 금색 그대로 |

**사용자가 겪는 일** — 체험판 슬롯머신에서 잭팟을 터뜨리면 잔액이 100 → 150 으로 오른다. Esc 로 나갔다 다시 들어오면 100 으로 돌아와 있다. 배지를 못 본 사용자는 '코인이 없어졌다' 고 신고한다.

**내용** — 체험판(Mock) 판정에서 SlotMachineSession 은 실제 지갑 잔액을 읽어 Mock 에 SeedBalance 로 심고, 이후 판마다 Mock 이 계산한 balanceAfter 를 그대로 표시 잔액으로 쓴다. HUD 는 그 숫자를 실제 잔액과 완전히 같은 자리·같은 글꼴(금색 큰 숫자)로 그린다. '체험판 · 코인 미반영' 칩이 붙긴 하지만, 잭팟이 나면 숫자가 +50 으로 뛰므로 배지보다 숫자가 훨씬 강하게 읽힌다. Esc 로 나갔다 다시 F 를 누르면 LoadBalance 가 실제 지갑을 다시 읽어 숫자가 통째로 원복된다. 사용자에게는 '딴 코인이 사라졌다' 로 보인다. 로컬 스택 실측 기준 지금 바로 발생하는 경로다(spins 404 → Mock, wallets/me 는 회원이면 200).

**근거** — festa-unity/.../Minigame/Slot/SlotMachineSession.cs:66-76 → 지갑 성공 시 `Balance = wallet.balance;` + `seedable.SeedBalance(wallet.balance);` festa-unity/.../Minigame/Slot/SlotMachineSession.cs:148-155 → `Simulated = result.simulated; ... Balance = result.balanceAfter;` (Mock 계산값) festa-unity/.../Integration/Mock/MockSlotMachineClient.cs:47-70 → `_balance -= bet; ... _balance += payout;` 후 balanceAfter 로 반환 festa-unity/.../Minigame/Slot/SlotMachineHud.cs:109-118 → 체험판 여부와 무관하게 `_balance.text = _session.Balance.Value.ToString("N0")` 로 같은 표시창에 그린다 로컬 Spring 실측: POST /api/v1/minigames/slot-machines/plaza-slot-01/spins -> 404 (게스트 토큰 첨부), GET /api/v1/wallets/me -> 403(게스트)/컨트롤러 존재

**제안 수정** — 체험판일 때는 잔액 숫자를 실제 잔액과 시각적으로 구분한다 — 예: Simulated 이면 숫자 옆에 '(가상)' 을 붙이고 색을 Muted 로 낮추거나, 실제 잔액은 고정으로 두고 '이번 판 결과: +50(가상)' 만 따로 보여 준다. 지금처럼 실제 잔액과 같은 자리에 같은 스타일로 그리면 배지가 이겨 낼 수 없다.


## 54. 초점 모드에서 나가는 방법이 Esc 키 하나뿐이고, 화면에는 그 안내조차 없다

| | |
|---|---|
| 축 | 상호작용·미니게임 |
| 상태 | **미검증** · 플레이 모드 필요 |
| 위치 | `festa-unity/Assets/_Project/Scripts/World/Interaction/InteractionFocusCamera.cs:184` |
| 처리 | ✅ 수정 (2026-09-09) — 초점 중 우상단 `[Esc] 나가기` 알약(IMGUI, 클릭·터치로도 해제). 캔버스 포커스 회복 요청·자동 해제 안전장치는 미착수 |

**사용자가 겪는 일** — 노트북을 들여다보다 웹 오버레이 바깥을 한 번 클릭한 뒤 Esc 를 누르면 아무 일도 안 일어난다. 화면은 노트북에 코를 박은 채 멈춰 있고 캐릭터는 안 움직인다. 태블릿에서는 처음부터 나갈 방법이 없다.

**내용** — 노트북·AI 직원·프로젝트 패널·설문 키오스크·관리 데스크·부스 게임기·광장 게임기는 F 를 누르면 InteractionFocusCamera 로 카메라를 당기고 입력을 잠그지만, Unity 쪽에 닫기 UI 가 하나도 없다. 유일한 탈출구는 InteractionFocusCamera.LateUpdate 의 Keyboard.current.escapeKey 다. 여기에 세 가지 실패 조건이 겹친다. ① Keyboard.current 가 null 인 기기(터치 전용 태블릿 — TryReadPointer 는 Touchscreen 경로를 이미 갖고 있어 터치 사용을 상정하고 있다)에서는 Esc 자체가 없다. ② WebGL 에서 captureAllKeyboardInput=false 이므로 캔버스가 focus 를 잃으면(React UI 를 한 번 클릭하면 잃는다) Esc 가 브라우저로 가고 Unity 에 안 들어온다. ③ 그 상태를 풀어 줄 수 있는 유일한 외부 주체는 FE 의 SetInputLocked('0') 인데, 그건 FE 오버레이가 열렸다 닫혀야 나온다 — 광장 게임기처럼 FE 수신부가 없는 대상은 열리는 오버레이 자체가 없다(결함 1). 반면 Unity 안에서 그리는 두 HUD(슬롯머신·타이밍 스톱)에는 ✕ 버튼과 'Esc 나가기' 문구가 둘 다 있다 — 초점 전용 대상만 비어 있다.

**근거** — festa-unity/.../World/Interaction/InteractionFocusCamera.cs:184-189 → `var kb = Keyboard.current; if (kb != null && kb.escapeKey.wasPressedThisFrame) { EndFocus(); return; }` — kb 가 null 이면 탈출 경로 없음 festa-unity/.../Integration/InputBridge.cs:42-44 → `WebGLInput.captureAllKeyboardInput = false;` (canvas focus 일 때만 키 입력) festa-unity/.../Content/BoothInteractionInput.cs:158-175 → TryReadPointer 가 Touchscreen 경로를 갖고 있다(터치 기기 상정) 비교: SlotMachineHud.cs:71 `FestaUiKit.CloseButton(...)` + :90 "Esc 나가기" 라벨, TimerStopGameHud.cs:77 CloseButton + :93 "Space 시작·정지 · Esc 나가기" Laptop/AiNpc/ProjectPanel/SurveyKiosk/ManagementDesk/GamePortal/ArcadeMachine 의 Interact() 에는 닫기 UI 생성 코드가 없다(각 파일 전문 확인)

**제안 수정** — 초점 모드가 켜지면 화면 구석에 공용 나가기 어포던스를 하나 띄운다 — InteractPromptUI.DrawKeycap 으로 '[Esc] 나가기' 알약을 그리고(IMGUI 라 씬 배선 불필요), 마우스/터치로도 눌리게 클릭 판정을 붙인다. 그러면 ①②③ 세 조건이 한 번에 막힌다. 겸해서 초점 진입 시 canvas.focus() 를 되찾도록 FE 에 요청하거나, 초점 시작 후 N 초 동안 입력이 하나도 안 들어오면 자동 해제하는 안전장치를 둔다.


## 55. '준비 중' 안내 알약이 미니게임 화면 위에 붙어 안 사라진다

| | |
|---|---|
| 축 | 상호작용·미니게임 |
| 상태 | **미검증** · 플레이 모드 필요 |
| 위치 | `festa-unity/Assets/_Project/Scripts/Content/BoothInteractionInput.cs:62` |
| 처리 | ✅ 수정 (2026-09-09) — 잠금 분기에서 `_passive = null` |

**사용자가 겪는 일** — 영상 화면이나 상담 데스크를 바라보다 F 로 다른 걸 열면, '영상 화면 · 준비 중' 알약이 미니게임 카드 한복판에 겹쳐 남는다. 사용자는 방금 연 화면이 '준비 중' 이라고 읽는다.

**내용** — BoothInteractionInput.Update 는 잠금 상태에 들어가면 UpdateHover(null)·ShowHint(null) 로 호버와 링만 끄고 곧바로 return 한다 — 수동 안내용 필드 _passive 는 건드리지 않는다. 반면 OnGUI 는 `if (_hovered != null) ... else if (_passive != null) DrawPassivePrompt(...)` 라, _hovered 가 null 이 된 순간 오히려 _passive 분기가 활성화된다. 즉 '영상 화면 · 준비 중' 같은 오브젝트를 조준한 채로 무언가가 입력을 잠그면(미니게임 HUD 열림, FE 오버레이 열림, 초점 진입), 그 알약이 화면 중앙에 붙박이로 남아 미니게임 카드 위에 겹친다. 잠금이 풀리고 다른 곳을 조준할 때까지 안 사라진다.

**근거** — festa-unity/.../Content/BoothInteractionInput.cs:62-67 → `if (InputBridge.IsLocked) { UpdateHover(null); ShowHint(null); return; }` — _passive 미처리 festa-unity/.../Content/BoothInteractionInput.cs:96-97 → _passive 는 잠금이 아닐 때만 갱신된다 festa-unity/.../Content/BoothInteractionInput.cs:299-305 → `if (_hovered != null) DrawPrompt(...); else if (_passive != null) DrawPassivePrompt(...);` (같은 함수의 주석 60-61행이 -437 을 근거로 '잠금 중에 프롬프트가 겹쳐 떠 있으면 눌러도 안 된다로 보인다' 며 _hovered 만 껐다 — _passive 는 -455 로 나중에 추가되면서 빠졌다)

**제안 수정** — 잠금 분기에 `_passive = null;` 한 줄을 추가한다. 필요하면 `_toast` 도 같이 판단한다 — 다만 토스트는 '보냈습니다' 안내라 잠금 직후에도 잠깐 보이는 편이 맞다.


## 56. 아바타 조립이 실패하면 몸이 통째로 사라지고 이름표만 공중에 뜬다

| | |
|---|---|
| 축 | 아바타·캐릭터 |
| 상태 | **검증통과** |
| 위치 | `festa-unity/Assets/_Project/Scripts/World/Avatar/CatalogAvatarVisualProvider.cs:64` |
| 처리 | ✅ 수정 (2026-09-09) — `CreateModular` 이 렌더러 0개면 root 를 파괴하고 null → placeholder 폴백이 실제로 발동. LogError 에 LastError 포함 |

**사용자가 겪는 일** — 월드에서 어떤 사람은 몸이 전혀 안 보이고 머리 위 이름표만 떠다닌다. 본인이 그 사람이면 3인칭 카메라 앞에 아무것도 없어서 자기 캐릭터를 조종하고 있는지조차 알 수 없다. 폴백 캡슐이라도 나오면 "뭔가 잘못됐다" 는 것이 보이는데, 지금은 그냥 없다.

**내용** — CatalogAvatarVisualProvider 의 클래스 주석은 "어느 쪽이든 실패하면 placeholder 로 폴백하고 월드는 계속 동작한다" 라고 적혀 있고, 37행에 `go ??= CreatePlaceholder(parent);` 가 있다. 그런데 모듈 조립 경로 CreateModular 는 **조립이 실패해도 항상 root GameObject 를 만들어 반환한다**(64행) — AvatarAssembler.Apply 가 신체 프리팹을 못 찾아 Fail() 하고 빠져나와도 반환값은 null 이 아니라 '자식이 하나도 없는 빈 오브젝트' 다. 그래서 `??=` 폴백이 절대 발동하지 않고, PlayerAvatarVisual 의 `if (_currentVisual == null) return;`(115행) 도 통과한다. 결과적으로 그릴 것이 0개인 상태로 FitVisualToWorld·AvatarAnimationLod.Register 까지 진행된다. assembler.LastError 는 Debug.LogError 로만 나가고(63행) 화면에는 아무 표시가 없다 — 릴리스 빌드에서는 콘솔도 없다.

**근거** — 에디터 실측(execute_code): 신체 프리팹을 비운 카탈로그 사본으로 CreateVisual 호출 → `CreateVisual 반환 = AvatarVisual_Modular`, `자식 렌더러 0개 / Animator 0개 / 자식 Transform 0개`, `Placeholder 캡슐 폴백이 걸렸는가 = False`. 코드: CatalogAvatarVisualProvider.cs:9 주석 "실패하면 placeholder 로 폴백", :37 `go ??= CreatePlaceholder(parent);`, :50-64 CreateModular 는 무조건 root 반환, :63 `if (!string.IsNullOrEmpty(assembler.LastError)) Debug.LogError(...)`. PlayerAvatarVisual.cs:115 `if (_currentVisual == null) return;`. AvatarAssembler.cs:454 `void Fail(string message) { LastError = message; Debug.LogError(...); }` — LastError 소비자는 저장소 전체에서 진단용 AvatarStressSpawner 하나뿐.

**제안 수정** — CreateModular 반환 직전에 `root.GetComponentsInChildren<Renderer>(true).Length == 0` 이면 root 를 파괴하고 null 을 돌려준다 — 그러면 37행의 placeholder 폴백이 실제로 작동한다. 그리고 assembler.LastError 를 PlayerAvatarVisual 이 읽어 로컬 플레이어면 화면에 드러낸다.


## 57. 특정 헤어를 쓴 사람은 조금만 멀어져도 자세가 얼어붙은 채 미끄러진다

| | |
|---|---|
| 축 | 아바타·캐릭터 |
| 상태 | **검증통과** |
| 위치 | `festa-unity/Assets/_Project/Scripts/World/Avatar/AvatarAnimationLod.cs:67` |
| 처리 | ✅ 수정 (2026-09-09) — `PickLiveProbe`: 켜진 첫 렌더러를 대표로 고르고, 꺼지면 그때그때 살아 있는 것으로 갈아탄다. 툴팁의 "1 m = 10 유닛" 도 13.26 으로 정정. 실동 확인은 빌드 2클라(체크리스트 R-7) |

**사용자가 겪는 일** — 축제장에서 16.6 m 넘게 떨어진 사람들 중 일부(헤어 01·04 착용자)가 걷는데 다리를 움직이지 않고 그대로 미끄러져 다닌다. 가까이 가면 갑자기 정상적으로 걷기 시작한다 — "저 사람 렉 걸렸나" 로 보이고, 실제로는 내 클라이언트가 그 사람 애니메이터를 한 번도 돌리지 않은 것이다.

**내용** — AvatarAnimationLod.Register 는 `Probe = skins[0]`(67행)로 가시성 대표 렌더러를 정하는데, skins 는 `GetComponentsInChildren<SkinnedMeshRenderer>(true)` — **비활성 렌더러까지 포함**한다. 그리고 중·원거리 밴드에서 `bool visible = e.Probe == null || e.Probe.isVisible;`(136행)가 false 면 Animator.Update 를 아예 부르지 않는다. 문제는 AvatarAssembler 가 메시 병합 때 원본 신체 렌더러들을 `p.enabled = false`(AvatarAssembler.cs:252)로 꺼 둔다는 것이다 — 꺼진 렌더러의 isVisible 은 항상 false 다. 카탈로그 아이템 단위 전수 검사 153조합 중 4조합에서 skins[0] 이 실제로 꺼진 렌더러였다(헤어 01·04, 남녀 각각). 이 헤어들은 스킨메시가 아니라 본 부착 프리팹이라 애니메이터 하위 첫 SkinnedMeshRenderer 자리가 병합으로 꺼진 `*_body_arms_lower` 로 밀린다. 근거리 밴드(0)는 Unity 의 cullingMode 에 맡기므로 영향이 없다 — 그래서 가까이 가면 멀쩡히 움직이고, 멀어지면 얼어붙는다.

**근거** — 에디터 실측(execute_code, 전수 스윕): `[F5] 아이템 단위 전수 153조합 중 skins[0](=LOD Probe) 이 disabled 인 경우: 4` — `Female/Hair/Shared_Hairstyle.01 → Probe=F_body_arms_lower`, `Female/Hair/Shared_Hairstyle.04 → Probe=F_body_arms_lower`, `Male/Hair/Shared_Hairstyle.01 → Probe=M_body_arms_lower`, `Male/Hair/Shared_Hairstyle.04 → Probe=M_body_arms_lower`. 코드: AvatarAnimationLod.cs:67 `Probe = skins.Length > 0 ? skins[0] : null`, :63 `var skins = animator.GetComponentsInChildren<SkinnedMeshRenderer>(true);`, :136 `bool visible = e.Probe == null || e.Probe.isVisible;`, :129 `if (e.Animator.enabled) e.Animator.enabled = false;`. AvatarAssembler.cs:252 `foreach (var p in parts) p.enabled = false;`. 밴드 경계 _nearDistance=220 유닛 = 16.6 m (1 m = 13.26 units).

**제안 수정** — Probe 를 `skins[0]` 이 아니라 **enabled 인 첫 렌더러**로 고르고, 조립·병합으로 enabled 가 바뀔 수 있으니 Probe 가 꺼져 있으면 그때그때 살아 있는 렌더러로 갈아탄다. 더 견고한 쪽은 렌더러 대신 아바타 루트의 bounds 를 카메라 프러스텀과 직접 비교하는 것이다. (참고: AvatarAnimationLod.cs:35 툴팁의 "1 m = 10 유닛" 은 틀렸다 — 확정값은 13.26 이라 실제 근거리 경계가 22 m 가 아니라 16.6 m 다.)


## 58. 아바타 저장이 실패해도 화면에는 성공한 것처럼 보인다

| | |
|---|---|
| 축 | 아바타·캐릭터 |
| 상태 | **검증통과** |
| 위치 | `festa-unity/Assets/_Project/Scripts/World/Avatar/PlayerAppearanceController.cs:210` |
| 처리 | ✅ #18 과 같은 수정 `4874eb7b` — 저장 실패 LogError + 토스트 |

**사용자가 겪는 일** — 옷을 갈아입고 '적용' 을 누르면 월드 아바타가 바로 바뀌니 저장된 줄 안다. 그런데 로그인 세션이 만료됐거나 서버가 그 파츠를 보유로 인정하지 않으면 저장은 실패했고, 다음 접속에 옛 외형(또는 기본 외형)이 뜬다. 실패했다는 사실을 알 방법이 화면에 하나도 없다.

**내용** — 프로필 저장은 PlayerAppearanceController.SaveToProfileAsync 하나로 나가는데, 실패 처리가 `if (!ok) Debug.LogWarning("[Appearance] 프로필 저장 실패 — 이번 세션에만 적용");`(210행) 뿐이다. LastRequestError 에도 기록하지 않아 HUD 가 읽을 값이 없고, 릴리스 WebGL 에는 콘솔이 없다. 반면 월드 내 '적용' 경로는 RequestChange 가 서버 NetworkVariable 반영을 확인해 즉시 외형을 바꾸므로 **화면상으로는 성공한 것처럼 보인다.** 실패 사유는 여러 갈래다 — 401(토큰 만료), 403(게스트), 409 AVATAR_ITEM_NOT_OWNED(보유하지 않은 파츠), 그리고 HttpUserApiClient.cs:76-82 의 echo 불일치(서버가 값을 정규화하면 왕복 무손실 계약 위반이라 false). 어느 쪽이든 사용자에게는 똑같이 '아무 일도 없음' 이다. 또 SaveToProfileAsync 는 `async void` 라 EnsureInitialized 단계에서 던진 예외는 어디에도 잡히지 않는다.

**근거** — PlayerAppearanceController.cs:206-211 `async void SaveToProfileAsync(string encoded) { ApiServices.EnsureInitialized(); bool ok = await ApiServices.User.UpdateMyAvatarAsync(encoded); if (!ok) Debug.LogWarning("[Appearance] 프로필 저장 실패 — 이번 세션에만 적용"); }` — LastRequestError 미기록. HttpUserApiClient.cs:76-82 echo 불일치 시 LogError 후 false. 저장 실패를 화면에 드러내는 코드는 `grep -rn "LastRequestError" Assets` 기준 HUD 표시부 외에 저장 경로 연결 없음.

**제안 수정** — SaveToProfileAsync 가 실패하면 LastRequestError 에 사유(401/403/409/echo 불일치)를 적고, 로컬 플레이어면 로비·월드 HUD 에 "외형이 계정에 저장되지 않았습니다 — <사유>" 를 띄운다. 401 이면 재로그인 유도, 409 면 어떤 파츠가 거부됐는지(서버가 ApiErrorDetail 로 assetKey 를 준다) 표시한다. async void 대신 Task 를 돌려 호출부가 결과를 알 수 있게 한다.


## 59. 저장해 둔 모자·안경이 말없이 사라지고, 상하의는 다른 옷으로 바뀌어 있다

| | |
|---|---|
| 축 | 아바타·캐릭터 |
| 상태 | **반박됨** |
| 위치 | `festa-unity/Assets/_Project/Scripts/World/Avatar/Lobby/CharacterLobbyController.cs:827` |
| 처리 | — 반박됨, 조치 없음 |

**사용자가 겪는 일** — 어제까지 쓰던 모자가 오늘 로그인하니 없어져 있다. 상의도 처음 보는 기본 옷으로 바뀌어 있다. 왜 바뀌었는지, 다시 쓸 수 있는지, 상점에서 사면 되는지 화면에 아무 설명이 없어서 "저장이 날아갔나" 로 읽힌다.

**내용** — SanitizeLocked 는 불러온 외형에 지금 기준으로 잠긴(=서버 owned 가 아닌) 파츠가 있으면 액세서리(모자·안경·한벌옷)는 0 으로 지우고 나머지는 카탈로그 기본값으로 바꾼다. 그런데 그 사실을 알리는 곳이 `Debug.LogWarning`(827행) 하나뿐이다 — SetStatus 호출이 없어 화면에는 아무 표시도 없다. 함수 주석 스스로 "조용히 바꾸지 않는다. 무엇이 왜 바뀌었는지 로그로 남긴다 — 기본값으로 슬쩍 되돌리는 것이 T-24 의 원인이었다" 라고 적어 두었는데, 릴리스 빌드에서 로그는 사용자에게 존재하지 않는다. 이 경로는 실제로 발생한다: 마이그레이션 V20__stop_selling_parts_missing_from_palette.sql 이 판매를 중단한 파츠들이 있고, 그것들은 카탈로그 응답에서 빠지므로 예전에 그 옷을 입고 저장한 사용자는 다음 로그인에 조용히 다른 옷을 입게 된다.

**근거** — CharacterLobbyController.cs:798-829 SanitizeLocked — :818 `if(item==null||AvatarOwnership.IsUnlocked(item)) continue;`, :822 `var fallback=accessory?null:_catalog.Default(category,config.gender);`, :823 `config.SetItem(category,fallback?fallback.itemId:0);`, :826-828 `Debug.LogWarning("[CharacterLobby] 잠긴 항목이 포함된 외형을 불러와 기본 제공 항목으로 교체했다 — " + ...)` — 이 함수 전체에 SetStatus 호출 없음. backend/src/main/resources/db/migration/V20__stop_selling_parts_missing_from_palette.sql (판매 중단 파츠 존재).

**제안 수정** — replaced 목록이 비어 있지 않으면 SetStatus 로 "보유하지 않은 항목 N개를 기본 항목으로 바꿨습니다 — 모자: 볼캡 → 없음" 처럼 실제 바뀐 내역을 화면에 띄운다. 액세서리를 지우는 경우 특히 명시한다(사라진 것은 눈에 안 띈다).


## 60. 아무도 안 만져도 슬롯머신이 6~10초마다 혼자 돌고, 당첨음이 아무 예고 없이 터진다

| | |
|---|---|
| 축 | 오디오·BGM |
| 상태 | **반박됨** |
| 위치 | `festa-unity/Assets/_Project/Scripts/Minigame/Slot/SlotMachineInteractable.cs:64` |
| 처리 | — 반박됨, 조치 없음 |

**사용자가 겪는 일** — 광장 아케이드 근처(반경 19 m)에 서 있으면 아무것도 안 했는데 몇 분에 한 번씩 카지노 당첨음이 갑자기 난다. 내가 뭘 잘못 눌렀나 싶어 화면을 돌아보게 된다. 두 기계가 겹치는 구간에서는 소리가 중간에 잘리기도 한다.

**내용** — SlotMachineInteractable 이 유휴 연출로 6~10초마다 SpinRandom() 을 부른다. 거리 게이트가 없어 플레이어가 어디 있든 계속 돈다. winChancePercent 5% 이므로 기계당 평균 2~3분에 한 번 당첨 연출이 뜨고, 2번 기계에서는 4.19 s 짜리 'Win Bonus Chimes' 가 19 m 반경으로 울린다. 사용자 조작과 아무 인과가 없는 소리다. 중첩·끊김 경로도 여기 있다: PlayWinFeedback 은 `winAudioSource.Stop(); winAudioSource.Play();` 라 앞 재생이 끝나기 전에 다음이 오면 중간에서 잘린다(4.19 s 클립 vs 최소 6 s 간격이라 유휴 단독으로는 드물지만, 플레이어 스핀과 유휴 스핀은 서로 다른 기계에서 동시에 날 수 있다). 두 기계는 6.8 m 떨어져 있어 19 m 반경 안에서 겹친다.

**근거** — festa-unity/Assets/_Project/Scripts/Minigame/Slot/SlotMachineInteractable.cs:36-38 `[SerializeField] bool _attractSpins = true;` `_attractIntervalSeconds = new(6f, 10f)` 동 :62-68 Update() — 거리·가시성 조건 없이 `if (!_reels.IsSpinning) _reels.SpinRandom();` 씬 실측: 두 인스턴스 모두 attractSpins=True interval=(6.00, 10.00), winChancePercent=5 Assets/ithappy/Casino_Free/Scripts/Slot_Machine/PresetUVSlotMachine.cs:549-552 Stop() 직후 Play() 클립 길이 실측: Arcade_02 'Win Bonus Chimes' 4.19 s

**제안 수정** — 유휴 스핀은 시각 연출로만 남기고 소리는 빼거나(당첨음은 플레이어가 건 스핀에서만), 유휴 스핀 자체에 거리 게이트를 건다 — 로컬 플레이어가 maxDistance 안에 없으면 SpinRandom 을 건너뛴다. 그러면 소리·연산·오디오 보이스가 함께 절약된다.


## 61. 3D 사운드 거리값이 미터 기준으로 찍혀 있다 — 월드는 1 m = 13.26 unit 인데 곱해지지 않았다

| | |
|---|---|
| 축 | 오디오·BGM |
| 상태 | **반박됨** |
| 위치 | `festa-unity/Assets/_Project/Scripts/Editor/FestaInteriorBuilder.cs:27` |
| 처리 | — 반박됨. 슬롯머신 두 대는 #24 에서 유닛 기준(20/106 u)으로 맞췼다 |

**사용자가 겪는 일** — 지금은 슬롯머신 두 대의 불일치로만 드러나지만, 앞으로 붙일 모든 효과음이 "거의 안 들린다" 또는 "맵 반대편에서도 들린다" 중 하나가 된다. 사용자에겐 소리가 있다 없다 하는 것으로 느껴진다.

**내용** — AudioSource 의 minDistance·maxDistance 는 월드 unit 이다. Arcade_01 은 min 1 / max 4 로 되어 있는데 이는 7.5 cm / 30 cm 다 — 사람이 기계에 코를 박아야 들리는 값이고, 명백히 "1 m / 4 m" 를 의도한 값이다. 반대로 Arcade_02 의 250 은 18.85 m 로 의도보다 훨씬 넓다. 지금 3D 소스가 둘뿐이라 피해가 작지만, 발소리·포털·상호작용음을 추가하는 순간 같은 실수가 전부 재발한다 — 기본값(min 1 / max 500)으로 컴포넌트를 붙이면 가청 반경이 0.075 m~37.7 m 가 된다.

**근거** — festa-unity/Assets/_Project/Scripts/Editor/FestaInteriorBuilder.cs:27 `const float M = 13.26f;  // 1 m` (월드 규약의 단일 출처) 씬 실측: Arcade_01 min=1.0 max=4.0 / Arcade_02 min=6.0 max=250.0 — 둘 다 M 이 곱해지지 않은 값 프로젝트에 AudioMixer 도, 거리값을 M 으로 환산해 주는 헬퍼도 없다 (Assets 전체에 AudioMixer 에셋 0건, ProjectSettings/AudioManager.asset 의 `Rolloff Scale: 1`)

**제안 수정** — 두 가지 중 하나. (a) 씬의 모든 AudioSource 거리값에 13.26 을 곱해 맞추고, 새 소스는 반드시 미터×13.26 으로 적는 규칙을 docs/28 에 남긴다. (b) ProjectSettings/AudioManager 의 `Rolloff Scale` 을 1/13.26 ≈ 0.0754 로 두어 미터로 적은 값이 그대로 통하게 한다 — 다만 이건 전역이라 이미 unit 기준으로 맞춰 둔 Arcade_02 도 함께 바뀌므로 (a) 를 권한다.


## 62. 탭을 옮겨도 음악이 계속 나오고, 새로고침 뒤 마우스를 안 누르면 소리가 아예 안 난다

| | |
|---|---|
| 축 | 오디오·BGM |
| 상태 | **반박됨** |
| 위치 | `festa-unity/Assets/Plugins/WebGL/FestaUnityBridge.jslib` |
| 처리 | — 반박됨(브라우저 자동재생 정책은 FE 첫 클릭에서 이미 처리), 조치 없음 |

**사용자가 겪는 일** — (1) 월드 화면을 새로고침하면 음악이 안 나온다. 사용자는 "소리가 고장났다" 고 느끼고, 아무 데나 클릭해야 살아난다는 걸 알 방법이 없다. (2) 다른 탭에서 일하는 동안 축제 음악이 뒤에서 계속 나온다. 어느 탭인지 찾아 헤매게 된다.

**내용** — 두 가지가 같은 뿌리다 — 프로젝트에 브라우저 오디오 수명주기를 다루는 코드가 한 줄도 없다. (1) 자동재생: Unity framework 는 suspended AudioContext 를 `mousedown` 과 `touchstart` 에서만 resume 한다. `keydown` 리스너가 없다. /app/world 를 새로고침하거나 북마크로 직행하면 그 문서에는 사용자 제스처가 없어 컨텍스트가 suspended 로 시작하고, 400 ms 폴링 resume() 도 사용자 활성화 없이는 실패한다. 이동하려고 WASD 만 누르는 사용자는 캔버스를 한 번 클릭할 때까지 계속 무음이다. FE 는 화면 BGM 의 자동재생 거부만 pendingGesture 로 드러내고(screenAudio.ts:150-156) Unity 쪽은 드러내는 곳이 없다. (2) 백그라운드: FestaUnityBridge.jslib 에도, WorldBgm 에도 visibilitychange·blur 처리가 없다. 탭을 숨기면 rAF 가 멎어 Update 가 안 돌지만 AudioContext 는 살아 있어 마지막 볼륨 그대로 루프가 계속된다.

**근거** — festa-unity/Builds/web/Build/60ce5ca59e1fdb2dd37399772ee3a010.framework.js:   `window.addEventListener("mousedown", _userEventCallback)` / `window.addEventListener("touchstart", _userEventCallback)` — 이 둘뿐, keydown 없음   `var resumeInterval = Module.setInterval(tryToResumeAudioContext, 400)` `grep -n "visibilitychange|hidden|AudioContext|audio" festa-unity/Assets/Plugins/WebGL/FestaUnityBridge.jslib` → 0건 festa-unity/ProjectSettings/ProjectSettings.asset:86 `runInBackground: 0` WorldBgm.cs 전문에 OnApplicationFocus·OnApplicationPause 없음

**제안 수정** — jslib 에 (a) `keydown` 에서도 `WEBAudio.audioContext.resume()` 을 시도하는 리스너, (b) `document.visibilitychange` 에서 hidden 이면 컨텍스트를 suspend·visible 이면 resume 하는 핸들러를 추가한다. 자동재생이 막힌 상태는 조용히 넘기지 말고(T-24 정신) FE 로 이벤트를 올려 "화면을 한 번 클릭하면 소리가 켜집니다" 를 띄운다.


## 63. 스폰 배정이 늦으면 11층 입장 첫 구간에 축제 BGM이 새어 나온다 (#140 의 남은 경로)

| | |
|---|---|
| 축 | 오디오·BGM |
| 상태 | **반박됨** |
| 위치 | `festa-unity/Assets/_Project/Scripts/World/Festival/WorldBgm.cs:74` |
| 처리 | — 반박됨, 조치 없음 |

**사용자가 겪는 일** — 11층 방에서 세션이 시작되는데 첫 몇 초 동안 축제 음악이 아주 작게 깔린다(볼륨 0.056). 조용한 아침 트랙 위에 밤 서커스 조성이 겹쳐 불협화음으로 들리고, 이유를 알 수 없는 소리라 사용자는 "음악이 이상하다" 고만 느낀다.

**내용** — #140 수정은 구역 판정의 '귀' 를 Camera.main 에서 로컬 플레이어로 옮겼다. 그러나 판정 조건은 "PlayerObject 가 존재하는가" 일 뿐 "서버가 배정한 자리에 놓였는가" 가 아니다. PlayerMovement 는 ServerSpawnPosition 을 못 받으면 최대 30초(SpawnWaitHardTimeout)까지 _spawnPlaced=false 로 대기하는데, 그동안에도 PlayerObject 는 존재하므로 WorldBgm 은 그 위치를 읽는다. 그 위치가 원점 근처면 z=0 → t=InverseLerp(-110,100,0)=0.5238 → circus=(0.5238-0.47)×1.9=0.1022 → 볼륨 0.0562 — WorldBgm.cs:83 에 적힌 "릴리스 빌드 실측 0.0562" 와 정확히 같은 값이다. 즉 카메라를 지웠어도 같은 좌표를 읽는 다른 문이 열려 있다. T-177 실기 검증(2026-08-31, 10/10 정상)에서 실제 경합은 관측되지 않았으므로 상시 재현은 아니다. 다만 스폰 경합은 타이밍 결함이라 회선·부하에 따라 되살아난다.

**근거** — WorldBgm.cs:71-93 TryGetEarPosition — `nm.LocalClient?.PlayerObject` 가 null 이 아니면 무조건 그 좌표를 쓴다. 배치 완료 여부를 보지 않는다. WorldBgm.cs:104-106 ZoneWeights 복도 식 — z=0 이면 circus 0.1022 PlayerMovement.cs:84-91 `SpawnWaitTimeout = 3f` / `SpawnWaitMinFrames = 180` / `SpawnWaitHardTimeout = 30f` PlayerMovement.cs:266-273 PlaceAt — drift > 0.5 이면 "스폰 경합 감지 — Owner 가 {위치} 에 있었다" 경고 PlayerMovement.cs:310-316 포기 로그 "원점 근처면 허공에서 떨어진다" 실제 배정 좌표는 (-29.5..5.5, ?, -248..-228): ConnectionManager.cs:213-228 SpawnColumns=8 SpawnRows=5 SpawnSpacing=5 SpawnCenter=(-12,0,-238) → 전부 z < -118 이므로 정상 배치되면 morning 100% 가 맞다 docs/KHS/26_다음_작업_인수인계.md:286 — T-177 10회 반복 검증에서 '스폰 경합 감지' 0회

**제안 수정** — '귀' 의 조건을 "PlayerObject 가 있다" 에서 "서버 배정 위치에 놓였다" 로 좁힌다 — PlayerMovement 에 `public bool SpawnPlaced => _spawnPlaced;` 를 노출하고 TryGetEarPosition 이 그것까지 확인한다. 배치 전에는 지금과 같이 양쪽 침묵.


## 64. DiagnosticKeys 가 신고하는 항목은 0 이지만, 검사 자체가 성립하지 않고 있다 — 실제 키 충돌 2건이 그 구멍으로 살아 있다

| | |
|---|---|
| 축 | UI·입력·접근성 |
| 상태 | **미검증** |
| 위치 | `festa-unity/Assets/_Project/Scripts/World/Avatar/Assembly/AvatarMeshMerge.cs:91` |
| 처리 | ✅ 수정 (2026-09-09) — ① `AvatarMeshMerge.Toggle` 은 PerfHud 가 켜진 씬에서 물러난다(이중 토글 해소, 로비에서는 그대로 동작) ② 미등록 4종(AvatarCostAb `=`·HitchLogger `` ` ``·SpawnRepeatProbe Home·AvatarMergeIntegrity Insert) `DiagnosticKeys.Claim` ③ 예약키에 F4·F8·F9·F10 추가, 플랫폼 조건 제거(에디터에서도 에러) |

**사용자가 겪는 일** — 개발자·계측자가 겪는다. `'` 를 눌러 메시 병합 A/B 를 돌린다고 믿지만 실제로는 상태가 그대로라 두 조건의 표본이 같은 값으로 섞인다. 릴리스 빌드를 뽑기 전 마지막 계측이 이렇게 날아가면 하루가 사라진다.

**내용** — 레지스트리에 등록된 키는 18개인데 소스가 실제로 읽는 키는 34개다. 미등록 16개 중 진단 도구가 4개(AvatarCostAb=`=`, AvatarMergeIntegrity=Insert, HitchLogger=` ` `, SpawnRepeatProbe=Home) — 이들은 Claim 을 부르지 않아 중복 검사 대상에서 통째로 빠진다. 그 결과 실제 충돌 2건이 신고 없이 살아 있다. ① `'`(Quote): PerfHud.cs:163 과 AvatarMeshMerge.cs:91 이 같은 static `AvatarMeshMerge.Enabled` 를 한 번의 입력에 두 번 뒤집는다 → 눌러도 아무 일도 일어나지 않는다(A/B 계측이 통째로 무효가 된다 — T-238 이 잡으려던 바로 그 사고다). ② `F`: BoothInteractionInput + PortalInteractor(위 항목). 게다가 브라우저 예약키 검사는 `Application.platform == RuntimePlatform.WebGLPlayer` 일 때만 돌아서 에디터·개발 중에는 죽어 있고, 예약 목록에도 주석이 '뺐다' 고 말하는 F4·F8·F9·F10 이 실제로는 들어 있지 않다.

**근거** — 에디터 실측(아래 스크립트): '레지스트리 등록: 18개 / 소스에서 읽는 키(추정): 34개'. [A] 미등록: Equals(AvatarCostAb.cs), Insert(AvatarMergeIntegrity.cs), BackQuote(HitchLogger.cs), Home(SpawnRepeatProbe.cs), LeftAlt/RightAlt(PlayerEmoteController.cs), W/A/S/D/화살표/Shift(PlayerMovement.cs). [B] 두 파일 이상이 읽는 키: `F`(BoothInteractionInput + PortalInteractor), `Quote`(PerfHud + AvatarMeshMerge), `Escape`(TimerStopGameHud + InteractionFocusCamera), `Space`(TimerStopGameHud + PlayerMovement). 코드: Diagnostics/PerfHud.cs:51 `const KeyCode _meshMergeKey = KeyCode.Quote;` → :163-169 `AvatarMeshMerge.Enabled = !AvatarMeshMerge.Enabled;`, World/Avatar/Assembly/AvatarMeshMerge.cs:91 `if (!Input.GetKeyDown(KeyCode.Quote)) return; Enabled = !Enabled;` (설치는 :75 `[RuntimeInitializeOnLoadMethod(AfterSceneLoad)]`, 개발/에디터에서만 — 즉 PerfHud 와 항상 공존한다). Diagnostics/DiagnosticKeys.cs:33-35 BrowserReserved 에 F4·F8·F9·F10 없음, :57 `if (Application.pl

**제안 수정** — ① AvatarMeshMerge.Toggle 을 지우고 PerfHud 한 곳만 남긴다(같은 기능이다). 로비에서도 필요하면 반대로 PerfHud 쪽을 지우고 Toggle 만 남긴 뒤 DiagnosticKeys.Claim 을 부른다. ② Claim 을 부르지 않는 4개 진단 도구에 Claim 을 넣는다. ③ BrowserReserved 에 F4·F8·F9·F10 을 추가하고, 예약키 검사의 `platform == WebGLPlayer` 조건을 없애 에디터에서도 에러가 나게 한다(어차피 WebGL 이 주 배포 대상이다).


## 65. 마우스 커서 잠금 정책이 아예 없다 — 시야를 돌리다 커서가 화면 밖으로 나가면 회전이 멈추고, 밖에서 버튼을 떼면 카메라가 혼자 돈다

| | |
|---|---|
| 축 | UI·입력·접근성 |
| 상태 | **미검증** |
| 위치 | `festa-unity/Assets/_Project/Scripts/World/Player/PlayerCameraFollow.cs:314` |
| 처리 | ⏸ 보류 — WebGL 포인터락은 사용자 제스처 안에서만 걸리고 브라우저마다 다르다. 실브라우저 실측 뒤 우클릭-누름 구간 잠금으로 도입 |

**사용자가 겪는 일** — 조금만 크게 시야를 돌리려 하면 화면 가장자리에서 회전이 뚝 끊긴다. 그 상태로 버튼을 떼고 마우스를 다시 들여오면 카메라가 제멋대로 크게 회전한다. '화면이 자꾸 튄다' 로 보고된다.

**내용** — 저장소 전체(vendor 포함)에 Cursor.lockState·Cursor.visible·Screen.SetResolution 이 단 한 줄도 없다. 그런데 시야 조작은 우클릭 드래그 + mouse.delta 방식이다(PlayerCameraFollow.UpdateOrbit). 포인터 락이 없으면 브라우저 커서가 캔버스를 벗어나는 순간 mousemove 가 캔버스로 오지 않아 회전이 끊기고, 캔버스 밖에서 버튼을 떼면 mouseup 이 캔버스에 전달되지 않아 Unity 는 rightButton.isPressed 를 계속 true 로 본다 — 마우스를 다시 캔버스로 들여오면 아무 버튼도 안 눌렀는데 시야가 홱 돈다. 또한 FE 의 조작 안내 카드(.world-hud-guide, 좌상단 246px)는 pointer-events: auto 라 그 위에서 시작한 드래그는 애초에 Unity 에 닿지 않는다.

**근거** — `grep -rn "Cursor\.lockState|Cursor\.visible|Screen\.SetResolution" --include=*.cs festa-unity/Assets` → 0건. World/Player/PlayerCameraFollow.cs:314-317 `var mouse = Mouse.current; if (mouse == null || !mouse.rightButton.isPressed) return; var delta = mouse.delta.ReadValue();`. festa-frontend/src/features/world/ui/worldHud.css:9-10 `.world-hud { pointer-events: none; } .world-hud > * { pointer-events: auto; }` + `.world-hud-guide { left: 20px; top: 20px; width: 246px; }`.

**제안 수정** — 우클릭을 누르는 동안 `Cursor.lockState = CursorLockMode.Locked`(WebGL 은 사용자 제스처 안에서 호출해야 하므로 rightButton.wasPressedThisFrame 프레임에), 떼면 None 으로 되돌린다. 최소한 `!mouse.rightButton.isPressed` 로 빠지는 프레임에 누적 delta 를 버리고, 캔버스 blur 시 드래그 상태를 강제로 해제하는 안전장치를 둔다.


## 66. 휴대폰·태블릿으로 들어오면 아무것도 못 한다 — 이동도 시야도 상호작용도 전부 키보드/마우스 전용인데 안내가 없다

| | |
|---|---|
| 축 | UI·입력·접근성 |
| 상태 | **미검증** |
| 위치 | `festa-frontend/src/unity/host/UnityHost.tsx:204` |
| 처리 | ➡ FE 소관(모바일 안내) |

**사용자가 겪는 일** — QR 로 들어온 방문객이 휴대폰으로 접속하면 로딩만 끝나고 캐릭터가 움직이지 않는다. 화면이 고장난 것처럼 보이고, 무엇이 잘못됐는지 알려 주는 문구가 한 줄도 없다.

**내용** — PlayerMovement 는 Keyboard.current 만 읽고(WASD·화살표·Shift·Space), PlayerCameraFollow 는 Mouse.current.rightButton 과 scroll 만 읽는다. BoothInteractionInput 은 조준(TryReadPointer)에만 Touchscreen 을 지원하고 실행은 F 키 전용이라, 터치로는 부스 오브젝트에 하이라이트만 걸리고 절대 열리지 않는다. FE 쪽에도 기기 판별이 전혀 없어서(isMobile·maxTouchPoints·userAgent·pointer:coarse 검색 0건) 휴대폰 사용자는 아무 안내 없이 월드에 들어와 30분짜리 다운로드를 받은 뒤 서 있기만 한다.

**근거** — World/Network/Player/PlayerMovement.cs:505-531 (kb.wKey/sKey/dKey/aKey/화살표/Shift/Space 만). World/Player/PlayerCameraFollow.cs:301-317 (Mouse.current.scroll, Mouse.current.rightButton). Content/BoothInteractionInput.cs:157-166 은 Touchscreen 포인터를 읽지만 :191-201 InteractKeyPressedThisFrame 은 keyboard.fKey / Input.GetKeyDown(KeyCode.F) 뿐. `grep -rn "isMobile|maxTouchPoints|userAgent|matchMedia|pointer: coarse" festa-frontend/src` → 0건.

**제안 수정** — 단기: FE 에서 `navigator.maxTouchPoints > 0 && matchMedia('(pointer: coarse)').matches` 일 때 월드 진입 전에 'PC 로 접속해 주세요' 안내를 띄우고 Unity 로딩을 시작하지 않는다(수십 MB 다운로드를 막는 효과가 더 크다). 중기: 온스크린 조이스틱 + 드래그 시야 + 탭 상호작용.


## 67. 창을 세로로 줄이면 조작 안내와 [F] 프롬프트 글자가 6~11px 로 줄어 읽을 수 없다

| | |
|---|---|
| 축 | UI·입력·접근성 |
| 상태 | **미검증** |
| 위치 | `festa-unity/Assets/_Project/Scripts/World/Interaction/InteractPromptUI.cs:33` |
| 처리 | ✅ 수정 (2026-09-09) — `InteractPromptUI.UiScale()` = `Clamp(Screen.height/1080, 0.75, 2.0)` 로 프롬프트 3곳·조작 카드·이모트 휠이 같은 배율 |

**사용자가 겪는 일** — 노트북에서 브라우저 창을 반만 띄우거나 세로가 짧은 화면을 쓰면 '조작 안내' 카드와 상호작용 프롬프트가 개미 글씨가 되어 무엇을 누르라는 건지 안 보인다. 처음 온 사람이 WASD·F 를 배울 유일한 통로가 이 카드다.

**내용** — 사용자용 HUD 세 곳이 전부 `ui = Screen.height / 1080f` 로 크기를 정하는데 하한이 없다. 개발용 PerfHud 만 `Mathf.Max(1f, Screen.height / 1080f)` 로 바닥을 깔아 두었다 — 정작 하한이 필요한 것은 사용자용 쪽이다. 백버퍼 높이 720px 에서 조작 카드 키캡이 11px, 600px 에서 9px, 500px 에서 8px 이 된다. WebGL 백버퍼 높이는 캔버스 CSS 높이 × devicePixelRatio(FE 가 1.5 로 상한) 라, DPR 1 인 흔한 노트북에서 브라우저 창을 반쯤 줄이면 바로 이 구간에 들어간다.

**근거** — World/Interaction/InteractPromptUI.cs:33·59·76 `float ui = Screen.height / 1080f;` (하한 없음). World/UI/ControlsHintHud.cs:123 동일. 대조: Diagnostics/PerfHud.cs:272 `float scale = Mathf.Max(1f, Screen.height / 1080f);`, :385 동일. 에디터 실측 표(높이px → 프롬프트/안내카드/안내키캡/칩글자): 400→9/7/6/7, 500→11/9/8/8, 600→13/11/9/10, 720→16/13/11/12, 1080→24/20/17/18. 현재 에디터 Game 뷰는 640x480.

**제안 수정** — 세 곳의 ui 계산을 `float ui = Mathf.Clamp(Screen.height / 1080f, 0.75f, 2.0f);` 로 바꾼다. 상한 2.0 은 아래의 알약 모서리 뒤집힘(반지름 64 초과)도 함께 막는다.


## 68. 이모트 휠만 화면 크기를 따라가지 않아 다른 HUD 와 크기가 어긋나고, 창이 작으면 커서에서 떨어져 엉뚱한 감정이 선택된다

| | |
|---|---|
| 축 | UI·입력·접근성 |
| 상태 | **미검증** |
| 위치 | `festa-unity/Assets/_Project/Scripts/Network/Player/PlayerEmoteController.cs:13` |
| 처리 | ✅ 수정 (2026-09-09) — 링 반경·데드존·글꼴 4종을 `UiScale()` 배율로, 배율이 5% 이상 바뀌면 스타일 재생성. `ClampCenter` 는 화면이 여백×2 보다 작으면 클램프 생략 |

**사용자가 겪는 일** — 고해상도 전체화면에서 감정 표현 휠만 유독 작고 글자가 흐리다. 창을 작게 쓰면 Alt+클릭했을 때 휠이 커서와 다른 곳에 뜨고, 원하는 감정 대신 옆 칸이 선택된다.

**내용** — 프로젝트의 모든 HUD 가 Screen.height/1080 배율을 쓰는데 PlayerEmoteController 만 링 반경 150px, 글자 15/17/18/20px 를 고정값으로 쓴다. 백버퍼 2160px(고DPI 전체화면)에서 다른 HUD 는 2배로 커지는데 이모트 휠만 그대로라 절반 크기로 보인다. 더 나쁜 것은 중심 클램프다 — ClampCenter 의 margin 이 150+74=224 인데 Screen.width 나 Screen.height 가 448 미만이면 Mathf.Clamp 의 min 이 max 보다 커진다. 그러면 포인터 위치에 따라 중심이 224 또는 (크기-224) 로 튀어, 휠이 커서에서 멀리 떨어져 그려지고 UpdateSelection 이 그 어긋난 중심 기준으로 각도를 재서 의도하지 않은 감정이 선택된다.

**근거** — Network/Player/PlayerEmoteController.cs:13 `const float WheelRadius = 150f;`(주석: 화면 픽셀), :203 `fontSize = 15`, :209 `fontSize = 17`, :212 `fontSize = 18`, :215 `fontSize = 20` — 전부 상수. :156-193 OnGUI 에 Screen.height 배율 없음(:161 은 y 뒤집기용). :85-90 `var margin = WheelRadius + 74f; return new Vector2(Mathf.Clamp(pointer.x, margin, Screen.width - margin), Mathf.Clamp(pointer.y, margin, Screen.height - margin));`. 대조: ControlsHintHud.cs:123, InteractPromptUI.cs:33 는 모두 ui 배율 사용.

**제안 수정** — WheelRadius 와 네 개 fontSize 를 `Screen.height / 1080f` 배율(위 항목과 같은 Clamp)로 곱한다. ClampCenter 는 margin 이 화면 절반을 넘으면 클램프를 생략하도록 `if (Screen.width < margin*2 || Screen.height < margin*2) return pointer;` 를 앞에 둔다.


## 69. 릴리스 빌드에서도 OnGUI 5종이 매 프레임 여러 번 돌며 GC 쓰레기를 만든다

| | |
|---|---|
| 축 | UI·입력·접근성 |
| 상태 | **미검증** · 플레이 모드 필요 |
| 위치 | `festa-unity/Assets/_Project/Scripts/World/Festival/PortalInteractor.cs:127` |
| 처리 | 🟡 부분 (2026-09-09) — GUI.* 만 쓰는 4종(BoothInteractionInput·PortalInteractor·PlayerEmoteController·ControlsHintHud)에 `useGUILayout = false` 로 Layout 패스 제거(호출 절반). '그릴 것 없으면 컴포넌트 끄기' 와 DevConnectionHud `#if` 제외는 미착수 — GC 실측 뒤 판단 |

**사용자가 겪는 일** — 걸어 다니는 동안 1~2초에 한 번 미세하게 프레임이 걸린다. 마우스를 많이 움직일수록 잦아진다 — 사용자는 '움직이면 뚝뚝 끊긴다' 로 느낀다.

**내용** — 씬의 @Diagnostics 계열 4종(PerfHud·FixedPoseBenchmark·AvatarStressSpawner·RenderCostProbe)은 PerfHud.ToolsEnabled 로 릴리스에서 enabled=false 가 되므로 OnGUI 가 호출되지 않는다. 그런데 BoothInteractionInput·PortalInteractor·ControlsHintHud·PlayerEmoteController·DevConnectionHud 다섯은 릴리스에서도 컴포넌트가 살아 있어 OnGUI 가 계속 불린다. IMGUI 는 한 프레임에 Layout·Repaint 로 최소 2회, 여기에 입력 이벤트(MouseMove·MouseDrag·KeyDown·ScrollWheel)마다 1회씩 더 불린다 — 마우스를 움직이며 걷는 동안 프레임당 5~8회가 된다. DevConnectionHud 의 본문은 첫 줄에서 return 하지만 메서드 호출과 IMGUI 이벤트 디스패치 비용은 그대로 든다. 팀이 이미 같은 원인으로 GC 를 측정해 두었다: 개발 패널 + PerfHud 를 켠 상태 ≈1.3 MB/s, 끄면 ≈0.3 MB/s — 그 0.3 MB/s 바닥이 릴리스에 남는 몫이다. WebGL 힙에서 이는 '초당 GC 1회 = 걷기 끊김' 으로 나타난다(-437 ⑤).

**근거** — 에디터 리플렉션 실측: OnGUI 를 선언한 MonoBehaviour 타입 10종(Assembly-CSharp) — AvatarCustomizationHud, PortalInteractor, ControlsHintHud, DevConnectionHud, PlayerEmoteController, AvatarStressSpawner, FixedPoseBenchmark, PerfHud, RenderCostProbe, BoothInteractionInput. 씬 배치 6개는 전부 @Diagnostics/@AvatarUI/@Network. 릴리스 게이트: PerfHud.cs:136 `if (!ToolsEnabled) { enabled = false; return; }`, RenderCostProbe.cs:96·FixedPoseBenchmark.cs:97·HitchLogger.cs:57 동일, AvatarStressSpawner.cs:59 `if (!PerfHud.ToolsEnabled) enabled = false;`, AvatarCustomizationHud.cs:26 `if (!Application.isEditor) enabled = false;`. 게이트 없음: BoothInteractionInput.cs:299, PortalInteractor.cs:127, ControlsHintHud.cs:118, PlayerEmoteController.cs:156. DevConnectionHud.cs:102-108 은 본문만 게이트(:108 `if (!Debug.isDebugBuild && !Application.isEditor) return;`). GC 수치 출처: Network/DevConnectionHud.cs:114-117 주석.

**제안 수정** — OnGUI 를 가진 다섯 컴포넌트 모두에 '그릴 것이 없으면 컴포넌트를 끈다' 규칙을 넣는다 — PortalInteractor·BoothInteractionInput 은 대상이 없을 때, PlayerEmoteController 는 휠이 닫혀 있을 때, ControlsHintHud 는 HostProvidesUi 이거나 소유자가 없을 때 `enabled = false`(입력 판정은 별도의 가벼운 컴포넌트나 코루틴으로 분리). DevConnectionHud 의 OnGUI 는 `#if UNITY_EDITOR || DEVELOPMENT_BUILD` 로 메서드 자체를 컴파일 제외한다(같은 파일 :79-86 이 서버 빌드용으로 이미 쓰는 기법이다).


## 70. 사람이 들어올 때마다 순간 멈춘다 — 한글 폰트 아틀라스를 빌드에서 비워 놓고 런타임에 굽는다

| | |
|---|---|
| 축 | UI·입력·접근성 |
| 상태 | **미검증** |
| 위치 | `festa-unity/Assets/_Project/Resources/Fonts/NotoSansKRBold_SDF.asset:137` |
| 처리 | ✅ 수정 (2026-09-09) — ① 세 폰트 `m_ClearDynamicDataOnBuild` 0. ② 이름표 글꼴 Noto 를 **28pt·pad 4·2048² 로 재생성**해 KS X 1001 한글 2350 + ASCII 를 미리 구움(1 아틀라스, Alpha8 4 MB). 90pt 그대로 굽자 1024² 아틀라스 26장이 돼 되돌렸다. 참조는 `Resources.Load` 경로 2곳뿐이라 GUID 변경 무해. ③ TMP Settings 전역 폴백에 Noto 추가. 이름표 선명도·`.data` 증가분은 빌드에서(체크리스트 R-11) |

**사용자가 겪는 일** — 다른 사람이 접속해 이름표가 처음 뜨는 순간 화면이 한 박자 멈춘다. 사람이 몰리는 시연 초반에 반복된다.

**내용** — 한글 TMP 폰트 셋 전부가 AtlasPopulationMode=Dynamic 이고 m_ClearDynamicDataOnBuild: 1 이다. 즉 에디터에서 쌓인 글리프표가 빌드 시점에 통째로 지워지고, 실행 중 처음 나오는 한글마다 FreeType 이 SDF 를 굽는다. 이름표(WorldNameplate)는 NotoSansKRBold_SDF 를 쓰므로 새 닉네임의 새 글자가 나올 때마다 이 비용이 든다 — WebGL(단일 스레드 WASM)에서는 그대로 프레임에 실린다. 별개의 문제로 TMP 전역 폴백 목록이 비어 있고 기본 폰트가 LiberationSans SDF(한글 없음)라, 폰트를 명시하지 않고 런타임에 만든 TMP 텍스트는 한글이 전부 □ 로 나온다. 지금 씬의 TMP 18개는 모두 Jua_SDF 를 명시하고 있어 당장은 안전하지만, 안전장치가 없다.

**근거** — 에디터 실측: `Jua_SDF mode=Dynamic 글리프=28 문자=28 자체폴백=0 atlasFmt=Alpha8`, `MalgunGothic_SDF mode=Dynamic 글리프=0 문자=0`, `NotoSansKRBold_SDF mode=Dynamic 글리프=12 문자=12`. 세 .asset 모두 `m_ClearDynamicDataOnBuild: 1`, `m_AtlasWidth/Height: 1024`, `m_FallbackFontAssetTable: []`. TMP Settings: `기본폰트=LiberationSans SDF`, `전역폴백 개수=0`, `m_fallbackFontAssets: []`(Assets/TextMesh Pro/Resources/TMP Settings.asset:36). 씬 TMP: `Jua_SDF 18개`, 한글인데 한글 폰트가 아닌 것 0건. 사용처: World/Interaction/WorldNameplate.cs:34 `const string FontResourcePath = "Fonts/NotoSansKRBold_SDF";`, :116-118.

**제안 수정** — ① 세 폰트의 m_ClearDynamicDataOnBuild 를 0 으로 바꾸고, 자주 쓰는 한글 상용 2350자 + 아스키를 에디터에서 미리 구워 커밋한다(아틀라스 1024²·Alpha8 = 1 MB, 다운로드는 crunch 대상이 아니라 그대로지만 프레임 끊김이 사라진다). ② TMP Settings 의 전역 폴백에 NotoSansKRBold_SDF 를 넣어, 폰트를 명시하지 않은 TMP 가 □ 로 떨어지지 않게 한다.


## 71. 모니터를 옮기면 화면이 흐려지거나 갑자기 무거워지고 새로고침 전까지 돌아오지 않는다

| | |
|---|---|
| 축 | UI·입력·접근성 |
| 상태 | **미검증** |
| 위치 | `festa-frontend/src/unity/host/loader.ts:57` |
| 처리 | ➡ FE 소관 — GitLab #143 에 실측과 함께 요청함(T-240) |

**사용자가 겪는 일** — 노트북에 외장 모니터를 붙여 창을 옮기면 그때부터 세상이 흐릿하거나(또는 프레임이 뚝 떨어지고) 새로고침해야만 정상으로 돌아온다. 사용자는 원인을 알 수 없다.

**내용** — FE 가 Unity 에 넘기는 devicePixelRatio 는 createUnityInstance 를 부르는 순간 한 번만 계산된다(resolveDevicePixelRatio). 그 뒤 resize·DPR 변경을 듣는 리스너가 FE 전체에 하나도 없다. 1배 모니터에서 시작해 2배 모니터로 창을 끌고 가면 Unity 는 계속 DPR 1 로 그려 물리 해상도의 절반만 채운다(흐려짐). 반대로 2배(상한 1.5) 모니터에서 1배로 옮기면 CSS 크기 대비 2.25배 픽셀을 계속 그려 GPU 부담만 남는다 — 이 상한을 도입한 계기가 바로 그 4.84배 사례였다.

**근거** — festa-frontend/src/unity/host/loader.ts:57 `devicePixelRatio: resolveDevicePixelRatio(),` (createUnityInstance 호출 시 1회 평가), :64-71 `export function resolveDevicePixelRatio() { const ratio = ... window.devicePixelRatio; ... return Math.min(ratio, MAX_DEVICE_PIXEL_RATIO); }`, :19-28 주석에 'DPR 2.2 가 표시 크기의 4.84배(백버퍼 2880x1530 vs CSS 1309x695)를 그려 정지 상태에서도 GPU 22.0ms'. `grep -rn "resize|ResizeObserver|matchMedia|devicePixelRatio" festa-frontend/src/unity` → loader.ts 의 주석 1줄 외 리스너 0건.

**제안 수정** — `matchMedia(`(resolution: ${window.devicePixelRatio}dppx)`)` 의 change 이벤트를 듣고 DPR 이 바뀌면 사용자에게 '화면을 옮겼습니다 — 새로고침하면 선명해집니다' 안내를 띄운다(캔버스 크기를 JS 로 직접 바꾸면 rAF 가 멈춰 NGO 까지 끊긴다는 것이 loader.ts:25-28 에 이미 실측으로 적혀 있으므로 자동 재적용은 하지 않는다).


# 심각도: 낮음

## 72. 상자·통 텍스처 3장이 압축 없이 들어가 25 MB 를 먹는다 — 로딩과 메모리 양쪽에 얹힌다

| | |
|---|---|
| 축 | 남은 성능 병목 |
| 상태 | **미검증** |
| 위치 | `festa-unity/Assets/_Project/Art/World/Textures/Tex_CratesAndBarrelsVol1_M.tga` |
| 처리 | ✅ 수정 (2026-09-09) — 3장 Uncompressed → DXT1/DXT5(1024 상한): A_color 9→1 MB, M 4→2 MB, O 3→1 MB (런타임 16→4 MB) |

**사용자가 겪는 일** — 로딩이 그만큼 길어지고, 이미 빠듯한 WebGL 힙을 25 MB 더 먹는다. _M·_O 는 밉맵이 없어 멀리 있는 상자·통 표면이 지글거린다.

**내용** — Assets/_Project/Art/World/Textures/Tex_CratesAndBarrelsVol1_* 3장이 압축 없이 임포트돼 있다. A_color01 은 1024x1024 RGB24 로 9.3 MB, _M 은 RGBA32 밉맵 없음 8 MB, _O 는 RGB24 밉맵 없음 7 MB — 합계 24.9 MB 다. 같은 해상도의 다른 텍스처들은 전부 DXT1/DXT5 로 2 MB 안팎이므로 이 3장만 10~5배 크다. DXT 로 바꾸면 약 4 MB 로 내려간다. 밉맵이 없는 것도 별개 문제다(_M, _O). 멀리서 볼 때 지글거리고 텍스처 캐시 적중률이 떨어진다. 씬이 참조하는 텍스처 121장 중 문제가 있는 것은 이 3장과 폰트 아틀라스(Alpha8 은 정상)뿐이다 — 나머지는 이미 잘 압축돼 있다. 즉 텍스처 예산 203 MB 중 낭비는 약 21 MB 로, 로딩 문제(결함 3)의 만능 해법은 아니지만 손이 가장 적게 가는 몫이다.

**근거** — 씬 참조 텍스처 121장 / 런타임 메모리 합계 203 MB. 문제 텍스처 실측:   Tex_CratesAndBarrelsVol1_A_color01 1024x1024 fmt=RGB24  9,558KB mips=11 [비압축]   Tex_CratesAndBarrelsVol1_M         1024x1024 fmt=RGBA32 8,192KB mips=1  [비압축][밉맵없음]   Tex_CratesAndBarrelsVol1_O         1024x1024 fmt=RGB24  7,168KB mips=1  [비압축][밉맵없음]   (참고·정상) Tex_CratesAndBarrelsVol1_N 1024x1024 DXT5 2MB mips=11 합계 24.9 MB → DXT 환산 약 4 MB, 회수 약 21 MB 2 MB 초과 텍스처 36장 중 2048px 는 7장: ArcadeMachine_Screen/Skin01/Skin02/Skin03(각 5MB), Lines(5MB), Screens_1(5MB), Slots(10MB DXT5)

**제안 수정** — 3장의 TextureImporter 를 열어 Compression=Normal Quality(DXT1/DXT5), Generate Mip Maps 체크. _M(메탈릭/마스크)·_O(AO) 는 컬러가 아니므로 sRGB 체크를 풀어야 한다 — 안 그러면 값이 감마 변환돼 재질이 달라 보인다. 덤으로 2048px 7장을 1024 로 내리면 25 MB 가 더 빠지는데, ArcadeMachine_Screen 처럼 가까이서 보는 화면 텍스처는 눈에 띌 수 있으니 그건 따로 눈으로 확인하고 결정해라.


## 73. [조치 불필요 · 오답 방지] 컨페티 AlwaysSimulate 8개는 비용이 0 이다 — 벤더 프리팹을 덮지 마라

| | |
|---|---|
| 축 | 남은 성능 병목 |
| 상태 | **미검증** · 플레이 모드 필요 |
| 위치 | `festa-unity/Assets/ithappy/Casino_Free/VFX/FX_Prefabs/Confetti/Confetti_blast_multicolor.prefab:47` |
| 처리 | ✔ 조치 불필요(오답 방지 항목) |

**사용자가 겪는 일** — 없음. 사용자가 느끼는 증상이 이 설정에서 나오지 않는다. 이 항목에 시간을 쓰면 진짜 병목(부스 내부 컬링 누수)을 놓친다.

**내용** — 조사 지시에 '벤더 프리팹 ithappy/Casino_Free 컨페티 8개가 cullingMode: AlwaysSimulate 다. 씬/런타임에서 덮는 방법과 실제 비용을 산정해라' 가 있었다. 산정 결과 **비용이 없다.** 이유는 이 시스템들이 전부 playOnAwake=False, loop=False, duration=1 이기 때문이다. AlwaysSimulate 는 '재생 중일 때 화면 밖이어도 시뮬레이션한다' 는 뜻이지 '항상 돈다' 는 뜻이 아니다. 정지 상태에서는 하는 일이 없다. maxParticles=1000 도 오해를 부르는 값이다 — 그건 상한이고 실제 방출량은 버스트 카운트로 정해진다. 슬롯머신 한 대가 한 번 터질 때 실제로 생기는 파티클은 약 101개다(두 대 합쳐 203개). 화면 밖에서 터져도 1초 동안 101개를 시뮬레이션할 뿐이다. 씬 실측으로 재생 중인 시스템 0개, 살아있는 파티클 0개를 확인했다. AlwaysSimulate 인 16개는 아케이드 슬롯머신 2대에 붙은 것들이고, 지시에 적힌 '8개' 는 프리팹 1개당 개수다.

**근거** — 씬 파티클 시스템 45개 중 AlwaysSimulate=16, Automatic=29 AlwaysSimulate 16개 전부: playOnAwake=False, loop=False, duration=1, 경로는 @Festival/Festival_Arcade/Arcade_0{1,2}_Slot_Machine_0{1,2}/Confetti_* 플레이 모드 실측: 재생 중인 시스템=0, 살아있는 파티클=0 버스트 실측(한 번 트리거 시 실제 방출량):   blast_multicolor: 본체 1 + circle 1 + confetti_large 10 + confetti_small 15 = 27   directional_multicolor: 본체 10 + flash_left(1+10+10) + flash_rigth(1+10+10) = 52   → 머신 1대당 약 101개, 2대 합쳐 약 203개 (maxParticles 1000 은 상한일 뿐) 프리팹 원본: Assets/ithappy/Casino_Free/VFX/FX_Prefabs/Confetti/Confetti_blast_multicolor.prefab:47,4933,9864,14692 (cullingMode: 3 = AlwaysSimulate) 한편 상시 도는 파티클은 따로 있다: @Festival/Festival_Ambience/Confetti (Automatic, loop=True, rate 9/s, 약 45개 동시) 와 불꽃 로켓 5개(loop=True) — 이쪽은 Automatic 이라 화면 밖이면 알아서 멈춘다

**제안 수정** — 조치하지 마라. 벤더 프리팹 덮어쓰기(원본 수정 금지 대상)도, 런타임 cullingMode 재설정 코드도 넣을 가치가 없다. 이 항목은 후보에서 지워라. 굳이 파티클을 손대야 한다면 대상은 이쪽이 아니라 상시 루프인 Festival_Ambience/Confetti(rate 9/s)와 불꽃 로켓 5개인데, 그것들은 이미 Automatic 이라 화면 밖에서 멈춘다.


## 74. 후처리를 하나도 안 쓰는데 HDR 렌더 타깃을 켜 두었다

| | |
|---|---|
| 축 | 남은 성능 병목 |
| 상태 | **미검증** |
| 위치 | `festa-unity/Assets/_Project/Settings/Rendering/Mobile_RPAsset.asset:26` |
| 처리 | ✅ 수정 (2026-09-09) — WebGL 이 쓰는 `Mobile_RPAsset.supportsHDR` false(후처리 없음). 에디터용 PC 에셋은 그대로. 밝기·불꽃놀이 동일한지 빌드에서(체크리스트 R-12) |

**사용자가 겪는 일** — 전 구간에서 GPU 시간이 조금씩 더 든다. 단독으로는 체감이 안 되지만 저사양에서 60fps 경계에 걸릴 때 차이를 만든다. 그리고 셰이더 변형이 늘어 첫 등장 멈춤(결함 4)을 키운다.

**내용** — WebGL 이 쓰는 Mobile_RPAsset 에 m_SupportsHDR: 1 이 켜져 있는데, 씬에 Volume 이 **0개**다. 즉 블룸·톤매핑·색보정 등 HDR 이 필요한 후처리가 하나도 없다. Mobile_Renderer 의 m_RendererFeatures 도 빈 배열이다. 후처리가 없으면 HDR 은 화면을 부동소수 중간 타깃에 그린 뒤 백버퍼로 블릿하는 비용만 남는다. 다만 과장하지 않겠다 — m_HDRColorBufferPrecision: 0(32Bit, R11G11B10F)이라 픽셀당 바이트는 RGBA8 과 같아서 대역폭 손해는 크지 않다. 남는 손해는 (a) 셰이더 변형이 늘어나는 것(결함 4 의 첫 등장 멈춤을 키운다)과 (b) sRGB 하드웨어 변환 대신 블릿 셰이더로 변환하는 것이다. m_RenderScale: 0.8 이라 어차피 중간 타깃은 생기므로 블릿 자체는 HDR 을 꺼도 남는다. 확정하지 않고 '재 볼 것' 으로 분류한다. 릴리스 빌드에서 HDR 켠 상태/끈 상태의 GPU 프레임 시간을 직접 비교해야 값을 말할 수 있다.

**근거** — Assets/_Project/Settings/Rendering/Mobile_RPAsset.asset   m_SupportsHDR: 1 / m_HDRColorBufferPrecision: 0 / m_MSAA: 1(없음) / m_RenderScale: 0.8   m_RequireDepthTexture: 0 / m_RequireOpaqueTexture: 0 (둘 다 꺼짐 — 잘 돼 있다) Assets/_Project/Settings/Rendering/Mobile_Renderer.asset:27  m_RendererFeatures: [] 씬 실측: Volume 개수 = 0, Camera 1대(Main Camera, allowHDR=True) ProjectSettings/QualitySettings.asset:129  WebGL: 0  -> WebGL 은 Mobile 품질(=Mobile_RPAsset) 사용. 에디터는 PC 라 확인 시 주의 확인 못 함: HDR on/off 의 실제 프레임 시간 차이 (릴리스 WebGL 실측 필요)

**제안 수정** — 먼저 재라. Mobile_RPAsset 의 HDR 만 끄고 다른 것은 그대로 둔 빌드를 하나 뽑아 같은 코스에서 GPU 프레임 시간을 비교해라. 유의미하면 끈다. 나중에 블룸을 넣기로 하면 되돌려야 하니, 끌 경우 docs/26_팀_결정_필요사항.md 에 '후처리 도입 시 HDR 재검토' 로 남겨라. 주의: 이 파일은 조명 담당이 만질 수 있는 영역과 가깝다 — 그림자 관련 값(m_ShadowDistance: 200, T-215)은 절대 함께 건드리지 마라.


## 75. [도구] 고정 포즈에서 드로우콜 상위 기여 오브젝트를 뽑는 스크립트

| | |
|---|---|
| 축 | 남은 성능 병목 |
| 상태 | **미검증** |
| 처리 | ⏸ 도구 — `OcclusionAbProbe`(7포즈 드로우콜)·`FestivalStaticCombiner` 보고로 오늘 판단을 대신했다. 상위 기여 오브젝트 스크립트는 후순위 |

**사용자가 겪는 일** — 직접적인 사용자 영향은 없다. 다만 이것 없이 '드로우콜이 줄었다' 를 판단하면 오늘 부스 내부 컬링처럼 절반만 동작하는 변경을 성공으로 오독하게 된다.

**내용** — 지시받은 '고정 포즈에서 상위 드로우콜 기여 오브젝트를 뽑는' 도구다. 결함이 아니라 다른 결함들을 재기 위한 계측 도구이므로 severity 는 낮음으로 둔다. 임의의 카메라 위치·각도를 넣으면 그 시점의 절두체 안 렌더러를 루트/그룹별로 묶어 서브메시(=배칭 전 드로우콜 상한)와 삼각형으로 정렬해 준다. **현재 Renderer.enabled 상태를 존중하므로** BoothInteriorCulling 같은 런타임 컬링의 효과가 그대로 반영된다 — 플레이 모드에서 돌리면 실제 상태, 에디트 모드에서 돌리면 저작 상태를 본다. 한계: 오클루전 컬링은 반영하지 못한다(런타임 Umbra 질의를 흉내 낼 수 없다). 그래서 이 스크립트의 숫자는 항상 실제보다 크고, UnityStats.drawCalls 와 함께 봐야 한다. 두 값의 차이가 곧 오클루전이 걷어내는 양이다. 이 도구로 이미 확인한 것: 축제 서쪽 끝(-754,173,0)에서 동쪽(yaw 85)을 보면 절두체 안 렌더러 1,031 / 서브메시 1,440 이고 그중 703 이 꺼졌어야 할 부스 내부다(결함 1).

**근거** — 이 스크립트로 실측한 결과(플레이 모드, 현재 enabled 상태 반영):   POSE (-754,173,0) yaw=85 far=4000 → 렌더러 1,031 / 서브메시 1,440 / 삼각형 1,387,890   그중 @BoothInteriors 가 렌더러 541 / 서브메시 703 / 삼각형 121,140 (드로우콜의 49%) 에디트 모드 같은 포즈(런타임 부스 내용물 없음) → 렌더러 906 / 서브메시 1,168 / 삼각형 1,226,514   상위: @World_11F/11th-0821-complete textures 136/298/155,554         @Festival/Festival_Trees 56/112/250,440         @Festival/Festival_Ambience 58/58/21,136         @World_11F/Corridor_Festival 54/56/75,970         @Festival/Festival_Slots 47/53/508,865 대조용 실제값(플레이 중, 카메라 (2100,60,950)): UnityStats drawCalls=579 batches=579 setPassCalls=53 triangles=131,140 shadowCasters=79

**제안 수정** — docs/KHS/ 아래 계측 스크립트로 보관해라. POSE 상수만 바꿔 축제 중심·복도·로비·부스 내부를 같은 방식으로 비교할 수 있다. 릴리스 빌드에서는 UnityStats 를 못 쓰므로(진단 도구가 Application.isEditor || Debug.isDebugBuild 게이트) 이 스크립트는 에디터 전용이다 — 릴리스 실측이 필요하면 DisplayRefreshAdapter 처럼 게이트 없는 로그를 따로 넣어야 한다.


## 76. 릴리스 빌드에는 '내가 스스로 나갔다' 를 표시하는 경로가 없다 — 의도적 퇴장도 '재접속 중…' 으로 보인다

| | |
|---|---|
| 축 | 네트워킹·재접속 |
| 상태 | **미검증** |
| 위치 | `festa-unity/Assets/_Project/Scripts/Network/DevConnectionHud.cs:107` |
| 처리 | ⏸ 보류 — FE 가 의도적 퇴장을 알리는 계약(`SetInputLocked` 류의 SendMessage) 필요. 사용자 테스트 뒤 |

**사용자가 겪는 일** — 오늘은 릴리스에 퇴장 버튼이 없어 드러나지 않는다. 그러나 '월드 나가기' 를 붙이는 순간, 나가기를 누른 사람에게 '재접속 중…' 이 뜨고 67초~수분 뒤 '월드 연결이 끊어졌습니다' 로 끝난다. 그리고 s_userInitiated 가 남는 경로에 걸리면, 그 뒤 진짜로 끊겼을 때 재접속이 아예 시도되지 않은 채 화면만 멈춘다.

**내용** — WorldReconnector.MarkUserInitiatedShutdown() 을 부르는 곳은 DevConnectionHud 두 군데뿐이고(:179 Disconnect 버튼, :237 ReturnToCustomization), 둘 다 OnGUI 안에서만 도달한다. 그 OnGUI 는 `if (!UnityEngine.Debug.isDebugBuild && !Application.isEditor) return;`(:107) 로 릴리스에서 아무것도 그리지 않는다. 즉 릴리스 WebGL 에서 앞으로 '월드 나가기' 를 붙이면 그 퇴장이 자동 재접속 대상으로 잡혀 '재접속 중…' 전면 오버레이가 뜬다. 덧붙여 s_userInitiated 는 static 이고 다음 끊김 이벤트에서만 지워진다(:98-104) — 마킹 뒤 끊김 이벤트가 오지 않으면(NetworkManager 가 먼저 파괴되는 등) 플래그가 true 로 남아 그 다음의 진짜 끊김이 조용히 사용자 의도로 처리되어 재접속이 아예 돌지 않는다.

**근거** — DevConnectionHud.cs:179  WorldReconnector.MarkUserInitiatedShutdown();   // OnGUI 내부 DevConnectionHud.cs:237  WorldReconnector.MarkUserInitiatedShutdown();   // ReturnToCustomization, 호출부는 OnGUI(:183) DevConnectionHud.cs:107  if (!UnityEngine.Debug.isDebugBuild && !Application.isEditor) return;   // 릴리스에서는 안 그린다 WorldReconnector.cs:33   static bool s_userInitiated; WorldReconnector.cs:98-104  if (s_userInitiated) { s_userInitiated = false; ... }   // 끊김 이벤트에서만 해제 festa-frontend/src/unity/host/UnityHost.tsx:193-194  disconnected 이면서 detail !== 'USER' 이면 '재접속 중…'

**제안 수정** — ① MarkUserInitiatedShutdown 을 개발 HUD 밖으로 꺼낸다 — ConnectionManager.Shutdown() 에 `bool userInitiated = true` 파라미터를 두고 그 안에서 부르면, 앞으로 어느 경로로 나가든 표시가 빠지지 않는다. ② s_userInitiated 를 무기한 유지하지 말고 마킹 시각을 함께 저장해 5초가 지나면 무효로 본다 — 끊김 이벤트가 오지 않은 마킹이 다음 세션의 재접속을 죽이지 않게. ③ FE 에 '월드 나가기' 를 붙일 때 이 두 가지를 같은 MR 에 넣는다.


## 77. 진단 키 레지스트리가 릴리스에서도 돈다 — 키가 겹치면 실사용자 콘솔에 빨간 에러가 뜬다

| | |
|---|---|
| 축 | 릴리스 관측성 |
| 상태 | **검증통과** |
| 위치 | `festa-unity/Assets/_Project/Scripts/Diagnostics/PerfHud.cs:131` |
| 처리 | ✅ 수정 (2026-09-09) — `DiagnosticKeys.Claim` 이 에디터·개발 빌드 아니면 즉시 반환(등록·에러 없음) |

**사용자가 겪는 일** — 오늘은 없다. 다음에 누가 단축키를 옮기는 날, 아무 관계 없는 사용자의 브라우저 콘솔에 빨간 에러가 뜨고 그 사용자가 캡처를 보내오면 우리는 엉뚱한 곳을 판다.

**내용** — `DiagnosticKeys.RegisterNonDiagnosticUsers` 는 `[RuntimeInitializeOnLoadMethod(BeforeSceneLoad)]` 이고 게이트가 없다 — 릴리스에서도 Dictionary 를 만들고 6개를 등록한다. 그리고 `PerfHud.OnEnable` 은 `DiagnosticKeys.Claim` 을 **네 번 부른 다음에야** `if (!ToolsEnabled) { enabled = false; return; }` 를 검사한다(PerfHud.cs:131-135). 순서가 뒤집혀 있다. 지금은 키가 안 겹치니 증상이 없다. 하지만 누가 진단 키를 하나 옮겨 충돌을 만들면, 그 에러가 개발 빌드가 아니라 **릴리스 사용자 콘솔에** 빨간 줄로 뜬다. 진단 도구는 릴리스에서 꺼져 있는데 진단 도구의 에러만 나가는 상태다. 비용 자체는 무시할 수준(부팅 1회, Dictionary 6개)이라 성능 문제는 아니다. 순서 문제다.

**근거** — DiagnosticKeys.cs:79-88 — `[RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)] static void RegisterNonDiagnosticUsers()`, 게이트 없음. PerfHud.cs:131-135 — `DiagnosticKeys.Claim(nameof(PerfHud), _toggleKey);` 외 3회 뒤에 `if (!ToolsEnabled) { enabled = false; return; }`. DiagnosticKeys.cs:47-48, 55-56 — 충돌·예약키 모두 `Debug.LogError`. MCP execute_code 출력 — PerfHud 가 `@Diagnostics` 에 enabled=True 로 붙어 있어 릴리스 빌드에서도 OnEnable 이 실행된다.

**제안 수정** — PerfHud.OnEnable 에서 `if (!ToolsEnabled) { enabled = false; return; }` 를 Claim 4줄보다 **위로** 올린다. 키 중복 검사를 에디터에서 잃고 싶지 않다면 Claim 을 `#if UNITY_EDITOR` 로 감싸고 그대로 위에 두면 된다 — 중복 검사는 개발자를 위한 것이지 사용자를 위한 것이 아니다. DiagnosticKeys.RegisterNonDiagnosticUsers 도 같은 이유로 `if (!Application.isEditor && !Debug.isDebugBuild) return;` 한 줄을 맨 앞에 둔다.


## 78. 게임기(GAME_PORTAL)는 유니티에만 있다 — 스튜디오에서 놓을 방법이 없다

| | |
|---|---|
| 축 | 부스스튜디오→런타임 |
| 상태 | **미검증** |
| 위치 | `festa-unity/Assets/_Project/Scripts/Booth/Catalog/BoothObjectType.cs:19` |
| 처리 | ➡ 스튜디오·BE 계약(GitLab #137·#56 흐름). Unity 쪽 `GAME_PORTAL` 경로는 실증 완료 |

**사용자가 겪는 일** — 부스 오너가 자기 게임을 부스에 걸 수 없다. Game Studio 로 게임을 publish 해도 부스에 놓을 오브젝트가 팔레트에 없다. 유니티 쪽 구현은 다 되어 있어서 '왜 안 되지' 로 시간이 샌다.

**내용** — 유니티는 GAME_PORTAL 을 완전히 지원한다: enum·파서 매핑·레지스트리 프리팹(ArcadeCabinet_GamePortal)·GamePortalInteractable·미연결 안내 토스트까지 다 있다. 그런데 서버 화이트리스트(LayoutObjectType)와 FE ObjectType 은 canonical 10종이고 GAME_PORTAL 이 없다. 서버가 UNKNOWN_OBJECT_TYPE 으로 거부하므로 이 타입은 게시본에 절대 실릴 수 없다. 목 데이터에만 존재하는 기능이다.

**근거** — festa-unity/Assets/_Project/Scripts/Booth/Catalog/BoothObjectType.cs:19,37 — GamePortal + "GAME_PORTAL" 매핑 festa-unity/Assets/_Project/ScriptableObjects/BoothObjectRegistry.asset:46-48 — type 11 → Assets/_Project/Prefabs/World/ArcadeCabinet_GamePortal.prefab (GamePortalInteractable 보유, 콜라이더 5개) backend/src/main/java/com/example/ssafesta/booth/LayoutObjectType.java:23-32 — 10종, GAME_PORTAL 없음 festa-frontend/src/entities/layout/types.ts:5-15 — 10종, GAME_PORTAL 없음 grep -rn 'GAME_PORTAL' backend/src festa-frontend/src → 0건 docs/26_팀_결정_필요사항.md:213 — '2026-08-21 GAME_PORTAL requiresConfig=true, BE whitelist 선배포 후 FE 전송' 로 합의됐으나 미이행

**제안 수정** — 릴리스에 넣을 거라면 순서가 정해져 있다 — ① BE LayoutObjectType 에 GAME_PORTAL(requiresConfig=true, LocalBounds 는 ArcadeCabinet 프리팹 실측)을 추가하고 배포 ② FE types.ts·objectTypes.ts·팔레트에 추가. 유니티는 변경 0 이다. 안 넣을 거라면 GitLab #56 에 '이번 릴리스 제외' 를 남겨 다음 사람이 다시 조사하지 않게 할 것.


## 79. 비스듬히 돌려 놓은 패널은 실제보다 두꺼운 보이지 않는 벽으로 막힌다

| | |
|---|---|
| 축 | 부스스튜디오→런타임 |
| 상태 | **미검증** · 플레이 모드 필요 |
| 위치 | `festa-unity/Assets/_Project/Scripts/Booth/Interaction/BoothInteractionTarget.cs:185` |
| 처리 | 🟡 부분 (2026-09-09) — 새 장식 래퍼(패널·트러스·태블릿)는 프리팹 공간의 얇은 BoxCollider 를 갖고 회전을 따라가므로 `EnsureCollider` 의 월드 AABB 경로를 타지 않는다. 기존 타입 프리팹(VideoScreen·ProjectPanel·RecruitmentBoard)은 여전히 런타임 AABB — 프리팹에 콜라이더를 심는 후속 |

**사용자가 겪는 일** — 오너가 패널을 비스듬히 돌려 놓으면 방문객이 패널 옆을 지나갈 때 보이지 않는 곳에서 막힌다. 눈에 보이는 것과 부딪히는 것이 어긋나 '조작이 이상하다' 로 읽힌다. 상호작용 사거리(표면 기준 20 unit)도 그만큼 넉넉해진다.

**내용** — VideoScreen·ProjectPanel·RecruitmentBoard 프리팹에는 콜라이더가 없고, BoothInteractionTarget.EnsureCollider 가 런타임에 하나 만들어 붙인다. 그런데 그 계산이 renderer.bounds(축 정렬 월드 AABB)를 받아 InverseTransformPoint 로 로컬로 되돌리는 방식이라, 회전이 0°·90°·180°·270° 가 아니면 회전으로 부풀어난 AABB 가 그대로 로컬 박스 크기가 된다. rotationY=15° 인 PROJECT_PANEL(실물 1.55 x 0.36 m)은 1.59 x 0.44 m 짜리 박스가 붙는다 — 두께 +22%. 45° 면 훨씬 커진다.

**근거** — festa-unity/Assets/_Project/Scripts/Booth/Interaction/BoothInteractionTarget.cs:185-195 — `var bounds = renderers[0].bounds; ... collider.center = transform.InverseTransformPoint(bounds.center); collider.size = |localMax - localMin|` 에디터 실측: VideoScreen·ProjectPanel·RecruitmentBoard 프리팹의 비트리거 콜라이더 = 0개 (이 경로를 반드시 탄다) 계약 AABB ProjectPanel = 1.55(x) x 2.72 x 0.36(z). 15° 회전 시 월드 AABB = 1.55·cos15 + 0.36·sin15 = 1.59, 1.55·sin15 + 0.36·cos15 = 0.75 → 로컬로 되돌려도 1.59 x 0.44 (재계산값) Tools/mock-api/booth-slot-layout.json:36 — panel-1 rotationY: 15 (비직각 회전이 실제 데이터에 있다)

**제안 수정** — EnsureCollider 가 renderer.bounds(월드 AABB) 대신 각 렌더러의 sharedMesh.bounds 를 이 트랜스폼 로컬로 옮겨 합치도록 바꾼다 — 회전에 영향받지 않는다. 더 좋은 쪽은 세 프리팹에 제대로 된 BoxCollider 를 미리 넣어 이 경로 자체를 안 타게 하는 것이다(F8 과 같은 작업).


## 80. objectId 가 비면 노트북·프로젝트·설문은 카메라만 들어가고 아무 일도 일어나지 않는다

| | |
|---|---|
| 축 | 상호작용·미니게임 |
| 상태 | **미검증** |
| 위치 | `festa-unity/Assets/_Project/Scripts/Content/LaptopInteractable.cs:43` |
| 처리 | ➡ 계약 — `objectId` 는 BE 검증 필수 필드. 게임 쪽은 빈 값이면 로그로 드러내는 후속 |

**사용자가 겪는 일** — 레이아웃 데이터에 식별자가 빠진 노트북/전시판/키오스크에 F 를 누르면 카메라가 그 앞으로 당겨지고 조작이 막히는데 웹 화면은 안 열린다. Esc 를 눌러야 돌아온다 — AI 직원과 부스 게임기는 같은 상황에서 '아직 준비 중이에요' 한 줄을 띄우고 카메라를 안 건드리는데, 이 셋만 다르게 동작한다.

**내용** — AiNpcInteractable 와 GamePortalInteractable 는 '보낼 수 없는 상태' 를 Interact() 앞머리에서 먼저 검사해 Toast 로 알리고 카메라를 건드리지 않는다(-448 에서 명시적으로 그렇게 고쳤다). 그런데 LaptopInteractable·ProjectPanelInteractable·SurveyKioskInteractable 은 순서가 반대다 — InteractionFocusCamera.FocusOn() 으로 먼저 카메라를 당기고 입력을 잠근 다음 Send*Interact() 를 부른다. 브리지는 objectId 가 비면 LogWarning 만 남기고 조용히 건너뛰며 OnSent 도 발화하지 않으므로 토스트조차 안 뜬다. ResolvedObjectId 는 objectId ?? id 라 둘 다 빈 레이아웃 데이터에서만 발생하지만, 발생하면 -448 이 고친 것과 완전히 같은 증상(아무 반응 없이 화면만 잠긴다)이 재현된다. ManagementDeskInteractable 도 같은 순서지만 payload 가 없어 항상 송신되므로 해당 없다.

**근거** — festa-unity/.../Content/LaptopInteractable.cs:43-44 → `InteractionFocusCamera.FocusOn(gameObject, 2.0f); BoothInteractBridge.SendLaptopInteract(...);` (검사 없음, 순서 역전) festa-unity/.../Content/ProjectPanelInteractable.cs:43-44 / SurveyKioskInteractable.cs:41-42 — 동일 패턴 festa-unity/.../Integration/Bridge/BoothInteractBridge.cs:88-93, 189-194 → `if (!HasObjectId(...)) return;` 이면 Send 도 OnSent 도 없음 비교: AiNpcInteractable.cs:46-54 주석 → "여기서 초점·잠금을 걸면 방문자에게는 '아무 반응 없이 화면만 잠기는' 상태가 된다 (2026-09-06 WebGL 실측, S15P21A604-448)" — 그래서 검사가 먼저다 festa-unity/.../Booth/Layout/BoothLayoutDto.cs:33-34 → `ResolvedObjectId => !string.IsNullOrEmpty(objectId) ? objectId : id;`

**제안 수정** — 세 컴포넌트의 Interact() 를 AiNpcInteractable.cs:46-54 와 같은 순서로 맞춘다 — `if (string.IsNullOrEmpty(_runtimeObject.ObjectId)) { BoothInteractionInput.Toast("이 오브젝트는 아직 연결되지 않았어요"); Debug.LogWarning(...); return; }` 를 FocusOn 앞에 둔다. 원칙으로 정리하면 '잠그기 전에 열릴 것이 있는지 먼저 확인한다' 이고, 이는 결함 1(광장 게임기)의 수정 방향과 같다.


## 81. 상호작용·이동·포털에 효과음이 하나도 없다 — 눌렀는지 소리로 알 수 없다

| | |
|---|---|
| 축 | 오디오·BGM |
| 상태 | **검증통과** |
| 위치 | `festa-unity/Assets/_Project/Scripts/Booth/Interaction/BoothInteractionTarget.cs` |
| 처리 | ⏸ 콘텐츠 — 효과음 자산이 없다. 사용자 테스트 뒤 |

**사용자가 겪는 일** — F 를 눌러 부스에 들어가거나 포털을 통과할 때 아무 소리가 없어 조작이 먹혔는지 화면만으로 판단해야 한다. 프레임이 출렁이는 구간에서는 그 화면 반응도 늦어 두 번 누르게 된다.

**내용** — Assets/_Project/Audio 에는 BGM 두 개(8.4 MB)뿐이다. 발소리, 포털 통과음, F 상호작용음, HUD 클릭음, 미니게임 베팅음이 전부 없다. 유일한 효과음은 벤더 카지노 에셋의 당첨음 두 개이고 그중 하나는 배선조차 안 돼 있다. '중첩 재생 경로' 를 찾는 것이 이 축의 항목이었는데, 중첩할 소리 자체가 없다.

**근거** — `find festa-unity/Assets/_Project/Audio -type f ! -name "*.meta"` → BGM_AlgorithmicMorning.mp3, BGM_MidnightCircus.mp3 두 개뿐 (8.4 M) `grep -rl "AudioSource|PlayOneShot|AudioClip|PlayClipAtPoint" --include=*.cs Assets/` (벤더·TMP 제외) → CharacterLobbyBuilder.cs(에디터), WorldBgm.cs, ithappy 카지노 2개 — 그게 전부 씬 전체 AudioSource 실측 = 2개 (둘 다 아케이드 슬롯머신), 런타임에 WorldBgm 이 2개 추가

**제안 수정** — 이번 릴리스에서 고칠 항목은 아니다. 다만 넣을 때는 위 6번(1 m = 13.26 unit)을 반드시 반영하고, 상호작용음은 2D(spatialBlend 0)로 두어 거리 환산 실수를 원천 차단한다.


## 82. BGM이 176초·200초마다 루프 이음매에서 미세하게 끊길 수 있다

| | |
|---|---|
| 축 | 오디오·BGM |
| 상태 | **반박됨** |
| 위치 | `festa-unity/Assets/_Project/Audio/BGM/BGM_AlgorithmicMorning.mp3` |
| 처리 | ⏸ 오디오 자산 편집 필요 — 후순위 |

**사용자가 겪는 일** — 약 3분마다 음악이 한 번 툭 끊겼다가 다시 시작한다. 계속 듣고 있으면 거슬리지만 원인을 짚기 어려워 "버벅인다" 로 보고된다.

**내용** — 두 BGM 은 MP3 원본이고 Unity 는 WebGL 빌드에서 이를 다시 손실 압축(framework 주석이 .m4a/AAC 를 전제로 계산한다)한 뒤 브라우저가 decodeAudioData 로 푼다. MP3·AAC 모두 인코더 지연(encoder delay)과 패딩이 디코드 결과 앞뒤에 무음 샘플로 남는다. AudioSource.loop=true 는 그 무음까지 포함해 반복하므로 루프마다 짧은 공백이 생긴다. 확인하지 못했다 — 실제 가청 여부는 브라우저에서 들어 봐야 한다.

**근거** — 클립 실측: BGM_AlgorithmicMorning 176.23 s / BGM_MidnightCircus 200.06 s, 둘 다 ch=2 44100 Hz, 원본은 .mp3 WorldBgm.cs:43 `src.loop = true;` festa-unity/Builds/web/Build/*.framework.js 의 _JS_Sound_Load 주석 — "Tests with aac audio sizes in a .m4a container" (WebGL 경로는 AAC 를 전제한다) 프로젝트에 루프 포인트 지정(AudioClip loop points·AudioSource.SetScheduledEndTime) 코드 없음

**제안 수정** — 확정되면 원본을 WAV(무손실)로 교체하거나, 두 AudioSource 를 번갈아 쓰며 PlayScheduled 로 이음매를 겹쳐 가린다. 어느 쪽이든 먼저 들어서 확인부터 한다.


## 83. 4K를 넘는 백버퍼에서 프롬프트 알약의 둥근 모서리가 뒤집혀 깨진다

| | |
|---|---|
| 축 | UI·입력·접근성 |
| 상태 | **미검증** |
| 위치 | `festa-unity/Assets/_Project/Scripts/World/UI/FestaUiKit.cs:346` |
| 처리 | ✅ #67 의 `UiScale()` 상한 2.0 으로 반지름이 64 를 넘지 않는다 |

**사용자가 겪는 일** — 초고해상도 화면에서 [F] 프롬프트와 조작 안내 카드의 흰 알약이 모서리가 뭉개지고 좌우가 겹쳐 보인다. 흔하지는 않지만 시연용 대형 디스플레이에서 나올 수 있다.

**내용** — FestaUiKit.RoundedTexture 는 Rounded(radius) 로 텍스처를 만들면서(내부에서 radius 를 [2,64] 로 자른다) out 파라미터 border 에는 **자르기 전 값**을 그대로 돌려준다. DrawRounded 는 그 border 로 9-슬라이스 UV 를 계산하므로, radius 가 64 를 넘으면 u2 < u1 이 되어 가운데 조각의 UV 폭이 음수가 되고 좌우 조각이 겹친다. DrawPrompt 가 넘기는 반지름은 round(56*ui)/2 라 ui > 2.28 — 백버퍼 높이 2464px 초과 — 에서 발동한다. 지금 FE 의 DPR 상한 1.5 덕분에 흔한 4K(물리 2160)에서는 반지름 56 으로 안전하지만, 5K·초고해상도나 상한을 올리는 순간 바로 나온다.

**근거** — World/UI/FestaUiKit.cs:343-348 `public static Texture2D RoundedTexture(int radius, out int border) { var s = Rounded(radius); border = radius; return s.texture; }` — Rounded(:314-316)는 `radius = Mathf.Clamp(radius, 2, 64);` 로 자른다. World/Interaction/InteractPromptUI.cs:144-156 이 그 border 로 u1=(bpx)/tw, u2=(px.xMax-bpx)/tw 를 계산한다. 해상도 표 실측: 높이 2464 → 반지름 64(경계), 3240 → 84 '← 64 초과: 9슬라이스 UV 뒤집힘'.

**제안 수정** — `border = Mathf.Clamp(radius, 2, 64);` 한 줄. (앞의 ui 상한 2.0 을 넣으면 발동 자체가 사라지지만, 이쪽도 함께 고치는 편이 안전하다.)


## 84. 릴리스에서 개발용 F2 패널 토글이 살아 있어, 사용자가 F2 를 눌러도 아무 일이 없는 것처럼 보이지만 상태는 바뀐다

| | |
|---|---|
| 축 | UI·입력·접근성 |
| 상태 | **미검증** |
| 위치 | `festa-unity/Assets/_Project/Scripts/Network/DevConnectionHud.cs:98` |
| 처리 | ✅ 수정 (2026-09-09) — 릴리스에서는 F2 토글 자체를 받지 않는다(패널을 그리지 않는 조건과 동일) |

**사용자가 겪는 일** — 직접적인 피해는 작다. 다만 릴리스 빌드에서 F2 를 눌렀을 때 '무언가 눌리기는 하는데 아무 일도 없는' 상태가 남고, 진단 키 정책의 예외 하나가 코드에 계속 남아 다음 사람이 함수키를 다시 쓰게 만든다.

**내용** — DevConnectionHud 는 릴리스에서 '그리기만' 막고 컴포넌트는 살려 둔다(진입 소비 로직이 빌드와 무관하게 돌아야 하기 때문 — 의도된 설계다). 그런데 Update 의 F2 토글 판정(:98-101)에는 릴리스 게이트가 없어 릴리스 빌드에서도 s_panelVisible 이 계속 뒤집힌다. 실제 표시는 :108 에서 막히므로 화면에 나오지는 않지만, 상태가 조용히 바뀐다는 점과 F2 가 진단용 함수키라는 점이 DiagnosticKeys 규칙 1('진단에는 함수키를 쓰지 않는다')과 어긋난다. 브라우저 예약 검사에서 F2 가 걸리지 않는 이유는 BrowserReserved 목록에 F2 가 없기 때문이다.

**근거** — Network/DevConnectionHud.cs:98-101 `void Update() { if (Application.isBatchMode) return; if (!InputBridge.IsLocked && WasToggleKeyPressedThisFrame()) s_panelVisible = !s_panelVisible; }` — isDebugBuild 검사 없음. :108 `if (!UnityEngine.Debug.isDebugBuild && !Application.isEditor) return;` 는 OnGUI 안에만 있다. Diagnostics/DiagnosticKeys.cs:82 `ClaimExternal("DevConnectionHud(신규 IS f2Key)", KeyCode.F2);`, :33-35 BrowserReserved 에 F2 없음. DiagnosticKeys.Dump() 실측 출력 첫 줄 `F2  DevConnectionHud(신규 IS f2Key)`.

**제안 수정** — Update 의 토글 판정 앞에 `if (!UnityEngine.Debug.isDebugBuild && !Application.isEditor) return;` 를 넣고, 키를 F2 에서 구두점 키(예: KeyCode.Equals 는 이미 AvatarCostAb 가 쓰므로 KeyCode.Backspace 대신 KeyCode.Tilde 계열 미사용 키)로 옮긴 뒤 DiagnosticKeys.Claim 으로 다시 등록한다.

## 86. 셰이더 워밍업(프리로드·분산 모두)이 월드 진입을 1~4분 굳힌다 — 변형 하나가 초 단위 (2026-09-09 실측 추가)

| | |
|---|---|
| 축 | 진입 성능 |
| 상태 | **재현됨** (릴리스 `37d9b4f4`, 크롬 5173) |
| 위치 | `Core/Bootstrap/ShaderWarmupSpreader.cs`(삭제), `Resources/FestaTrackedVariants.shadervariants`(이동) |
| 처리 | ✅ 제거 (2026-09-09, `30ecdeaa`) — [T-246](../../25_트러블슈팅.md#t-246). 프레임 3.4/10.8/13.8/45.9/58.0/53.6초, `완료 133 변형` +240s, 첫 접속 서버가 끊고 `connected +210.2s`. #29/#43 은 ⏸ 로 되돌림. 빌드 확인 R-15 |

**사용자가 겪는 일** — 월드(회원은 로비)가 뜬 직후 화면이 3~4분 굳고, 첫 접속은 끊긴다. 사용자: "월드 불러오는데 렉이 저따구로 걸리면 게임을 어떻게 해".

## 87. 스튜디오 팔레트 assetCode 7종이 정본 28행에 없어 게시본이 상자로 뜬다 (2026-09-09 실측 추가)

| | |
|---|---|
| 축 | 부스 계약 정합 |
| 상태 | **재현됨** (부스 1 v15 `WALL_PLAIN` → `Unknown assetCode … 타입 기본 자산으로 대체`) |
| 위치 | FE `features/studio/model/visualAssets.ts` / Unity `BoothObjectRegistry.asset` |
| 처리 | ✅ 게임 쪽 별칭 7행 (`36731693`) + ➡ FE 정본화 요청 [#154](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/issues/154). 빌드 확인 R-16 |

**사용자가 겪는 일** — 스튜디오에서 기본 패널·그래픽 카운터·진열 선반·트러스·화분을 놓고 게시하면 월드에서 전부 기본 상자 세트로 보인다. 템플릿 4종은 정본 코드라 정상.

## 88. 미게시 방을 20초마다 다시 묻는다 — 11실 × 6회 헛조회 (2026-09-09 실측 추가)

| | |
|---|---|
| 축 | 네트워크 낭비 |
| 상태 | **재현됨** (`아직 못 채운 방 11실 — 재시도 1/6` … 6/6) |
| 위치 | `Booth/Runtime/WorldBoothPublishedBootstrap.cs` RetryLoopAsync |
| 처리 | ✅ 수정 (`36731693`) — `PublishedSlotResolution` 이 404/409 를 확정 답으로 기록, 일시 실패·미응답 방만 재시도. 빌드 확인 R-17 |

**사용자가 겪는 일** — 직접 보이는 피해는 없다. 입장 후 2분간 66번의 불필요한 요청이 Spring 으로 간다(동시 접속 50명이면 3,300 요청).

## 89. 브라우저 창이 포커스를 잃으면 Unity 가 통째로 멈춘다 — `runInBackground` 꺼짐 (2026-09-09 실측 추가)

| | |
|---|---|
| 축 | 접속 안정성 |
| 상태 | **재현됨** (회원 로비에서 5분 정지, 클릭하면 재개) |
| 위치 | `ProjectSettings/ProjectSettings.asset` `runInBackground: 0` |
| 처리 | ✅ 켬 (`36731693`). 빌드 확인 R-18 |

**사용자가 겪는 일** — 로딩 중 다른 창을 보고 오면 로딩이 멈춰 있고, 월드에서 알림·메신저를 잠깐 보면 접속이 끊긴다(NGO 하트비트 30초). "입장 렉" 의 한 원인.

