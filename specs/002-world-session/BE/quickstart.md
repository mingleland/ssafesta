# Quickstart: World Session 발급 API (002 BE분)

**Plan**: [plan.md](plan.md) | 검증 시나리오와 통보 체크리스트. 구현 코드는 여기 없다.

## 1. 전제

```bash
# 저장소 루트에서. DB는 compose로 (기존과 동일)
docker compose -f backend/compose.yaml up -d

# 신규 env — Base64 32바이트 이상 (예시는 openssl로 생성)
export CONNECTION_TOKEN_SECRET=$(openssl rand -base64 48)
export JWT_SECRET=<기존 값>
```

- `CONNECTION_TOKEN_SECRET` 미설정·32바이트 미만이면 **기동이 실패해야 정상**이다 (R-07 fail-fast). `jwt-secret`과 같은 패턴으로 기본값을 두지 않는다.
- `backend/.env.example`에는 **이름만** 올린다(값 비움, 헌법 15조). compose가 주입하는 값이 아니라 실행 환경에 `export` 하는 값이라는 주석을 함께 둔다 — 지금 `JWT_SECRET`도 문서화돼 있지 않아 같은 줄에 함께 적는다.
- local 프로필의 endpoint 기본값은 `ws://127.0.0.1:7777` — 로컬 Docker `festa-world-01`과 일치.

## 2. 수동 검증 (curl)

```bash
# ① 게스트 토큰 발급
AT=$(curl -s -X POST http://localhost:8080/api/v1/auth/guest | jq -r .accessToken)

# ② 세션 발급 — 본문 없이
curl -s -X POST http://localhost:8080/api/v1/world-sessions -H "Authorization: Bearer $AT" | jq
# 기대: sessionId(ws_*), worldId "11F", channelId "11F-01",
#       endpoint {scheme:"ws", host:"127.0.0.1", port:7777}, connectionToken, expiresAt(+120s)

# ③ 층 파라미터 — 유효/무효
curl -s -X POST http://localhost:8080/api/v1/world-sessions -H "Authorization: Bearer $AT" \
  -H 'Content-Type: application/json' -d '{"worldId":"11F"}' | jq .worldId          # "11F"
curl -s -X POST http://localhost:8080/api/v1/world-sessions -H "Authorization: Bearer $AT" \
  -H 'Content-Type: application/json' -d '{"worldId":"1F"}' | jq                    # 400 VALIDATION_FAILED, field:"worldId"

# ④ 미인증
curl -s -X POST http://localhost:8080/api/v1/world-sessions | jq .code              # "UNAUTHORIZED"
```

토큰 내용 확인은 외부 사이트에 붙여넣지 말고 로컬에서:

```bash
curl -s -X POST http://localhost:8080/api/v1/world-sessions -H "Authorization: Bearer $AT" \
  | jq -r .connectionToken | cut -d. -f2 | base64 -d 2>/dev/null | jq
# 기대 claims: jti·sub·role·playerId·nickname·avatarCode·sessionId·worldId·channelId·iat·exp
# 게스트: role "GUEST", nickname "게스트-xxxx", avatarCode null
```

## 3. 자동 검증 (테스트가 고정하는 시나리오)

```bash
cd backend && ./mvnw test
```

| # | 시나리오 | 근거 |
|---|---|---|
| 1 | 회원 AT → 200, 스키마 전 필드 + claims가 `users` 행과 일치 | OpenAPI, 헌법 16조 |
| 2 | 게스트 AT → 200, `role=GUEST`·파생 nickname·`avatarCode=null` | R-05 |
| 3 | 미인증 → 401 봉투 | OpenAPI |
| 4 | 정지 계정 → 403 | R-08 |
| 5 | `worldId:"11F"` → 200 / `worldId:"1F"` → 400 `FIELD_INVALID` `field:"worldId"` | FR-012, R-03 |
| 6 | `exp - iat` == 120s, `expiresAt` == `exp`, `iss`/`aud` 고정값 | world-entry-token.md |
| 7 | 연속 2회 발급 → `jti`·`sessionId`·토큰 전부 상이 | 불변식 4 |
| 8 | HS256 헤더 + **`JWT_SECRET`로는 서명 검증 실패** (키 분리 증명) | R-04 |
| 9 | 로그에 토큰 원문·Secret 부재 | 불변식 3 |
| 10 | 기존 전체 회귀 green | — |

8번이 이 설계의 존재 이유를 고정하는 테스트다 — 실수로 `JwtEncoder`를 재사용하면 여기서 죽는다.

## 4. 통보 체크리스트 (헌법 24조 — 머지 전 게시, 승인 후)

| # | 대상 | 내용 |
|---|---|---|
| ① | Jira `S15P21A604-84` | 제목·본문 `GET` → **`POST`** 정정 (R-01 — docs/08·ADR·OpenAPI 전부 POST) |
| ② | Jira `S15P21A604-170` | 완료 조건에서 "발급 저장(1회용 마킹)·사용 토큰 정리" 제거 → **`jti` 유일성 + TTL 120초**로 축소. 재사용 차단 원장은 게임 서버 소유 (R-06, FR-013·014) |
| ③ | Infra (정승욱) | `world-session.openapi.yaml` 요청 본문에 optional `worldId` 추가 요청 — #31 결정·헌법 9조 반영, 가산적 변경 (R-03) |
| ④ | Unity (게임 파트) | 게스트 nickname 파생 규칙(`게스트-xxxx`) 통보 + avatarCode claim 크기(최대 3800자)가 NGO approval payload 상한을 견디는지 -85 실측 항목으로 (R-05·R-09) |
| ⑤ | `docs/08` §16 정합화 | **하지 않는다** — infra-003 T070 소유. 중복 편집 금지 |

## 5. 완료 판정 (Jira -84 완료 조건과 대응)

- [ ] 응답 스키마가 계약과 일치 — §3 테스트 1·2
- [ ] 통합 테스트 (권한·스키마) — §3 전체
- [ ] OpenAPI(springdoc) 반영 — `/v3/api-docs`에 `POST /api/v1/world-sessions` 노출
- [ ] 기록 의무 — `docs/HDD/작업일지.md` 갱신, 문제 발생 시 T-번호 (헌법 29조)
