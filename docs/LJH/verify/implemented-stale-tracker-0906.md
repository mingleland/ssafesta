# IMPLEMENTED_STALE_TRACKER 후보 — 2026-09-06 실측

> ASC 관제센터 전달용 evidence. **SSAFY FESTA 구현 작업과 섞지 않는다** — 이 문서는 ASC 판정 로직
> 개선의 입력이지 FE 백로그가 아니다.
>
> 배경: 09-06 세션에서 `asc proceed --work <KEY>` 가 work-binding 결함으로 실패해 WORK_STATE 판정이
> 돌지 않았다. 그 판정이 있었다면 아래 3건은 자동으로 잡혔어야 한다. 사람이 직접 대조해서 찾았다.

## 판정 방법

`git log origin/develop --grep=<KEY>` 로 develop 도달 여부를 보고, 코드 실물을 `git show origin/develop:<path>`
로 확인한 뒤 JAM live 의 `statusCategory` 와 대조했다. **tracker state ≠ execution reality** 를 기본 가정으로 뒀다.

## 후보 3건

| Jira | 요약 | tracker | 실행 현실 | 근거 |
|---|---|---|---|---|
| `S15P21A604-429` | [FE] World Preparing UX | `진행 중`(indeterminate) | **develop 도달·구현 완료** | `a1270cc2` 09-05 19:52. `events.ts:81·109·128` + `UnityHost.tsx:14·88·142·152` |
| `S15P21A604-428` | [FE] Overlay 닫은 뒤 canvas focus 복구 | `진행 중` | **develop 도달·구현 완료** | `bc7c7512` 09-05 18:06. `OverlayFrame.tsx:37-47` |
| `S15P21A604-421` | [FE] UnityHost canvas id·tabIndex | `진행 중` | **develop 도달·구현 완료** | `UnityHost.tsx:138` `<canvas id="unity-canvas" tabIndex={-1}>` |

## 왜 tracker 가 낡았나 — 결함이 아니라 규약의 결과다

세 건 모두 **의도적으로 `Closes` 를 쓰지 않았다.** 팀 규약상 완료 조건이 실 WebGL·컨테이너·실서버
acceptance 이고 그것이 아직 안 끝났기 때문이다(`docs/jira-gitlab-workflow.md` §2, 로컬 규약 21항).
즉 **"코드가 develop 에 있다" 와 "완료다" 가 이 팀에서는 다른 사건**이다.

따라서 ASC 가 `IMPLEMENTED_STALE_TRACKER` 를 낼 때 이 구분이 필요하다:

```text
IMPLEMENTED_STALE_TRACKER        tracker 가 실수로 뒤처진 것 — 상태 정정이 남은 액션
IMPLEMENTED_PENDING_ACCEPTANCE   코드는 도달했고 tracker 도 의도적으로 열려 있는 것 — 남은 액션은 검증이지 정정이 아니다
```

**이 3건은 후자다.** 전자로 판정해 "상태 정정" 을 권하면 팀 규약을 어기게 만든다 — 실 acceptance 없이
Done 으로 옮기는 것이 정확히 규약이 막는 행위다.

## 실제로 사고가 될 뻔한 사례 — #129

같은 낡음이 **타 파트 판단을 흔들었다.** 게임 파트가 09-05 21:28 에 "develop 에서 `onWorldLoadStart`
수신부가 grep 되지 않는다, 다른 이름이면 jslib 을 맞추겠다" 고 적었는데, FE 구현은 **19:52 에 이미
머지돼 있었다**(1시간 36분 앞). 그대로 뒀으면 Unity 가 이름을 바꿔 **맞는 배선이 어긋났을 것**이다.
09-06 에 실물 근거를 붙여 #129 에 정정했다.

시사점: tracker 낡음은 내부 위생 문제로 끝나지 않는다. **다른 파트가 그 상태를 근거로 자기 코드를
바꾸려 할 때 실제 회귀를 만든다.** WORK_STATE 판정이 도는 것이 그래서 값이 있다.

## 이번에 확인된 ASC 쪽 제약 (동결 대상 — 여기서 고치지 않았다)

- `asc proceed --work <KEY>` 실패: *"작업 항목을 읽을 통로가 없다 — Profile bindings 에 작업 항목 provider 를 선언하라"*.
  그런데 `profile.json` 에는 `{ "role": "work", "adapter": "jam", "resource": "S15P21A604" }` 가 **실재**하고,
  monitor pass 는 같은 바인딩으로 `detected 43` 을 낸다. **탐지는 되고 항목 읽기만 실패**한다.
- `canonical.sources` 가 `[]` 라 정본 대조 단계가 통째로 비어 있다(REQ-0001 이 UNDECIDABLE 로 남은 원인).
- `asc host claude probe` 가 `external_write_guard` 를 "미설치" 로 보고하나 `~/.claude/settings.json` 에
  실제로 걸려 있다 — probe 오탐. guard 는 `findManaged` 로 physical session 바인딩을 찾지 못하면
  `exit 0` 하므로, **바인딩 없는 세션에서는 설치돼 있어도 no-op** 이다.
