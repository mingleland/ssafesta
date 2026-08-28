# GitHub → GitLab 이관 스크립트 (2026-08-24, 일회성)

`kanghyunsoon/ssafesta` → `s15-metaverse-game-sub1/S15P21A604` 이관에 실제로 쓴 스크립트다.
재실행할 일은 없지만 **무엇을 어떻게 옮겼는지의 근거**로 남긴다.

실행 전 GitHub 덤프를 같은 폴더에 만든다 (`gh` 인증 필요):

```bash
gh api --paginate "repos/kanghyunsoon/ssafesta/issues?state=all&per_page=100&direction=asc" > items.json
gh api --paginate "repos/kanghyunsoon/ssafesta/issues/comments?per_page=100" > comments.json
gh api "repos/kanghyunsoon/ssafesta/labels?per_page=100" > labels.json
# PR 별: pr_<N>.json / pr_<N>_reviews.json / pr_<N>_rcomments.json
```

`--paginate` 는 JSON 배열을 이어 붙여 `][` 가 생기므로 각 스크립트가 `,` 로 치환해 읽는다.

| 스크립트 | 역할 |
|---|---|
| `migrate.js` | 이슈·PR 83 건 생성. **빈 프로젝트의 iid 자동증가로 GitHub 번호를 그대로 보존** — 매 건 iid 를 검증하고 어긋나면 즉시 중단한다. PR 은 `[PR]` 접두사 + `gh:PR` 라벨 |
| `verify.js` | 제목·상태·노트 수·라벨 전수 대조 |
| `fixlinks.js` | 본문·노트의 GitHub URL 재작성 (앵커 없는 이슈/PR → `#N`, blob/tree → GitLab 경로) |
| `prlinks.js` | `[PR]` 이슈에 GitLab 머지 커밋 diff·대상 브랜치·변경 통계 링크 추가 |
| `openmr.js` | 열려 있던 PR 을 실제 MR 로 전환 (**미사용** — 소스 브랜치를 정리해 기록 유지로 결정) |

모두 `GITLAB_TOKEN=<api 스코프> node <스크립트>` 로 실행한다. 토큰은 인자·코드에 넣지 않는다.

## 이관의 구조적 한계 (재논의 방지)

- **작성자는 전부 토큰 소유자로 기록된다.** GitLab REST API 는 생성 주체를 토큰 소유자로
  고정하고, 타인 명의 생성(`sudo`)은 **인스턴스 관리자** 권한이다 — 프로젝트 Owner 로는 안 된다.
  GitLab 공식 GitHub 임포터도 이메일이 일치할 때만 작성자를 살린다. 원작성자는 각 본문·노트
  머리의 `작성: @아이디 · KST` 가 기록이고, **정본 이력은 GitHub 아카이브다 (삭제 금지).**
- **머지된 PR 은 MR 로 재현할 수 없다.** MR 은 소스 브랜치와 diff 가 있어야 만들어지고
  머지 완료 상태는 API 로 만들 수 없다. 머지 커밋 SHA 는 GitLab 히스토리에 그대로 있으므로
  (이관 시점 40/40 확인) `prlinks.js` 의 링크로 변경 내용을 전부 확인할 수 있다.
