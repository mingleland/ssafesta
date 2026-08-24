# BE Tasks: FESTA Game Studio

**Shared spec**: [../spec.md](../spec.md) | **FE tasks**: [../FE/tasks.md](../FE/tasks.md)

BE가 소유하는 21개 작업이다. #48에서 `strdeok`가 구현을 진행하므로 이 문서는 계약과 완료 조건만 소유한다.

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

## Policy and security verification

- [ ] T084 Implement/test `config_id` INTEGER mapping, Int32 boundaries, and whitelist deployment order
- [ ] T085 Encode unpublish, soft/hard delete, and Published-history policy in integration tests
- [ ] T086 [P1] Define display-only score schema isolated from Coin/Reward/Inventory
- [ ] T087 Verify Guest authoring denial and Published-play allowance
- [ ] T088 Verify invalid coordinates, unknown fields, and broken references are rejected without correction

## Summary

- Total: 21
- Completed: 0
- Remaining: 21
- Coordination: implementation is tracked by GitHub #48; do not duplicate it from FE/docs branches
