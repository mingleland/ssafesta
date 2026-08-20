# 임대 만료 시 AI 대화 유예·종료 계약 — BE 관점 검토

> 황덕 백엔드 파트. GitHub issue #14 (spec 008 × spec 004 D03) 답변 근거.
> 작성일: 2026-08-20 | 기준 커밋: `back` `13c26c4`
> 관련: 헌법 3·14·16·19·20·24·30조 · spec 004 D03/FR-008/FR-009 · spec 008 FR-005/FR-007 · docs/07 §5·§7 · docs/14 §4·§6
> **모든 권고는 제안이며 확정이 아니다** (헌법 30조). 확정 필요 항목은 §8에 모았다.

---

## 0. 사실 확인

요청서의 현황 인식은 **전부 맞다.** 코드로 확인했다.

| 주장 | 확인 |
|---|---|
| 임대 유효 조건 `status='ACTIVE' AND ends_at > now()` | ✅ `BoothLeaseRepository` 쿼리에만 존재 |
| 스케줄러 없이 조회 시점 논리 판정 | ✅ `BoothQueryService` |
| 만료 부스 공개 조회 `409 BOOTH_LEASE_EXPIRED` | ✅ `BoothExpiredException` + `ErrorCode` |
| 재임대 시 stale ACTIVE → EXPIRED + 슬롯 연결 해제 | ✅ `BoothLeaseService.releaseStaleLeases()` |
| Conversation API·SSE 유예 종료·만료 Push 없음 | ✅ 백엔드에 대화 관련 코드 0 |

### 다만 여섯 가지를 덧붙인다. 이 중 (2)와 (3)이 권장안을 바꾼다

**(1) `leaseEndsAt`을 주는 endpoint가 이미 있다.**

```java
// BoothQueryService.findPublicBooth() → PublicBoothView
new PublicBoothView(booth.getId(), lease.getSlotId(), booth.getName(),
                    lease.getStatus().name(), true, lease.getEndsAt());
```

`GET /api/v1/booths/{boothId}`는 **`permitAll()`**이고(`SecurityConfiguration:56`) 유효 임대의
`endsAt`을 이미 반환하며 만료면 `409 BOOTH_LEASE_EXPIRED`를 낸다.
즉 **권장안 1·2는 BE 신규 개발 0으로 오늘 성립한다.** 남은 것은 "이걸 그대로 쓸지, 내부용을 따로 낼지"뿐이다.
`remainingSeconds`도 이미 계산돼 있다 (`BoothLease.remainingSecondsAt`, 슬롯·내 부스 응답에서 사용 중).

**(2) P0에서 `ends_at`은 불변이다. 이게 이 논의의 핵심 사실이다.**

| 값을 움직일 수 있는 기능 | 상태 |
|---|---|
| 연장·자동 갱신 | ⛔ **없다** (D05 / FR-012) |
| 사용자 취소·환불 | ⛔ **없다** (D06 / FR-013) |
| 관리자 강제 비공개 | 004 범위 제외, U-01에 걸려 있음 |

`ends_at`은 임대 생성 시 `now + 24h`로 한 번 쓰이고 그 뒤 **누구도 UPDATE하지 않는다.**
따라서 **한 번 검증해 받은 `leaseEndsAt`은 낡을 수 없다.**
→ *"질문마다 Spring에 다시 묻는다"가 시간 만료에 대해 사주는 것이 없다.* 매 질문 조회의 유일한 이득은
**시간이 아닌 취소**를 즉시 반영하는 것이고, 그 기능들이 지금 P0에 없다.

**(3) 그 불변성이 깨지는 경로가 딱 하나 있다 — 회원 탈퇴.**

`AccountDeletionService`는 하드 삭제한다.

```java
// :24-26
DELETE FROM ai_document_chunks WHERE ... a.booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?)
DELETE FROM ai_documents  WHERE agent_id IN (SELECT id FROM ai_agents WHERE booth_id IN (...))
DELETE FROM ai_agents     WHERE booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?)
// :41-42
DELETE FROM booth_leases  WHERE booth_id IN (...) OR lessee_user_id = ?
DELETE FROM booths        WHERE owner_user_id = ?
```

방문자가 대화 중일 때 **부스 소유자가 탈퇴하면 임대·부스·에이전트·문서·chunk가 전부 사라진다.**
`leaseEndsAt` 스냅샷만 들고 있으면 FastAPI는 **존재하지 않는 부스와의 대화를 계속 서빙한다.**
데이터 유출은 아니다 — chunk가 삭제됐으니 검색은 0건이고 헌법 17조 필터도 그대로다. 하지만
**AI가 근거 0건으로 "모른다"만 답하는 상태**가 되고, 사용자에게는 원인 불명의 고장으로 보인다.
→ §1의 권고가 이 한 줄 때문에 갈린다.

**(4) 헌법 14조가 이 논의의 직접 선례다. 권장안 1과 방향이 반대다.**

> 14. 게임 서버는 접속 승인 시 토큰 서명을 **자체 검증**하고 … *게임 서버가 매 접속마다 Spring에 조회하는 구조를 만들지 않는다 — Spring 장애가 월드 입장을 막으면 안 된다 (3조 정신).*

이미 한 번, 같은 모양의 문제(다른 서버가 Spring 소유 상태를 확인해야 한다)에서 팀은
**"매 요청 조회"를 명시적으로 버렸다.** 권장안 1(매 질문 서버 간 조회)은 그 결정을 AI 경로에서 되돌린다.
헌법 3조 문구 위반은 아니다(3조는 AI 장애가 월드를 막는 것을 금지한다) — 그러나 14조의 괄호가
같은 정신을 일반화해 적어놨다.

**(5) BE에는 스케줄러가 없다.** 만료는 **읽는 순간** 판정된다 (`ends_at > now()`).
"만료 시각에 이벤트를 Push한다"는 **스케줄러를 새로 만든다는 뜻**이다. 지금 없는 컴포넌트다.

**(6) JWT는 HS512 대칭키다.**

```java
// JwtConfiguration
NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS512)
```

서명 스냅샷을 지금 키로 발급하면 **FastAPI가 access token을 위조할 수 있는 키를 갖는다.**
서명 방식으로 가려면 별도 키 또는 비대칭(RS256/EdDSA) 도입이 선행한다.

---

## 1. Lease 유효성 검증 — 어느 서버가, 언제

### 권고 — **생성 시 Spring 권위 검증 + 질문마다 로컬 deadline 비교 + `ai_agents` 단건 존재 확인**

```text
[대화 생성]  React → FastAPI
             FastAPI → Spring  "boothId 7 / agentId 78 지금 유효한가"
                     ← 200 { leaseValid, leaseEndsAt, serverTime }   또는  409 BOOTH_LEASE_EXPIRED
             FastAPI: conversation 행에 leaseEndsAt 저장

[질문마다]   now >= leaseEndsAt              → 409 BOOTH_LEASE_EXPIRED  (Spring 조회 없음)
             ai_agents 행 없음 / 비ACTIVE     → 409 (탈퇴·비활성 감지, 단건 인덱스 읽기)
             그 외                            → 통과

[Spring 재조회]  시간 만료 목적으로는 하지 않는다
```

세 갈래로 나누는 이유가 각각 다르다.

- **생성 시 Spring HTTP**: 만료 술어를 **Spring 한 곳에만** 둔다. 004에서 이미 기록한 위험이다 —
  *"술어가 최소 세 곳에서 하중을 받는다. 한 곳에서라도 시간 조건을 빠뜨리면 조용히 틀린다"*
  (`부스_임대_구현_정리.md` §3). 네 번째 사본을 FastAPI에 만들지 않는다.
- **질문마다 로컬 deadline**: `endsAt`이 불변이므로(§0-2) 이것으로 FR-008이 **정확히** 충족된다.
  Spring 장애가 진행 중 대화를 죽이지 않는다.
- **`ai_agents` 단건 확인**: §0-3(탈퇴)을 **HTTP 없이 공짜로** 잡는다.
  이건 정책 판정이 아니라 **존재 확인**이므로 술어 복제가 아니다 — 이 선이 중요하다.
  *정책 술어는 Spring만, 존재 확인은 DB 직접 읽기 허용.*
  FastAPI는 `ai_documents`·`ai_agents` 읽기 권한을 이미 갖는 설계다 (`AI_문서처리_Job_설계_검토.md` §3 하이브리드).

### 무엇을 검증하는가 — 임대만 보면 부족하다

지금 `GET /booths/{id}`는 **agent를 보지 않는다.** 클라이언트가 보낸 `agentId`를 그대로 믿으면
헌법 16조(클라이언트 주장 불신)와 17조(Vector 격리)가 앱 로직에만 의존하게 된다.
검증은 **한 번의 호출로 둘을 함께** 답해야 한다.

1. `boothId`의 임대가 유효한가 (+ `leaseEndsAt`)
2. `agentId`가 **그 booth 소속**이고 `status='ACTIVE'`인가 — `ai_agents.booth_id`가 이미 있다

### 후보안

| | 방식 | 장점 | 단점 |
|---|---|---|---|
| A | **생성 + 매 질문마다** FastAPI → Spring HTTP (요청서 권장안) | 탈퇴·관리자 조치가 즉시 반영 · 술어 단일 · FastAPI가 상태를 안 들고 있음 | **헌법 14조가 기피한 형태** · Spring 장애 시 AI 대화 정지 · 재시도/timeout 정책이 hot path에 들어옴 · TTFT 15s 예산 소모 · 시간 만료에 대해선 얻는 것이 없다(§0-2) |
| **B (권고)** | **생성 시 Spring + 질문마다 로컬 deadline + `ai_agents` 존재 확인** | 술어 단일 · 호출 **대화당 1회** · Spring 장애가 진행 중 대화를 안 죽인다 · 탈퇴도 잡힌다 · 헌법 14조와 같은 결 | 관리자 강제 비공개(U-01)는 즉시 반영 안 됨 → **그 기능이 열릴 때 재논의 필요** · conversation 행에 `leaseEndsAt` 저장 필요 |
| C | FastAPI가 `booth_leases`를 **DB 직접 판정** | HTTP 0 · 내부 인증 설계 불필요 | **만료 술어가 두 코드베이스에 복제된다** — 004에서 이미 위험으로 기록 · Flyway 변경이 FastAPI를 조용히 깨뜨림 · docs/07 §5 소유권(Booth/Lease = Spring) 위반 |
| D | Spring 서명 **부스 접근 토큰**을 클라이언트가 운반, FastAPI 로컬 검증 | 서버 간 호출 0 · 헌법 14조 패턴 그대로 · 오프라인 검증 | 대칭키 공유 시 access token 위조 가능 → 별도 키/비대칭 선행(§0-6) · FE 변경 · TTL 내 취소 불가 · **호출이 대화당 1회뿐인데 사서 쓸 이득이 없다** |
| E | Spring이 대화 생성·질문을 프록시 | 검증이 자연스럽게 한 곳 | **헌법 3조 위반** — Spring이 AI 응답을 동기 중계 |

> **A를 고를 수 있는 유일한 근거**: "탈퇴·수동 데이터 보정 중인 부스와 대화가 이어지는 것을 1초도
> 감수할 수 없다." 그 판단이면 A가 맞다. 비용은 질문당 VPC 내 1 RTT(수 ms)로 작다.
> B는 그 창을 **`ai_agents` 존재 확인으로 닫되 관리자 조치는 못 닫는다**는 차이다.

---

## 2. `leaseEndsAt` 전달 방식

### 권고 — **검증 응답 본문에 실어 보낸다. 전달 수단을 새로 만들지 않는다**

```http
GET /internal/ai/booth-access?boothId=7&agentId=78
```

```json
{
  "boothId": 7,
  "agentId": 78,
  "leaseValid": true,
  "leaseEndsAt": "2026-08-21T02:20:25Z",
  "remainingSeconds": 3421,
  "serverTime": "2026-08-20T01:23:24Z"
}
```

만료면 `409` + `{"code":"BOOTH_LEASE_EXPIRED", ...}` — 공개 경로와 **같은 코드·같은 봉투**.

`serverTime`을 넣는 이유는 §6이다 — **시계 오차 탐지가 공짜가 된다.**
`remainingSeconds`는 이미 계산 로직이 있다.

### P0 실행 경로는 두 가지다

| | 방식 | BE 작업 | 단점 |
|---|---|---|---|
| P0-즉시 | 기존 공개 `GET /api/v1/booths/{boothId}` 사용 | **0** | agent 짝 검증 없음(§1) · 공개 응답이라 필드 추가가 사용자 계약 변경(헌법 24조) · 속도 제한이 사용자 트래픽과 섞임 |
| **P0-권고** | 내부 endpoint 신설 | 반나절 + 내부 인증 결정 | 내부 인증 방식(서비스 토큰/SG/mTLS)이 미결 — `AI_문서처리_Job_설계_검토.md` §7-5와 **같은 항목**이므로 함께 정하면 된다 |

### 후보안

| | 방식 | 장점 | 단점 |
|---|---|---|---|
| **A (권고)** | 검증 응답 본문 | 새 메커니즘 0 · 검증과 전달이 한 번에 · 오차 탐지 공짜 | 내부 인증 결정 필요(이미 열린 항목) |
| B | 서명 snapshot(JWT) | 재검증 불필요 · 헌법 14조 패턴 | 키 설계·FE 변경 (§0-6) |
| C | DB 직접 읽기 | HTTP 0 | §1-C와 동일 — 술어 복제 |
| D | FastAPI가 `startsAt + 24h`로 자체 계산 | 호출 0 | D02가 바뀌면 두 곳 수정 · `startsAt`을 얻는 경로가 결국 필요 · **기간 정책이 AI 서버에 박힌다** |

---

## 3. 만료 직후 신규 질문의 오류 표현

### 권고 — **새 코드를 만들지 않는다. `BOOTH_LEASE_EXPIRED` 하나로 통일**

요청서 권장안 5는 SSE 오류 코드로 `LEASE_EXPIRED`를 제안하는데, **이건 정정을 권한다.**
`BOOTH_LEASE_EXPIRED`가 이미 `ErrorCode` enum에 있고 `GET /booths/{id}`가 이미 그 코드로 응답하며
**FE·Unity가 이미 그걸로 분기한다.** 같은 조건에 두 번째 이름을 만들면

- FE가 파서·문구를 두 벌 관리한다 (D-BE-01에서 막 끝낸 문제와 같은 종류)
- 헌법 24조 통보 대상이 늘어난다
- "부스 입장 거부"와 "AI 질문 거부"가 사용자에게는 **같은 사건**인데 코드가 달라진다

| 시점 | 표현 |
|---|---|
| 스트림 **개시 전** (첫 바이트 전) | `409 CONFLICT` + `{"code":"BOOTH_LEASE_EXPIRED","message":"...","requestId":"..."}` |
| 스트림 **개시 후** | `event: error` / `data: {"code":"BOOTH_LEASE_EXPIRED", ...}` → 연결 종료 |
| Conversation 상태 | `EXPIRED`로 전이 (docs/14 §4의 `status` 어휘에 추가 필요) |

**FastAPI도 Spring과 같은 오류 봉투 `{code, message, requestId}`를 쓴다.** D-BE-01에서 확정된 형태다.
다르면 FE가 서버별로 파서를 두 개 만든다.

**새 SSE 이벤트 이름은 만들 수 없다** — 헌법 19조가 `start/token/source/done/error` 5종으로 고정했다.
따라서 종료 통지는 반드시 `error` 이벤트다.

### 후보안 (HTTP status)

| | 장점 | 단점 |
|---|---|---|
| **409 CONFLICT (권고)** | `GET /booths/{id}`와 동일 · 재시도 불가 상태를 이미 표현 중 | 409에 원인이 여럿(이미 004에서 4종) — `code`로 갈라야 한다 |
| 403 FORBIDDEN | "권한 없음"이 직관적 | **권한 문제로 오독된다.** 재로그인 유도 같은 잘못된 복구를 FE가 시도 |
| 410 GONE | "사라졌다"는 의미가 맞아 보임 | **재임대로 다시 유효해질 수 있다.** GONE은 영구 소멸 의미 |
| 200 + `done` 이벤트에 사유 | 스트림 계약이 단순 | **오류를 성공으로 표현.** FE가 정상 종료와 구분 못 한다. FR-005 취지 위반 |

---

## 4. 진행 중 응답의 유예·종료

### 권고 — 요청서 권장안 4를 **채택**한다. 단 한 줄을 정정해야 한다

> **`leaseEndsAt + 60초` 상한은 사실상 발동하지 않는다. 새 타이머를 만들지 마라.**

응답 시작 시각을 `s`라 하면,

```text
신규 질문은 s < leaseEndsAt 일 때만 통과한다  (FR-008)
전체 응답 timeout은 s + 60s               (헌법 19조 / spec 008 FR-007)
따라서 종료 ≤ s + 60 < leaseEndsAt + 60    ← 상한은 항상 이미 만족된다
```

즉 **유예를 위한 새 장치가 필요 없다. 기존 전체 timeout이 그대로 유예다.**
이게 이 안의 가장 큰 장점인데, 문장이 "절대 상한을 둔다"로 적히면 구현자가 타이머를 **두 개** 만들고
둘이 어긋나는 버그를 만든다. 계약서에는 이렇게 적기를 권한다.

> 진행 중인 응답은 **자신의 전체 timeout(60s)까지만** 살아 있다. 임대 만료는 진행 중 응답을 절단하지 않는다.
> 결과적으로 어떤 응답도 `leaseEndsAt + 60s`를 넘겨 살아남지 않는다.

### 먼저 정해야 하는 해석 — 이게 실제 쟁점이다

D03의 **"이미 열린 대화"**가 무엇인지 두 가지로 읽힌다.

| 해석 | 결과 | 판단 |
|---|---|---|
| **(i) 진행 중인 응답 1건** (권고) | 그 답변만 끝내고 세션을 닫는다. 후속 질문은 즉시 차단 | FR-008("신규 AI 요청 즉시 차단")과 정합 |
| (ii) 열린 대화 **세션** | 만료 후 N초 동안 후속 질문도 허용 | **FR-008과 충돌한다.** 후속 질문은 "신규 요청"이다 |

(i)이 맞다고 본다. 다만 사용자에게 보이는 것이 달라지는 지점이라 **팀 확정 1줄**이 필요하다.

### 후보안

| | 방식 | 장점 | 단점 |
|---|---|---|---|
| a | 만료 순간 **즉시 절단** | 가장 단순 · 정책이 칼같다 | 반쯤 쓰인 답변이 잘린다 · **D03/FR-009 위반**("짧은 유예 후 종료") |
| **b (권고)** | 진행 중 응답 완료 허용, 상한 = 기존 60s | 새 타이머 0 · D03 충족 · UX 자연스러움 | 최악의 경우 `leaseEndsAt` 이후 최대 60s 동안 응답이 흐른다(의도된 것) |
| c | 고정 추가 유예 (예: +120s, 후속 질문 허용) | 사용자가 대화를 마무리할 시간을 준다 | **FR-008 충돌** · spec 008 60s 상한 초과 · 새 타이머·새 상태 필요 |
| d | 유예 없이 `done` 이벤트에 "만료됨" 플래그 | 스트림이 항상 정상 종료 | 오류를 성공으로 표현(§3과 같은 문제) |

---

## 5. Push vs FastAPI 자체 deadline

### 권고 — **Push 없음. FastAPI가 받은 `leaseEndsAt`으로 자체 계산**

근거 셋.

1. **시간 만료는 결정적이다.** `endsAt`은 불변이고(§0-2) 양쪽이 같은 UTC 시각을 본다.
   통보로 알아야 할 새 정보가 없다. Push는 **이미 아는 사실을 다시 보내는 채널**이 된다.
2. **BE에 스케줄러가 없다.** 만료는 읽기 시점 판정이다(§0-5). Push는 스케줄러 신설을 요구한다.
3. **지금 만들면 두 번 만든다.** 004가 실시간 전파를 미룬 논거가 그대로 적용된다 —
   *"005가 시작되면 외관·Layout 수정도 같은 채널로 전파해야 하므로, 지금 만료만 따로 푸는 건
   두 번 만드는 일이 된다"* (`부스_임대_구현_정리.md` §8, U-04).

### Push가 실제로 필요해지는 조건 — 시간이 아닌 취소

| 트리거 | 지금 상태 |
|---|---|
| 관리자 강제 비공개 · 신고 처리 | 004 C-05 범위 제외, **U-01**에 걸림 |
| 회원 정지 | endpoint 제거된 상태 (U-01) |
| 회원 탈퇴 | **지금 있다** — §1 권고의 `ai_agents` 확인으로 대체 |

앞의 두 개가 열리는 시점에 **U-04 채널에 함께 태운다.** 그때는 부스 만료·외관·Layout·강제 비공개가
같은 신호를 쓰므로 채널이 하나면 된다.

### 후보안

| | 방식 | 장점 | 단점 |
|---|---|---|---|
| **A (권고)** | FastAPI 자체 deadline | 새 인프라 0 · BE 스케줄러 불필요 · 결정적 | 비시간 취소를 반영 못 함 → 위 표의 트리거가 열릴 때 재논의 |
| B | Spring → FastAPI **webhook** | 취소 즉시 반영 | **스케줄러 신설** · 전달 실패 재시도·순서 문제 · U-04와 중복 채널 |
| C | Redis pub/sub | Redis 이미 있음 · 다수 구독자 | 휘발성 — FastAPI 재기동 중 이벤트 유실 · docs/15가 영구 기록의 Redis 단독 저장을 금지 |
| D | FastAPI가 Spring을 폴링 | 구현 단순 | **A의 정확도를 못 넘으면서 부하만 늘린다.** 폴링 주기가 곧 오차 |

---

## 6. 서버 시간 오차와 재시도

### 6-1. 시각 — 절대 시각이 권위, 상대값은 오차 탐지용

| | 방식 | 장점 | 단점 |
|---|---|---|---|
| A | 절대 시각(UTC ISO-8601)만 | 재기동·저장에 안전 · 이미 `Instant`/`TIMESTAMPTZ`·`jdbc.time_zone: UTC` | 두 서버 시계 오차에 노출 |
| B | 상대값(`remainingSeconds`)만 | 시계 오차 면역 | **프로세스 재기동 시 소실** → 절대 시각이 결국 필요 · 네트워크 지연이 오차로 들어옴 |
| **C (권고)** | 절대 시각 권위 + `remainingSeconds`·`serverTime` 동봉 | 저장은 절대값 · `\|(now_ai + remainingSeconds) − leaseEndsAt\|`로 **오차를 상시 측정** · 추가 비용 0 | 필드 2개 추가 |

**자동 보정은 하지 않는다.** 임계 초과 시 로그·지표만 남긴다 —
`coin_reconciliation_runs`가 "탐지만 하고 고치지 않는" 것과 같은 성격이다. 시계를 앱이 보정하면
어느 쪽이 틀렸는지 아무도 모르게 된다.

**경계 규칙 두 개.**

- **차단 판정은 보수적으로(이른 쪽).** FR-008이 하드 요구, FR-009는 소프트다. 애매하면 막는다.
- **클라이언트 시계는 어떤 판정에도 쓰지 않는다** (헌법 16조).

전제: EC2 + Amazon Time Sync. 실측 전 허용 오차는 **±2s** 정도를 권고하되 수치는 팀 확정 항목이다.

### 6-2. 검증 호출 실패 — fail closed, 단 진행 중 스트림은 건드리지 않는다

| 대상 | 권고 |
|---|---|
| 검증 호출 timeout | **1s** (TTFT 15s 예산의 1/15 이내) |
| 재시도 | **1회** (즉시~200ms 백오프), 총 예산 2s 이내 |
| 최종 실패 시 **신규 대화·신규 질문** | **fail closed** — 거부. 미검증 부스에 대화를 열지 않는다 (헌법 16조) |
| 최종 실패 시 **진행 중 스트림** | **영향 없음.** 이미 검증된 deadline으로 돌아가므로 재검증 대상이 아니다 |

이 분리가 이 안의 핵심이다 — **게이트는 닫고, 이미 통과한 것은 다시 묻지 않는다.**

| | 방식 | 장점 | 단점 |
|---|---|---|---|
| **fail closed (권고)** | 검증 불가 시 거부 | FR-008에 구멍이 없다 · Spring이 죽으면 토큰 갱신부터 막히므로 실질 가용성 손실이 작다 | Spring 순단에 신규 대화가 막힌다 |
| fail open | 검증 불가 시 허용 | 데모 중 Spring 순단에도 AI가 살아 있다 | **만료 부스에 대화가 열린다** — FR-008 구멍. 헌법 16조와도 어긋난다 |
| 캐시 fallback | 직전 검증 결과를 짧게 재사용 | 순단을 흡수 | 상태·TTL이 늘어난다. §1-B에서 이미 대화당 1회뿐인데 캐시할 것이 없다 |

---

## 7. 담당 범위

### BE (Spring) — 내가 한다

| # | 항목 | 규모 |
|---|---|---|
| 1 | `GET /internal/ai/booth-access?boothId=&agentId=` — 임대 유효 + agent 소속·ACTIVE + `leaseEndsAt`·`remainingSeconds`·`serverTime` | 반나절 (§2 결정 후) |
| 2 | 내부 인증 적용 — `AI_문서처리_Job_설계_검토.md` §7-5와 **같은 결정**을 재사용 | 결정 대기 |
| 3 | `docs/08`·`docs/14`에 이 계약 반영 (오류 코드 통일, `status` 어휘) | 문서 |
| 4 | (P0-즉시로 갈 경우) 신설 없이 `GET /api/v1/booths/{boothId}` 사용 안내 | 0 |
| 5 | 상담(spec 011) 경로에 같은 술어 적용 — **FastAPI 무관, Spring 내부에서 끝난다** | 011 착수 시 |

> D03은 "신규 **AI/상담** 요청 차단"이다. 상담은 Spring 소유(`consultations`)이므로
> **이 계약은 AI(FastAPI) 경로에만 필요하다.** 상담 차단은 011에서 같은 repository 술어를 쓰면 된다.

### AI (FastAPI)

| # | 항목 |
|---|---|
| 1 | 대화 생성 시 booth-access 검증 호출, `leaseEndsAt`을 conversation에 저장 |
| 2 | 질문마다 로컬 deadline 비교 + `ai_agents` 존재·ACTIVE 단건 확인 |
| 3 | 만료 시 `409` / 스트림 중이면 `event: error` + `BOOTH_LEASE_EXPIRED` 후 종료, conversation `EXPIRED` |
| 4 | Spring과 같은 오류 봉투 `{code, message, requestId}` |
| 5 | 시계 오차 지표 (`serverTime` 대조), 검증 호출 timeout·재시도·fail closed |

---

## 8. 요약과 팀 확정 필요 항목

### 요약 — 요청서 권장안 대비

| # | 요청서 권장안 | BE 의견 |
|---|---|---|
| 1 | 생성 + **매 질문**마다 서버 간 검증 | ⚠️ **수정 권고** — 생성 시 1회 + 질문마다 로컬 deadline. `endsAt`이 불변이라 매 질문 조회가 시간 만료에 대해 얻는 것이 없고, 헌법 14조가 같은 형태를 이미 기피했다. 탈퇴 창은 `ai_agents` 단건 확인으로 닫는다 |
| 2 | Backend가 검증 결과와 `leaseEndsAt` 전달 | ✅ **동의.** `serverTime`·`remainingSeconds`를 함께 넣기를 추가 권고. **이미 있는 endpoint로 오늘 가능** |
| 3 | 만료 후 신규 질문 즉시 차단 | ✅ **동의** |
| 4 | 진행 중 응답만 완료, `leaseEndsAt + 60s` 절대 상한 | ✅ 동의 · ⚠️ **문장 정정** — 그 상한은 항상 이미 만족되므로 **새 타이머를 만들지 않는다**. 기존 전체 timeout이 곧 유예다 |
| 5 | SSE `error` + `LEASE_EXPIRED` 후 종료 | ⚠️ **코드명 정정** — `BOOTH_LEASE_EXPIRED` 재사용. 이미 enum·FE 분기에 있는 코드다 |
| 6 | 60초를 spec 008 전체 timeout과 일치 | ✅ **동의** (헌법 19조와도 일치) |
| — | (누락) 검증 대상에 **agentId–boothId 짝** | ➕ **추가 권고** — 클라이언트가 보낸 `agentId`를 그대로 믿으면 헌법 16·17조가 앱 로직에만 의존한다 |
| — | (누락) **Push 여부** | ✅ 불필요. 비시간 취소(U-01)가 열릴 때 U-04 채널에 함께 태운다 |

### 팀 확정이 필요한 항목 (헌법 30조 — 임의 확정하지 않음)

| # | 항목 | 결정권 | 비고 |
|---|---|---|---|
| 1 | **검증 시점** — 매 질문(A) vs 생성 1회 + 로컬 deadline(B) | AI + BE | §1. 실질 차이는 "탈퇴·관리자 조치 반영 지연을 감수하는가" 하나다 |
| 2 | D03 "이미 열린 대화" = **진행 중 응답 1건** vs 대화 세션 | 기획 + AI + FE | §4. 세션으로 읽으면 FR-008과 충돌한다 |
| 3 | 오류 코드 `BOOTH_LEASE_EXPIRED` 재사용 | BE + AI + FE | §3. 헌법 24조 통보 대상 |
| 4 | 내부 endpoint 신설 vs 기존 공개 endpoint 사용 | BE + AI | §2. 신설이면 §5 인증 결정과 묶인다 |
| 5 | 내부 API 인증 방식 (서비스 토큰 / SG / mTLS) | BE + 인프라 | `AI_문서처리_Job_설계_검토.md` §7-5와 **동일 항목** — 함께 정한다 |
| 6 | 허용 시계 오차(±2s 권고) · 검증 timeout 1s · 재시도 1회 | BE + AI + 인프라 | §6. 실측 후 조정 |
| 7 | 검증 실패 시 fail closed | BE + AI + 기획 | §6-2. 데모 안정성을 우선하면 논쟁 여지 |
| 8 | Conversation 저장 위치·`EXPIRED` 상태 어휘 | AI + BE | spec 008 C-01 미결. **`ai_conversations` 테이블이 V1에 없고, 탈퇴 삭제 대상에도 없다** — 개인정보 삭제(D11·001 탈퇴)와 연결되는 별개 결손 |

### 이 계약이 나중에 어디로 확장되는가

- **C-06 게스트 AI 이용**: 게스트도 쓴다면 검증 응답에 사용자 종류가 필요 없다(부스만 본다) — 이 계약은 그대로 유효
- **C-07 AI 이용료(코인)**: 과금은 **반드시 Spring**이다 (헌법 20조 — Coin 변경은 REST/DB 트랜잭션).
  그때 booth-access 검증과 차감을 같은 호출에 묶을 수 있으므로 §1-B의 "대화당 1회"가 오히려 유리해진다
- **U-01 관리자 강제 비공개**: §1-B의 유일한 구멍이 여기서 열린다. **그 spec 착수 시 이 문서 §5를 다시 본다**
