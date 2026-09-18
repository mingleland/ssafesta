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
    "mine": false,
    "facade": {
      "themeCode": "DEFAULT",
      "primaryColor": "#3B82F6",
      "signText": "AI 프로젝트 전시관",
      "logoUrl": "https://cdn.example.com/logo.png"
    }
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
    "mine": false,
    "facade": null
  }
]
```

| 필드 | 값 |
|---|---|
| `type` | `USER_RENTAL` \| `ADMIN` \| `EVENT`. **`USER_RENTAL`만 임대할 수 있다**(FR-002) — 나머지는 `409 BOOTH_SLOT_NOT_RENTABLE`이다 |
| `status` | `AVAILABLE` \| `OCCUPIED`. **`ends_at`을 반영한 값이다** — 만료된 임대가 남아 있어도 `AVAILABLE`로 보인다 |
| `remainingSeconds` | 남은 시간. 만료·미임대면 `null` (FR-007, SC-005) |
| `entryAvailable` | 지금 입장 가능한가. 만료됐으면 `false` |
| `mine` | 요청자가 임차인인가. 비인증 요청은 항상 `false` |
| `facade` | 점유 중인 부스의 외관 4필드. **빈 슬롯은 `null`**이다. 값은 `GET /booths/{boothId}`의 `facade`와 같다 (S15P21A604-622 · GitLab #171) |

> **간판 12개를 요청 하나로 그린다.** Unity가 축제장에 들어서면 부스마다 간판을 세우는데, 이 필드가 없으면 슬롯 목록 1회 + 부스당 상세 1회로 최대 13요청이 든다. 부스 행은 `boothName` 때문에 이미 읽고 있으므로 질의가 늘지 않는다.
>
> 프로젝트명·대표 이미지는 여기 싣지 않는다 — 시야에 든 부스만 `GET /booths/{boothId}/projects/published`로 따로 읽는다(#171 합의).

> `status`가 `AVAILABLE`인데 월드에는 부스가 보일 수 있다. 만료를 월드에 실시간 전파하지 않기 때문이다(FR-019). **이 응답이 권위다.**

### 슬롯 1은 `EVENT`다 (2026-09-10, S15P21A604-615 · GitLab #170)

축제장 1번 자리는 이벤트 부스이고 임대 대상이 아니다. **클라이언트는 슬롯 번호를 박아 두지 말고 이 `type`을 읽는다** — 자리가 바뀌는 날 하드코딩은 조용히 틀린다.

- 임대 시도는 `409 BOOTH_SLOT_NOT_RENTABLE`이다. 별도 검사가 아니라 `USER_RENTAL`만 통과시키는 기존 게이트 그대로다.
- **이벤트 콘텐츠는 부스가 아니다.** 경품 상점과 그 설문은 부스 밖에서 `surveyKey`로 찾는다(GitLab #173). 부스에 태우려면 가짜 유저·임대·게시 레이아웃이 필요하고, 그중 하나가 어긋나면 이벤트가 조용히 404가 된다.
- 그래서 `GET /api/v1/booths/{boothId}`에 `boothType`을 두지 **않는다**. `EVENT` 슬롯은 임대되지 않아 부스 행이 생길 수 없고, 그 응답의 값은 언제나 "프로젝트 부스" 하나뿐이라 물어볼 것이 없다.

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
  "chargedCoin": 50,
  "balanceAfter": 200
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

## DELETE /api/v1/booth-slots/{slotId}/leases/mine

내 활성 임대를 **반납**한다 (FR-020, D12). **환불은 없다** (FR-021).

**204 No Content** — 본문 없음.

돌려줄 것이 없다. 잔액이 변하지 않으므로 `POST`처럼 `balanceAfter`를 실을 이유가 없고, 슬롯 상태는 `GET /api/v1/booth-slots`가 권위다.

**처리 순서** (전부 하나의 트랜잭션 — FR-020)

1. `MEMBER` 인증 확인 (게스트는 `403`)
2. 요청자의 지갑 행 잠금 — 임대 경로와 **같은 직렬화**다. 반납과 재임대가 같은 락을 타야 "반납이 커밋되기 전에 재임대가 한도 검사를 통과"하는 창이 안 생긴다
3. 요청자의 `ACTIVE` 임대 행을 **잠근다** (시간 조건 없이)
4. 잠근 뒤 유효성(`status = ACTIVE AND ends_at > now`)을 **다시** 판정 — 유효하지 않으면 `404`
5. 잠긴 임대의 슬롯이 경로의 `{slotId}`와 일치하는지 확인 — 다르면 `404`
6. 임대를 `CANCELLED`로 전이하고, 부스의 슬롯 연결을 끊고, 그 부스의 AI 문서를 비활성화한다 — **만료와 같은 경로**다

**경로에 `{slotId}`를 두는 이유.** 활성 임대는 1인 1개(D01)라 슬롯 없이도 찾을 수 있다. 그래도 **클라이언트가 어느 자리를 반납하는지 말하게** 해서, 낡은 화면이 엉뚱한 부스를 날리는 것을 막는다. `POST`와 같은 자리에 서는 이점도 있다.

**응답 코드**

| 코드 | 오류 코드 | 상황 |
|---|---|---|
| 204 | — | 반납 성공. **코인은 돌아오지 않는다** |
| 401 | — | 인증 실패 |
| 403 | `MEMBER_ONLY` | 게스트 토큰 (FR-016) |
| 404 | `ACTIVE_LEASE_NOT_FOUND` | 반납할 내 활성 임대가 이 자리에 없다 |

> ⚠️ **재시도한 `DELETE`는 `404`다.** 이미 반납됐기 때문이며 **오류 상황이 아니다.** 클라이언트는 이것을 오류 배너로 띄우지 말고 **슬롯 목록과 내 부스를 다시 읽는 신호**로 쓴다. `ACTIVE_LEASE_NOT_FOUND`는 "임대가 아예 없다"와 "내 임대가 다른 자리에 있다"를 **구분하지 않는다** — 클라이언트가 할 일이 양쪽 다 새로고침으로 같기 때문이다.

> **만료와 경계에서 만나면** 임대 행 락으로 직렬화된다. 반납이 먼저 잠그고 그때까지 유효하면 `CANCELLED`, 만료 배치가 먼저 잠갔으면 `EXPIRED`가 되고 반납은 `404`다. **해제는 어느 쪽이든 한 번만** 일어난다.

> **이미 열려 있던 AI 상담은 즉시 끊기지 않는다.** 대화는 생성 시 저장한 `leaseEndsAt`을 보기 때문이다(spec 008 FR-024). 신규 대화는 막히고, 열린 대화도 문서가 비활성이라 "자료 미준비" 안내만 나가며, 30분 유휴 TTL로 사라진다. spec 004 Edge Cases 참조.

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
    "chargedCoin": 50
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
| 변심 환불 | D06 — **환불 없음.** 서버 오류는 `REFUND` 원장으로 복구 |
| 임대 반납(취소) | D12 — **있다.** `DELETE /booth-slots/{slotId}/leases/mine`, 환불 없이 슬롯만 해제 |
| 만료 사전 알림 | C-03 제외. 남은 시간 표시로 충족 |
| 신고·관리자 비공개 처리 | C-05 제외 (016 소관 + ADMIN 권한 모델 미정) |
| 부스 변경 실시간 이벤트 | FR-019 — 범위 밖. `docs/HDD/부스_변경_신호_계약.md` |
| 부스 외관(facade) 설정 | P1 (docs/02 STUDIO-13). 스키마도 미정 (U-05) |
