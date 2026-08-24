# FESTA Game Studio GitLab 이관·브랜치 정리 기록

> **상태**: GitLab 이관 완료 / Branch Convention 정리 완료
>
> **기준일**: 2026-08-24
>
> **GitLab 정본**: [S15P21A604](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604)
>
> **범위**: Game Studio spec 019, Frontend Web Runtime, 공통 계약 문서, GitHub 원본 Issue·PR 대응

이 문서는 이관 전 계획서가 아니라 실제 GitLab ref를 검사하고 브랜치를 정리한 결과다. 제품 계약은
변경하지 않았으며 GitHub 원본 branch·PR·Issue는 삭제하지 않았다.

## 1. 적용한 최상위 규정

브랜치 판단 순서는 다음과 같다.

1. `AGENTS.md` — 저장소 운영 규칙의 단일 출처
2. `.specify/memory/constitution.md` 10조 — `ai/back/front/game` 파트 CI/CD, `develop` 실사용 통합, Squash Merge
3. `docs/17_Git_개발_Convention.md` §2~§3 — 작업 브랜치는 자기 파트 브랜치에서 분기하고
   `<type>/<jira-key>-<short-description>` 또는 Jira Key가 없을 때 `<type>/<short-description>`의 영문
   kebab-case 사용

따라서 도구 이름인 `codex/`는 팀 브랜치 유형으로 사용하지 않는다. Game Studio 코드는 `front`에서
분기한 `feature/` 브랜치, 공통 계약 문서는 `develop` 대상 `docs/` 브랜치로 관리한다.

## 2. GitLab 기준선 실측

Git transport로 GitLab의 모든 branch ref와 기본 HEAD를 직접 조회했다.

| 역할 | GitLab ref | 확인 SHA | 처리 |
|---|---|---|---|
| 기본·배포 | `main` | `21eefb8` | 보존, 직접 push 금지 |
| 통합 | `develop` | `429985d` | 보존, 문서 MR 대상 |
| AI | `ai` | `e3133cc` | 보존 |
| Backend | `back` | `0304230` | 보존 |
| Frontend | `front` | `e92aeb0` | 보존, Game Studio 코드 MR 대상 |
| Unity | `game` | `b192fc7` | 보존 |
| 동결 기준 | `v0.0.1-poc` | peeled commit `4b1b675` | 보존, 재발급 금지 |

GitLab HEAD는 `main`을 가리킨다. 위 6개 공통·파트 브랜치와 tag는 정리 대상에서 제외했다.

## 3. Game Studio 최종 브랜치

| 역할 | 최종 GitLab branch | 기준 SHA | MR 대상 | 설명 |
|---|---|---|---|---|
| 코드 | `feature/game-studio-web-runtime` | `1f169f9` | `front` | 기존 Published Runtime → Maker → Local Publish → Portal fix 전체를 포함 |
| 문서 | `docs/game-studio-contract-status` | `91db12f` 이후 | `develop` | Portal·Session 계약, GitLab 인수인계, 작업일지·트러블슈팅 |

코드 최종 SHA에는 기존 4단 작업 브랜치의 커밋이 모두 선형으로 포함된다. 중간 branch ref를 없애도
커밋과 최종 diff는 `feature/game-studio-web-runtime`에서 보존된다. 문서 브랜치는 이미 `develop`에
Squash Merge된 PR #53 위에 후속 계약·이슈 상태만 더한다.

## 4. 제거한 GitLab 브랜치

### 이미 파트·통합 브랜치에 포함된 브랜치

- `codex/game-studio-authoring-shell` — `front`의 ancestor
- `codex/game-studio-web-runtime-core` — `front`의 ancestor
- `codex/spec-007-develop-pr` — `develop`의 ancestor
- `docs/005-contract-fixes` — `develop`의 ancestor
- `docs/59-spec-canonicalization` — `develop`에 patch-equivalent 반영
- `docs/game-studio-contract-2026-08-24` — `back`의 ancestor
- `docs/issue-agreements-sync` — `develop`의 ancestor
- `docs/journal-2026-08-23` — `back`의 ancestor
- `feature/booth-slot-published-layout` — `back`의 ancestor
- `feature/error-field-and-facade-palette` — `back`의 ancestor

### 최종 브랜치로 통합한 중간·구명칭 브랜치

- `codex/game-studio-published-runtime-shell`
- `codex/game-studio-maker-redesign`
- `codex/game-studio-local-publish-loop`
- `codex/game-studio-portal-contract-fix`
- `codex/game-studio-docs-sync`
- `codex/game-studio-issue-cleanup`
- `feature/game-studio-foundation`

삭제는 새 코드·문서 ref를 먼저 push하고 SHA를 확인한 뒤 수행했다. force push, history rewrite,
`git reset --hard`, `git clean`, tag 삭제는 하지 않았다.

## 5. GitHub 원본 PR 대응

아래 이름은 이력 식별용이며 GitLab의 현재 브랜치명이 아니다.

| GitHub PR | 과거 Head | GitLab 현재 대응 |
|---:|---|---|
| [#72](https://github.com/kanghyunsoon/ssafesta/pull/72) | `codex/game-studio-published-runtime-shell` | `feature/game-studio-web-runtime`에 포함 |
| [#79](https://github.com/kanghyunsoon/ssafesta/pull/79) | `codex/game-studio-maker-redesign` | `feature/game-studio-web-runtime`에 포함 |
| [#80](https://github.com/kanghyunsoon/ssafesta/pull/80) | `codex/game-studio-local-publish-loop` | `feature/game-studio-web-runtime`에 포함 |
| [#82](https://github.com/kanghyunsoon/ssafesta/pull/82) | `codex/game-studio-portal-contract-fix` | `feature/game-studio-web-runtime` tip |
| [#53](https://github.com/kanghyunsoon/ssafesta/pull/53) | `codex/game-studio-docs-sync` | `develop`에 Squash Merge, 후속은 `docs/game-studio-contract-status` |

GitLab에서는 코드 branch 하나를 `front` 대상으로, 문서 branch 하나를 `develop` 대상으로 MR한다.
팀 규정에 따라 두 MR 모두 Squash Merge하고 완료 뒤 source branch를 삭제한다.

## 6. Issue 인수인계

| GitHub 원본 | 담당 | GitLab에서 유지할 완료 조건 |
|---|---|---|
| [#48 Draft/Publish API](https://github.com/kanghyunsoon/ssafesta/issues/48) | `strdeok` | Spring endpoint·DB migration·Published Query |
| [#55 Published Runtime](https://github.com/kanghyunsoon/ssafesta/issues/55) | `ghKim`, `colosair` | 실제 Published API·Asset resolver browser E2E |
| [#56 GAME_PORTAL](https://github.com/kanghyunsoon/ssafesta/issues/56) | `strdeok`, `ghKim`, `colosair` | Binding/resolver/whitelist·Unity 진입·browser E2E |
| [#69 사용자 Asset](https://github.com/kanghyunsoon/ssafesta/issues/69) | `strdeok`, `ghKim`, `colosair` | stable `asset://` 업로드·검사·보존·Published resolve |
| [#73 사용성·성능](https://github.com/kanghyunsoon/ssafesta/issues/73) | `ghKim`, `colosair` | 사람 5명/20분 테스트·활성 PC 탭 FPS |
| [#78 GameProject v1.1](https://github.com/kanghyunsoon/ssafesta/issues/78) | `strdeok`, `ghKim`, `colosair` | BE validator·AI 허용 출력·API E2E |
| [#81 GameSession·Coin](https://github.com/kanghyunsoon/ssafesta/issues/81) | `strdeok`, `ghKim`, `colosair` | 서버 권위 가격·차감·idempotency·세션 정책 |

GitLab Issue/MR 본문에서는 GitHub `#번호`를 그대로 쓰지 않고 위 원본 URL 또는 실제 GitLab 번호를
사용한다. 완료 근거가 없는 항목은 이관만으로 닫지 않는다.

## 7. 검증 결과

- GitLab remote branch 23개를 fetch해 commit graph와 ancestor를 확인했다.
- 열린 MR ref 패턴 `refs/merge-requests/*/{head,merge}`는 Git transport에서 광고되지 않았다.
- 최종 코드 branch는 `front` 대비 기존 4단 코드 작업과 Portal fix를 모두 포함한다.
- 최종 문서 branch는 `develop` 기준 후속 2개 문서 커밋을 보존한 상태에서 이 문서 갱신을 추가한다.
- GitHub 원격과 바탕화면 Unity 작업트리는 변경하지 않았다.
- `main/develop/ai/back/front/game`과 `v0.0.1-poc`는 삭제·이동하지 않았다.

## 8. 운영자가 GitLab UI에서 확인할 항목

SSO 계정 권한이 필요한 설정은 Git ref 검사로 대신 확정하지 않는다.

- `main/develop/ai/back/front/game` Branch Rule과 force push 금지
- MR Squash 기본값·approval 수
- Jenkins Webhook과 CI/CD Variable scope
- 이관된 Issue의 assignee·label·attachment 작성자 매핑
- `feature/game-studio-web-runtime → front`, `docs/game-studio-contract-status → develop` MR 생성 여부

## 9. 정리 범위 밖

- GitHub branch·PR·Issue 삭제 또는 archive
- `main/develop/ai/back/front/game` push·삭제
- tag 삭제·재발급
- Git history rewrite와 force push
- Jenkins credential·Secret 값 조회 또는 변경
- 공유 Unity 작업트리와 `festa-unity/**` 수정
