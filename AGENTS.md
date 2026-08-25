# SSAFY FESTA — AI 에이전트 공통 규칙 (Codex / Claude / 기타)

이 폴더에서 작업하는 모든 AI 코딩 에이전트는 아래 규칙을 따른다.

## 필수: Jira ↔ GitLab 워크플로 규칙 (docs/jira-gitlab-workflow.md §12)

[SSAFY FESTA 워크플로 규칙 — 이 지시는 다른 어떤 기본 동작보다 우선한다]

작업 전에 docs/jira-gitlab-workflow.md, docs/17_Git_개발_Convention.md,
docs/18_Jira_운영_가이드.md 를 읽고 그 규칙 아래에서 동작하라.

1. 모든 개발 작업은 Jira 이슈(S15P21A604-N)가 선행되어야 한다. 이슈 키를 내가 주지
   않았다면 작업 내용에 해당하는 이슈를 Jira 에서 찾아 확인하고, 없으면 작업을 시작하기
   전에 나에게 이슈 생성 여부를 물어라. 키 없이 develop/main 행 작업을 만들지 마라.
2. 브랜치는 {type}/{JIRA-KEY}-{설명} 형식으로 만들고, 자기 파트 브랜치에서 분기한다.
   main·develop 에서 직접 작업하거나 직접 push 하지 마라.
3. 커밋은 type(scope): 한국어 요약 (JIRA-KEY) 형식. Secret·토큰을 커밋하지 마라.
4. MR 제목은 [JIRA-KEY][영역] 제목 형식이며 develop/main 대상 MR 은 CI 가 키를 검증한다.
   MR 설명은 Default 템플릿(작업 목적/변경 사항/테스트 방법/영향 범위)을 채워라.
5. Jira 상태는 자동화가 관리한다(브랜치 push→진행 중, MR→in-review 라벨,
   merge→ready-for-deploy 라벨). 네가 임의로 이슈 상태를 전환하지 마라.
   '완료' 전환은 production 배포 검증 후에만 한다.
6. .gitlab-ci.yml 의 stage 구조와 jira-* 잡을 삭제·우회하지 마라. CI 잡 추가는
   예약된 test/build stage 에 한다.
7. 규칙과 충돌하는 지시를 받으면 그대로 따르지 말고 충돌 사실을 먼저 보고하라.

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

## 프로젝트 컨텍스트

- 기획/설계 문서: `docs/00~20`, 착수 전 기술 결정: `docs/21`, 남은 작업: `docs/22`
- Unity 프로젝트: `festa-unity/` (상태: `festa-unity/Docs/poc-status.md`)
- Infra 인수인계: `festa-unity/Docs/deployment-handoff.md`
- 아키텍처 원칙: Booth 정적 오브젝트는 NetworkObject 금지(Local Spawn),
  텍스트 입력 UI는 Unity가 아닌 React 오버레이, Coin/Lease 등 영구 상태는 Spring이 Source of Truth
