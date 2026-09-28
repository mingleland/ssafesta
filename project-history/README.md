# SSAFESTA 개발 이력 보존본

이 디렉터리는 SSAFESTA 를 개발하면서 쓴 세 곳의 기록을 한 저장소 안에 남긴 것이다. 원본 서비스는 모두 닫히거나 비공개라서, 여기와 이 저장소의 Issues·Pull requests 가 이력을 읽을 수 있는 곳이다.

| 원본 | 기간 | 이 저장소에서 보는 곳 |
|---|---|---|
| 최초 GitHub (`kanghyunsoon/ssafesta`, 비공개) | 2026-08-10 ~ 08-25 | Issues·PR **#1~#84** (원래 번호 그대로) |
| SSAFY GitLab (`S15P21A604`) | 2026-08-24 ~ 09-27 | Issues·PR **#85~#1628** (생성 시각 순), [gitlab/](gitlab/) |
| Jira (`S15P21A604`) | 같은 기간 | [jira/](jira/README.md) — 이슈 976건 전부 |

## 번호와 원본 ID

- 원본 ID 와 새 번호의 대응은 [mappings/items.json](mappings/items.json) 에 있다. 모든 이전 항목의 본문 첫머리에도 원본 ID(`GitLab !1013`, `GitLab #269`, `kanghyunsoon/ssafesta#12`)와 원래 작성자·시각을 적었다.
- GitLab 이슈 #1~#83 은 최초 GitHub #1~#83 을 2026-08-24 에 옮긴 사본이라 새 저장소에서는 하나로 합쳤다. 사본에 나중에 달린 댓글은 "GitLab 이관본 추가 댓글" 로 붙어 있다.
- 새 저장소의 PR 작성자·생성 시각·병합 주체는 이전 시점의 이전 계정이다. 원래 값은 본문 머리말에 있다.
- 원본에서 merge commit 으로 병합된 MR·PR 은 실제 병합 커밋을 이용해 **Merged** 로 보인다. squash 로 병합된 것은 원래 head 커밋이 병합 커밋의 조상이 아니어서 커밋을 새로 만들지 않는 한 Merged 가 될 수 없으므로 **Closed** 로 두고 `original: merged` 라벨과 실제 병합·squash 커밋을 본문에 적었다.
- PR 은 원래 대상 브랜치 대신 원래 base 커밋에 고정한 임시 브랜치(`gl-mr/N/base`, `gh-pr/N/base`)를 기준으로 만들어, 당시 diff 를 그대로 보여 준다.

## Git 이력

- GitLab 의 브랜치 227개와 태그 1개를 커밋 SHA 그대로 옮겼다. 기본 브랜치는 GitLab 과 같은 `develop` 이다.
- 최초 GitHub 의 브랜치 27개는 `github-original/*` 로 옮겼다. 최초 GitHub 커밋 707개 중 647개는 GitLab history 에 그대로 있고, 나머지 60개는 그 브랜치와 PR 에서 보인다.
- 삭제된 브랜치의 끝 커밋 중 되살린 10개는 `archive/deleted-branches/*` 에 있다. 서버에서 이미 지워진 5개는 복구하지 못했다.

## Jira 와 코드의 연결

[mappings/jira-links.json](mappings/jira-links.json) 은 Jira 이슈마다 커밋·브랜치·GitLab MR·이슈를 모은 것이다. 근거는 커밋 메시지·브랜치 이름·MR 제목/본문의 이슈 키와 Jira 개발 패널이다. 976건 중 827건이 하나 이상과 이어지고, MR 1363건 중 1340건에 Jira 키가 있다.

## 수량 검증 (2026-09-28 수집)

| 항목 | 원본 서버 | 보존 |
|---|---|---|
| Jira 이슈 / 댓글 / 변경 이력 | 976 / 7562 / 24287 | 976 / 7562 / 24287 |
| Jira 첨부 (개수·바이트) | 2 · 1447197 | 2 · 1447197 |
| GitLab MR / 이슈 | 1363 / 264 | 1363 / 264 |
| GitLab note (MR+이슈) / 이벤트 | — | 7676 / 9288 |
| GitLab 업로드 | 52 | 52 (크기 일치) |
| 최초 GitHub PR / 이슈 / 댓글 | 47 / 37 / 225 | 47 / 37 / 225 |

알려진 공백과 원인은 [manifest.json](manifest.json) 의 `known_gaps` 에 있다.

## 공개본과 원본

여기 있는 파일은 공개용으로 걸러 낸 것이다. 이메일·전화번호는 가렸고, Jira 계정 ID·아바타·웹훅 URL 은 뺐다. 가공 전 API 응답, 첨부, CI package 원본은 `ssafesta-original-private-20260928.tar`(SHA-256 `0a1b2c787146d49b61825c21cd0854aafdf16aebded4b60d4b914d9f728683db`)로 묶어 팀이 비공개로 보관한다.

## 구성

- [jira/](jira/README.md): 이슈별 Markdown(설명·필드·댓글·변경 이력), `issues.jsonl`, 프로젝트·스프린트 메타데이터, 첨부
- [gitlab/](gitlab/): MR·이슈·note·이벤트·파이프라인 JSONL, 라벨, 업로드 이미지
- [github-original/](github-original/): 최초 GitHub 이슈·PR·댓글
- [mappings/](mappings/): 원본 ID ↔ 새 번호, Jira ↔ 코드 연결, MR 병합 분석
- [contributors.json](contributors.json): 팀원별 GitHub·GitLab·Jira 계정
- 이전 도구: [../tools/history-migration/](../tools/history-migration/)
