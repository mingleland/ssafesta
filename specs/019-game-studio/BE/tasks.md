# BE Tasks: FESTA Game Studio

**Shared spec**: [../spec.md](../spec.md) | **FE tasks**: [../FE/tasks.md](../FE/tasks.md)

BE가 소유하는 26개 작업이다. #48에서 `strdeok`가 구현을 진행하므로 이 문서는 계약과 완료 조건만 소유한다.

## Setup

- [ ] T014 Create the Spring `game` package boundary
- [ ] T015 Add `games`, `game_drafts`, `game_published_versions`, and pointer-FK migration
- [ ] T016 Define Game Studio error codes and exception mapping

## Draft and Publish

- [ ] T032 Add revision-conflict and authorization integration tests
- [ ] T033 Add invalid-reference and immutable-Publish integration tests
- [ ] T035 Implement Game, GameDraft, and GamePublishedVersion entities/repositories
- [ ] T036 Implement revision-aware Draft service
- [ ] T037 Implement Schema and semantic validator adapter
- [ ] T038 Implement atomic validate→append→pointer Publish transaction
- [ ] T039 Implement authoring endpoints

## Published Runtime

- [ ] T044 Add public/private/not-published Runtime query tests
- [ ] T046 Implement Published query service and endpoint

## Portal Binding

- [ ] T051 Add Binding/Booth/Game status and signed Int32 configId matrix tests
- [ ] T053 Implement GamePortalBinding and `GAME_PORTAL` LayoutConfigResolver validation
- [ ] T054 Implement no-store Portal resolution endpoint
- [ ] T079 Implement persisted Asset source allow-list validation

## Game lifecycle (2026-08-25 #48 승인 — ②④⑤)

- [ ] T089 Implement `GET /games/mine` — soft-delete 포함, `deletedAt`, 활성 우선 + `updatedAt` 내림차순, 6필드
- [ ] T090 Implement `PATCH /games/{gameId}` visibility — `publishedVersion` null 이어도 PUBLIC 허용
- [ ] T091 Implement `DELETE /games/{gameId}` soft delete — 삭제본 5개 초과 시 최고령 1건 FIFO hard delete
- [ ] T092 Implement `POST /games/{gameId}/restore` — 활성 상한 게이트, 미삭제 대상은 멱등 200
- [ ] T093 Add limit tests — 활성 20 / 삭제본 5 경계, `GAME_LIMIT_EXCEEDED` 문구가 상한과 해결 방법을 담는지

## Policy and security verification

- [ ] T084 Implement/test `config_id` INTEGER mapping, Int32 boundaries, and whitelist deployment order
- [ ] T085 Encode unpublish, soft/hard delete, and Published-history policy in integration tests
- [ ] T086 [P1] Define display-only score schema isolated from Coin/Reward/Inventory
- [ ] T087 Verify Guest authoring denial and Published-play allowance
- [ ] T088 Verify invalid coordinates, unknown fields, and broken references are rejected without correction

## Summary

- Total: 26
- Completed: 0
- Remaining: 26
- Coordination: implementation is tracked by GitHub #48; do not duplicate it from FE/docs branches
