# Implementation Plan: 캐릭터 커스터마이징

**Branch**: `game` | **Date**: 2026-08-12 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/013-avatar-customization/spec.md`

---

## Summary

월드 입장 전 캐릭터 생성 화면에서 외형을 고르고, 확정한 외형이 월드의 내 캐릭터와 **다른 접속자 화면에 동일하게** 적용된다.

기술 접근: 모듈러 프리팹 에셋의 파츠를 **ID 집합**으로 표현하고, 그 ID만 NGO로 동기화한다.
3D 조립은 각 클라이언트가 로컬에서 수행한다. 기존 `IAvatarVisualProvider` 경계를 그대로 재사용해
`NetworkPlayer`·Connection·Booth Runtime을 건드리지 않고 시각 계층만 교체한다.

**이 계획의 지배적 제약은 기능이 아니라 용량이다.** 신규 에셋 소스가 약 141MB(텍스처 약 80MB)이고
현재 Web 빌드가 약 87MB이므로, 카테고리·항목 수는 용량 실측 결과로 역산한다 (Phase 0).

## Technical Context

**Language/Version**: C# 9 / Unity 6000.0.78f1

**Primary Dependencies**: Netcode for GameObjects 2.4.3, Unity Transport 2.5.1, URP 17.0.4, 모듈러 캐릭터 에셋(벤더), uGUI

**Storage**: `IAvatarProfileStore` 추상화 — 1차는 PlayerPrefs(로컬). Backend는 계약 제안만 (`contracts/avatar-profile-api.md`)

**Testing**: 에디터 수동 검증 + Multiplayer Play Mode + 브라우저 2탭 + Docker Dedicated Server 회귀

**Target Platform**: Unity Web (WebGL, 데스크톱 크롬 기준) 클라이언트 + Linux Dedicated Server

**Project Type**: Unity 클라이언트 단일 프로젝트 (서버는 같은 프로젝트의 Dedicated Server 빌드)

**Performance Goals**: 조립은 Spawn·변경 시점에만 1회. 목표 인원 동시 접속 시 데스크톱 크롬 30fps 이상.
외형 동기화가 이동 동기화 지연에 영향을 주지 않을 것

**Constraints**: **Web 빌드 총량이 현재(약 87MB)를 넘지 않을 것**. 네트워크 payload는 고정 크기 struct(수십 바이트).
`NetworkPlayer` 수정 금지. 벤더 에셋 원본 수정 금지

**Scale/Scope**: 카테고리 6~10종 예상(실조사로 확정), 항목 수는 용량 예산으로 역산. 1채널 30~40명 목표

## Constitution Check

*GATE: Phase 0 이전 통과 필수. Phase 1 설계 후 재확인.*

| 조항 | 게이트 | 판정 |
|---|---|---|
| 2 — 실시간/영구 분리 | 게임 서버가 외형 **식별자**만 권위 보유, 경제·임대 미변경 | ✅ 통과 |
| 5 — 아바타는 데이터로 생성 | ID만 동기화 / 3D는 로컬 생성 / 외형에 NetworkObject 없음 / 캐릭터당 NetworkObject 1개 | ✅ 통과 |
| 16 — 클라이언트 불신 | 서버가 카탈로그 범위 내 ID인지 검증 | ✅ 통과 |
| 23 — 외형 데이터 표현 | 네트워크=고정 크기 struct, 저장=TEXT, itemId는 인덱스 아님 | ✅ 통과 |
| 24 — 계약 변경 절차 | 외형 데이터 형식 변경은 Unity+BE 합의 | ✅ 통과 (contracts/ 문서화) |
| 25 — UI는 웹 레이어 | Unity Lobby는 이관 전까지의 구현. `AvatarBridge` 경계 유지 | ⚠️ 조건부 통과 → Complexity Tracking |
| 27 — 기준선 동결 | `NetworkPlayer`/`ConnectionManager`/`BoothRuntime` 무수정 | ✅ 통과 |
| 28 — 범위 통제 | 상점·인벤토리·BlendShape 제외 | ✅ 통과 |

**Post-Design 재확인 (Phase 1 이후)**: `contracts/`와 `data-model.md` 작성 후에도 위 판정 변동 없음.
외형 struct가 고정 크기이므로 23조가 설계 수준에서 강제된다.

## Project Structure

### Documentation (this feature)

```text
specs/013-avatar-customization/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 미확정 항목 해소
├── data-model.md        # Phase 1 — 엔티티 정의
├── quickstart.md        # Phase 1 — 새 항목 추가 절차
├── contracts/
│   ├── network-avatar-config.md   NGO 동기화 계약
│   ├── avatar-profile-api.md      Spring 저장 API 제안
│   └── avatar-bridge.md           Unity ↔ React 브릿지 계약
└── tasks.md             # Phase 2 — $speckit-tasks 산출물
```

### Source Code (repository root)

```text
festa-unity/Assets/_Project/
├── Scripts/World/Avatar/
│   ├── Core/            AvatarConfig, AvatarPartCategory, Gender
│   ├── Catalog/         AvatarItemDefinition(SO), AvatarCatalog(SO), AvatarPreset(SO)
│   ├── Assembly/        AvatarAssembler, ModularAvatarVisualProvider
│   ├── Network/         NetworkAvatarConfig(struct), PlayerAppearanceController
│   ├── Persistence/     IAvatarProfileStore, LocalAvatarProfileStore
│   └── Lobby/           AvatarPreviewController, CharacterLobbyFlow, UI/
├── ScriptableObjects/Avatar/    항목 정의 에셋 (항목 추가의 등록 지점)
├── Prefabs/Avatar/              프로젝트용 아바타 프리팹 (벤더 원본 복제)
└── Scenes/
    ├── CharacterLobby.unity     [신규] 월드 접속 앞단
    └── main.unity               [기존] 무수정

festa-unity/Assets/_Project/Scripts/World/Avatar/   ← 기존 파일 처리
   IAvatarVisualProvider.cs      유지 (교체 가능성의 근거)
   PlayerAvatarVisual.cs         유지 + Provider 교체
   PlayerAppearanceController.cs 유지 + 동기화 타입 교체
   AvatarBridge.cs               유지
   AvatarAppearance.cs           대체 → Core/AvatarConfig
   AvatarCatalog.cs              대체 → Catalog/AvatarCatalog
   CatalogAvatarVisualProvider.cs 대체 → Assembly/ModularAvatarVisualProvider
   SidekickRuntimeService.cs     제거 (Phase 9)
   AvatarCustomizationHud.cs     제거 (Lobby로 대체)
```

**Structure Decision**: Unity 단일 프로젝트. 아바타 기능은 `Scripts/World/Avatar/` 아래 6개 하위 폴더로
책임을 분리한다 — Core(표현) / Catalog(정의) / Assembly(조립) / Network(동기화) / Persistence(저장) / Lobby(UI).
이 분리는 §Complexity Tracking의 "Lobby는 나중에 React로 이관" 조건을 지키기 위해서다.
Lobby 폴더만 들어내도 나머지가 그대로 동작해야 한다.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| 헌법 25조 — 커스터마이징 UI를 Unity에 구현 | 정식 창은 React 담당이지만, FE의 Overlay Platform이 아직 확정되지 않았고 아바타 검증에는 즉시 조작 가능한 UI가 필요하다 | React 창을 기다리면 아바타 기능 전체가 FE 일정에 종속된다. `AvatarBridge` 경계를 유지해 **Lobby 폴더만 들어내면 이관 가능**하도록 격리하는 조건으로 허용 |
| 벤더 프리팹 복제본을 `_Project/Prefabs/Avatar/`에 생성 | 벤더 원본을 수정하지 않으면서 프로젝트 전용 설정(레이어·스케일·Animator)을 적용해야 한다 | 원본 직접 수정은 에셋 업데이트 시 전부 소실된다 |
