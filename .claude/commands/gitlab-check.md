---
description: GitLab 멘션·할당·리뷰요청(To-Do) 확인 — 새 항목만 보고
---

GitLab 에서 나를 태그·할당·리뷰요청한 항목(To-Do)을 확인해 **새로 생긴 것만** 보고하라.

접근: 사용자 환경변수 `GITLAB_TOKEN` + REST (lab.ssafy.com, 프로젝트 id 1443023).
토큰 값은 절대 출력하지 않는다.

```bash
TOK=$(powershell.exe -NoProfile -Command "[Environment]::GetEnvironmentVariable('GITLAB_TOKEN','User')" | tr -d '\r\n')
curl -sS --header "PRIVATE-TOKEN: $TOK" "https://lab.ssafy.com/api/v4/todos?state=pending&per_page=50"
```

수행:
1. 상태 파일 `.claude/state/gitlab-last-check.txt` 를 읽는다(없으면 최초 실행으로 간주).
2. pending To-Do 를 조회한다. 각 항목의 `created_at` 이 마지막 확인 시각보다 **이후**인 것만 "새 항목"이다.
3. 새 항목이 **없으면** 딱 한 줄로만 보고한다: `GitLab: 새 멘션 없음 (대기 N건)`. 그 이상 쓰지 마라.
4. 새 항목이 **있으면** 각각에 대해 실제 내용을 확인해 보고한다:
   - `action_name`(mentioned / directly_addressed / assigned / review_requested), 대상(Issue #N 또는 MR !N), 제목, 작성자
   - 해당 이슈·MR 의 **본문과 마지막 코멘트**를 조회해 **나에게 무엇을 요구하는지** 1~2줄로 요약
   - 내가 답해야 하는지, 코드 작업이 필요한지, 단순 통보인지 구분
   - 관련 Jira 이슈 키가 제목·본문에 있으면 함께 표기
5. 조회가 끝나면 상태 파일에 현재 UTC 시각을 기록한다 (`date -u +%Y-%m-%dT%H:%M:%SZ`).
6. **To-Do 를 임의로 done 처리하지 마라.** 사용자가 직접 처리한다.
7. 추측 금지 — 조회 실패 시 실패 사실만 보고한다.

보고 형식(새 항목이 있을 때만):

```
# GitLab 새 멘션 N건 (대기 총 M건)

## [Issue #99] 제목 — 작성자
- 유형: directly_addressed
- 요구사항: (한두 줄)
- 필요 조치: 답변 필요 / 코드 작업 / 확인만
- 관련 Jira: S15P21A604-N (있으면)
```

우선순위는 나에게 답변을 요구하는 것 → 코드 작업 → 단순 통보 순으로 정렬한다.
