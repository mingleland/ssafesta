# Contract: 부스 운영 대시보드 요약 API (P1)

**Spec**: `015-dashboard` | **Date**: 2026-09-13 | **Ticket**: `S15P21A604-501`

> `docs/08` §15 에 이름과 예시 JSON만 있고 구현이 없었다. 이 파일이 응답 스키마의 정본이며 `docs/08` §15 를 여기에 맞춘다.

공통 규약은 `docs/08` §1 을 따른다 — 전역 오류 봉투 `{code, message, requestId, errors, warnings}`, 모든 경로는 `/api/v1` 아래.

---

## `GET /api/v1/booths/{boothId}/dashboard/summary?from=&to=`

부스 운영자가 자기 부스의 한 기간을 한 번에 본다. **집계는 서버가 계산한다** (FR-010) — 프론트가 원본을 받아 세지 않는다.

**권한**: Owner · `ADMIN` · `CONTENT_EDITOR` (FR-007). `CONSULTANT` 는 제외된다 — 상담원은 상담을 하지 부스 운영 지표를 보지 않는다. 판정은 `BoothAccessGuard.requireEditor` 하나로, 부스 콘텐츠 편집 권한과 같은 게이트다.

**기간**: `from` 포함, `to` 제외. 둘 다 필수이며 ISO-8601 (`2026-09-01T00:00:00Z`).

```json
{
  "from": "2026-09-01T00:00:00Z",
  "to": "2026-09-14T00:00:00Z",
  "visits": 120,
  "uniqueVisitors": 88,
  "averageDwellSeconds": 143,
  "openVisits": 4,
  "consultations": 12,
  "consultationsEnded": 6,
  "surveyResponses": 31,
  "leaseCostCoin": 300,
  "surveyRewardCoin": 155,
  "aiUsages": null
}
```

### `null` 은 0 이 아니다

**`null` = 집계할 원천이 아직 없다. `0` = 원천은 있고 그 기간에 0건이었다.**

이 구분이 없으면 FE 는 "AI 이용 0건" 카드를 영원히 띄운다. 수치가 0인 것과 셀 것이 없는 것은 운영자에게 전혀 다른 사실이다.

| 필드 | spec 015 | 상태 |
|---|---|---|
| `visits` · `uniqueVisitors` · `averageDwellSeconds` · `openVisits` | FR-001 | `booth_visit_events` (`S15P21A604-240`) |
| `consultations` · `consultationsEnded` | FR-004 | `consultations` (`S15P21A604-137`) |
| `surveyResponses` | FR-003 | `survey_responses` ⋈ `surveys.booth_id` |
| `leaseCostCoin` · `surveyRewardCoin` | FR-005 | `coin_ledger_entries` ⋈ `booth_leases`·`surveys` (`reference_id`) |
| `aiUsages` | FR-002 | **원천 없음** — AI 대화 기록 표가 저장소에 없다 |

`null` 인 칸은 **`aiUsages` 하나**다. AI 상담 대화를 남기는 표가 생기면 채운다(`S15P21A604-139` 계열). 표 이름·컬럼은 AI 파트 소관이라 여기서 정하지 않는다.

### 코인 칸이 "수익" 이 아닌 이유 — 부스는 코인을 벌지 않는다

원장(`coin_ledger_entries`)에 실제로 쓰이는 사유는 다섯 가지다.

| 사유 | 방향 | 부스와의 관계 |
|---|---|---|
| `INITIAL_GRANT` · `DAILY_GRANT` | 회원에게 지급 | 없음 |
| `PURCHASE` | 회원이 지출 | 카탈로그(아바타 파츠) — 플랫폼이 판다, 부스가 아니다 |
| `SURVEY_REWARD` | 응답자에게 지급 | 부스의 설문이 계기지만 **부스가 내지 않는다** (차감되는 지갑이 없다) |
| `MINIGAME_REWARD` | 회원에게 지급 | 없음 |
| `BOOTH_LEASE` | 부스 소유자가 지출 | **부스의 비용**이다 — 수익이 아니다 |

부스로 코인이 **들어오는** 경로는 하나도 없다. `revenueCoin` 은 그런 경로가 있다고 가정한 필드이고, `booth_daily_metrics.revenue_coin` 컬럼도 같은 가정 위에 있다.

**확정 (2026-09-13, 백엔드 파트, `docs/26`)**: `revenueCoin` 을 없애고 **쓴 코인**(`leaseCostCoin`)과 **뿌린 코인**(`surveyRewardCoin`) 두 칸으로 바꿔 읽는다. 둘 다 양수다.

타협이 아니라 원래 맞는 지표다. 이 대시보드의 존재 이유가 GitLab #94 의 *"Coin 시스템 유효성을 배포 후 사용자 반응으로 검증하라"* 이고, 그 관점에서 부스는 코인을 **뿌리는 쪽**이자 **쓰는 쪽**이다. 특히 `surveyRewardCoin` 은 `surveyResponses` 옆에서 그대로 읽힌다 — 코인 155개를 뿌려 응답 31개를 받았다.

진짜 유입 경로가 생기면(`S15P21A604-634`) 그때 `revenueCoin` 을 이 둘 **옆에 더한다**. `booth_daily_metrics.revenue_coin` 컬럼은 그 자리로 남겨 둔다.

### 오류

| 오류 | 조건 |
|---|---|
| `400 VALIDATION_FAILED` | `from` 이 `to` 보다 뒤이거나 같다 |
| `403 BOOTH_EDITOR_FORBIDDEN` | 호출자가 Owner·`ADMIN`·`CONTENT_EDITOR` 가 아니다 (FR-007·FR-008) |
| `404 BOOTH_NOT_FOUND` | 그런 부스가 없다 |

**다른 부스의 지표는 경로로 막힌다** (FR-008). `boothId` 가 경로에 있고 게이트가 그 부스 기준으로 판정하므로, 남의 부스 id 를 넣으면 `403` 이다.

### 데이터가 0건인 부스

모든 수치가 `0` 이고 `200` 이다 (FR-009·SC-002). 나눗셈은 SQL 의 `avg` 가 하며, 대상 행이 없으면 `null` 을 돌려주므로 `coalesce(..., 0)` 로 받는다 — 0 으로 나누는 경로가 없다.

---

## 미결 — 이 계약에 영향을 주는 것

| Clarification | 이 구현의 처리 |
|---|---|
| **C-01** "방문"의 정의 | `S15P21A604-240` 에서 **부스 구역 진입**으로 잡았다. 발신은 React 호스트이고, 게임 파트와의 합의는 `docs/08` §14-1 에 질문으로 남아 있다 |
| **C-02** 기간 필터 | `from`·`to` 를 **필수**로 받는다. FE 검토(2026-09-05)는 "단일 기간부터" 의견이었으나, 기간을 안 받으면 전체 누계만 가능하고 나중에 넣으면 기존 호출이 전부 바뀐다. 일별 추이(시계열 배열)는 넣지 않았다 — FE 가 미루자고 한 것은 그쪽이다 |
| **C-03** 만료 임대의 과거 지표 | **보인다.** `requireEditor` 는 부스 소유·직원 여부만 보고 임대 상태를 보지 않는다. 임대가 끝났다고 지난 기간 숫자가 사라지면 운영자가 행사 뒤 정산을 못 한다. 기획이 반대로 정하면 게이트 한 줄이다 |
| **C-04** 실시간 vs 사전 집계 | **실시간.** 부스 12개 규모에서 원본 스캔이 사전 집계보다 싸고, 사전 집계는 갱신 실패 시 조용히 틀린 숫자를 보여준다. `booth_daily_metrics` 는 비워 둔다 — 느려지면 그때 얹는다 |
| **C-05** 관리자용 전체 지표 | **없다.** 전역 관리자 권한 모델이 미정이고 `docs/26` 에 결정 요청으로 올라가 있다 (`S15P21A604-165`) |
