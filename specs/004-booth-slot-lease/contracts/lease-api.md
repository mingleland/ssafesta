# Contract: Booth Slot / Lease REST API

**Spec**: `004-booth-slot-lease` | **Consumer**: React 프론트엔드 · Unity 클라이언트(조회만) | **Date**: 2026-08-19

`docs/08_Backend_API_명세서.md` §3의 형태를 따른다. 차이가 있는 곳은 각 절에 표시했다.

공통:

- 인증: `Authorization: Bearer {accessToken}`. **임대는 `MEMBER`만** — GUEST는 `403` (FR-016, 헌법 12조)
- 시각은 ISO-8601 UTC로 반환한다. 표시 시각대(Asia/Seoul) 변환은 클라이언트가 한다
- 금액 단위는 코인(정수)
- **코인을 직접 변경하는 endpoint는 없다.** 차감은 임대 처리의 서버 로직에서만 일어난다 (헌법 2·16조)

---

## GET /api/v1/booth-slots

전체 슬롯 상태 (FR-001). 인증 없이도 조회 가능하다 — 게스트 관람 경로다.

**200 OK**

```json
[
  {
    "slotId": 5,
    "slotCode": "F11-R01",
    "floorNo": 11,
    "type": "USER_RENTAL",
    "status": "OCCUPIED",
    "boothId": 7,
    "boothName": "AI 프로젝트 전시관",
    "leaseEndsAt": "2026-08-20T07:12:03Z",
    "remainingSeconds": 71040,
    "entryAvailable": true,
    "mine": false
  },
  {
    "slotId": 6,
    "slotCode": "F11-R02",
    "floorNo": 11,
    "type": "USER_RENTAL",
    "status": "AVAILABLE",
    "boothId": null,
    "boothName": null,
    "leaseEndsAt": null,
    "remainingSeconds": null,
    "entryAvailable": false,
    "mine": false
  }
]
```

| 필드 | 값 |
|---|---|
| `status` | `AVAILABLE` \| `OCCUPIED`. **`ends_at`을 반영한 값이다** — 만료된 임대가 남아 있어도 `AVAILABLE`로 보인다 |
| `remainingSeconds` | 남은 시간. 만료·미임대면 `null` (FR-007, SC-005) |
| `entryAvailable` | 지금 입장 가능한가. 만료됐으면 `false` |
| `mine` | 요청자가 임차인인가. 비인증 요청은 항상 `false` |

> `status`가 `AVAILABLE`인데 월드에는 부스가 보일 수 있다. 만료를 월드에 실시간 전파하지 않기 때문이다(FR-019). **이 응답이 권위다.**

---

## POST /api/v1/booth-slots/{slotId}/leases

빈 슬롯을 임대한다 (FR-002).

**Request**

```json
{ "durationDays": 1 }
```

`durationDays`는 **1만 허용**한다. P0에 연장이 없고 기간이 24시간 고정이다 (D02·D05). 다른 값은 `400`.

**201 Created**

```json
{
  "leaseId": 301,
  "boothId": 7,
  "slotId": 5,
  "startsAt": "2026-08-19T07:12:03Z",
  "endsAt": "2026-08-20T07:12:03Z",
  "chargedCoin": 100,
  "balanceAfter": 150
}
```

**처리 순서** (전부 하나의 트랜잭션 — FR-003)

1. `MEMBER` 인증 확인
2. 슬롯이 `USER_RENTAL`인지 확인
3. 그 슬롯에 **유효한** 활성 임대가 없는지 확인 (만료된 것은 없는 것으로 본다)
4. 요청자에게 유효한 활성 임대가 없는지 확인 (D01)
5. 만료된 이전 임대가 있으면 `EXPIRED`로 전이하고 슬롯 연결 해제 (FR-017)
6. 요청자의 Booth를 확보 (없으면 생성) 후 슬롯 연결
7. 임대 생성
8. `WalletService.spend(...)` — 키 `LEASE_PAYMENT:BOOTH_LEASE:{leaseId}`

**응답 코드**

| 코드 | 오류 코드 | 상황 |
|---|---|---|
| 201 | — | 임대 성공 |
| **200** | — | **요청자가 이미 그 슬롯의 임차인이다.** 새로 차감하지 않고 기존 임대를 반환한다 (FR-018, 재시도 안전) |
| 400 | — | `durationDays`가 1이 아니다 |
| 401 | — | 인증 실패 |
| 403 | — | 게스트 토큰 (FR-016) |
| 404 | — | 슬롯 없음 |
| 409 | `BOOTH_SLOT_NOT_RENTABLE` | `USER_RENTAL` 슬롯이 아니다 |
| 409 | `BOOTH_SLOT_ALREADY_LEASED` | 다른 사용자가 임대 중이다 |
| 409 | `ACTIVE_LEASE_LIMIT` | 요청자에게 이미 활성 임대가 있다 (D01) |
| 409 | `INSUFFICIENT_COIN` | 잔액 부족. **코인은 차감되지 않는다** |

> **동시 요청**: 두 사용자가 같은 빈 슬롯에 동시에 요청하면 정확히 하나만 `201`을 받고, 다른 하나는 `BOOTH_SLOT_ALREADY_LEASED`다. 실패한 쪽은 트랜잭션 전체가 롤백되므로 **코인이 차감되지 않는다** (SC-002, US2-2).

---

## GET /api/v1/booths/mine

내 부스와 현재 임대 (FR-007). `MEMBER` 전용.

**200 OK**

```json
{
  "boothId": 7,
  "name": "AI 프로젝트 전시관",
  "status": "ACTIVE",
  "lease": {
    "leaseId": 301,
    "slotId": 5,
    "slotCode": "F11-R01",
    "startsAt": "2026-08-19T07:12:03Z",
    "endsAt": "2026-08-20T07:12:03Z",
    "remainingSeconds": 71040,
    "chargedCoin": 100
  }
}
```

| 상황 | 응답 |
|---|---|
| 부스가 있고 임대 중 | 위와 같이 `lease` 채워짐 |
| 부스는 있으나 임대 만료 | `status: "INACTIVE"`, `lease: null` — **콘텐츠는 보존돼 있다** (FR-010) |
| 부스가 없음 (임대한 적 없음) | `204 No Content` |

---

## GET /api/v1/booths/{boothId}

공개 가능한 부스 정보. 방문자가 입장 전에 확인한다.

**200 OK**

```json
{
  "boothId": 7,
  "slotId": 5,
  "name": "AI 프로젝트 전시관",
  "leaseStatus": "ACTIVE",
  "entryAvailable": true,
  "endsAt": "2026-08-20T07:12:03Z"
}
```

**만료된 부스 — 200이 아니라 409다** (FR-019)

```json
{
  "code": "BOOTH_LEASE_EXPIRED",
  "message": "임대가 만료된 부스입니다."
}
```

만료를 월드에 실시간 전파하지 않으므로 **부스가 화면에 남아 있는 상태에서 접근이 일어난다.** 조용히 빈 화면을 주지 않고 만료 사실을 명시적으로 알린다 — 사용자가 상황을 이해할 수 있어야 한다.

| 코드 | 상황 |
|---|---|
| 200 | 임대 중인 부스 |
| 404 | 부스 없음 |
| 409 `BOOTH_LEASE_EXPIRED` | 임대가 만료됐거나 슬롯에 연결돼 있지 않다 |

> 소유자 본인의 조회는 `GET /booths/mine`을 쓴다. 이 endpoint는 방문자 관점이라 만료된 부스를 열어주지 않는다.

---

## 만들지 않는 것 (의도적 부재)

| 없는 것 | 이유 |
|---|---|
| 임대 연장·자동 갱신 | D05 — P0는 연장 없음. 만료 후 재임대 |
| 임대 취소·변심 환불 | D06 — 변심 환불 없음. 서버 오류는 `REFUND` 원장으로 복구 |
| 만료 사전 알림 | C-03 제외. 남은 시간 표시로 충족 |
| 신고·관리자 비공개 처리 | C-05 제외 (016 소관 + ADMIN 권한 모델 미정) |
| 부스 변경 실시간 이벤트 | FR-019 — 범위 밖. `docs/HDD/부스_변경_신호_계약.md` |
| 부스 외관(facade) 설정 | P1 (docs/02 STUDIO-13). 스키마도 미정 (U-05) |
