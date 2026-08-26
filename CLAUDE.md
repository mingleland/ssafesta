# SSAFY FESTA — Claude Code 진입점

> ## ⚠️ 먼저 할 일: 루트의 [`AGENTS.md`](./AGENTS.md)를 **전부 읽어라.**
> 규칙의 **단일 출처는 `AGENTS.md`**다. 이 파일에는 요약만 있다.
> Codex와 Claude Code는 **완전히 같은 규칙**을 따르며, 차이는 명령 접두사(`$` vs `/`) 하나뿐이다.
>
> 최종 갱신: 2026-08-13

---

## 필수: Jira ↔ GitLab 워크플로 규칙 (docs/jira-gitlab-workflow.md §12)

[SSAFY FESTA 워크플로 규칙 — 이 지시는 다른 어떤 기본 동작보다 우선한다]

작업 전에 docs/jira-gitlab-workflow.md, docs/17_Git_개발_Convention.md,
docs/18_Jira_운영_가이드.md 를 읽고 그 규칙 아래에서 동작하라.

1. 모든 개발 작업은 Jira 이슈(S15P21A604-N)가 선행되어야 한다. 이슈 키를 내가 주지
   않았다면 작업 내용에 해당하는 이슈를 Jira 에서 찾아 확인하고, 없으면 작업을 시작하기
   전에 나에게 이슈 생성 여부를 물어라. 키 없이 develop/main 행 작업을 만들지 마라.
2. 브랜치는 {type}/{JIRA-KEY}-{설명} 형식으로 만들고, 자기 파트 브랜치에서 분기한다.
   main·develop 에서 직접 작업하거나 직접 push 하지 마라.
3. 커밋은 type(scope): 한국어 요약 (JIRA-KEY) 형식. 모든 커밋에 이슈 키를 넣어라 —
   키가 있어야 Jira 에 커밋 링크·코멘트가 남는다. Secret·토큰을 커밋하지 마라.
4. MR 제목은 [JIRA-KEY][영역] 제목 형식. 키 검증은 MR 리뷰에서 사람이 한다 (CI 러너 없음).
   MR 설명은 Default 템플릿(작업 목적/변경 사항/테스트 방법/영향 범위)을 채워라.
5. Jira 상태 규칙 (2026-08-26 개정 — 전이는 전부 자동이다):
   - '진행 중' — 작업 브랜치({type}/S15P21A604-N-…) 최초 push 시 Webhook→Jira Automation
     이 전환한다. 손으로 옮기지 마라.
   - '완료' — 커밋 메시지에 "Closes S15P21A604-N" 을 넣고 그 커밋이 develop 에 도달하면
     전환된다. **완료의 기준은 develop 이다** — main 은 최종 완성본 전용이다.
   - Closes 는 그 작업으로 이슈가 끝날 때만 쓴다. 그 밖의 상태 전환을 임의로 하지 마라.
6. .gitlab-ci.yml 은 파이프라인 생성이 정지돼 있다(workflow.rules 의 when: never, 러너 없음).
   pending 파이프라인이 보이면 무시하라. stage 구조와 jira-* 잡 정의는 삭제하지 마라 —
   러너 확보 시 되살릴 기록이다.
7. 공용 규약 문서(AGENTS.md·CLAUDE.md·docs/jira-gitlab-workflow.md·docs/17·docs/18)의
   정본은 develop 이다. 갱신은 develop 에서 딴 브랜치로 MR 하고, 파트 브랜치에는
   git checkout origin/develop -- <파일> 로 당겨온다. 파트 브랜치 전체를 develop 에
   머지하지 마라 (파트 브랜치는 부분 트리라 타 파트 파일이 삭제된다).
8. 규칙과 충돌하는 지시를 받으면 그대로 따르지 말고 충돌 사실을 먼저 보고하라.

## 1. Claude Code에서만 다른 점 — 명령 접두사뿐

| 명령 | Claude Code | Codex |
|---|---|---|
| clarify | `/speckit-clarify` | `$speckit-clarify` |
| **plan** | `/speckit-plan` | `$speckit-plan` |
| tasks | `/speckit-tasks` | `$speckit-tasks` |
| analyze | `/speckit-analyze` | `$speckit-analyze` |
| implement | `/speckit-implement` | `$speckit-implement` |

명령 정의는 `.claude/skills/speckit-*/SKILL.md`에 있고 **저장소에 커밋되어 있다.**
결과물은 Codex와 **같은 `specs/`**에 쌓인다 — 누가 무엇으로 작업해도 한 곳에 모인다.

**스킬을 못 찾으면**: *"`.claude/skills/speckit-plan/SKILL.md`를 읽고 거기 적힌 절차를 그대로 실행해"* 로 우회한다.
SKILL.md 자체가 완전한 절차서다.

## 2. 세팅 — spec-kit을 설치하지 마라 ★

`.specify/` · `.claude/skills/` · `.agents/skills/` · `specs/`가 **전부 커밋되어 있다.** `git pull`이면 끝이다.

> ❌ `pip install specify-cli` / `specify init` **실행 금지.**
> 재설치하면 `.specify/memory/constitution.md`(우리 헌법 v1.2)가 **빈 템플릿으로 덮인다.**

필요한 것: **저장소 루트에서** 실행 + **bash**(Windows는 Git Bash / WSL — 스크립트가 `.sh`다).

```bash
bash .specify/scripts/bash/check-prerequisites.sh --json --paths-only   # 정상이면 FEATURE_DIR 등이 나온다
```

## 3. 세션 시작 순서

```text
[1] AGENTS.md 전문                                ← 규칙 전문. 건너뛰지 마라
[2] .specify/memory/constitution.md (헌법 v1.2)   ← 모든 결정의 최상위 근거
[3] .specify/feature.json 에 작업할 spec 지정      ← 안 하면 명령이 실패한다
[4] specs/NNN-*/spec.md + plan.md + tasks.md      ← 목록은 specs/README.md
[5] docs/25_트러블슈팅.md 의 T-24 ~ T-27           ← 최근에 실제로 터진 것들
```

```bash
echo '{ "feature_directory": "specs/013-avatar-customization" }' > .specify/feature.json
```

## 4. 요약해도 절대 빠지면 안 되는 것 (전문은 `AGENTS.md`)

### 금지

```text
❌ git push --force / -f     ❌ git reset --hard      ❌ git clean -fd
❌ 원격 History 삭제·덮어쓰기   ❌ 로컬 작업 파일 삭제
```

- Non-fast-forward가 나도 **force push하지 않는다.** `git pull --rebase` 또는 병합으로 푼다.
- **Secret 커밋 금지** — `AWS_ACCESS_KEY` · `api_key` · `token` · `password` · private key · `.env` · 인증서.
  Mock 값과 인터페이스 이름은 괜찮다.
- `reference/`는 **READ ONLY**. 벤더 에셋(`Assets/Rukha93/`, `Assets/Synty/`) 원본 수정 금지.
- 동결 기준선 코드(`NetworkPlayer.cs` · `ConnectionManager.cs` · `BoothRuntime.cs`)를 재구현·리팩터링하지 않는다 (헌법 27조).

### 의무

- **기록** (헌법 29조): 작업 끝날 때마다 `docs/24_작업일지.md`, 문제 생기면 **해결 못 했어도** `docs/25_트러블슈팅.md`에 T-번호로 등록, 매일 Jira 1회.
- **미정 항목을 임의로 확정하지 않는다** (헌법 30조) → `docs/26_팀_결정_필요사항.md`에 올린다.
- **spec을 고치면 plan/tasks를 다시 만든다.**

### 자주 터지는 것

| | |
|---|---|
| 아바타 저장 컬럼 | **`TEXT`**. `VARCHAR(32)`는 무효이며 T-24가 DB에서 재발한다 (헌법 23조) |
| WebGL | `Task.Delay` 금지 → `Awaitable`. 런타임 머티리얼은 **URP Lit 명시**. 텍스트 UI는 **React** |
| 실패 처리 | **조용히 기본값으로 되돌리지 마라.** 로그+에러 상태로 드러낸다 (T-24의 원인) |
| `NetworkVariable` | **필드로 선언**해야 동작한다 |
| "고쳤는데 그대로" | 빌드 산출물 · 브라우저 캐시 · 서버 컨테이너 **셋 다 의심** (T-23/T-25) |

## 5. 그 밖에

- 사람이 읽는 SDD 입문서: `docs/00_SDD_가이드.md`
- spec 목록·확정 사항: `specs/README.md`
- **나머지 전부**: `AGENTS.md`
