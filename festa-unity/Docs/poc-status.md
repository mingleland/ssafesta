# POC 진행 현황

> 마지막 갱신: **2026-08-21** — 08-08 기록은 그대로 두고, 그 뒤 무효가 된 전제를 문서 끝
> "2026-08-21 갱신" 절에 모았다. **ALB 전제는 폐기됐다** (#30).

## 🎯 마일스톤: Docker 컨테이너 멀티플레이 검증 완료

```
브라우저 3탭 (WebGL) → ws://127.0.0.1:7777 → Docker(ubuntu 22.04) → Unity Dedicated Server
```

- 이미지: `festa-world:dev` (Dockerfile: `festa-unity/Docker/`)
- 실행: `docker run -d --name festa-world-01 -p 7777:7777 festa-world:dev`
- 배포와 동일한 체인의 로컬 검증 완료. **남은 것: AWS + ALB + wss만.**
- Web 빌드 로컬 서빙: Compression `Disabled` + `python -m http.server 8000 --directory Builds/web` (localhost:8000 고정)
- 참고: Dedicated Server 빌드의 셰이더 제거 경고, IMGUI stripped 경고는 정상. 서버가 BoothRuntime을 실행하는 건 불필요 작업 — 추후 서버에서 스킵 최적화

## 검증 완료

| POC | 내용 | 결과 |
|---|---|---|
| **A. Multiplayer** | Dedicated(에디터) + Client 2 (Multiplayer Play Mode) | ✅ 상호 스폰(자신=노랑/원격=흰색), WASD 이동 양방향 동기화, 연결 종료 시 Despawn, Connection Approval 로그 확인 |
| **B. Booth Runtime** | Mock Layout JSON → Parser → Factory → Local Spawn | ✅ VIDEO_SCREEN / AI_AGENT / PROJECT_PANEL / SURVEY 4종 placeholder + 라벨 생성 |
| **C. Spring Boundary** | IBoothApiClient / IUserApiClient + Mock | ✅ BoothRuntime이 인터페이스 경유로 동작 (컴파일/실행 검증) |
| **D. AI Boundary** | IAiAgentClient + Mock 스트리밍 | ✅ AI NPC 클릭 → 토큰 단위 Mock 응답 로그 |

## 검증 완료 (추가) — Web 빌드 로컬 검증

- ✅ **브라우저(WebGL) 2탭 ↔ 에디터 서버, ws://127.0.0.1:7777 멀티플레이 동작 확인**
- ✅ Booth Runtime / AI Mock도 WebGL에서 동작 (Awaitable 교체 후)
- Week 1 관문의 로컬 절반 통과. 남은 절반: AWS + ALB에서 **wss** (ADR 결정 1의 최종 관문)

### Web 빌드에서 잡은 문제 (재발 방지)

5. **Task.Delay는 WebGL에서 완료되지 않음** → Mock 3종을 `Awaitable.WaitForSecondsAsync`로 교체
6. **런타임 CreatePrimitive 머티리얼이 빌드에서 마젠타** → `Shader.Find("Universal Render Pipeline/Lit")`로 명시 생성
7. **Active Input Handling이 "Input System (New)"만이면 빌드에서 OnGUI(IMGUI)가 렌더링 안 됨** → Player Settings에서 `Both`로 변경 (DevConnectionHud가 IMGUI라서. 정식 UI 전환 시 원복 검토)
8. HUD의 Addr/Port는 **게임 서버 주소(127.0.0.1:7777)** — 웹페이지 http 포트를 넣으면 안 됨

## 알려진 이슈 (신규)

- ~~**플레이어끼리 충돌 없음** — PlayerMovement가 물리 없이 Transform 직접 이동(POC). 정식 이동 구현(CharacterController) 때 처리~~
  → **2026-08-21 해결.** `CharacterController` 이동으로 교체하고 11층 월드 콜리전을 넣었다 (T-167).
  단 **플레이어끼리 충돌은 의도적으로 껐다** — `Player` 레이어(8) 를 만들어 Player↔Player 를 해제했다.
  이동이 client-authoritative 라 서로 밀면 클라이언트마다 결과가 달라 떨리고, 40명 로비에서 통행도 막힌다.
  되돌리려면 충돌 매트릭스에서 다시 켜면 된다.

## 이번 검증에서 잡은 버그 (재발 방지)

1. **Transform Scale 오입력** — "NetworkTransform Scale 동기화 해제"를 Transform 값 0 입력으로 오해 → 캡슐 납작 현상. 프리팹/부모 Scale은 항상 (1,1,1)
2. **런타임 TextMesh 폰트 미지정** — Unity 6는 기본 폰트 자동 지정이 없음 → 글리치 얼룩. `LegacyRuntime.ttf` 명시 지정으로 해결
3. **라벨 스케일 왜곡** — 비균등 스케일 도형의 자식 TextMesh가 찌그러짐 → 역스케일 보정
4. **피벗 중심 묻힘** — primitive 스폰 Y=0이면 절반이 지면 아래 → 스폰 위치에 절반 높이 가산 (Player Y=1, Booth groundLift)

## 알려진 이슈 / 지켜볼 것

- `TaskCanceledException: A task was canceled` — Play 종료 시 Mock의 `Task.Delay`가 끊기며 발생. **무해**. Web 빌드 검증 때 `Awaitable.WaitForSecondsAsync` 교체와 함께 정리 예정
- **Mock `Task.Delay`는 WebGL에서 미동작 가능성** — Web 빌드에서 AI Mock/Booth 로딩이 멈추면 이것부터 의심
- `[EmoteId] Write permissions` 에러가 한때 관측됨 — 이후 재현 안 됨. 다시 나오면 전체 스택 캡처
- 부스 라벨이 뒤에서 보면 거울상 — TextMesh 단면 특성. placeholder라 무시, 정식 프리팹에서 해결

## 다음 우선 작업

1. Web 빌드 → 로컬 브라우저 2개 접속 테스트 (ws://)
2. Linux Dedicated Server 빌드 → Docker 로컬 실행
3. AWS 단일 인스턴스 + ALB + ACM → **HTTPS 페이지에서 wss:// 접속** ← Week 1 성공 판정 기준
4. 실측 결과로 ADR(docs/21) 확정 → constitution.md → SDD(spec-kit) 착수

---

## 2026-08-22 갱신 — 월드 구조 확장 (페스티벌 존)

월드가 11층 단일 방에서 **엘리베이터 게이트 → 11층 → 전환 복도 → 야외 밤 축제** 구조로
확장됐다 (`game` 브랜치, 커밋 `d10cacb`). 11층 방은 부스 구역이 아니며, 축제의 외부 부스
12개가 입구 역할을 하고 상호작용 시 내부 부스 공간으로 이동시키는 플로우가 다음 작업이다.

- 씬 루트: `@World_11F/Corridor_Festival`(복도) · `@Festival`(야시장) · `@Moonlight` · `@MoonSprite`
- 부스 슬롯: `@Festival/Festival_Slots/FestivalSlot_01~12` — 자식 교체로 부스 판매·교체 대응
- 조명: 실내 전용 전제가 깨졌다 — 달빛 디렉셔널 + URP shadowDistance 900. 씬 조명 127개.
  **WebGL 광원 제한 대비 라이트맵 베이크 전환이 배치 확정 후 필수다**
- 통행: 방→축제 전 구간 콜라이더 검증 완료 (8지점 바닥 + 5방향 차단 + 개구부)
- 에셋: 임포트 6팩 798 MB 중 실사용 19 MB 만 `_Project/Art/{World,VFX}` 병합, 벤더 삭제

## 2026-08-21 갱신 — 이 문서의 08-08 기록 중 무효가 된 것

이 아래는 08-08 시점 기록이다. **문장을 지우지 않고** 어긋난 전제만 여기 모아 둔다.

- ❌ **"남은 것: AWS + ALB + wss만"** → **ALB 는 폐기됐다** (#30). IAM 이 없어 ACM 인증서를
  쓸 수 없다. 구조는 **Cloudflare Edge + EC2 Nginx 이중 종료**이고 브라우저는
  `wss://world.<domain>`(443) 만 본다. Nginx 가 7777 로 넘긴다. wss 종단 실측은 **#52**.
- ❌ **"Web 빌드 로컬 서빙: … localhost:8000 고정"** → WebGL 빌드에 **파일명 해시**를
  켰다(`nameFilesAsHashes`, `0641192`). CDN 캐시 정책이 내용 hash 를 전제하는데 파일명이
  고정이면 `immutable` 캐시에 새 릴리스가 묶인다. 로컬 서빙 절차 자체는 그대로다.
- ⚠ **POC D "AI NPC 클릭 → Mock 응답"** 은 **에디터에서만 동작했다.** `OnMouseDown` 은
  Unity 6 WebGL 에서 발생하지 않는다 (T-166). 08-21 에 클릭·호버를 전부
  `BoothInteractionInput` 중앙 디스패처로 옮겼고 프로젝트에 `OnMouse*` 가 0개다 (T-176).
  이제 WebGL 에서도 동작한다.
- ⚠ **"부스 라벨 거울상 / placeholder"** 는 유효하지만, 부스 파츠는 그 뒤 Stand Expo Pack
  실프리팹으로 교체됐다.

## 2026-08-21 현재 알려진 이슈

- 🔴 **허공 스폰 수정이 실기 검증 대기다 (T-177).** 이동 권위가 Owner 라 서버 배정 위치가
  스폰 직후 경합에서 밀리면 원점(방 밖)에서 낙하한다. Owner 가 `ServerSpawnPosition`
  NetworkVariable 로 스스로 텔레포트하도록 고쳤다. **서버·클라를 같은 빌드로** 띄워
  확인해야 한다(NetworkVariable 추가 = 직렬화 변경).
- 🔴 **`BoothSlot_7` 이 방 밖 `z=-405` 에 있다**(미커밋). 그대로면 런타임 부스가 월드 밖에
  생겨 노트북·AI 상호작용에 접근할 수 없다.
- **플레이어끼리 충돌은 의도적으로 껐다** (위 항목 참조). 되돌리려면 `Player` 레이어(8)
  충돌 매트릭스를 다시 켠다.
- **모델 재임포트 후에는 조명 재적용이 필요하다** — 천장 머티리얼 슬롯이 1칸 빠진다(109→108).
  `Festa/World/조명만 다시 적용 (콜라이더 제외)`. 콜라이더는 애셋 내장이라 무관하다.
- **원본 `.skp` 에 같은 이름 메시가 2개다**(`함께가요 미래로!`) — `Identifier uniqueness violation`
  과 `SketchUpImporter generated inconsistent result` 경고의 원인이다. 후처리기를 꺼도 나오므로
  **SketchUp 에서 하나를 개명해야만** 사라진다. 두 메시가 동일 지오메트리라 실질 영향은 낮다.

## 2026-08-21 검증 완료 (추가)

| 항목 | 결과 |
|---|---|
| 월드 콜리전 밀폐 | 스폰에서 flood-fill 도달 8,207칸 / **방 경계 밖 0칸**. 바닥 커버리지 1,806/1,806 |
| 스폰 40슬롯 | 지오메트리 겹침 0/40, 전부 접지(y 0.1837), 아바타 최소 중심거리 5.00 (지름 4.4) |
| 스폰 안정성 | 서로 다른 `clientId` 로 재접속 반복 → 전부 같은 슬롯·좌표 (T-175) |
| 모델 최적화 | 오브젝트 2043→156, 렌더러 1425→138, 인스턴싱 42/42, 정점버퍼 41→37.2 MB |
| spec 016 | WebGL 빌드 실제 클릭으로 왕복 검증 통과 |
