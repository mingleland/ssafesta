# Quickstart: 프로젝트 전시 (009 BE분)

**Date**: 2026-08-28 | **Spec**: [../spec.md](../spec.md) | **계약**: [../contracts/project-api.md](../contracts/project-api.md)

구현이 끝났는지 사람이 확인하는 절차다. 상세 규칙은 계약과 [data-model.md](data-model.md)에 있고
여기서 되풀이하지 않는다.

---

## 1. 전제

| | |
|---|---|
| 빌드 | **Maven.** `backend/mvnw`(Git Bash) · `backend\mvnw.cmd`(PowerShell). **Gradle 아니다** |
| DB | PostgreSQL 17. 통합 테스트는 Testcontainers가 띄운다 — 로컬 DB 불필요 |
| 브랜치 | `feat/S15P21A604-110-project-api` (`origin/develop` 기준) |
| 마이그레이션 | `V14__project_one_per_booth.sql` 한 개. 앞 버전은 V13까지 |

---

## 2. 회귀 기준선 — 착수 직후 한 번 재고 적어 둔다

```bash
cd backend && ./mvnw test
```

`origin/develop` 기준 실측값을 [tasks.md](tasks.md)에 기록한 뒤 시작한다. 참고값은
2026-08-28 016 머지 시점의 **43 클래스 / 393 테스트, 실패 0**이다 — 그대로 믿지 말고 다시 잰다.

**이 단계를 건너뛰면 안 되는 이유**: `HttpUrlValidator` 추출이 `BoothHomepageService`를 건드린다.
기준선을 모르면 나중에 실패 1건이 내가 낸 것인지 원래 있던 것인지 판정할 수 없다.

---

## 3. 자동 검증

```bash
cd backend && ./mvnw test
```

기대: **기준선 + 4 클래스, 실패 0** — 47 클래스 / 459 테스트 (2026-08-28 실측).

| 테스트 | 무엇을 지키나 |
|---|---|
| `project/ProjectApiIntegrationTest` | 계약 전부 — 아래 §3-1 |
| `project/ProjectValidationApiIntegrationTest` | US2 — 거부 사유가 전달되는지 |
| `project/ProjectConcurrencyIntegrationTest` | 동시 `POST`에서도 부스당 1개 (I-1) |
| `project/ProjectConstraintTranslationTest` | 제약 이름별 번역 (컨테이너 없음) |
| `booth/BoothHomepageApiIntegrationTest` (기존 19개) | **검증기 추출이 016 문구를 안 깼는지** |

마지막 줄이 핵심이다. 문구가 한 글자만 달라져도 이 19개가 잡는다 — 그게 추출을 안전하게 만드는
장치다.

### 3-1. `ProjectApiIntegrationTest`가 덮어야 하는 것

Jira 완료 조건 3개:

1. **URL 검증 실패 field 오류 계약** — 5개 URL 필드를 `@ParameterizedTest`로 돌려 각각에
   `javascript:alert(1)` 투입. `400 VALIDATION_FAILED` + `errors[0].rule == "FIELD_INVALID"` +
   `errors[0].field == <그 필드명>`.
   **메시지가 "형식이 올바르지 않습니다"가 아니라 "http 또는 https로 시작해야 합니다"인지까지
   단언한다** — 검증 순서(scheme이 host보다 먼저)가 살아 있는지 잡는 유일한 자리다
2. **타 Booth 수정 차단** — A 부스 소유자 토큰으로 B 부스 프로젝트에 `PATCH` →
   `403 BOOTH_EDITOR_FORBIDDEN`
3. **부스당 1개** — 같은 부스에 `POST` 두 번 → 두 번째 `409 PROJECT_ALREADY_EXISTS`

추가로 반드시:

4. **URL 5필드 삭제 — 파라미터화** — 값이 있는 상태에서 `{"<필드>": null}` `PATCH` → 그 필드만
   `null`, 나머지 4개 그대로. null 계약을 실제로 지키는지 보는 자리
5. **왕복 무손실** — 스킴을 `HtTpS`로 저장하고 그대로 돌아오는지. 정규화가 끼어들면 이 케이스만 잡는다
6. **C-06 세 갈래** — `{"description": null}` 삭제 / 키 누락 유지 / `{}` 400.
   ⚠️ `jsonPath().doesNotExist()`는 **명시적 null도 통과**한다(T-97). `value(nullValue())` +
   키 존재를 함께 단언한다
7. **만료 부스** — `PATCH` → `409 BOOTH_LEASE_EXPIRED`, 그러나 `GET`은 값을 돌려준다 (FR-008)
8. **`name` 경계** — 100자 통과 / 101자 400 / `null` 400 / 공백만 400
9. **게스트 거부** — `403 MEMBER_ONLY`
10. **빈 목록** — 프로젝트 없는 부스의 `GET` → `200 {"projects": []}`. **404 아니다**
11. **등록 성공** — `name`만 보낸 `POST` → `201`, URL 5필드가 **키는 있고 값은 `null`**.
    키 존재를 함께 단언한다 (C-03, I-4)
12. **URL 길이·형식 나머지** — 2049자 `400` / `not a url` `400` / `data:text/html,x` `400`(스킴 사유).
    5필드 중 대표 1개로만 확인해도 된다 — 필드 매핑은 1번이 이미 덮는다

---

## 4. 손으로 한 번 — API 왕복 (2026-08-28 실행 완료 ✅)

Testcontainers 가 아니라 **실제 스택**으로 돌린다. MockMvc 가 안 덮는 것이 여기서 확인된다 —
앱 부팅, Flyway 실전 적용, 서블릿 컨테이너, 보안 필터 체인 전체.

### 4-1. 준비 — 처음 쓰는 사람이 막히는 자리 셋

초판은 *"앱을 실행한 뒤 회원 토큰과 임대된 부스를 준비한다"* 한 줄이었는데, 실제로 해 보니
그 한 줄에 막히는 지점이 셋 있었다. 그대로 적는다.

**⑴ `JWT_SECRET` 은 base64 다.** `JwtConfiguration` 이 `Base64.getDecoder().decode()` 한 뒤
64바이트 이상을 요구한다. 평문을 주면 `Illegal base64 character` 로 **기동 자체가 실패**한다.

```bash
docker compose -f backend/compose.yaml up -d          # postgres + redis
cd backend
JWT_SECRET='<base64, 디코드 후 64바이트 이상>' \
CONNECTION_TOKEN_SECRET='<같은 형식>' \
GOOGLE_CLIENT_ID=dummy GOOGLE_CLIENT_SECRET=dummy \
GOOGLE_REDIRECT_URI=http://localhost:18080/login/oauth2/code/google \
KAKAO_REST_API_KEY=dummy KAKAO_CLIENT_SECRET=dummy \
KAKAO_REDIRECT_URI=http://localhost:18080/login/oauth2/code/kakao \
./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=18080
```

OAuth 값은 **기본값이 없어** 넷 다 채워야 부팅한다. 로컬 스모크에는 더미로 충분하다.

**⑵ 회원 Access Token 을 HTTP 로 얻을 경로가 없다.** 발급은 OAuth 핸드셰이크뿐이라 로컬에서는
직접 발행해야 한다 — `sub`=userId, `role`=`MEMBER`, `sid`, `jti`, `iat`, `exp` 를 담아
**HS512** 로 서명한다(키는 위 base64 를 디코드한 원본 바이트).

**⑶ `SessionRevocationFilter` 가 `sid` 를 Redis 와 대조한다.** 토큰만 위조하면 401 이다.
키 이름은 **`auth:session:{userId}`** 다(`session:{userId}` 아님).

```bash
docker exec ssafesta-local-redis-1 redis-cli SET "auth:session:4" "<sid>" EX 3600
```

부스·임대는 psql 로 넣는다 — `booths` 한 행, `booth_leases` 에 `status='ACTIVE'` 이고
`ends_at` 이 미래인 행, `booths.current_slot_id` 갱신.

> ⚠️ **Git Bash 에서 `curl -d` 에 한글을 직접 쓰지 마라.** 본문이 깨져
> `400 요청 본문을 읽을 수 없습니다` 가 나온다 — 제품 결함으로 오인하기 딱 좋다.
> UTF-8 파일로 저장해 `--data-binary @body.json` 로 보낸다.

### 4-2. 결과 — 12건 전부 통과

| # | 요청 | 기대 | 실측 |
|:--:|---|---|:--:|
| 1 | `POST /booths/{id}/projects` — `name`만 | `201`, URL 5필드 키 존재 + `null` | ✅ |
| 2 | 같은 요청 다시 | `409 PROJECT_ALREADY_EXISTS` | ✅ |
| 3 | `GET /booths/{id}/projects` | `200`, 배열 길이 1 | ✅ |
| 4 | `PATCH` — `{"videoUrl": "javascript:alert(1)"}` | `400` + **스킴 사유 문장** | ✅ |
| 5 | `PATCH` — `{"videoUrl": "https://youtu.be/abc123"}` | `200` 저장 | ✅ |
| 6 | `PATCH` — `{"videoUrl": null}` | `200`, 그 필드만 `null` | ✅ |
| 7 | `PATCH` — `{}` | `400 수정할 내용이 없습니다.` | ✅ |
| 8 | 게스트 토큰으로 `POST`·`GET` | `403 MEMBER_ONLY` | ✅ |
| 9 | `PATCH` — `{"deployUrl": "https://example.com:99999"}` | `400` 포트 사유 | ✅ |
| 10 | `PATCH` — `{"portfolioUrl": "https://한글도메인.com/내포트폴리오"}` | `200`, **원문 그대로** | ✅ |
| 11 | 토큰 없이 `GET` | `401` | ✅ |
| 12 | `PATCH /projects/99999` | `404 PROJECT_NOT_FOUND` | ✅ |

**4번이 이 절의 목적이다.** 실제 HTTP 로 사용자에게 도달한 문장:

```json
{ "code": "VALIDATION_FAILED",
  "message": "영상 주소는 http 또는 https로 시작해야 합니다.",
  "errors": [ { "rule": "FIELD_INVALID", "field": "videoUrl",
                "message": "영상 주소는 http 또는 https로 시작해야 합니다." } ] }
```

"형식이 올바르지 않습니다"가 **아니다** — scheme 을 host 보다 먼저 보는 순서가 실전에서 지켜졌다.

10번도 실전에서만 보이는 것이었다. 한글 도메인이 **punycode 로 바뀌지 않고 원문 그대로**
저장·반환된다 — 판정만 `IDN.toASCII` 로 하고 값은 건드리지 않는다는 불변식 I-3 이 성립한다.

### 4-3. 곁다리로 확인된 것

`ddl-auto: validate` 로 부팅에 성공했다는 것은 **엔티티 매핑이 실제 스키마와 일치**한다는 뜻이다.
Flyway 도 실측했다 — `flyway_schema_history` 최신 행이 `14 | project one per booth | t`,
`pg_indexes` 에 `ux_projects_booth` 존재. Testcontainers 밖에서 V14 가 도는 것을 처음 본 자리다.

**끝나면 치운다** — 스모크용 user·booth·lease·project 행과 `auth:session:{id}` 키를 지우고
앱을 내린다. 컨테이너는 두어도 된다.

---

## 5. 문서 반영 확인 (구현과 같은 커밋)

- [x] `docs/08` §5 — endpoint 4개 이름뿐인 서술 → 실제 shape·오류·게이트. homepage 절(`:443`) 형식
- [x] `docs/08` §18 — `PROJECT_NOT_FOUND` · `PROJECT_ALREADY_EXISTS` 추가.
      **§1.3-1 전역 rule 표에는 넣지 않는다** (최상위 code이지 rule이 아니다)
- [x] `docs/sdd/parts/BE.md:18` — 009의 `CRUD + S3`에서 **S3 제거** (C-03으로 업로드 미지원 확정)
- [x] `docs/24_작업일지.md` 기록. 문제 생기면 `docs/25_트러블슈팅.md`에 T-번호
- [x] `specs/009` 리뷰 서명은 **하지 않았다** — C-02가 아직 열려 있다

---

## 6. 완료 기준

- [x] `cd backend && ./mvnw test` — **47 클래스 / 459 테스트, 실패 0**
- [x] §3-1의 12개 케이스가 전부 있다
- [x] §4를 손으로 한 번 돌렸다 — 12건 전부 통과 (2026-08-28)
- [x] §5 문서 5줄 반영
- [ ] `Closes S15P21A604-110`이 **develop에 도달하는 커밋 메시지**에 있다 — MR 설명만으로는
      전환이 발화하지 않는다. 머지 시 squash·merge 두 메시지를 눈으로 확인한다
      (109 실측: merge 커밋 본문에 들어가 발화)
