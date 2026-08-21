# Contract: Wallet REST API (조회 전용)

**Spec**: `003-wallet-coin` | **Consumer**: React 프론트엔드 | **Date**: 2026-08-19

003이 HTTP로 노출하는 것은 **조회 2종뿐이다.** 코인을 변경하는 REST endpoint는 만들지 않는다 (FR-010·FR-011, 헌법 2·16조). 차감은 [wallet-service-api.md](wallet-service-api.md)의 내부 서비스 계약으로만 일어난다.

공통:

- 인증: `Authorization: Bearer {accessToken}`, role은 **`MEMBER`만**. GUEST는 `403`.
- 시각은 ISO-8601 UTC(`Instant`)로 반환한다. 표시 시각대 변환(Asia/Seoul)은 프론트가 한다.
- 금액 단위는 코인(정수).

---

## GET /api/v1/wallets/me

내 지갑 잔액을 조회한다 (FR-001).

**Request**: body 없음.

**200 OK**

```json
{
  "userId": 42,
  "balance": 250,
  "updatedAt": "2026-08-19T04:12:03.221Z"
}
```

**응답 코드**

| 코드 | 상황 |
|---|---|
| 200 | 정상 |
| 401 | Access Token 없음·만료·세션 폐기 |
| 403 | GUEST 토큰 (게스트는 지갑이 없다 — FR-003b) |
| 404 | 회원인데 지갑이 없다 — **정상 상태가 아니다.** 서버는 ERROR 로그를 남긴다 (지갑은 회원 생성 시 함께 만들어진다) |

> 이 요청이 **인증된 MEMBER 요청**이므로, 당일 일일 지급이 아직이면 응답 전에 지급이 반영된다 (FR-003). 즉 이 API의 `balance`는 항상 일일 지급이 반영된 값이다.

---

## GET /api/v1/wallets/me/transactions

내 거래 내역을 페이지 단위로 조회한다 (FR-012, US3).

**Query parameters**

| 이름 | 기본값 | 제약 |
|---|---|---|
| `page` | `0` | 0 이상 |
| `size` | `20` | 1~100. 범위를 벗어나면 `400` |

정렬은 `created_at DESC, id DESC` 고정이다 (클라이언트가 정렬을 지정하지 않는다).

**200 OK**

```json
{
  "content": [
    {
      "id": 1187,
      "entryType": "SPEND",
      "amount": -100,
      "balanceAfter": 150,
      "reasonType": "LEASE_PAYMENT",
      "referenceType": "BOOTH_LEASE",
      "referenceId": "317",
      "createdAt": "2026-08-19T04:12:03.221Z"
    },
    {
      "id": 1186,
      "entryType": "CHARGE",
      "amount": 50,
      "balanceAfter": 250,
      "reasonType": "DAILY_GRANT",
      "referenceType": null,
      "referenceId": null,
      "createdAt": "2026-08-19T00:03:11.004Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 37,
  "totalPages": 2
}
```

**필드 규약**

| 필드 | 값 |
|---|---|
| `entryType` | `CHARGE` \| `SPEND` \| `REWARD` \| `REFUND` |
| `amount` | **부호 있는 값.** 지급 양수, 차감 음수 |
| `balanceAfter` | 해당 항목 반영 직후 잔액 |
| `reasonType` | 사유 코드. 아래 표 참조 |
| `referenceType`·`referenceId` | 참조 대상. 없으면 `null` |

**reasonType 코드** (003 시점. 004·010·012·014가 값을 추가한다)

| 코드 | 의미 | entryType |
|---|---|---|
| `INITIAL_GRANT` | 가입 초기 지급 200코인 | `CHARGE` |
| `DAILY_GRANT` | 일일 지급 50코인 | `CHARGE` |
| `ADMIN_ADJUSTMENT` | 관리자 조정 | `CHARGE` 또는 `SPEND` |

> 프론트는 **모르는 `reasonType`을 만나도 깨지지 않아야 한다.** 코드를 그대로 표시하거나 일반 문구로 대체하되, 항목 자체를 숨기지 않는다 — 숨기면 사용자가 잔액 변화를 설명할 수 없게 되어 SC-005를 위반한다.

**응답 코드**

| 코드 | 상황 |
|---|---|
| 200 | 정상 (내역이 없으면 `content: []`) |
| 400 | `page`·`size`가 범위를 벗어남 |
| 401 | 인증 실패 |
| 403 | GUEST 토큰 |

---

## 만들지 않는 것 (의도적 부재)

| 없는 것 | 이유 |
|---|---|
| `POST /wallets/me/spend` 등 차감 API | 클라이언트가 차감 대상·금액을 지시하면 헌법 16조 위반. 차감은 기능(임대·AI·미니게임)의 서버 로직에서만 발생한다 |
| 충전 API | 현금 충전을 하지 않는다. 코인은 지급·보상으로만 들어온다 (팀 결정 2026-08-19) |
| 관리자 조정·정합성 점검 API | ADMIN 권한 모델 미정 (U-01). 서비스 계층까지만 구현한다 |
| 게스트용 지갑 API | 게스트는 지갑이 없다 (헌법 12조) |
