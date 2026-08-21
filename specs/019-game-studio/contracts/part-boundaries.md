# Game Studio 파트 간 책임 및 통신 규약

> 상태: Draft / Issues #20~#22 검토 대기

## 데이터 흐름

```text
Studio(FE)
  → Asset reference + Tile/Object/Event GameProject Draft
  → Spring(BE) validate/save/publish
  → Published GameProject
  → Web Runtime(FE)

Unity Booth Interaction
  → React Host(FE)
  → Portal Binding 조회(BE)
  → Web Runtime(FE)

AI(optional, future)
  → async generation
  → user review
  → ordinary Asset/Dialogue/Event
```

## 계약 표

| Boundary | Producer | Consumer | Contract | Required for MVP |
|---|---|---|---|:---:|
| Studio → Spring | FE | BE | GameProject Draft + revision | ✅ |
| Spring → Runtime | BE | FE Runtime | Published GameProject + version | ✅ |
| Asset Catalog → Studio/Runtime | FE static catalog / future BE | FE | stable Asset reference + metadata | ✅ builtin only |
| Studio → Preview | FE Studio | FE Runtime | GameProject preview message | ✅ |
| Unity → React Host | Unity | FE Host | `BOOTH_GAME_INTERACT` | 선택 통합 |
| React Host → Spring | FE | BE | Portal Binding resolution | 선택 통합 |
| AI → Studio | AI | FE | 생성 Job/결과 | ❌ P2 후보 |

## 금지 경계

- FE가 Published Version의 최종 유효성을 단독 판정하지 않는다.
- BE가 Phaser/2D 프레임 실행을 구현하지 않는다.
- Unity가 GameProject JSON을 읽거나 2D 게임 결과를 판정하지 않는다.
- AI 장애가 Studio 저장, Publish, Runtime 로드를 차단하지 않는다.
- AI raw provider 응답을 GameProject에 저장하지 않는다.
- Runtime 완료 payload를 Coin/Reward 근거로 그대로 사용하지 않는다.
- Asset binary, `data:`/`blob:`/`file:` URI, 만료 URL을 GameProject에 저장하지 않는다.
- preset 편의 속성을 Runtime 전용 Door/NPC 로직으로 이중 구현하지 않는다.

## 답변 대기 항목

- FE: 앱/배포 단위, 인증 공유, preview/overlay lifecycle, 편집 패널 실제 배치 — #20
- BE: revision/상태코드, 저장 모델, semantic validation, portal resolution, Asset upload/resolver — #21
- AI: MVP 제외 확정, 후속 생성 기능과 async job 경계 — #22
