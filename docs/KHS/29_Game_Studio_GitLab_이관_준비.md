# FESTA Game Studio GitLab 이관 준비

> **상태**: 이관 전 정리 완료 / 실제 GitLab Project 생성·Import·Remote 추가·Push는 수행하지 않음
>
> **기준일**: 2026-08-24
>
> **범위**: Game Studio spec 019, Frontend stacked PR, 관련 GitHub Issue와 운영 연결 계약

이 문서는 GitHub에서 진행 중인 Game Studio 작업을 잃지 않고 GitLab으로 넘기기 위한 인수인계 기준이다.
제품 계약을 바꾸지 않으며, 이 문서만으로 이관을 자동 실행하지 않는다.

## 1. 결론

- **권장 경로**는 GitHub 저장소 Import다. Git branch/tag뿐 아니라 Issue, Pull Request, comment, label,
  milestone 등 협업 이력을 함께 옮길 수 있다.
- 현재 Game Studio는 3단 stacked PR이므로, 가장 안전한 순서는 **GitHub에서 #72 → #79 → #80을
  Squash Merge한 뒤 Import**하는 것이다.
- GitHub에서 병합할 시간이 없다면 모든 source branch를 먼저 Import하고 GitLab에서 아래 대응표와 같은
  base를 가진 MR을 다시 만든다. 중간 branch를 삭제하거나 한 번에 `front`로 합치지 않는다.
- 현재 worktree의 `origin`은 GitHub가 아니라 `C:\Users\SSAFY\Desktop\SSAFESTA` 로컬 저장소다.
  **`origin`을 이관 원본이나 목적지로 사용하지 않는다.** 정본 원격 별칭은 `github`이고 URL은
  `https://github.com/kanghyunsoon/ssafesta.git`이다.
- 현재 worktree에서 `git push --mirror`를 실행하지 않는다. 로컬 backup ref와 공유 object database의
  임시 객체를 이관 범위로 오인할 수 있으므로 GitHub Import 또는 별도 fresh clone을 사용한다.

공식 근거:

- [GitHub에서 GitLab으로 Project Import](https://docs.gitlab.com/user/project/import/github/)
- [GitLab Repository Mirroring](https://docs.gitlab.com/user/project/repository/mirror/)
- [GitLab Branch Rules·Protected Branch](https://docs.gitlab.com/user/project/repository/branches/protected/)
- [GitLab CI/CD Variables](https://docs.gitlab.com/ci/variables/)

## 2. 이관 기준 Ref

### 공통·파트 기준선

| 역할 | GitHub ref | 2026-08-24 확인 SHA | 비고 |
|---|---|---|---|
| 문서 정본 | `develop` | `5e390137c58316f5bffbdbd2f50eca27d480ce3e` | spec·공유 문서 병합 대상 |
| Frontend 파트 | `front` | `5022335c41da3d5788f65dbb0f54e3617a23471a` | Game Studio 코드 PR의 최초 base |
| 동결 기준선 tag | `v0.0.1-poc` | Import 후 동일 tag 존재 확인 | 삭제·재발급 금지 |

### Game Studio Pull Request 체인

| 순서 | GitHub PR | Base → Head | 확인된 Head SHA | 현재 상태 |
|---:|---|---|---|---|
| 1 | [#72](https://github.com/kanghyunsoon/ssafesta/pull/72) | `front` → `codex/game-studio-published-runtime-shell` | `7786ed651444bece6d318641ab086a7da7b1b7bb` | OPEN, CLEAN/MERGEABLE |
| 2 | [#79](https://github.com/kanghyunsoon/ssafesta/pull/79) | `codex/game-studio-published-runtime-shell` → `codex/game-studio-maker-redesign` | `3f695b57162a79e00dcb9be226f0ea353a65c2e7` | OPEN, CLEAN/MERGEABLE |
| 3 | [#80](https://github.com/kanghyunsoon/ssafesta/pull/80) | `codex/game-studio-maker-redesign` → `codex/game-studio-local-publish-loop` | `02a1d76a1c9cec7c65ca9ef8ef9c98532cfd61b1` | OPEN, CLEAN/MERGEABLE |
| 문서 | [#53](https://github.com/kanghyunsoon/ssafesta/pull/53) | `develop` → `codex/game-studio-docs-sync` | 이 문서가 포함된 원격 최신 Head | OPEN, CLEAN/MERGEABLE |

PR #79는 #72 병합 뒤 base를 `front`로, PR #80은 #79 병합 뒤 base를 `front`로 바꿔 최종 diff를
확인한다. GitLab에서 그대로 이어갈 경우에는 이 순서를 바꾸지 않고 각각 MR을 재생성한다.

## 3. GitHub Issue 인수인계표

GitLab은 Issue에는 `#`, Merge Request에는 `!`를 사용한다. GitHub Import 후 기존 문서의 `#번호`가
MR을 자동으로 가리킨다고 가정하지 말고 아래 원본 URL을 MR·Issue 설명에 남긴다.

| GitHub | 담당 | 이관 시 상태와 다음 행동 |
|---|---|---|
| [#48 Draft/Publish API](https://github.com/kanghyunsoon/ssafesta/issues/48) | `strdeok` | OPEN 유지. Spring Draft/Publish/Published Query 구현과 DB migration을 GitLab Issue로 확인 |
| [#55 Published Web Runtime](https://github.com/kanghyunsoon/ssafesta/issues/55) | `ghkim1632`, `colosair` | OPEN 유지. #48 응답과 실제 Asset resolver를 사용한 browser E2E 추가 |
| [#56 GAME_PORTAL Binding](https://github.com/kanghyunsoon/ssafesta/issues/56) | `strdeok`, `ghkim1632`, `colosair` | OPEN 유지. Unity는 진입 trigger만, React가 웹 Runtime을 열도록 수직 연결 |
| [#69 사용자 Asset](https://github.com/kanghyunsoon/ssafesta/issues/69) | `strdeok`, `ghkim1632`, `colosair` | OPEN 유지. stable `asset://` 업로드·검사·보존·Published resolver 연결 |
| [#73 사용성·성능](https://github.com/kanghyunsoon/ssafesta/issues/73) | `ghkim1632`, `colosair` | OPEN 유지. 사람 5명/20분 테스트와 활성 PC 탭 FPS 증거 수집 |
| [#78 GameProject v1.1](https://github.com/kanghyunsoon/ssafesta/issues/78) | `strdeok`, `ghkim1632`, `colosair` | OPEN 유지. FE candidate를 BE validator·AI 허용 출력 계약으로 승인 |
| [#81 GameSession·Coin](https://github.com/kanghyunsoon/ssafesta/issues/81) | `strdeok`, `ghkim1632`, `colosair` | OPEN 유지. 가격·차감·idempotency를 서버 권위 계약으로 확정 |

Import 직후에는 각 항목의 assignee, label(`front`, `back`), comment, attachment, 원본 작성자를 표와 대조한다.
사용자 email mapping이 맞지 않으면 작성자·assignee가 Import 실행자로 치환될 수 있으므로 팀원 계정 연결을
먼저 확인한다.

## 4. Repository 실측 결과

2026-08-24 현재 GitHub remote를 fetch한 뒤 확인한 값이다.

| 항목 | 결과 | 이관 판단 |
|---|---:|---|
| GitHub remote branch | 23개 | Import 후 동일 ref 수와 핵심 branch SHA를 재확인 |
| Git tag | 1개 | `v0.0.1-poc` 보존 |
| 현재 문서 기준 tracked files | 1,867개 | Import 전후 기본 file tree 비교 |
| Git object connectivity | `git fsck --connectivity-only` exit 0 | 참조된 commit/tree/blob 손상 없음 |
| Submodule | 없음 | 별도 submodule credential 이관 불필요 |
| Git LFS pointer | 없음 | 현재는 일반 Git blob으로 Unity Asset을 보관 |
| Pack size | 약 630.46 MiB | GitLab Project/Import 용량 제한 사전 확인 |
| 가장 큰 tracked blob | 약 11.88 MiB FBX | 10 MiB 이상 FBX 3개가 있으므로 push 제한 확인 |
| 저장소 내 CI 정의 | `.gitlab-ci.yml`, `Jenkinsfile`, GitHub Workflow 없음 | Pipeline은 저장소 Import만으로 복구되지 않음 |
| Docker 진입점 | `backend/compose.yaml`, `festa-unity/Docker/Dockerfile` | Import 후 경로·대소문자 유지 확인 |

공유 worktree object store에는 가상 병합 검사에서 생긴 dangling/temporary object가 있지만 connectivity는
정상이다. 이 객체들은 GitHub ref에 포함되지 않으므로 **삭제 작업을 이관 준비 단계에서 하지 않고**, 원격
GitHub Import 또는 fresh clone으로 격리한다. 현재 작업 저장소에서 `git gc`, `git prune`, 강제 reset을
실행하지 않는다.

## 5. CI/CD·Webhook·Secret 경계

- `docs/15_Infra_AWS_설계서.md`의 현재 결정대로 Jenkins Pipeline은 유지하고 GitHub Webhook만 GitLab
  Webhook으로 전환한다.
- 저장소에 Pipeline 정의 파일이 없으므로 Infra owner가 Jenkins Job/Shared Library/credential을 별도
  인벤토리로 확인해야 한다. Repository Import 완료를 CI 이관 완료로 간주하지 않는다.
- GitHub의 required status checks는 GitLab Import에서 자동 보존된다고 가정하지 않는다. `main`,
  `develop`, `front`, `back`, `ai`, `game`의 Branch Rule과 MR approval, Squash 정책을 수동 대조한다.
- Secret 값은 문서·Git history·명령행에 복사하지 않는다. GitLab에는 UI의 masked/hidden CI/CD Variable,
  Jenkins Credentials 또는 AWS Secret 저장소로 다시 등록하고 key 이름·scope·owner만 체크리스트에 남긴다.
- GitLab remote URL, deploy token, PAT는 이 문서에 적지 않는다.

## 6. 실행 전 체크리스트

### 팀 결정

- [ ] GitLab namespace, project path, visibility, default branch를 확정한다.
- [ ] Import owner와 검증 owner를 서로 다른 사람으로 지정한다.
- [ ] GitHub를 언제 read-only로 전환할지 cutover 시간을 정한다.
- [ ] #72·#79·#80을 GitHub에서 먼저 병합할지, GitLab에서 stacked MR로 이어갈지 결정한다.
- [ ] GitHub Issue/PR Markdown attachment Import 옵션을 켤지 확인한다.
- [ ] GitLab Project 용량 제한이 약 630 MiB pack과 12 MiB 단일 blob을 허용하는지 확인한다.

### Import 직전 동결

- [ ] `github/develop`, `github/front`와 4개 Game Studio branch의 최종 SHA를 이 문서 표와 갱신한다.
- [ ] PR #53·#72·#79·#80이 OPEN/CLEAN인지 또는 병합 완료인지 기록한다.
- [ ] Issue #48·#55·#56·#69·#73·#78·#81의 state/assignee/label을 export한다.
- [ ] `v0.0.1-poc` tag를 확인한다.
- [ ] Frontend Game Studio에서 `npm test`, `npm run build`, `npm run lint`를 통과시킨다.
- [ ] GameProject fixture 7/7, Runtime trace 6/6을 통과시킨다.
- [ ] 이관 시작 뒤 GitHub와 GitLab 양쪽에 동시에 쓰지 않는다.

## 7. Import 후 검증 체크리스트

- [ ] `main`, `develop`, `front`, `back`, `ai`, `game` 및 Game Studio 4개 branch가 존재한다.
- [ ] 핵심 branch SHA와 `v0.0.1-poc` tag가 이관 전 snapshot과 일치한다.
- [ ] GitLab default branch와 Branch Rule이 팀 전략과 일치하며 force push가 금지됐다.
- [ ] PR #53·#72·#79·#80에 대응하는 MR의 base/head/diff가 대응표와 같다.
- [ ] 7개 OPEN Issue의 본문·comment·attachment·label·assignee가 보존됐다.
- [ ] 기존 GitHub `#번호` 링크가 Issue/MR을 잘못 가리키는 곳을 원본 URL 또는 GitLab `!번호`로 고친다.
- [ ] Jenkins Webhook을 GitLab event로 바꾸고 파트 branch push와 develop MR pipeline을 각각 1회 검증한다.
- [ ] CI/CD Secret은 값 노출 없이 protected/environment scope만 검증한다.
- [ ] Frontend 204 tests/build/lint와 Mock `제작 → 저장 → 게시 → /play` browser smoke를 다시 통과시킨다.
- [ ] Backend·Unity·AI가 없는 Mock 범위와 운영 multi-user 범위를 혼동하지 않았는지 릴리스 설명을 확인한다.

## 8. Cutover 완료 조건

다음 조건을 모두 만족하기 전에는 GitHub를 archive하거나 GitLab을 정본으로 선언하지 않는다.

1. branch/tag SHA 검증이 끝났다.
2. OPEN PR/Issue의 GitLab 대응표가 완성됐다.
3. Branch Rule, Squash, approval와 Jenkins Webhook이 검증됐다.
4. Secret이 저장소 밖에서 복구됐고 로그에 노출되지 않았다.
5. Game Studio 자동 검증과 Mock browser smoke가 통과했다.
6. #48·#55·#56·#69·#78·#81은 운영 배포 blocker로 계속 추적된다.

## 9. 이번 정리에서 의도적으로 하지 않은 일

- GitLab Project/Group 생성
- GitLab remote 추가 또는 기존 remote URL 변경
- GitHub/GitLab mirror 설정
- branch/tag push, force push, history rewrite
- GitHub PR/Issue 종료 또는 GitHub 저장소 archive
- local dangling object 삭제, `git gc`, `git prune`
- Jenkins Webhook·credential·CI/CD Variable 변경
