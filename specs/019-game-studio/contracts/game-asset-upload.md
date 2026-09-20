# Game Asset Upload 계약

> 상태: **v1.0 확정 요청** — #69 · `S15P21A604-255`. `game-api.md` v1.0 §Asset Boundary의 "MVP에 사용자 upload endpoint를 포함하지 않는다"를 대체한다.
> 저장소 규약은 [`infra-002 object-storage-contract.md`](../../infra-002-environments/contracts/object-storage-contract.md)를 그대로 따르고 여기서 다시 정의하지 않는다.

## 1. 범위

제작자가 자기 이미지를 올려 stable `asset://` reference로 Draft/Publish에 저장하고, 다른 사용자가 Published를 열었을 때 같은 그림이 보이게 하는 것까지다.

- 업로드 `kind`는 **`IMAGE`·`TILESET`만**이다. `AUDIO`는 v1 미지원이며 명시적 오류로 거부한다 (#69 §3② 합의, 2026-08-25).
- `builtin://` catalog는 이 계약이 건드리지 않는다. 두 source는 공존한다.
- v1 제외: 오디오·동영상 업로드, AI 이미지 생성, 범용 미디어 편집.

## 2. stable `asset://` 형식

```text
asset://game/{gameId}/{assetId}
```

로컬 Preview의 `asset://local/{gameId}/{assetId}`와 **arity가 같고 authority만 다르다.** FE의 승격은 같은 스킴 안에서 authority를 바꾸는 문자열 교체이며 새 스킴이 아니다.

- `assetId`는 **서버가 발급한다.** 클라이언트가 제시한 id는 받지 않는다 — 서버가 소유권과 유효성을 보증해야 하는 식별자를 클라이언트가 정하면 그 보증의 근거가 사라진다.
- 형식은 `stableId` 패턴(`^[A-Za-z][A-Za-z0-9_-]{0,63}$`)을 만족하는 **추측 불가능한 26자**다. 그래서 FE는 이 값을 `assets[].id`로 그대로 재사용해도 되고, 자기 id를 따로 유지해도 된다 — 계약이 요구하지 않는다.
- `{gameId}`는 저장 중인 GameProject의 `gameId`와 같아야 한다. 다르면 DB 조회 전에 문자열 단계에서 거부한다. **타인 Asset 참조 차단의 1차 방어선이 이 규칙이다.**
- `objectKey`는 `assetId`에서 유도하지 않는다. 전달 주소는 서버만 안다 (infra-002 §Upload flow 2).

## 3. Endpoint

```text
POST   /api/v1/games/{gameId}/assets                     업로드 시작 — assetId 발급 + PUT grant
POST   /api/v1/games/{gameId}/assets/{assetId}/complete  업로드 완료 — 실제 바이트 검증
GET    /api/v1/games/{gameId}/assets                     목록 + 상태 + 상한
GET    /api/v1/games/{gameId}/assets/{assetId}           단건 상태 (폴링)
DELETE /api/v1/games/{gameId}/assets/{assetId}           soft delete
GET    /api/v1/games/{gameId}/assets/{assetId}/content   전달 — 302 redirect
```

소유자만 호출할 수 있다. `/content`만 Published 공개 정책(`game-api.md` §Runtime)을 따른다.

### 3.1 시작

요청은 `kind`, `contentType`, `byteSize`, 파일명이다. 응답은 grant다.

```json
{
  "assetId": "aB7kQ2mZ9xR4tL6vN0wY3sJ8pc",
  "status": "UPLOADING",
  "uploadUrl": "https://<provider>/...",
  "requiredHeaders": { "Content-Type": "image/png" },
  "expiresAt": "2026-08-26T04:10:00Z"
}
```

- `uploadUrl`은 **R2 버킷으로 직접 가는 presigned PUT**이다. 바이트는 Spring 을 거치지 않는다.
- `uploadUrl`은 민감정보다. log·DB·cache에 남기지 않고 GameProject에는 어떤 경우에도 저장하지 않는다.
- `requiredHeaders`는 **그대로 보내야 한다.** `Content-Type`이 서명에 포함되므로 다른 값을 보내면 R2 가 403 으로 거절하고, 그 실패는 Spring 로그에 남지 않는다.
- grant 만료는 10분이다. 만료 후 `complete`는 실패하고 행은 `FAILED`가 된다. **presigned URL 의 서명 수명도 정확히 10분이다** — 더 길면 `complete` 가 이미 거절한 뒤에 바이트가 도착해 아무도 가리키지 않는 객체가 남는다.
- 선언값(`contentType`·`byteSize`)은 **거절용으로만** 쓴다. 통과는 §5의 실제 바이트 검사가 결정한다. 서명에 두 값이 박혀 있어도 마찬가지다 — 서명은 "이 객체 하나를 쓸 수 있다"는 허가이고 내용에 대한 진술이 아니다.
- `complete` 는 R2 에서 객체를 **다시 읽어** 검증한다. 상한은 계약의 5 MiB 이고 선언 크기가 아니다 — 1 KiB 라고 선언하고 4 GiB 를 올리는 경우가 바로 선언 상한이 못 잡는 경우다.

### 3.2 완료

presigned PUT이라 서버는 업로드 요청 본문을 보지 못한다. 그래서 **검증은 전부 `complete` 시점에 한다.** 성공하면 `READY`, 실패하면 `FAILED`와 실패 `rule`이 남는다.

```json
{
  "assetId": "aB7kQ2mZ9xR4tL6vN0wY3sJ8pc",
  "status": "READY",
  "kind": "IMAGE",
  "source": "asset://game/123/aB7kQ2mZ9xR4tL6vN0wY3sJ8pc",
  "contentType": "image/png",
  "byteSize": 184320,
  "width": 512,
  "height": 512
}
```

- **idempotent하다.** 같은 grant로 두 번 호출해도 앞의 결과를 그대로 반환하고 검증을 다시 돌리지 않는다.
- `FAILED`는 되살리지 않는다. 재시도는 §3.1부터 새 `assetId`로 시작한다 — 실패한 object를 덮어쓰게 두면 "검증을 통과한 바이트"와 "저장된 바이트"가 갈릴 수 있다.

### 3.3 목록 — 상한을 여기서 내려준다

상한은 FE 상수와 동기화하지 않고 **응답으로 전달한다.** 값이 바뀔 때 FE 배포를 기다리지 않기 위해서다.

```json
{
  "assets": [ "<AssetStatus>" ],
  "limits": {
    "maxBytesPerFile": 5242880,
    "maxAssetsPerProject": 300,
    "maxWidth": 4096,
    "maxHeight": 4096,
    "allowedContentTypes": ["image/png", "image/jpeg", "image/gif", "image/webp"]
  }
}
```

`deleted_at`이 있는 행은 목록에서 제외한다.

### 3.4 전달 — Spring 이 바이트를 중계한다 (2026-09-02 정정, 원안 2026-08-26)

> **정정.** 원안은 `/content` 가 **짧은 수명의 presigned GET 으로 302 redirect** 한다고 적었고, 근거는 "019 의 소비자는 `<img src>` 다" 였다. **두 전제 모두 실측과 다르다.**
>
> | 원안의 전제 | 실측 |
> |---|---|
> | 소비자는 `<img src>` | `remoteAssetRepository.ts` 는 `Authorization` 헤더를 붙인 `fetch` 로 받아 blob 으로 만든다 |
> | 302 로 버킷을 열어주면 된다 | `object-storage-contract.md` §CORS 는 버킷에 `AllowedMethods: PUT` 만 허용한다 — 브라우저 GET 은 redirect 든 직접이든 CORS 에서 죽는다 |
>
> 302 는 인프라 계약을 바꾸지 않으면 애초에 동작하지 않는다. 그리고 브라우저에 presigned GET 을 넘기면 그 URL 은 자신을 만들어 낸 권한 검사(§7 공개 판정)보다 오래 산다 — 비공개 게임의 이미지 주소가 검사 없이 유효한 채로 남는다.

`/content` 는 R2 에서 객체를 읽어 **바이트를 직접 응답한다.** 302 도, presigned GET 도 FE 로 나가지 않는다. FE resolver 는 `asset://game/{g}/{a}` → `/api/v1/games/{g}/assets/{a}/content` 문자열 변환 하나로 그대로 유지된다. 응답은 `Cache-Control: private, max-age=300` 과 `X-Content-Type-Options: nosniff` 이고, `Content-Type` 은 **검증으로 확정된 실제 타입**이다 (선언값이 아니다).

읽기는 그 행이 적어 둔 `provider`·`storage_bucket` 을 따른다. 지금 활성인 write provider 가 아니다 — MinIO fallback 으로 활성이 옮겨가도 R2 에 남은 옛 객체는 계속 R2 에서 읽힌다 (spec 007 FR-030 과 같은 불변식).

읽기는 **행에 기록된 `byte_size` 와 길이가 정확히 같을 것**을 요구한다. `byte_size` 는 검증 때 실제로 읽은 바이트 수라서, 길이가 다르면 그것은 우리가 승인한 객체가 아니다 — `GAME_ASSET_NOT_READY` + rule `OBJECT_MISSING` 으로 거절한다. 객체가 사라진 경우와 같은 응답이고, 둘 다 "행은 READY 인데 저장소가 그것을 뒷받침하지 않는다" 다. `GAME_ASSET_DELETED` 는 일어나지 않은 삭제를 주장하는 것이 된다.

> `ponytail:` 이미지 1장당 Spring 왕복 1회 + R2 GET 1회. 목록 화면에서 병목이 되면 그때 캐시나 batch 를 붙인다 — `asset://` 형식은 그때도 바뀌지 않는다.
>
> `ponytail:` 길이 일치는 **같은 길이의 다른 내용**으로 바꿔치기하는 것을 잡지 못한다. 크기가 다른 교체는 크든 작든 걸린다. 남은 창은 grant 의 10분이고, 소유자 자신의 서명 URL 이 필요하며, 닿는 범위는 자기 게임의 자산뿐이다 (그 사람은 이미 검증을 통과하는 것이면 무엇이든 올릴 수 있다). 이것이 허용되지 않는 시점이 오면 읽기에서 `sha256` 을 검증한다 — 요청당 최대 5 MiB 해시다.

## 4. 상태

```text
UPLOADING ──complete 성공──> READY
    │                          │
    └──검증 실패·만료──> FAILED  └──delete──> (soft deleted)
```

**`READY`만 Draft 저장과 Publish에서 참조할 수 있다.** `UPLOADING`·`FAILED` 참조는 저장을 거부한다 — 통과시키면 나중에 "실행 시점의 깨진 이미지"가 되고, 그건 저장 시점의 오류보다 진단하기 어렵다.

## 5. 검증

| 항목 | 값 | 근거 |
|---|---|---|
| 파일당 크기 | **5 MiB** | FE 현재값 (`localAssetRepository.ts:20`) |
| 프로젝트당 개수 | **300** | spec 019 / `game-api.md` 상한표 |
| 허용 MIME | `image/png` · `image/jpeg` · `image/gif` · `image/webp` | FE 오류 문구와 동일 |
| 픽셀 | **4096 × 4096** | decompression bomb 차단 |

- 상한값을 새로 정하지 않고 FE 현재값을 채택했다. 로컬에서 통과한 파일이 업로드에서 거부되면 사용자가 이해할 수 없다.
- **SVG는 거부한다.** 스크립트를 담을 수 있어 사용자 업로드로 받지 않는다. FE 검사(`file.type.startsWith('image/')`, `:57`)도 4종 화이트리스트로 좁혀야 로컬 통과 → 업로드 거부가 사라진다.
- 확장자·선언 MIME 위장은 **magic number + 실제 디코드**로 판정한다. 디코드에 실패하면 손상으로 본다.
- 검증 실패 object는 삭제하고 `READY`로 승격하지 않는다 (infra-002 §Verification outcomes 그대로).

## 6. 오류 코드

`game-api.md` §공통 오류 봉투를 그대로 쓴다. `errors[].rule`에 위반 항목을 넣는다.

| code | 상황 |
|---|---|
| `GAME_ASSET_KIND_UNSUPPORTED` | `AUDIO` 등 v1 미지원 kind |
| `GAME_ASSET_TYPE_UNSUPPORTED` | MIME 화이트리스트 밖 (SVG 포함) |
| `GAME_ASSET_TOO_LARGE` | 5 MiB 초과 |
| `GAME_ASSET_DIMENSION_EXCEEDED` | 픽셀 상한 초과 |
| `GAME_ASSET_QUOTA_EXCEEDED` | 프로젝트당 300개 초과 |
| `GAME_ASSET_CORRUPTED` | magic number 불일치·디코드 실패 |
| `GAME_ASSET_NOT_FOUND` | 없는 `assetId` |
| `GAME_ASSET_FORBIDDEN` | 다른 Game·다른 소유자의 Asset 참조 |
| `GAME_ASSET_NOT_READY` | `UPLOADING`/`FAILED` Asset을 Draft·Publish에서 참조 |
| `GAME_ASSET_DELETED` | 삭제된 Asset을 새로 참조 |
| `GAME_ASSET_IN_USE` | 현재 Draft가 쓰는 Asset을 `force` 없이 삭제 |

## 7. 삭제와 보존

- 삭제는 **soft**다. `deleted_at`만 세우고 object는 지우지 않는다.
- 현재 Draft가 참조 중이면 HTTP 409 + `GAME_ASSET_IN_USE`와 **사용 위치 목록**(Scene 배경 / Tile / Object sprite / Dialogue portrait 등)을 `errors[]`로 반환한다. FE가 확인시킨 뒤 `?force=true`로 다시 호출한다.
- **Published Version이 참조하는 Asset의 binary는 Game이 존속하는 동안 삭제하지 않는다.** Published는 불변인데(계약 원칙 6) 그림만 사라지면 불변이 아니다. 삭제된 Asset도 Published 참조 경로에서는 `/content`가 계속 전달하고, Draft/Publish의 **새 참조만** 거부한다.
- 회원 탈퇴 hard delete는 metadata 행과 object를 제거한다 (`game-api.md` §Persistence Boundary, FR-040). **"함께"가 동시를 뜻하지 않는다** — 아래 §7.1이 순서와 실패 의미를 정의한다.

### 7.1 탈퇴 삭제 — 순서와 실패

R2 삭제는 네트워크 호출이라 **DB 트랜잭션에 넣을 수 없다.** 넣으면 R2 지연이 DB 락을 잡고, R2는 롤백되지 않으며, R2가 잠깐 죽으면 탈퇴 자체가 실패한다. 그래서 **DB가 진실이고 객체 정리는 뒤따라 수렴한다.**

```text
[하나의 트랜잭션]
  ① game_assets 에서 objectKey 목록을 SELECT   ← 지우기 전에 읽어야 한다
  ② 삭제 큐에 key INSERT
  ③ game_assets → game_published_versions → game_drafts → games → … → users 삭제
[커밋]  ← 여기까지가 "탈퇴 완료"다
  ④ sweeper 가 큐를 읽어 객체 DELETE (infra-002 §삭제 규칙 5개를 그대로 따른다)
  ⑤ 성공한 key 의 큐 행 제거
```

**① 이 순서 제약이다.** `game_assets` 행을 지운 뒤에는 어떤 객체를 지워야 하는지 알 방법이 없다.

**② 는 반드시 같은 트랜잭션이다 — 권고가 아니라 조건이다.** 밖에서 하면 이렇게 된다.

```text
큐에 key 넣기      → 성공
users 삭제         → 실패, 롤백
sweeper 가 삭제    → 그대로 실행
결과: 탈퇴하지 않은 회원의 이미지가 사라진다
```

회원도 게임도 남아 있는데 그림만 없어지고, **탈퇴 실패 로그를 봐도 보이지 않는다** — 누가 그 게임을 열 때 드러난다.

| 실패 지점 | 결과 |
|---|---|
| ①②③ 중 어디든 | **전부 롤백.** 회원·게임·객체 모두 그대로. 사용자에게 실패를 알린다 |
| 커밋 직후 프로세스 종료 | 큐가 DB 에 있으므로 재시작 후 sweeper 가 집어간다 |
| ④ 객체 삭제 실패 | 큐 행 유지 → 다음 주기 재시도 (infra-002 규칙 5). **탈퇴는 이미 완료다** |
| ④ 성공 후 ⑤ 전에 종료 | 다음 주기에 같은 key 재삭제 → 없는 key 는 멱등 성공(규칙 4) → 큐 행 제거 |
| 일부 key 만 실패 | 큐가 key 단위라 성공한 것만 빠지고 실패한 것만 남는다 |

**큐에는 `objectKey` 문자열만 남는다.** 소유자 식별 정보를 남기지 않는다 — `games/12/aB7kQ2mZ.png` 만으로는 누구 것인지 알 수 없으므로, 개인 식별 정보는 커밋 시점에 이미 사라진 것이다. 그래서 큐가 비지 않아도 탈퇴는 완료다.

> ⚠️ **④가 계속 실패하면 드러내야 한다.** 재시도만 반복하면 큐가 영원히 비지 않고, **탈퇴한 사람의 이미지가 남아 있는 것을 아무도 모른다.** 임계치(같은 key 10회 또는 24시간)를 넘으면 로그와 에러 상태로 올린다 — 실패를 조용히 기본값으로 덮은 것이 T-24 의 원인이었고, 여기서 그 대가는 개인정보다.

`@Scheduled` 는 현재 코드에 0 개다. **007 의 미완료 문서 sweeper 는 결국 만들어지지 않았으므로**(-106 은 재시도 루프를 지우고 끝났다) 019 의 것이 첫 번째다 — `GameAssetDeleteQueue.sweep()`, `fixedDelay` 1분. 007 이 나중에 자기 sweeper 를 만들 때 이 주기에 얹을 수 있다.

큐 적재 지점은 셋이고 **모두 행을 지우는 트랜잭션 안**이다.

| 지점 | 무엇을 지우는가 |
|---|---|
| 회원 탈퇴 (`AccountDeletionService`) | 그 회원 게임의 모든 asset 행 |
| 검증 실패 (`complete` → `FAILED`) | 방금 거절한 객체 |
| 발급 시 죽은 행 청소 (`issue`) | 만료된 `UPLOADING`·오래된 `FAILED` 의 객체 |

세 번째가 R2 로 옮기면서 새로 필요해진 것이다. 만료된 grant 도 `PUT` 을 받았을 수 있고 — 브라우저는 올렸다고 알려주지 않는다 — 바이트가 행 안에 없으므로 행만 지우면 객체가 남는다.

sweep 은 작업을 **락으로 붙잡지 않고 임대한다.** `UPDATE ... FOR UPDATE SKIP LOCKED ... RETURNING` 으로 `next_attempt_at` 을 밀어 두고 트랜잭션을 끝낸 뒤 저장소를 호출한다 — 50행 × 왕복 1회를 트랜잭션 안에서 하면 가장 느린 provider 가 답할 때까지 락을 잡는다. 재시도 간격은 `attempts` 에 따라 늘고 30분에서 멈춘다.
- Game soft delete는 Asset을 지우지 않는다. 복구 대상이기 때문이다.

## 8. 저장 경계

Asset metadata와 binary는 **GameProject JSONB 밖**에 둔다 (계약 원칙 8).

```sql
game_assets
  id                 BIGINT PK
  game_id            BIGINT NOT NULL REFERENCES games(id)
  asset_id           VARCHAR(64) NOT NULL          -- 서버 발급
  kind               VARCHAR(20) NOT NULL CHECK (kind IN ('IMAGE','TILESET'))
  status             VARCHAR(20) NOT NULL CHECK (status IN ('UPLOADING','READY','FAILED'))
  content_type       VARCHAR(100)                  -- 검증으로 확정된 실제 타입
  byte_size          BIGINT
  width, height      INTEGER
  sha256             CHAR(64)
  provider           VARCHAR(20) NOT NULL          -- R2 | MINIO_LOCAL (infra-002)
  storage_bucket     TEXT NOT NULL                 -- 발급 시점의 버킷. 읽기·삭제가 이것을 따른다
  object_key         TEXT NOT NULL                 -- games/{gameId}/assets/{assetId}
  failure_rule       VARCHAR(60)                   -- FAILED 사유
  declared_content_type VARCHAR(100) NOT NULL      -- 시작 요청의 선언값. complete 가 실제값과 대조한다
  declared_byte_size    BIGINT NOT NULL
  upload_expires_at  TIMESTAMPTZ NOT NULL          -- grant 만료(10분). quota 술어이기도 하다
  created_by_user_id BIGINT NOT NULL REFERENCES users(id)
  created_at, updated_at, deleted_at TIMESTAMPTZ
  UNIQUE (game_id, asset_id)

game_asset_delete_queue                            -- §7.1
  id                 BIGINT PK
  provider, storage_bucket, object_key             -- 좌표만. 소유자 식별 정보를 남기지 않는다
  attempts           INTEGER NOT NULL DEFAULT 0
  last_error         VARCHAR(200)                  -- 예외 클래스명만 (메시지는 endpoint 를 흘린다)
  created_at, next_attempt_at TIMESTAMPTZ
  UNIQUE (provider, storage_bucket, object_key)
```

`storage_bucket` 이 원안에 없던 컬럼이다. `provider` 만으로는 부족하다 — 버킷은 배포 설정이고, 계약의 fallback 은 "설정 변경 + 재배포" 라서 옛 행이 가리키는 버킷과 지금 활성인 버킷이 다를 수 있다.

`object_key` 에는 **업로더가 정한 문자열이 하나도 들어가지 않는다.** 원본 파일명을 넣으면 경로·traversal·다른 사람 이름이 버킷 안으로 그대로 실려 간다. `assetId` 는 한 번만 쓰이므로 키를 재사용하거나 버전을 붙일 일이 없다.

`UNIQUE(game_id, asset_id)`가 §2의 `asset://game/{gameId}/{assetId}` 형식과 1:1이다. 소유권은 조회 조건 자체에 들어가고 서비스가 따로 기억할 규칙이 아니다.

## 9. Draft/Publish 검증 연동

`GameProjectValidator.isPersistableSource()`는 현재 `builtin://`만 허용한다. **그 완화는 발급 endpoint와 같은 커밋에 들어간다** — 두 쪽이 갈리면 존재할 수 없는 reference를 저장할 수 있게 된다.

완화 후 규칙:

```text
builtin://                     허용 (기존)
asset://game/{gameId}/{id}     gameId 일치 + (game_id, asset_id) 존재 + status=READY 일 때만 허용
asset://local/...              거부
data: · blob: · file: · base64 · 서명 URL   거부
```

- Draft 저장과 Publish 양쪽에서 같은 규칙을 적용한다. 자동 보정은 하지 않는다 (`game-api.md` §Draft 저장).
- Publish 트랜잭션은 `game_published_versions` append 전에 Asset 상태를 다시 확인한다. Draft 저장 이후 삭제된 Asset이 Published에 들어가지 않게 한다.

## 10. 후속

- #55 Published Runtime resolver는 §3.4의 `/content` 경로를 그대로 쓴다.
- v1에 오디오가 필요해지면 §5 상한표에 kind별 행 하나와 코덱 검사만 추가한다. `assetId` 발급·상태 머신·보존 정책은 그대로 재사용하므로 **확장이지 되돌리기가 아니다.**
