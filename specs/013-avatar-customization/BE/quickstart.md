# Quickstart: 013a 아바타 저장 API 검증 (BE분)

**Phase 1** | 2026-08-24 | 계약: [contracts/avatar-profile-api.md](../contracts/avatar-profile-api.md) · 규칙: [data-model.md](data-model.md) §3

## 전제

- Docker 실행 중 (Testcontainers가 PostgreSQL 17을 띄운다 — 003·004·005와 동일)
- 저장소 루트에서 실행

## 1. 통합 테스트

```bash
cd backend && ./mvnw test -Dtest=MyAccountAvatarApiIntegrationTest
```

전체 회귀:

```bash
cd backend && ./mvnw test
```

**기대**: 전부 green. 기존 테스트(#58 봉투·닉네임·탈퇴)가 하나도 깨지지 않아야 한다 — `MyAccountResponse` 필드 추가는 가산적 변경이다.

## 2. 수동 확인 (선택)

```bash
# 저장 — 프리셋 형식
curl -i -X PUT localhost:8080/api/v1/users/me/avatar \
  -H "Authorization: Bearer <member-access-token>" -H "Content-Type: application/json" \
  -d '{"avatarCode":"sk_01"}'
# → 200 {"avatarCode":"sk_01"}

# 복원 — 프로필에 실려 온다
curl -s localhost:8080/api/v1/users/me -H "Authorization: Bearer <member-access-token>"
# → { ..., "avatarCode": "sk_01" }

# 거부 — 3801자
curl -i -X PUT localhost:8080/api/v1/users/me/avatar \
  -H "Authorization: Bearer <member-access-token>" -H "Content-Type: application/json" \
  -d "{\"avatarCode\":\"$(printf 'a%.0s' {1..3801})\"}"
# → 400 VALIDATION_FAILED, errors[0] = { rule: FIELD_INVALID, field: "avatarCode", ... }
```

## 3. 시나리오 ↔ 근거 매핑

통합 테스트가 아래를 전부 커버해야 구현 완료다. (전체 목록과 순서는 tasks.md가 확정한다.)

| 시나리오 | 근거 |
|---|---|
| `PUT` 200 + echo → `GET /users/me` 왕복 **바이트 동일** | SC-002 서버 몫, data-model §2 불변식 |
| `fa\|3=SK_Hair_Long_01\|c=FF8800` 모듈러 실측형 통과 | T-24 회귀 방지 — 파이프·언더스코어·대문자가 게이트에 걸리면 안 된다 |
| 경계: 3800자 통과 / 3801자 400 | R-04 |
| 제어문자(`\n`) 포함 400 / blank 400 | R-03, data-model §3 |
| 400 봉투: `VALIDATION_FAILED` + `FIELD_INVALID`/`field: "avatarCode"` | R-07, docs/08 §1.3-1 |
| 게스트 토큰 → 403 `MEMBER_ONLY` | 헌법 12·16조, R-08 |
| 미인증 → 401 | 리소스 서버 기본 |
| 신규 사용자 `GET /users/me` → `avatarCode: null` | data-model §2 — 서버는 기본값을 만들지 않는다 |
| 재저장(값 → 다른 값) 후 최신값 복원 | FR-013 |

## 4. 완료 후 통보 (헌법 24조·29조)

- [ ] `contracts/avatar-profile-api.md` 상태줄 갱신(제안 → 구현) + 확정값 기입: PUT 채택, `GET /users/me` 포함 분기 채택, 길이 3800, 문자셋 인쇄 가능 ASCII
- [ ] 파트 통보 — Unity: mock 주석 `PATCH` → `PUT` 정정 요청(R-01) + 문자셋 확인(R-03). FE: `MyAccountResponse.avatarCode` 필드 추가(가산적)
- [ ] `docs/08` §2(Auth/User)에 endpoint 추가
- [ ] `docs/HDD/작업일지.md` 기록, 문제 발생 시 트러블슈팅 T-번호
