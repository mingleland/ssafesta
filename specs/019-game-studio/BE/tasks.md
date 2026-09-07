# BE Tasks: FESTA Game Studio

**Shared spec**: [../spec.md](../spec.md) | **FE tasks**: [../FE/tasks.md](../FE/tasks.md)

BE가 소유하는 26개 작업이다. #48에서 `strdeok`가 구현을 진행하므로 이 문서는 계약과 완료 조건만 소유한다.

## Setup

- [x] T014 Create the Spring `game` package boundary
- [x] T015 Add `games`, `game_drafts`, `game_published_versions`, and pointer-FK migration
- [x] T016 Define Game Studio error codes and exception mapping

## Draft and Publish

- [x] T032 Add revision-conflict and authorization integration tests
- [x] T033 Add invalid-reference and immutable-Publish integration tests
- [x] T035 Implement Game, GameDraft, and GamePublishedVersion entities/repositories
- [x] T036 Implement revision-aware Draft service
- [x] T037 Implement Schema and semantic validator adapter
- [x] T038 Implement atomic validate→append→pointer Publish transaction
- [x] T039 Implement authoring endpoints

## Published Runtime

- [x] T044 Add public/private/not-published Runtime query tests
- [x] T046 Implement Published query service and endpoint

## Portal Binding

- [ ] T051 Add Binding/Booth/Game status and signed Int32 configId matrix tests
- [ ] T053 Implement GamePortalBinding and `GAME_PORTAL` LayoutConfigResolver validation
- [ ] T054 Implement no-store Portal resolution endpoint
- [x] T079 Implement persisted Asset source allow-list validation

## Game lifecycle (2026-08-25 #48 승인 — ②④⑤)

- [x] T089 Implement `GET /games/mine` — soft-delete 포함, `deletedAt`, 활성 우선 + `updatedAt` 내림차순, 6필드
- [x] T090 Implement `PATCH /games/{gameId}` visibility — `publishedVersion` null 이어도 PUBLIC 허용
- [x] T091 Implement `DELETE /games/{gameId}` soft delete — 삭제본 5개 초과 시 최고령 1건 FIFO hard delete
- [x] T092 Implement `POST /games/{gameId}/restore` — 활성 상한 게이트, 미삭제 대상은 멱등 200
- [x] T093 Add limit tests — 활성 20 / 삭제본 5 경계, `GAME_LIMIT_EXCEEDED` 문구가 상한과 해결 방법을 담는지

## Policy and security verification

- [ ] T084 Implement/test `config_id` INTEGER mapping, Int32 boundaries, and whitelist deployment order
- [x] T085 Encode unpublish, soft/hard delete, and Published-history policy in integration tests
- [ ] T086 [P1] Define display-only score schema isolated from Coin/Reward/Inventory
- [x] T087 Verify Guest authoring denial and Published-play allowance
- [x] T088 Verify invalid coordinates, unknown fields, and broken references are rejected without correction

## Game Asset 업로드 (#69 · Jira S15P21A604-107) — 계약 [`../contracts/game-asset-upload.md`](../contracts/game-asset-upload.md)

FE는 이 계약대로 `remoteAssetRepository.ts`를 이미 구현해 develop에 넣었다. **BE 슬라이스 1은 2026-09-02 MR !215 로 채웠다** — 남은 것은 편집기 UI가 부르는 슬라이스 2다.
R2 자격증명은 아직 없다(Jira S15P21A604-232). 어댑터 뒤에 두고 테스트는 `FakeObjectStorage` 로 돈다 — 계약 형태는 R2 유무와 무관하다.

### 슬라이스 1 — FE가 실제로 호출하는 경로 ✅ 2026-09-02 (MR !215)

- [x] T094 Add `game_assets` migration (V18) — 계약 §8 DDL, `UNIQUE(game_id, asset_id)`, `kind`·`status` CHECK. `game_asset_delete_queue` 도 같은 마이그레이션에 있다 (§7.1)
- [x] T095 Define `GAME_ASSET_*` error codes — 계약 §6의 11종, `errors[].rule`에 위반 항목
- [x] T096 Add object storage port + S3-compatible adapter — -106 의 어댑터를 `ai/` 에서 중립 `storage/` 로 승격했다. presign PUT · HEAD · 상한+1 GET · DELETE. **presign GET 은 넣지 않았다** — §3.4 가 Spring 중계로 바뀌어 쓸 자리가 없다. **MinIO compose 서비스도 넣지 않았다** — 테스트는 fake 를 쓰고, 실제 fallback 은 자격증명(S15P21A604-232) 이후 일이다
- [x] T097 Implement `POST /games/{gameId}/assets` — 추측 불가 26자 `assetId` 서버 발급, 서버 생성 `objectKey`, 10분 grant, `status`·`expiresAt` 포함. 선언 `contentType`·`byteSize`는 거절용으로만
- [x] T098 Implement `POST /games/{gameId}/assets/{assetId}/complete` — HEAD 가 아니라 **객체를 읽어** 검증한다(magic number · 프레임별 치수 · 파일 전체 픽셀 예산 · 실제 디코드 · 5 MiB). `READY`/`FAILED`+`failure_rule`, **멱등**(행 잠금), `FAILED`는 되살리지 않음
- [x] T099 Implement `GET /games/{gameId}/assets/{assetId}/content` — **302 redirect 가 아니라 Spring 중계다** (계약 §3.4, 2026-09-02 정정 — 버킷 CORS 가 `PUT` 만 허용해 브라우저 GET 이 죽는다). `Cache-Control: private, max-age=300` + `X-Content-Type-Options: nosniff`. 소유자 또는 Published 참조 자산, 익명 GET 은 `permitAll` 로 열고 판정은 서비스가 한다
- [x] T100 Relax `GameProjectValidator` asset source — 메서드는 `isPersistableSource()` 가 아니라 `unusableReason()` 이다(MISSING·UPLOADING·FAILED·DELETED 네 갈래를 구분해 메시지에 싣는다). `asset://game/{gameId}/{id}` 를 gameId 일치 + 행 존재 + `READY` 일 때만 허용. **T097과 같은 커밋에 들어갔다** (계약 §9)
- [x] T101 Add upload E2E integration test — **MinIO Testcontainer 가 아니라 `FakeObjectStorage`** 로 왕복을 돈다(시작 → 버킷 쓰기 → complete → `READY` → `/content` 200 + 바이트). 실제 어댑터는 `S3ObjectStorageTest` 가 오프라인 서명(bucket·key·TTL)과 404 번역으로 따로 덮는다
- [x] T102 Add rejection tests — 위장 MIME · 디코드 실패 · SVG · 5 MiB 초과 · 4096px 초과 · 프레임 합 픽셀 예산 · 애니메이션 WebP · 타 Game asset 참조 · `UPLOADING` 참조 Draft 저장 거부. 발급 거절 4종(kind·MIME·크기·quota)과 쓰기 경로 소유권도 포함한다

### 슬라이스 2 — FE 편집기 UI(#69 ①~④, 미착수)와 Jira S15P21A604-176 대기

- [ ] T103 Implement `GET /games/{gameId}/assets` — 목록 + `limits` 응답으로 상한 하달, `deleted_at` 행 제외 (§3.3)
- [ ] T104 Implement `GET /games/{gameId}/assets/{assetId}` — 단건 상태 폴링
- [ ] T105 Implement `DELETE /games/{gameId}/assets/{assetId}` — soft delete, 현재 Draft 사용 중이면 409 `GAME_ASSET_IN_USE` + 사용 위치 목록, `?force=true`로 재호출
- [x] T106 Implement 탈퇴 hard delete 순서 — 삭제 큐 INSERT → 행 삭제를 **하나의 트랜잭션**으로 (`AccountDeletionService`, §7.1). 큐에는 `objectKey` 문자열만이 아니라 `provider`·`storage_bucket` 까지 넣는다 — fallback 이 활성 버킷을 옮긴 뒤에는 key 만으로 어디를 지울지 알 수 없다
- [x] T107 Add object sweeper — **007 sweeper 에 얹지 않았다.** 그 sweeper 는 끝내 만들어지지 않아 019 의 `GameAssetDeleteQueue` 가 첫 번째다(계약 §7.1 정정). `@Scheduled` 1분, `FOR UPDATE SKIP LOCKED` 임대 후 트랜잭션 밖에서 삭제, 지수 백오프(상한 30분), 10회 이상은 `log.error` 로 올린다 (§7.1 경고)

## Summary

- Total: 40 (기존 26 + Asset 업로드 14)
- Completed: 32
- Remaining: 8 — Asset 쪽은 슬라이스 2의 T103·T104·T105 셋뿐이고, Jira S15P21A604-176(FE 편집기 UI) 대기다
- 슬라이스 1(T094~T102) + T106·T107 은 2026-09-02 MR !215 로 완료했다 — Jira S15P21A604-107 범위
- Coordination: implementation is tracked by GitHub #48; do not duplicate it from FE/docs branches
- Asset 업로드는 #69 · Jira S15P21A604-107 / S15P21A604-176 으로 추적한다
