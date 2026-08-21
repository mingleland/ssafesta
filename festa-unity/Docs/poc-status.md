# POC 진행 현황

> 마지막 갱신: 2026-08-08 (Docker 검증 완료)

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
