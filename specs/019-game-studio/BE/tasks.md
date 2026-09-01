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

FE는 이 계약대로 `remoteAssetRepository.ts`를 이미 구현해 develop에 넣었다. BE만 비어 있다.
R2 자격증명은 아직 없다(Jira S15P21A604-232) — 어댑터 뒤에 두고 MinIO로 검증한다. 계약 형태는 R2 유무와 무관하다.

### 슬라이스 1 — FE가 실제로 호출하는 경로

- [ ] T094 Add `game_assets` migration (V17) — 계약 §8 DDL 그대로, `UNIQUE(game_id, asset_id)`, `kind`·`status` CHECK
- [ ] T095 Define `GAME_ASSET_*` error codes — 계약 §6의 11종, `errors[].rule`에 위반 항목
- [ ] T096 Add object storage port + S3-compatible adapter — presign PUT, HEAD, ranged GET, presign GET. `provider`는 `R2`·`MINIO_LOCAL`(infra-002). `compose.yaml`에 minio 서비스 추가
- [ ] T097 Implement `POST /games/{gameId}/assets` — 추측 불가 26자 `assetId` 서버 발급, 서버 생성 `objectKey`, 10분 grant, `status`·`expiresAt` 포함. 선언 `contentType`·`byteSize`는 거절용으로만
- [ ] T098 Implement `POST /games/{gameId}/assets/{assetId}/complete` — HEAD + magic number + 실제 디코드 + 5 MiB·4096×4096 검증, `READY`/`FAILED`+`failure_rule`, **멱등**, `FAILED`는 되살리지 않음
- [ ] T099 Implement `GET /games/{gameId}/assets/{assetId}/content` — 302 redirect, `Cache-Control: private, max-age=300`. 소유자 또는 Published 공개 정책. 삭제된 Asset도 Published 참조 경로에서는 계속 전달 (§7)
- [ ] T100 Relax `GameProjectValidator.isPersistableSource()` — `asset://game/{gameId}/{id}`를 gameId 일치 + `(game_id, asset_id)` 존재 + `status=READY`일 때만 허용. **T097과 같은 커밋에 넣는다** (계약 §9). Publish 트랜잭션은 append 직전 상태를 재확인
- [ ] T101 Add upload E2E integration test (MinIO Testcontainer) — 시작 → presigned PUT → complete → `READY` → `/content` 302
- [ ] T102 Add rejection tests — 위장 MIME, 디코드 실패, SVG, 5 MiB 초과, 4096px 초과, 타 Game asset 참조, `UPLOADING` 참조 Draft 저장 거부

### 슬라이스 2 — FE 편집기 UI(#69 ①~④, 미착수)와 Jira S15P21A604-176 대기

- [ ] T103 Implement `GET /games/{gameId}/assets` — 목록 + `limits` 응답으로 상한 하달, `deleted_at` 행 제외 (§3.3)
- [ ] T104 Implement `GET /games/{gameId}/assets/{assetId}` — 단건 상태 폴링
- [ ] T105 Implement `DELETE /games/{gameId}/assets/{assetId}` — soft delete, 현재 Draft 사용 중이면 409 `GAME_ASSET_IN_USE` + 사용 위치 목록, `?force=true`로 재호출
- [ ] T106 Implement 탈퇴 hard delete 순서 — `objectKey` SELECT → 삭제 큐 INSERT → 행 삭제를 **하나의 트랜잭션**으로 (§7.1). 큐에는 `objectKey` 문자열만
- [ ] T107 Add object sweeper — 스케줄러를 새로 만들지 않고 007 미완료 문서 sweeper 주기에 얹는다. 같은 key 10회 또는 24시간 초과 시 로그·에러 상태로 드러낸다 (§7.1 경고)

## Summary

- Total: 40 (기존 26 + Asset 업로드 14)
- Completed: 21
- Remaining: 19 — 이 중 슬라이스 1(T094~T102) 9개가 Jira S15P21A604-107 범위다
- Coordination: implementation is tracked by GitHub #48; do not duplicate it from FE/docs branches
- Asset 업로드는 #69 · Jira S15P21A604-107 / S15P21A604-176 으로 추적한다
