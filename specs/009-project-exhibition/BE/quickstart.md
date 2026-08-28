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

기대: **기준선 + 2 클래스, 실패 0.**

| 테스트 | 무엇을 지키나 |
|---|---|
| `project/ProjectApiIntegrationTest` | 계약 전부 — 아래 §3-1 |
| `project/ProjectConcurrencyIntegrationTest` | 동시 `POST`에서도 부스당 1개 (I-1) |
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

---

## 4. 손으로 한 번 — API 왕복

Testcontainers가 아니라 실제 스택으로 한 번 돌려 본다. `docker compose -f backend/compose.yaml up -d`
로 DB를 띄우고 앱을 실행한 뒤, 회원 토큰과 임대된 부스를 준비한다(`bruno/` 컬렉션 재사용).

| # | 요청 | 기대 |
|:--:|---|---|
| 1 | `POST /api/v1/booths/{boothId}/projects` — `name`만 | `201`, URL 5필드가 **키는 있고 값은 `null`** |
| 2 | 같은 요청 다시 | `409 PROJECT_ALREADY_EXISTS` |
| 3 | `GET /api/v1/booths/{boothId}/projects` | `200`, `projects` 배열 길이 1 |
| 4 | `PATCH /api/v1/projects/{id}` — `{"videoUrl": "javascript:alert(1)"}` | `400`, `field: "videoUrl"`, **스킴 사유 문장** |
| 5 | `PATCH` — `{"videoUrl": "https://youtu.be/x"}` | `200`, 저장됨 |
| 6 | `PATCH` — `{"videoUrl": null}` | `200`, `videoUrl`이 `null`, 나머지 유지 |
| 7 | `PATCH` — `{}` | `400` |
| 8 | 게스트 토큰으로 1 | `403 MEMBER_ONLY` |

4번이 통과하는지 눈으로 확인하는 것이 이 절의 목적이다 — 자동 테스트가 같은 것을 보지만,
사용자에게 실제로 도달하는 문장을 한 번은 사람이 읽어야 한다.

---

## 5. 문서 반영 확인 (구현과 같은 커밋)

- [ ] `docs/08` §5 — endpoint 4개 이름뿐인 서술 → 실제 shape·오류·게이트. homepage 절(`:443`) 형식
- [ ] `docs/08` §18 — `PROJECT_NOT_FOUND` · `PROJECT_ALREADY_EXISTS` 추가.
      **§1.3-1 전역 rule 표에는 넣지 않는다** (최상위 code이지 rule이 아니다)
- [ ] `docs/sdd/parts/BE.md:18` — 009의 `CRUD + S3`에서 **S3 제거** (C-03으로 업로드 미지원 확정)
- [ ] `docs/24_작업일지.md` 기록. 문제 생기면 `docs/25_트러블슈팅.md`에 T-번호
- [ ] `specs/009` 리뷰 서명은 **하지 않는다** — C-02가 아직 열려 있다

---

## 6. 완료 기준

- [ ] `cd backend && ./mvnw test` — 기준선 + 2 클래스, **실패 0**
- [ ] §3-1의 10개 케이스가 전부 있다
- [ ] §4를 손으로 한 번 돌렸다
- [ ] §5 문서 5줄 반영
- [ ] `Closes S15P21A604-110`이 **develop에 도달하는 커밋 메시지**에 있다 — MR 설명만으로는
      전환이 발화하지 않는다. 머지 시 squash·merge 두 메시지를 눈으로 확인한다
      (109 실측: merge 커밋 본문에 들어가 발화)
