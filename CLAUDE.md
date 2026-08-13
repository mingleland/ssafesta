# SSAFY FESTA — AI 세션 공통 규칙

> **규칙의 단일 출처는 저장소 루트의 [`AGENTS.md`](./AGENTS.md)다.**
> 이 파일은 중복해서 적지 않는다. 규칙이 두 곳에 있으면 반드시 어긋난다.

이 저장소는 **Codex 단독 운영**을 기준으로 한다. Claude Code로 작업하는 경우에도
`AGENTS.md`를 **먼저 전부 읽고** 그 규칙을 그대로 따른다.

## Claude Code에서만 다른 점 — 명령 접두사

| Codex | Claude Code |
|---|---|
| `$speckit-plan` | `/speckit-plan` |
| `$speckit-tasks` | `/speckit-tasks` |
| `$speckit-implement` | `/speckit-implement` |

명령 정의는 `.claude/skills/speckit-*/SKILL.md`에 있고 **저장소에 커밋되어 있다.
spec-kit을 따로 설치하지 마라** (`specify init`은 헌법 파일을 덮어쓸 수 있다 — `AGENTS.md` §1).

결과물은 Codex와 **같은 `specs/`**에 쌓인다. 누가 작업해도 한 곳에 모인다.

## 먼저 읽을 것

1. `AGENTS.md` — 전체 규칙
2. `.specify/memory/constitution.md` — 헌법 v1.2
3. `docs/00_SDD_가이드.md` — 사람이 읽는 SDD 입문서

## 최소한 이것만은 (전문은 AGENTS.md)

- `.specify/feature.json`에 작업할 spec을 **먼저 지정**한다. 안 하면 명령이 실패한다.
- `git push --force` / `git reset --hard` / `git clean -fd` **금지**. 로컬 파일 삭제 금지.
- Secret 커밋 금지. `reference/`는 READ ONLY.
- 작업일지(`docs/24`) · 트러블슈팅(`docs/25`) 기록은 **의무**다 (헌법 29조).
- 미정 항목을 **임의로 확정하지 않는다** (헌법 30조).
