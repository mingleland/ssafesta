# Published Runtime `UNSUPPORTED_SCHEMA` 실경로 검증 — 2026-09-14 (S15P21A604-713)

GitLab [#55](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/issues/55) 의 마지막 잔여 1건이다.
`-517` 에서 오류 4상태 중 3종(404·비공개·미발행)을 실측했고 `UNSUPPORTED_SCHEMA` 만
*"게시 검증을 통과해야 만들 수 있어서"* 미확인으로 남아 있었다.

## 0. 무엇을 확인했나

```text
대상      GET /api/v1/games/1/published → /app/games/1/play
전제      로컬 실 스택 (Spring 8080 · Postgres 5432 · vite 5176)
결과      PASS — 화면까지 도달했고 문구·재시도 판정·격리가 모두 계약대로다
```

## 1. 재현 경로는 셋인데 그중 하나만 쓸 수 있다

`publishedGameRepository.ts` 가 `UNSUPPORTED_SCHEMA` 를 내는 자리는 셋이다.

| | 조건 | 이번에 썼나 |
|---|---|---|
| (a) | 서버가 `GAME_SCHEMA_UNSUPPORTED` 봉투를 보낸다 (`:111`) | 아니다 — 서버가 미지원 버전을 발급해야 해서 BE 협조가 필요하다 |
| (b) | `parseGameProject` 가 프로젝트 자체를 미지원으로 본다 (`:83`) | 아니다 — `project_json` 을 건드리면 손상 판정과 섞인다 |
| **(c)** | 게시 응답과 `GameProject` 의 `schemaVersion` 이 다르다 (`:90`) | **썼다** |

(c) 를 고른 이유는 **게시 검증을 다시 통과할 필요가 없어서**다. 이미 게시된 행의 컬럼 하나만
어긋나게 두면 되고, 원복도 그 한 컬럼이다.

## 2. 변경 전 상태와 원복 SQL을 먼저 적어 두었다

```text
id | game_id | version_no | schema_version | project_json->>'schemaVersion'
1  | 1       | 1          | 1.0.0          | 1.0.0

UPDATE game_published_versions SET schema_version = '1.0.0' WHERE id = 1;   -- 원복
```

주입:

```sql
UPDATE game_published_versions SET schema_version = '2.0.0' WHERE id = 1;
```

`project_json` 은 건드리지 않았다. 서버는 이 불일치를 검증하지 않고 그대로 내려준다 —
판정은 FE 몫이라는 것이 여기서 드러난다.

```text
GET /api/v1/games/1/published  → 200
schemaVersion = 2.0.0 / project.schemaVersion = 1.0.0
```

## 3. 화면 — PASS

```text
경로        /login → 게스트로 둘러보기 → /app/games/1/play
화면        제목  "게임을 열 수 없습니다"
            본문  "게시 응답과 GameProject의 schemaVersion이 일치하지 않습니다."
            버튼  "나가기" 하나
콘솔 error   0건
```

세 가지가 계약대로다.

**문구가 정확하다** — `:91` 의 메시지가 그대로 나온다. 일반 오류 문구로 뭉개지지 않았다.

**재시도 버튼이 없다.** `UNSUPPORTED_SCHEMA` 는 `retryable: false` 라 다시 눌러도 같은 답이다.
`GameOverlay` 가 `state.error.retryable` 일 때만 다시 시도를 그리므로 이 판정이 화면에 반영됐다.
404·네트워크 오류와 구별된다.

**격리된다.** `GameRuntimeErrorBoundary` 가 오류를 받아 앱 전체를 내리지 않고 이 화면만 바꾼다.
콘솔에 error 레벨 로그가 0건이고, "나가기" 로 정상 복귀한다.

## 4. 원복 대조

```text
id | game_id | version_no | schema_version | project_json->>'schemaVersion'
1  | 1       | 1          | 1.0.0          | 1.0.0        ← §2 의 변경 전과 일치

GET /api/v1/games/1/published → schemaVersion 1.0.0 / project 1.0.0
/app/games/1/play             → "열쇠와 문" 정상 로드 (체력 3/3 · INVENTORY 비어 있음)
```

원복 직후 **첫 탭은 여전히 오류 화면이었다.** DB·서버는 이미 정상이었다. 원인은 캐시였고
[T-96](../25_트러블슈팅.md) 으로 등록했다 — 이 검증 절차 자체의 함정이라 같이 남긴다.

## 5. 이것으로 `#55` 오류 4상태가 전부 실측됐다

| 상태 | 언제 | 근거 |
|---|---|---|
| 404 | `-517` | 없는 id |
| 비공개 | `-517` | `PATCH visibility` |
| 미발행 | `-517` | 게시 전 게임 |
| **미지원 schema** | **`-713` (이 문서)** | 게시 행의 `schema_version` 불일치 |

범위 5건(T045~T050)이 모두 소진됐다.
