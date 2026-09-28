"""project-history 의 README·manifest·contributors 를 검증 결과에서 만든다."""
import json
import os
import sys
from datetime import datetime, timezone

ROOT, OUT = sys.argv[1], sys.argv[2]
v = json.load(open(os.path.join(ROOT, "analysis", "phase-a-verification.json"), encoding="utf-8"))
jl = json.load(open(os.path.join(ROOT, "analysis", "jira-links-summary.json"), encoding="utf-8"))
gr = json.load(open(os.path.join(ROOT, "analysis", "git-relation.json"), encoding="utf-8"))
refs = json.load(open(os.path.join(ROOT, "work", "refs-plan.json"), encoding="utf-8"))
mp = json.load(open(os.path.join(ROOT, "work", "mapping.json"), encoding="utf-8"))
tar_sha = open(os.path.join(ROOT, "archive", "ssafesta-original-private-20260928.tar.sha256")).read().split()[0]
people = json.load(open(os.path.join(ROOT, "tools", "people.json"), encoding="utf-8"))
git_names = {
    "kanghyunsoon": ["강형순", "KHS", "kanghyunsoon"], "colosair": ["이정헌", "colosair"], "strdeok": ["황덕", "Deok", "strdeok"],
    "ghkim1632": ["김가현", "ghkim1632", "Gahyeon Kim"], "Alexjung0115": ["정승욱", "Alexjung0115"], "busypark": ["박준우", "busypark"]}
contributors = [{"name": p["name"], "github": p["github"], "gitlab": p["gitlab"], "jira_display_name": p["name"],
                 "git_author_names": git_names[p["github"]]} for p in people]
contributors.append({"name": "(자동화)", "git_author_names": ["Codex", "ci", "Ubuntu", "GitHub"], "note": "도구·CI·서버에서 만든 커밋"})
json.dump(contributors, open(os.path.join(OUT, "contributors.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
kinds = {}
for it in mp["items"]:
    k = f"{it['origin']}|{it['new_kind']}|{it['final_state']}"
    kinds[k] = kinds.get(k, 0) + 1
g, j, h = v["gitlab"], v["jira"], v["github_original"]
manifest = {
    "generated_at": datetime.now(timezone.utc).isoformat(), "captured_at": "2026-09-28",
    "sources": {"gitlab": "lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604 (GitLab 18.11.5 CE)", "jira": "ssafy.atlassian.net project S15P21A604",
                "github_original": "github.com/kanghyunsoon/ssafesta (비공개, 변경하지 않음)"},
    "counts": {"gitlab": {k: g[k] for k in ("merge_requests", "issues", "events", "labels", "milestones", "releases", "branches", "uploads", "pipelines", "jobs", "packages", "commit_notes")},
               "jira": {k: j[k] for k in ("issues_listed", "comments", "changelog_histories", "worklogs", "attachments", "boards", "sprints", "issue_types")},
               "github_original": h, "git": {k: gr[k] for k in ("github_commits", "gitlab_commits", "shared", "github_only")}, "pushed_refs": refs},
    "new_repository_items": {"total": len(mp["items"]), "by_origin_kind_state": kinds},
    "jira_links": jl, "known_gaps": {
        "gitlab_system_notes_hidden_by_server": [m for m in g["x_total_mismatches"] if "related_issues" not in m["dir"]],
        "gitlab_mr_1220_related_issues": "외부(Jira) 연동 이슈 목록이 마지막 페이지에서 끊겨 896건 중 800건만 받음. MR 본문·커밋은 전부 보존",
        "unrecoverable_deleted_branch_commits": [s for s in g["push_event_commits_unrecoverable"] if s not in ("55d97c1f0ba24a7b98e0b82a7f25b1deae6a47af", "6b41f38c3dbb3d8a6fec2cfcb3771937b97a14c5", "9a3726f217389146923989709744cd1629b2e73d")],
        "gitlab_lfs_unidentified_bytes": g["project_statistics"]["lfs_objects_size"] - 8862278,
        "gitlab_project_export": "서버에서 비활성(404). API 원본 덤프와 Git mirror 를 정본으로 사용",
        "jira_official_backup": "사이트 관리자 권한 없음"},
    "private_original_archive": {"file": "ssafesta-original-private-20260928.tar", "sha256": tar_sha,
                                 "note": "가공 전 API 응답·첨부·package 원본. 개인정보·웹훅 URL 등을 포함해 공개하지 않고 팀이 오프라인으로 보관한다."},
}
json.dump(manifest, open(os.path.join(OUT, "manifest.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
readme = f"""# SSAFESTA 개발 이력 보존본

이 디렉터리는 SSAFESTA 를 개발하면서 쓴 세 곳의 기록을 한 저장소 안에 남긴 것이다. 원본 서비스는 모두 닫히거나 비공개라서, 여기와 이 저장소의 Issues·Pull requests 가 이력을 읽을 수 있는 곳이다.

| 원본 | 기간 | 이 저장소에서 보는 곳 |
|---|---|---|
| 최초 GitHub (`kanghyunsoon/ssafesta`, 비공개) | 2026-08-10 ~ 08-25 | Issues·PR **#1~#84** (원래 번호 그대로) |
| SSAFY GitLab (`S15P21A604`) | 2026-08-24 ~ 09-27 | Issues·PR **#85~#{len(mp['items'])}** (생성 시각 순), [gitlab/](gitlab/) |
| Jira (`S15P21A604`) | 같은 기간 | [jira/](jira/README.md) — 이슈 {j['issues_listed']}건 전부 |

## 번호와 원본 ID

- 원본 ID 와 새 번호의 대응은 [mappings/items.json](mappings/items.json) 에 있다. 모든 이전 항목의 본문 첫머리에도 원본 ID(`GitLab !1013`, `GitLab #269`, `kanghyunsoon/ssafesta#12`)와 원래 작성자·시각을 적었다.
- GitLab 이슈 #1~#83 은 최초 GitHub #1~#83 을 2026-08-24 에 옮긴 사본이라 새 저장소에서는 하나로 합쳤다. 사본에 나중에 달린 댓글은 "GitLab 이관본 추가 댓글" 로 붙어 있다.
- 새 저장소의 PR 작성자·생성 시각·병합 주체는 이전 시점의 이전 계정이다. 원래 값은 본문 머리말에 있다.
- 원본에서 merge commit 으로 병합된 MR·PR 은 실제 병합 커밋을 이용해 **Merged** 로 보인다. squash 로 병합된 것은 원래 head 커밋이 병합 커밋의 조상이 아니어서 커밋을 새로 만들지 않는 한 Merged 가 될 수 없으므로 **Closed** 로 두고 `original: merged` 라벨과 실제 병합·squash 커밋을 본문에 적었다.
- PR 은 원래 대상 브랜치 대신 원래 base 커밋에 고정한 임시 브랜치(`gl-mr/N/base`, `gh-pr/N/base`)를 기준으로 만들어, 당시 diff 를 그대로 보여 준다.

## Git 이력

- GitLab 의 브랜치 {refs['heads']}개와 태그 {refs['tags']}개를 커밋 SHA 그대로 옮겼다. 기본 브랜치는 GitLab 과 같은 `develop` 이다.
- 최초 GitHub 의 브랜치 {refs['github_original']}개는 `github-original/*` 로 옮겼다. 최초 GitHub 커밋 {gr['github_commits']}개 중 {gr['shared']}개는 GitLab history 에 그대로 있고, 나머지 {gr['github_only']}개는 그 브랜치와 PR 에서 보인다.
- 삭제된 브랜치의 끝 커밋 중 되살린 {refs['archive_deleted']}개는 `archive/deleted-branches/*` 에 있다. 서버에서 이미 지워진 5개는 복구하지 못했다.

## Jira 와 코드의 연결

[mappings/jira-links.json](mappings/jira-links.json) 은 Jira 이슈마다 커밋·브랜치·GitLab MR·이슈를 모은 것이다. 근거는 커밋 메시지·브랜치 이름·MR 제목/본문의 이슈 키와 Jira 개발 패널이다. {j['issues_listed']}건 중 {jl['with_any_link']}건이 하나 이상과 이어지고, MR {jl['gitlab_mrs']}건 중 {jl['gitlab_mrs_with_jira_key']}건에 Jira 키가 있다.

## 수량 검증 (2026-09-28 수집)

| 항목 | 원본 서버 | 보존 |
|---|---|---|
| Jira 이슈 / 댓글 / 변경 이력 | {j['approximate_count']} / {j['comments']['server_total']} / {j['changelog_histories']['server_total']} | {j['issues_dumped']} / {j['comments']['dumped']} / {j['changelog_histories']['dumped']} |
| Jira 첨부 (개수·바이트) | {j['attachments']['metadata']} · {j['attachments']['metadata_bytes']} | {j['attachments']['downloaded_size_match']} · {j['attachments']['downloaded_bytes']} |
| GitLab MR / 이슈 | {g['merge_requests']['x_total']} / {g['issues']['x_total']} | {g['merge_requests']['detail_dumped']} / {g['issues']['listed']} |
| GitLab note (MR+이슈) / 이벤트 | — | {g['merge_requests']['notes'] + g['issues']['notes']} / {g['events']} |
| GitLab 업로드 | {g['uploads']['listed']} | {g['uploads']['downloaded_size_match']} (크기 일치) |
| 최초 GitHub PR / 이슈 / 댓글 | {h['pulls']} / {h['issues']} / {h['issue_comments']['expected']} | {h['pulls']} / {h['issues']} / {h['issue_comments']['dumped']} |

알려진 공백과 원인은 [manifest.json](manifest.json) 의 `known_gaps` 에 있다.

## 공개본과 원본

여기 있는 파일은 공개용으로 걸러 낸 것이다. 이메일·전화번호는 가렸고, Jira 계정 ID·아바타·웹훅 URL 은 뺐다. 가공 전 API 응답, 첨부, CI package 원본은 `ssafesta-original-private-20260928.tar`(SHA-256 `{tar_sha}`)로 묶어 팀이 비공개로 보관한다.

## 구성

- [jira/](jira/README.md): 이슈별 Markdown(설명·필드·댓글·변경 이력), `issues.jsonl`, 프로젝트·스프린트 메타데이터, 첨부
- [gitlab/](gitlab/): MR·이슈·note·이벤트·파이프라인 JSONL, 라벨, 업로드 이미지
- [github-original/](github-original/): 최초 GitHub 이슈·PR·댓글
- [mappings/](mappings/): 원본 ID ↔ 새 번호, Jira ↔ 코드 연결, MR 병합 분석
- [contributors.json](contributors.json): 팀원별 GitHub·GitLab·Jira 계정
- 이전 도구: [../tools/history-migration/](../tools/history-migration/)
"""
open(os.path.join(OUT, "README.md"), "w", encoding="utf-8", newline="\n").write(readme)
print("ok")

