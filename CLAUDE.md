# SSAFY FESTA — AI 세션 공통 규칙

이 폴더에서 작업하는 모든 AI 세션(Claude 등)은 아래 규칙을 따른다.

## 필수: 기록 규칙

1. **작업일지** — 하나의 작업(기능 구현, 검증, 문서 작성, 설정 변경)이 끝나면
   `docs/작업자 이니셜/24_작업일지.md`의 **해당 날짜 섹션에 즉시 기록**한다.
   날짜 섹션이 없으면 만든다 (최신 날짜가 위). 👤 사람 / 🤖 AI 구분 표기.
2. **트러블슈팅** — 작업 중 문제가 발생하면 해결 여부와 무관하게
   `docs/작업자 이니셜/25_트러블슈팅.md`에 **T-번호를 따서 반드시 등록**한다 (증상/원인/해결/예방).
   작업일지에는 T-번호로 링크만 남긴다. 이 규칙에 예외는 없다.
3. 세션을 종료하기 전, 위 두 문서가 이번 세션의 작업을 반영하고 있는지 확인한다.

## 필수: 코드 규칙

- **기준선 동결 준수** — `docs/23_기준선_동결_워크플로.md`. 동결된 기준선 코드를
  재구현/리팩터링하지 않는다. 현재 기준선: `v0.0.1-poc` (POC — Multiplayer + Booth Runtime)
- Git/커밋/브랜치 규칙: `docs/17_Git_개발_Convention.md`
- `reference/` 폴더는 READ ONLY. 절대 수정하지 않는다.
- Unity 관련 함정 목록: `docs/작업자 이니셜/25_트러블슈팅.md` — 같은 실수를 반복하지 않는다.
  특히: WebGL에서 Task.Delay 금지(Awaitable 사용), 런타임 TextMesh는 폰트 명시,
  런타임 머티리얼은 URP Lit 명시, NetworkVariable은 필드로 선언.
- Unity 변경은 먼저 Editor Refresh/Compile과 Console Error를 확인한다. 사용자가 명시하지 않은
  Play Mode 전환·WebGL/Linux 빌드는 실행하지 않는다.
- 로컬 시각 QA 산출물(`festa-unity/Assets/Screenshots/`, `design-qa.md`)은 소스 에셋이 아니며
  커밋에도 포함하지 않는다. 이 폴더는 **일회성 산출물이므로 정리 대상에 포함해도 된다** —
  다만 삭제는 다른 파일과 같이 사전 고지·승인을 받는다 (2026-08-20 정리 시 실제 삭제됨).
  이슈·문서의 근거로 남겨야 하는 스크린샷은 `docs/작업자 이니셜/verify/`에 커밋해 보존한다.
- 모듈 외형 `fa` 전체값은 접속 승인용 짧은 preset 필드에 넣지 않는다. 씬 간에는
  `AvatarSceneHandoff`, 멀티플레이에는 `FixedString4096Bytes` 외형 상태를 사용한다.
- **씬·프리팹·.meta 파일은 텍스트로 직접 편집하지 않는다.** `.unity`, `.prefab`, `.asset`,
  `.meta` 는 GUID 참조가 얽힌 YAML이다. 텍스트로 고치면 참조가 끊기고 씬이 열리지 않는다.
  씬/오브젝트/컴포넌트 변경은 **Unity MCP 도구로만** 수행한다. MCP가 응답하지 않으면
  (에디터가 꺼져 있으면) 작업을 멈추고 사용자에게 에디터를 켜달라고 요청한다.
- `festa-unity/Library/`, `Temp/`, `Obj/`, `Build/`, `Logs/` 는 생성물이다. 읽지도 쓰지도 않는다.
- 파일 삭제는 `rm` 이든 `git rm` 이든 실행 전에 무엇을 왜 지우는지 먼저 밝히고 승인을 받는다.
- **에셋 미사용 판정은 GUID 검색으로 끝내지 않는다.** 다음 셋은 GUID 참조가 없어도 사용 중이다.
  1. `Resources/` — 코드가 경로로 로드한다 (`Resources.Load<T>("Avatar/UI/...")`). 폴더를 옮기면
     컴파일은 통과하고 런타임에 조용히 null이 된다.
  2. 이름으로 찾는 셰이더 — `Shader.Find("Festa/Avatar/GarmentTint")` 등.
  3. `ProjectSettings/` 참조 — URP 파이프라인·QualitySettings 품질 티어·Input Actions.
  판정은 `AssetDatabase.GetDependencies`로 하고, 모델 파일에 임베드된 머티리얼→텍스처 링크는
  텍스트 검색으로 보이지 않으므로 반드시 Unity로 확인한다.
- 에셋 이동은 `AssetDatabase.MoveAsset`으로만 한다. 탐색기·`mv`로 옮기면 참조가 끊긴다.
- `Assets/Plugins/WebGL/`은 Unity 규약 폴더다. `.jslib`는 여기 있어야 WebGL 빌드에 포함된다.
- `reference/` 는 READ ONLY. 읽기만 하고 쓰기·이동·삭제를 시도하지 않는다.


## 프로젝트 컨텍스트

- 기획/설계 문서: `docs/00~20`, 착수 전 기술 결정: `docs/21`, 남은 작업: `docs/22`
- Unity 프로젝트: `festa-unity/` (상태: `festa-unity/Docs/poc-status.md`)
- Infra 인수인계: `festa-unity/Docs/deployment-handoff.md`
- 아키텍처 원칙: Booth 정적 오브젝트는 NetworkObject 금지(Local Spawn),
  텍스트 입력 UI는 Unity가 아닌 React 오버레이, Coin/Lease 등 영구 상태는 Spring이 Source of Truth
