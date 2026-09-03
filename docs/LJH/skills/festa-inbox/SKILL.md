---
name: festa-inbox
description: SSAFY FESTA에서 나에게 액션을 요구하는 이슈·MR·리뷰·코멘트를 판정한다. 개수 변화가 아니라 "무엇이 나를 기다리는가"를 가린다. "이슈 확인", "MR 확인", "새 응답 있나", "뭐가 왔나", "리뷰 확인", "내가 할 일", "현황 파악" 같은 요청에 쓴다. 종결 잔존(닫혔는데 내 기록엔 열린 것)과 미열람 호명(내가 참여한 적 없는데 나를 부른 것)까지 잡는 것이 목적이다.
---

# FESTA 인박스 — 개수가 아니라 의미

**"몇 건"은 답이 아니다.** 과거 사고 3건이 이 스킬의 존재 이유다.

- **T-20** — 닫힌 이슈가 내 backlog에 "막힌 것"으로 3일간 잔존했다. 열린 목록만 조회하면 종결된 항목은 목록에서 빠져 **대조 대상에서 조용히 사라진다.**
- **오판 사고** — 리드가 즉답을 달았는데 "코멘트 1개 = 내가 아는 그 상태"로 읽어 '응답 없음'이라 보고했다.
- **시스템 노트 오염** — 어떤 이슈는 20개 note 중 **13개가 `mentioned in commit` 류 시스템 이벤트**였다. 개수만 세면 실제 답이 0인데 늘어난 것으로 보인다.

## 0. 가장 중요한 규칙

**실패를 0건으로 바꾸지 마라.** 명령 실패·권한 부족·페이지네이션 누락은 **"확인 불가"**로 분리한다. 이 스킬이 낼 수 있는 최악의 산출은 거짓 "새 응답 없음"이다.

먼저 인증을 확인한다. 실패하면 이후 전부 확인 불가다.

```bash
glab auth status --hostname lab.ssafy.com
```

## 1. 수집 — 4개 소스를 합친다

한 소스만 쓰면 반드시 구멍이 생긴다.

### 1-1. To-Do — **1차 수집원**

가장 중요하다. 내가 **참여한 적 없는 스레드에서 나를 호명한 것**은 이것으로만 잡힌다.

```bash
glab api "todos?state=pending&per_page=100" --paginate | jq -s 'add'
```

**`--paginate` 의 출력은 페이지별 JSON 배열이 개행 없이 이어 붙은 것**(`[...][...]`)이라 `json.load` 한 번으로는 `Extra data` 로 죽는다 — 그래서 첫 페이지 100건만 보고 "전수"라고 오판한 사고가 있었다(2026-09-03, 실제 pending 214건). `jq -s 'add'` 가 페이지 배열들을 하나로 합친다. 합친 뒤 **`length` 가 `X-Total` 헤더와 같은지** 한 번 대조한다(`glab api "todos?state=pending&per_page=1" -i | grep -i x-total`). 다르면 "확인 불가"다.

`action_name`이 판정에 직결된다: `directly_addressed`(직접 호명) · `mentioned`(멘션) · `assigned`(배정) · `review_requested`(리뷰 요청) · `approval_required`.

To-Do는 사용자가 수동으로 완료 처리할 수 있으므로 **유일한 근거로 쓰지 않는다.** 아래 소스로 보완한다.

### 1-2. 내 MR — 작성자와 리뷰어 양쪽 + 승인 상태

`--author=@me`만 보면 **리뷰어로 지정된 남의 MR을 통째로 놓친다.**

```bash
glab mr list --author=@me
glab mr list --reviewer=@me
```

`--reviewer=@me`로 나온 각 MR에는 **승인 상태를 반드시 붙인다.** To-Do의 `review_requested`는 수동 완료 처리가 가능해 단독 근거가 못 되므로, **approvals가 정본이고 To-Do는 보조**다. To-Do를 완료 처리했어도 이 경로로 잡혀야 한다.

```bash
glab api "projects/s15-metaverse-game-sub1%2FS15P21A604/merge_requests/<IID>/approvals"
```

- **핵심 조건**: open MR + 내가 reviewer + `user_has_approved == false` → **내 액션 필요(리뷰 미완)**
- `user_can_approve`는 보조 검증만 — `false`면 승인 권한 문제이므로 "확인 불가"로 분리한다

**approvals 단독으로 판정하지 마라.** 실측 확인: 내가 **author**인 MR(reviewer는 남)에도 `user_can_approve=true`·`user_has_approved=false`가 그대로 나온다. 이 두 필드만 보면 **내가 올린 MR이 전부 "내 리뷰 미완"으로 둔갑한다.** 반드시 `--reviewer=@me` 목록으로 대상을 좁힌 뒤 approvals를 붙이는 순서를 지킨다.

### 1-3. 닫힌 것 — 종결 잔존 탐지

**이게 빠지면 T-20이 재발한다.** 단, 전체 closed를 매번 훑지 않는다. `docs/LJH/backlog.md`에서 추적 중인 IID를 먼저 뽑고 그것만 직접 조회하는 편이 빠르고 정확하다.

```bash
grep -oE '(work_items|issues|merge_requests)/[0-9]+' docs/LJH/backlog.md | grep -oE '[0-9]+$' | sort -u
glab api "projects/s15-metaverse-game-sub1%2FS15P21A604/issues/<IID>" | python -c "import sys,json;d=json.load(sys.stdin);print(d['iid'],d['state'],d['title'][:40])"
```

플래그를 쓸 때는 **`--closed`/`-c`·`--all`/`-A`·MR은 `--merged`/`-M`**이다. `--state`라는 플래그는 **없다**(실측 확인).

### 1-4. 전체 열린 이슈 — 나를 부른 남의 것

```bash
glab issue list --per-page 40
```

## 2. 시스템 노트 분리 — 버리지 말고 **갈라놓는다**

`system: true`인 note는 **대화에서만** 제외한다. assign·merge·close는 시스템 노트지만 **액션 판정에는 결정적**이므로 리소스 필드로 따로 본다.

```bash
glab api "projects/s15-metaverse-game-sub1%2FS15P21A604/issues/<IID>/notes?sort=asc&per_page=100" --paginate
```

**`sort=asc`를 반드시 명시한다.** 기본값은 `desc`(최신 먼저)라, 배열 끝에서 "마지막 코멘트"를 찾으면 **가장 오래된 것**을 집는다(실측: 실제 마지막 13:27인데 배열 끝은 12:40). 이 하나로 판정이 통째로 뒤집힌다.

## 3. 판정 — 5범주

각 항목의 **마지막 사람 코멘트**와 **리소스 상태**를 함께 본다.

| 범주 | 조건 | 왜 |
|---|---|---|
| **미열람 호명** | To-Do에 있는데 내 코멘트가 하나도 없음 | 내가 아직 안 본 것. 코멘트 비교로는 원천적으로 못 잡는다 |
| **응답 도착** | 내 코멘트 뒤에 남의 사람 코멘트가 있음 | 가장 놓치기 쉽다. 내가 물어본 것에 답이 온 상태 |
| **내 액션 필요** | 마지막 사람 코멘트가 남이고 나를 멘션·assign / MR에 미해결 discussion / **내가 reviewer인데 `user_has_approved=false`** | 질문·요청·리뷰가 걸려 있음 |
| **대기 중** | 마지막 사람 코멘트가 나이고 상대 무응답 | 정상. 액션 없음 |
| **종결 잔존** | 닫혔는데 backlog·작업일지엔 열린 걸로 기재 | T-20. 이 범주가 이 스킬의 존재 이유 |

### MR은 discussion 단위로 본다

전체 마지막 코멘트가 나여도, **다른 스레드에 미해결 질문이 남아 있을 수 있다.** MR 하나가 판정 단위가 아니라 **미해결 discussion 하나**가 단위다.

```bash
glab api "projects/s15-metaverse-game-sub1%2FS15P21A604/merge_requests/<IID>/discussions?per_page=100" --paginate
```

`notes[].resolvable == true && resolved == false`인 discussion이 미해결이다.

MR 상태는 리소스 필드로 본다(실측 확인된 필드):

```
detailed_merge_status   mergeable / broken_status / ci_still_running / discussions_not_resolved 등
has_conflicts           충돌 여부
draft                   Draft 여부
blocking_discussions_resolved   미해결 discussion이 머지를 막는지
```

## 4. 본문 대조 — 분류된 것만 읽는다

**미열람 호명**·**응답 도착**·**내 액션 필요**로 분류된 것만 본문을 읽고 **요구사항을 뽑는다.** 개수만 보고하면 지시를 놓친다.

> `#104` — BE 답변: Draft 없음은 204 확정, `INTERNAL_ERROR`로 정렬하라. **FE 작업 2건 지시됨**

## 5. Jira — 3단 fallback

JAM은 **선호 경로지 제약이 아니다.** 가용한 경로가 있는데 "확인 불가"로 끝내면 정보를 버리는 것이다.

```
1순위  JAM jira_search
2순위  Atlassian MCP          — JAM 미연결 또는 호출 실패일 때만
3순위  "Jira 확인 불가"        — 둘 다 실패할 때만
```

**정상 응답 0건은 fallback하지 않는다.** `결과 없음`과 `조회 실패`는 다르다 — JAM이 정상 동작해 0건을 반환했으면 그것이 답이므로 2순위로 내려가지 않는다. fallback 조건은 **tool 부재 또는 호출 실패**뿐이다.

2순위 도구는 **기능 기준으로 탐색한다** — 도구 ID는 세션마다 바뀌므로 하드코딩하지 않는다. `ToolSearch`에 `jira jql search` 류로 질의해 Atlassian MCP의 JQL 검색 도구를 찾는다.

2순위를 썼으면 **보고에 출처를 반드시 명시한다:**

```text
Jira source: Atlassian MCP (JAM unavailable)
```

이 한 줄이 결과 신뢰도와 JAM 복구 필요성을 동시에 드러낸다. 1순위로 조회했을 때는 출처 표기가 불필요하다(기본 경로이므로).

## 6. 보고

```text
# 미열람 호명 ⚠️
#48   BE가 나를 직접 호명 — 내 코멘트 0건, 한 번도 안 본 스레드

# 응답 도착 — 읽어야 함
#104  BE 답변(13:27): CONFIG_NOT_FOUND는 #56이 아니라 MR !1에서 이미 처리됨 (앞 코멘트 정정)

# 내 액션 필요
!13   미해결 discussion 1건 — guard 이관 방식 질문
!22   내가 reviewer인데 미승인 — user_has_approved=false (To-Do는 이미 완료 처리됨)

# 종결 잔존 ⚠️
#96   CLOSED(08-25 14:02)인데 backlog.md:23에 "리드 답변 대기"로 남아 있음

# 대기 중 (액션 없음)
!14 !15 — 리뷰어 지정 후 무응답

Jira source: Atlassian MCP (JAM unavailable)
```

이상 없으면 두 줄로 끝낸다.

```text
새 응답 0건 · 미열람 호명 0건 · 리뷰 미완 0건 · 종결 잔존 0건
확인 불가 0건
```

## 후속 고도화 (이번 범위 밖)

- MR inline diff 코멘트의 `position` 기반 파일·라인 상세화. 지금은 discussion 미해결 여부까지만 판정하고 어느 코드 줄의 지적인지는 구분하지 않는다.
