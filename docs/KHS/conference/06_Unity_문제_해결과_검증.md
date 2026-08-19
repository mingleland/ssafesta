# Unity 문제 해결과 검증

## 1. KHS 작업에서 반복된 문제

Unity Editor에서 정상으로 보이는 코드가 WebGL이나 Linux Dedicated Server에서는 다르게 동작했고, 런타임 생성 자산과 NGO의 제약 때문에 “컴파일은 되지만 화면이나 동기화가 깨지는” 문제가 반복됐다. KHS는 개별 수정에 그치지 않고 원인과 예방 규칙을 남기는 방식으로 대응했다.

세부 발생 기록은 [KHS 트러블슈팅](../25_트러블슈팅.md)에 있고, 이 문서는 그중 재사용 가능한 기술 패턴만 요약한다.

## 2. WebGL 비동기 처리

### 문제

WebGL은 브라우저 실행 환경과 단일 Thread 제약 때문에 일반 .NET 비동기 코드가 Native Build와 다르게 동작할 수 있다. 특히 게임 Loop와 무관한 `Task.Delay` 기반 대기는 WebGL에서 문제가 되기 쉽다.

### 적용 방식

- Unity Frame과 연결된 대기는 `Awaitable` 계열을 우선한다.
- `UnityWebRequest` 실패는 `await` 예외와 `request.result`를 모두 고려한다.
- HTTP 오류 하나가 Booth Runtime 전체를 중단시키지 않게 Result별로 분기한다.

### 프로젝트 예방 규칙

- WebGL 대상 코드에 `Task.Delay`를 새로 넣지 않는다.
- Network/HTTP 실패 경로도 Editor와 WebGL에서 각각 확인한다.
- CORS, Mixed Content(`https` 페이지의 `http/ws` 호출), Timeout을 코드 오류와 분리한다.

## 3. URP에서 마젠타 Material

### 문제

런타임에 기본 Material이나 호환되지 않는 Shader를 사용하면 URP Build에서 오브젝트가 마젠타로 보인다. Editor에 남아 있는 Shader가 Build에서 Strip되어 런타임 `Shader.Find`가 실패할 수도 있다.

### 적용 방식

- Placeholder는 `Universal Render Pipeline/Lit`을 명시해 Material을 생성했다.
- Avatar Assembler는 원본 Material의 Texture와 주요 값을 보존하면서 호환 Runtime Material을 준비했다.
- 동일 원본 Material 변환 결과는 Cache해 불필요한 Material 생성을 줄였다.
- 아바타별 색은 공유 Material 변경 대신 `MaterialPropertyBlock`으로 적용했다.

### 프로젝트 예방 규칙

- Built-in Shader 전제의 Asset을 URP에 바로 사용하지 않는다.
- Runtime에만 찾는 Shader는 실제 Build 포함 여부를 확인한다.
- 색 변경 시 `sharedMaterial` 자체를 수정하지 않는다.

## 4. 런타임 TextMesh 글리치

### 문제

Unity 6에서 코드로 만든 `TextMesh`가 Font와 Material을 명시하지 않으면 글자가 깨지거나 이상한 Texture로 보일 수 있었다.

### 적용 방식

`LegacyRuntime.ttf`를 명시적으로 불러와 `TextMesh.font`와 `MeshRenderer.material` 양쪽에 연결했다. Network Player 이름표와 Booth Placeholder Label에 같은 규칙을 사용했다.

## 5. NGO 제약과 오래된 Server Build

### 문제 1: NetworkVariable 선언

NGO IL Post Processor는 `NetworkVariable`을 필드로 기대한다. Property 형태로 감추면 생성 코드가 인식하지 못할 수 있다.

### 예방

동기화 값은 `public readonly NetworkVariable<T>` 필드로 선언하고 읽기/쓰기 권한을 생성자에서 명시한다.

### 문제 2: Client만 최신인 상태

Unity Client 코드를 바꿔도 Docker 안의 Linux Server가 이전 Build면 새 RPC나 긴 외형 문자열을 처리하지 못한다. 화면에서는 Button이 무반응처럼 보일 수 있다.

### 적용 방식

- 외형 변경 요청 후 서버 `NetworkVariable`에 같은 값이 반영되는지 감시한다.
- 일정 시간 안에 적용되지 않으면 “서버 Build가 최신인지 확인” 오류를 표시한다.
- Client 변경뿐 아니라 Server Build 재생성 여부를 검증 절차에 포함한다.

## 6. 아바타 파츠와 Material 문제를 분리해 진단한 방법

화면에서 피부가 보이는 현상은 한 원인으로 단정할 수 없다.

| 증상 | 우선 확인할 층 |
|---|---|
| 목이 분리되어 보임 | Head/Body Skeleton·Bind Pose·Transform |
| 옷 사이로 피부가 보임 | Body Part 숨김 규칙 또는 의상 Mesh 크기 |
| 상의 없음이 다시 생김 | Config 병합·Category 독립성 |
| 홍채 변경 시 눈 전체 변경 | Eye Shader Property/Material Slot 분리 |
| 피부색이 얼굴에만 적용 | Head와 Body Renderer 적용 범위 |
| 색이 다른 사용자까지 변함 | 공유 Material 수정 여부 |

KHS 구현에서는 선택 데이터, 조립·숨김, Material Property의 세 층을 따로 확인하도록 구조를 나눴다.

## 7. 검증 순서

### 코드 단계

1. Unity Compile Error 0건 확인
2. Console의 신규 Error 확인
3. DTO Encode→Decode 왕복 확인
4. Unknown Type·404 같은 실패 경로 확인

### Editor 단계

1. 성별·각 파츠 탭 전환
2. 없음 상태 유지와 다른 Category 변경 간 독립성
3. 얼굴 6색·의상 24색 격리
4. 좌/우 Drag와 Wheel Zoom
5. UI 위 Pointer에서 Camera 입력 차단

### 멀티플레이 단계

1. 최신 Server Build 실행
2. Client 2개 이상 접속
3. Player Spawn·이동·이름표 확인
4. 한 Client의 외형 변경이 자기 화면과 원격 화면에 모두 반영되는지 확인
5. 퇴장 후 Session과 Runtime Object 정리 확인

### Build 단계

1. WebGL에서 Font·Shader·HTTP 확인
2. Linux Server Container 기동 로그 확인
3. Browser ↔ Docker WebSocket 접속 확인
4. 운영 전 `wss`, CORS, HTTPS Mixed Content 검증

## 8. 관련 기록과 코드

- [KHS 트러블슈팅](../25_트러블슈팅.md)
- `festa-unity/Assets/_Project/Scripts/Booth/Factory/BoothObjectFactory.cs`
- `festa-unity/Assets/_Project/Scripts/Integration/Spring/HttpBoothApiClient.cs`
- `festa-unity/Assets/_Project/Scripts/World/Avatar/Assembly/AvatarAssembler.cs`
- `festa-unity/Assets/_Project/Scripts/World/Avatar/PlayerAppearanceController.cs`
