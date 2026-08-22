# SSAFY FESTA — AI 세션 공통 규칙

이 폴더에서 작업하는 모든 AI 세션(Claude 등)은 아래 규칙을 따른다.

## 필수: 기록 규칙

1. **작업일지** — 하나의 작업(기능 구현, 검증, 문서 작성, 설정 변경)이 끝나면
   `docs/{이니셜}/24_작업일지.md`의 **해당 날짜 섹션에 즉시 기록**한다.
   날짜 섹션이 없으면 만든다 (최신 날짜가 위). 👤 사람 / 🤖 AI 구분 표기.
2. **트러블슈팅** — 작업 중 문제가 발생하면 해결 여부와 무관하게
   `docs/{이니셜}/25_트러블슈팅.md`에 **T-번호를 따서 반드시 등록**한다 (증상/원인/해결/예방).
   작업일지에는 T-번호로 링크만 남긴다. 이 규칙에 예외는 없다.
3. 세션을 종료하기 전, 위 두 문서가 이번 세션의 작업을 반영하고 있는지 확인한다.

## 필수: 코드 규칙

- **기준선 동결 준수** — `docs/23_기준선_동결_워크플로.md`. 동결된 기준선 코드를
  재구현/리팩터링하지 않는다. 현재 기준선: `v0.0.1-poc` (POC — Multiplayer + Booth Runtime)
- Git/커밋/브랜치 규칙: `docs/17_Git_개발_Convention.md`
- `reference/` 폴더는 READ ONLY. 절대 수정하지 않는다.
- Unity 관련 함정 목록: `docs/25_트러블슈팅.md` — 같은 실수를 반복하지 않는다.
  특히: WebGL에서 Task.Delay 금지(Awaitable 사용), 런타임 TextMesh는 폰트 명시,
  런타임 머티리얼은 URP Lit 명시, NetworkVariable은 필드로 선언.
- **파일 역할 주석** — 새 구현 파일(설정·스타일 제외) 최상단에 "이 파일이 시스템에서 왜 존재하는가"를
  한 줄로 남긴다. 기준: React/프레임워크 관례를 모르는 사람이 파일명·위치만으로 이 파일의 역할을
  못 알아볼 때만. 이미 있는 WHY 주석(출처·근거)과는 별개로 공존 가능 — 그건 "왜 이렇게 짰나",
  이건 "이 자리가 시스템에서 뭐 하는 자리인가". 예: `main.tsx` → "React 앱을 브라우저 DOM에 최초
  마운트하는 진입점". 대상 아님: package.json·tsconfig·vite.config 등 웹 개발 전반에 보편적인
  설정 파일, index.css.

## 프로젝트 컨텍스트

- 기획/설계 문서: `docs/00~20`, 착수 전 기술 결정: `docs/21`, 남은 작업: `docs/22`
- Unity 프로젝트: `festa-unity/` (상태: `festa-unity/Docs/poc-status.md`)
- Infra 인수인계: `festa-unity/Docs/deployment-handoff.md`
- 아키텍처 원칙: Booth 정적 오브젝트는 NetworkObject 금지(Local Spawn),
  텍스트 입력 UI는 Unity가 아닌 React 오버레이, Coin/Lease 등 영구 상태는 Spring이 Source of Truth
