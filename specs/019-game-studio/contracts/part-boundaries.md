# Game Studio 파트 간 책임 및 통신 규약

> 상태: Draft v0.3 — #20~#22와 #33~#34 반영 / FE 내부 #35만 추적

## 데이터 흐름

```text
Game Studio(FE, festa-frontend/src/game-studio)
  → Asset reference + Tile/Object/Event GameProject Draft
  → Spring(BE) validate/save/publish
  → Published GameProject
  → Web Runtime(FE)

Unity Booth Interaction
  → React Host(FE)
  → Portal Binding 조회(BE)
  → Web Runtime(FE)

AI(optional, P2)
  → persistent async generation job
  → GameProject v1-compatible candidate/patch
  → Editor validation + user review
  → ordinary Dialogue/Event
```

## 계약 표

| Boundary | Producer | Consumer | Contract | Required for MVP |
|---|---|---|---|:---:|
| Studio → Spring | Game Studio FE | BE | GameProject Draft + expectedRevision | ✅ |
| Spring → Runtime | BE | FE Runtime | Published GameProject + version | ✅ |
| Asset Catalog → Studio/Runtime | Game Studio FE builtin catalog / future BE | FE | stable Asset reference + metadata | ✅ builtin only |
| Studio → Preview | FE Studio | FE Runtime | GameProject preview message | ✅ |
| Unity → React Host | Unity | FE Host | `BOOTH_GAME_INTERACT` | 선택 통합 |
| React Host → Spring | FE | BE | Portal Binding resolution | 선택 통합 |
| AI → Studio | AI | Game Studio FE | v1-compatible candidate/patch | ❌ P2 후보 |

## 금지 경계

- FE가 Published Version의 최종 유효성을 단독 판정하지 않는다.
- BE가 2D renderer/프레임 실행을 구현하지 않는다.
- Unity가 GameProject JSON을 읽거나 2D 게임 결과를 판정하지 않는다.
- AI 장애가 Studio 저장, Publish, Runtime 로드를 차단하지 않는다.
- AI raw provider 응답을 GameProject에 저장하지 않는다.
- Runtime 완료 payload를 Coin/Reward 근거로 그대로 사용하지 않는다.
- Asset binary, `data:`/`blob:`/`file:` URI, 만료 URL을 GameProject에 저장하지 않는다.
- preset 편의 속성을 Runtime 전용 Door/NPC 로직으로 이중 구현하지 않는다.

## 확정 및 후속 항목

- FE Host 확정(#20): 같은 `festa-frontend` 배포, lazy route, same-origin Preview, 기존 인증/API client 재사용.
- BE 확정(#21): Draft/Published 2테이블, revision 409, 단일 Publish transaction, Portal no-store.
- AI 확정(#22): MVP 비의존, P2 candidate/patch, spec 007 Job 정책 재사용.
- 제품 정책 확정(#33): 새 진입 REST 차단, loaded local session 완료 허용, 일반 soft/탈퇴 hard delete, 이력 유지, Ranking P1 절연.
- 교차 계약 확정(#34): signed Int32 `configId`, 별도 INTEGER 공개 ID, `GAME_PORTAL requiresConfig=true`, BE-first 배포.
- Studio 내부 대기(#35): renderer, Preview sandbox/CSP/UI, builtin Asset resolver.
