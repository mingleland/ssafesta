# BE Implementation Plan: FESTA Game Studio

**Date**: 2026-08-21 | **Shared spec**: [../spec.md](../spec.md) | **API contract**: [../contracts/game-api.md](../contracts/game-api.md)

## Summary

기존 Spring 앱에 `game` 도메인을 추가해 mutable Draft, immutable Published Version, 현재 공개본 포인터,
Game Portal Binding을 소유한다. 게임 프레임 실행·2D 물리·진행 중 로컬 세션은 서버 책임이 아니다.

## Technical Context

- Java 21, Spring Boot 4.1, Spring MVC/JPA/Security, Flyway, PostgreSQL JSONB
- `games`, `game_drafts`, `game_published_versions`, `game_portal_bindings`
- Draft save는 `expectedRevision` 낙관적 잠금
- Publish는 validate → immutable append → pointer update 단일 transaction
- Published body는 cache 가능, Portal resolution과 신규 진입 판정은 `no-store`

## Source Boundary

```text
backend/src/main/java/com/example/ssafesta/game/
├── api/
├── application/
├── domain/
└── persistence/
backend/src/test/java/com/example/ssafesta/game/
backend/src/main/resources/db/migration/
```

## Implementation Phases

1. migration과 도메인/오류 경계를 추가한다.
2. revision-aware Draft와 Schema+semantic validation을 구현한다.
3. atomic Publish와 Published query를 구현한다.
4. `GAME_PORTAL` whitelist와 resolver를 구현한다.
5. soft delete, 회원 탈퇴 hard delete, 이력 보존 정책을 통합 테스트로 고정한다.

## Fixed Policies

- 일반 게임 삭제는 `deleted_at` soft delete다.
- 회원 탈퇴는 Game, Draft, Published Version, Asset, Score까지 hard delete한다.
- Published Version 이력은 Game이 존속하는 동안 유지하고 hard delete 때 제거한다.
- 랭킹은 P1 표시 전용 후보이며 Coin/Reward/Inventory와 FK도 연결하지 않는다.
- 공개 중단은 신규 REST 조회만 차단한다. 진행 중 로컬 세션을 끊는 소켓은 만들지 않는다.
- 공개 `config_id`는 `INTEGER UNIQUE NOT NULL CHECK (config_id > 0)`, 전용 sequence는 1부터 시작한다.

## Verification

권한, revision conflict, invalid reference, immutable Published, transaction rollback, soft/hard delete,
Portal owner/status, `config_id` 0·음수·overflow 거부와 2147483647 왕복을 integration test로 검증한다.
