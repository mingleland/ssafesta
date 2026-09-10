---
name: festa-inbox
description: SSAFY FESTA에서 나에게 액션을 요구하는 이슈·MR·리뷰·코멘트를 판정한다. 개수 변화가 아니라 "무엇이 나를 기다리는가"를 가린다. "이슈 확인", "MR 확인", "새 응답 있나", "뭐가 왔나", "리뷰 확인", "내가 할 일", "현황 파악" 같은 요청에 쓴다. ASC 가 붙어 있으면 원본 대조는 `asc inbox` 가 한다 — 이 스킬은 ASC 가 보지 않는 소스(MR 리뷰어 승인, backlog 문서)를 더하고 팀 어휘로 옮긴다.
---

# FESTA 인박스 — 개수가 아니라 의미

## 0-A. ASC 가 붙어 있으면 거기서 시작한다 (2026-09-08 개정)

**원본 현재 상태 대조는 ASC 가 한다.** `asc inbox` 는 목록을 보여 주기 전에 각 요청의 원본을
다시 읽고, 닫힌 원본의 요청을 `OBSOLETE` 로 물린다 — 처분이 아니라 관측이고, 근거가 함께
남는다. 못 읽으면 "원본 대조 실패" 로 그대로 말한다.

```bash
asc inbox          # 원본과 맞춘 뒤 판단 대기 목록
asc inbox show <REQ-ID>
```

그러니 이 스킬은 그것을 다시 하지 않는다. 여기서 더하는 것은 **ASC 가 보지 않는 것**뿐이다:

```text
MR 리뷰어 승인 상태     ASC 의 관측 소스가 아니다 (아래 1-2)
backlog·작업일지 대조   내 문서가 낡았는지는 ASC 가 모른다 (아래 1-3)
팀 5범주 어휘           표현이지 판정이 아니다 (아래 3)
provider 함정 지식      paginate·sort=asc·system note (아래 1-1·2)
```

ASC 가 없는 환경에서는 아래 전 절차를 그대로 돌린다 — 이 스킬은 자립 동작해야 한다.

**"몇 건"은 답이 아니다.** 과거 사고 3건이 이 스킬의 존재 이유다.

- **T-20** — 닫힌 이슈가 내 backlog에 "막힌 것"으로 3일간 잔존했다. 열린 목록만 조회하면 종결된 항목은 목록에서 빠져 **대조 대상에서 조용히 사라진다.** (ASC inbox 쪽 잔존은 이제 `asc inbox` 가 잡는다. 여기서 보는 것은 **내 문서**의 잔존이다.)
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

### 1-5. 열린 이슈 **전수**의 마지막 발언자 — To-Do 밖의 구멍

**To-Do 는 내가 답할 차례를 다 잡아 주지 않는다.** 상대가 답하면서 나를 직접 `@mention` 하지
않으면 To-Do 가 안 생긴다. 그러면 "내 답변 차례"가 **어느 소스에도 안 잡힌 채로** 남는다 —
1-1 은 호명이 없어 못 보고, 1-3 은 열려 있어 못 보고, 1-4 는 목록만 주지 마지막 발언자를 안 본다.

**`--author=@me` 로 좁히지 마라.** 내가 연 것만 보면 **남이 연 스레드에서 내가 답할 차례**가 통째로
빠진다(2026-09-10 실측: `#166`·`#120`·`#137`·`#56` 네 건). **열린 이슈 전수**를 돌린다 — 이 프로젝트는
열린 이슈가 40건 안팎이라 전수가 현실적이다.

```bash
P=projects/s15-metaverse-game-sub1%2FS15P21A604
# 열린 이슈 전수 (author 로 좁히지 않는다)
glab api "$P/issues?state=opened&per_page=100" --paginate | jq -s 'add|.[].iid'
# 각각에 대해 — 내 코멘트 수와 마지막 사람 발언자
glab api "$P/issues/<IID>/notes?sort=asc&per_page=100" --paginate \
  | jq -s 'add|[.[]|select(.system==false)]|{mine:([.[]|select(.author.username=="colosair")]|length), last:(last|.author.username)}'
```

- `mine > 0` 이고 `last != 나` → **응답 도착 미회신.** 내가 연 것이든 남이 연 것이든 같다
- `mine == 0` 이고 `last != 나` → 라벨을 본다. `front` 가 있으면 미열람 호명, 없으면 관측만

### 1-6. 시각 필터 금지

**수집에 `created_at >= …` 류 시각 필터를 걸지 않는다.** To-Do 는 오래된 것이 남아 있는 것이 정상이고,
"최근 것만 보면 된다" 는 전제가 성립하지 않는다 — 며칠 묵은 미회신이 정확히 이 스킬이 잡아야 할 것이다.

같은 이유로 **"직전 회차 이후" 로 범위를 좁히지 않는다.** 직전 회차가 놓친 것은 이번에도 놓친다.

수집은 전수로 하고, **줄이는 것은 보고 단계에서 한다** — 판정이 끝난 뒤 "대기 중" 을 한 줄로 접는 것은
괜찮지만, 수집에서 빼면 그 항목은 판정 자체를 받지 못한다.

**To-Do 숫자만으로 "미열람 0건 · 미응답 0건" 이라 판정하지 않는다.** 최소 세 축을 함께 본다 —
① GitLab To-Do ② **열린 이슈 전수**의 마지막 발언자 ③ 내가 리뷰·요청 책임을 진 MR·issue.

(근거: 2026-09-10 하루에 같은 계열로 두 번 났다. 오전에는 `#148`·`#150`·`#159` — **내가 연 이슈**에 온
답이 To-Do 에 없어 빠졌고, 그래서 1-5 를 만들었다. 저녁에는 그 1-5 가 `--author=@me` 로 좁혀져 있고
To-Do 에 시각 필터까지 걸어 `#166`·`#120`·`#137`·`#56` 이 또 빠졌다. `#120` 은 가격표가 30분 사이 두 번
뒤집힌 건이었다. **한 번은 소스가 부족해서, 한 번은 내가 소스를 좁혀서** 났다)

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
| **종결 잔존** | 닫혔는데 backlog·작업일지엔 열린 걸로 기재 | T-20. **문서 쪽 잔존이 이 스킬의 몫이다** — ASC 요청 쪽 잔존은 `asc inbox` 가 물린다 |

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
