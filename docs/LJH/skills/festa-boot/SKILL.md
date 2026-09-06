---
name: festa-boot
description: SSAFY FESTA front 파트 세션 부팅. 규칙 적재 → 원격 실측 → 낡음 탐지 → 인계 대조 순으로 세션 시작 상태를 잡는다. "세션 시작", "부팅", "상황 보고", "이어받기", "인계받았다", 또는 인계 프롬프트를 붙여넣었을 때 쓴다. 환경·동기화·검사 가능 여부만 판정하고, 이슈·MR 응답 판정은 festa-inbox 소관이다.
---

# FESTA 세션 부팅

**인계 문서를 믿지 않는 것이 목적이다.** 인계는 작성 시점의 스냅샷이라 부팅 시점엔 낡았을 수 있다. 실측으로 대조하고 어긋난 것만 드러낸다. 인계가 없어도 자립 동작해야 한다.

## 0. 가장 중요한 규칙

**실패를 "이상 없음"으로 바꾸지 마라.** 명령이 죽거나, 권한이 없거나, 페이지네이션을 안 돌렸으면 **"확인 불가"**로 분리해 보고한다. 0건과 확인 불가는 다르다. 이 규칙 하나가 거짓 안심을 막는다.

## 1. 규칙 적재 — 읽기만, 출력 금지

순서대로 읽는다. **요약도 출력하지 않는다** — 매 세션 같은 내용이라 노이즈다.

1. `AGENTS.md` 전문
2. `CLAUDE.md` 최상단 §12 워크플로 규칙
3. `docs/LJH/26_로컬_규약_스냅샷.md` — A 섹션의 **자율 처리 판정**이 이 파트 운영 방침이다

## 2. 원격 실측 — 병렬로

```bash
git fetch origin --prune
git status --short
git rev-list --left-right --count front...origin/front
git branch --show-current
```

`front`만 보면 낡는다 — `develop`·`back`·`ai`·`game`의 신규 커밋도 같이 훑는다.

```bash
for B in develop back ai game; do
  echo "-- $B: $(git log -1 --format='%h %ad %s' --date=format:'%m-%d %H:%M' origin/$B)"
done
```

**glab 인증을 먼저 확인한다.** git fetch 성공과 API 인증은 별개다.

```bash
glab auth status --hostname lab.ssafy.com
```

실패하면 이후 GitLab 항목은 전부 **"확인 불가"**다. 빈 결과를 0건으로 읽지 마라.

## 3. 낡음 탐지 — 이 스킬의 핵심 산출

과거에 실제로 터진 사고를 체크로 고정한 것이다.

### 3-1. 공용 문서가 develop보다 낡았나

front의 공용 문서 사본은 **낡았다고 가정**한다(develop·game·back이 계약 정본을 먼저 갱신하는 구조).

```bash
git diff --stat origin/develop front -- docs/ specs/ | tail -5
```

### 3-2. 의존성 신뢰도 — lockfile 기준

`node_modules` **존재는 정상의 증거가 아니다.** lockfile이 바뀌었는데 설치가 과거 버전이면 조용히 깨진다(T-21: 검증 불가 상태에서 "로컬만으로 가능"이라 보고한 사고).

```bash
cd festa-frontend
test -d node_modules || echo "MISSING: npm ci 필요"
git log -1 --format=%cd --date=format:'%m-%d %H:%M' -- package-lock.json
stat -c '%y' node_modules 2>/dev/null | cut -c1-16
```

lockfile 커밋 시각이 `node_modules` 갱신 시각보다 **나중이면 재설치 대상**이다. 판정만 하고 `npm ci`는 돌리지 않는다 — 필요하면 작업 착수 시.

**vitest는 부팅마다 돌리지 않는다.** 느리고, 기준선은 작업 착수 때 잡으면 된다. 여기서는 "검사 가능/불가"만 판정한다.

### 3-3. 미완결 작업 흔적

```bash
git log --oneline -5
git stash list
ls .git/MERGE_HEAD .git/REBASE_HEAD 2>/dev/null && echo "중단된 머지/리베이스 있음"
```

## 4. 인계 대조 — 인계 프롬프트가 있을 때만

인계가 주장한 항목을 실측과 맞춘다. **어긋난 것만** 보고한다.

> 인계는 MR !13 리뷰 대기라 했으나 08-26 09:12 머지됨 — 반영 확인 필요

인계가 없으면 이 단계를 건너뛴다. 인계 내용을 그대로 복창하지 않는다.

## 5. 이슈·MR은 얕게만

여기서는 **건수와 변화 여부만** 본다. 응답 판정·본문 해석은 `festa-inbox` 소관이다. 두 스킬이 같은 일을 깊게 하면 부팅이 느려진다.

```bash
glab api "todos?state=pending&per_page=100" --paginate 2>/dev/null | python -c "import sys,json;print('pending todo:',len(json.load(sys.stdin)))"
glab mr list --author=@me 2>&1 | head -3
```

pending todo가 있으면 **"festa-inbox로 확인 필요"**라고만 적는다.

## 6. Jira — JAM 경유

Jira 조회는 **JAM(`jira_search`)으로만** 한다. JAM tool이 없으면 **"Jira 확인 불가 — JAM 미연결"**로 보고하고 넘어간다. 추측하지 않는다.

JAM은 `JIRA_BASE_URL`·`JIRA_EMAIL`·`JIRA_API_TOKEN` 환경변수가 있어야 산다. 없으면 사용자에게 알리되 값을 만들어내지 않는다.

## 7. 보고 — 짧게

이상 없으면 3~4줄로 끝낸다.

```text
front = origin/front 동기 · 미커밋 0 · 의존성 정상
원격 신규: develop +2(문서), back +1
pending todo 3건 → festa-inbox 확인 권장
변경점 없음. 지시 대기.
```

변경·이상이 있는 항목만 펼친다. 매 세션 같은 규칙을 다시 읊지 않는다.

**확인 불가 항목이 있으면 반드시 별도 줄로 드러낸다:**

```text
⚠️ 확인 불가: glab 인증 실패 — GitLab 항목 전체 미확인
```
