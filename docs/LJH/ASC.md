# ASC

ASC(Agent Session Control)는 프로젝트 독립적인 별도 Repository로 분리되었다 (2026-08-22).

Canonical:
- 운영모델 (v5.1 동결): `<ASC Repository>/docs/design/operating-model.md`
- C-01 Approval Port 구현 계약: `<ASC Repository>/docs/contracts/C-01_approval-port.md`

로컬 경로: `projects/asc/` (SSAFESTA와 sibling repository).

SSAFESTA는 ASC의 attach 대상 프로젝트 중 하나다.
ASC Core 및 구현 계약의 정본은 SSAFESTA Repository에 두지 않는다.

## 이 저장소에 무엇이 생기는가 — 아무것도 생기지 않는다

기본값인 local scope에서 attach는 **작업 트리에 한 바이트도 만들지 않는다.**
Runtime State는 사용자 소유 공간(`~/.asc/workspaces/<workspace-id>/`)에 살고,
`.gitignore`도 `.git/info/exclude`도 건드리지 않는다.

저장소 안의 `.asc/`는 팀이 명시적으로 `--scope project`로 채택했을 때만 생기며,
그 결정은 팀이 한다. SSAFESTA는 채택하지 않았다.

따라서 **"저장소에 `.asc/`가 없다"는 것으로 부착 여부를 판정할 수 없다.**
지금 붙어 있는지는 런타임에게 묻는다:

```bash
asc setup status --json
```

`runtime.kind`가 `UNRESOLVED`면 이 경로에 붙은 workspace가 없는 것이고,
`REGISTERED`·`LINKED_WORKTREE`면 붙어 있는 것이다.

## Profile 정본

SSAFESTA Profile 정본은 비공개 evidence 저장소가 들고,
이 기계의 사본은 `~/.asc/profiles/<profile-id>/profile.json`에 있다.
공개 ASC 저장소가 담고 다니는 profile은 예시(example)이며 실 프로젝트 설정이 아니다.

## 현재 결합 (2026-09-05 실측)

```text
code authority   GitLab (Profile의 code-primary binding)
canonical        develop
work authority   Jira — JAM 경유
```

Jira/JAM 결합은 **선언은 되어 있으나 감시 채널로는 서지 않는다** —
ASC가 code binding과 work binding을 동시에 선언하면 두 binding이 공통으로 제공하는
capability가 갈려 채널이 만들어지지 않는다. 이 제약이 풀릴 때까지 Profile은
code binding 하나만 선언하고, 감시는 GitLab을 본다.
