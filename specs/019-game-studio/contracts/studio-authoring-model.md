# Game Studio 편집·Asset 모델 v1.0/v1.1

> 상태: v1.0 호환 + v1.1 Frontend candidate 구현. Backend·AI 최종 허용 계약은 Issue #78에서 확정한다.

## 1. 편집 화면의 논리 영역

편집기는 아래 다섯 영역을 한 작업 공간에서 제공한다. v1은 PC 1280px 이상을 권장하되 760~1039px에서는
세 영역을 compact 배치하고 문서 전체 가로 스크롤 대신 중앙 Canvas 내부 이동을 사용한다. Shift+F 집중 모드는 양쪽 panel을 숨기고,
더 좁은 창에서는 패널을 서로 겹치게 축소하지 않고 가로 탐색을 허용한다. 각 영역이 수정하는 데이터의 소유권은 바꾸지 않는다.

| 영역 | 주 역할 | 수정 대상 |
|---|---|---|
| Scene 목록 | 생성·정렬·시작 Scene 지정 | `scenes[]`, `startSceneId` |
| Object/Asset 목록 | 준비된 preset·시각 자료 선택 | `assets[]`, 새 Object 기본값 |
| Map/Dialogue 작업 공간 | Tile 칠하기, Object 배치, Dialogue graph 편집 | 선택한 Scene |
| Properties | 선택 대상의 이름·위치·Component 편집 | Scene/Object/Component |
| Event Editor | Trigger·Condition·Action 구성 | `events[]`, Dialogue Choice |

선택 상태, 확대/축소, 열린 패널, undo/redo history는 편집기 로컬 상태이며 GameProject에 저장하지 않는다.
Object Layer의 편집 숨김·이동 잠금도 Runtime 의미가 아닌 브라우저별 편집 보조 상태다. 사용자는 Layer 목록에서
ID·종류 검색, 숨김, 잠금, Sprite `zIndex` 앞뒤 정렬을 수행할 수 있다.

Canvas 기본 도구는 `선택`과 `화면 이동`이다. 선택 도구는 단일 클릭, Shift/Ctrl 추가 선택, 빈 영역 드래그
선택을 제공한다. 방향키와 drag는 잠기지 않은 선택 묶음을 하나의 command로 이동하고 상대 간격을 보존한다.
화면 이동과 격자 표시 여부는 Editor 상태이며 GameProject에 저장하지 않는다.

## 2. 원본 데이터와 실행 결과

```text
Asset Catalog + GameProject JSON
              ↓
      Preview / Published Runtime
              ↓
         플레이 화면 구성
```

- GameProject JSON이 배치와 규칙의 원본이다.
- 배경을 한 장의 완성 이미지로 저장하거나 플레이 화면 캡처를 원본으로 사용하지 않는다.
- Preview와 Published Runtime은 같은 GameProject snapshot, Asset resolver, Event 의미를 사용한다.
- Runtime은 Studio store나 화면 컴포넌트를 직접 읽지 않는다.

## 3. Asset 참조

`assets[]`는 binary가 아니라 `id`, `kind`, `source`, 선택적 `integrity`만 저장한다.

- MVP 영구 저장은 버전이 고정된 `builtin://` catalog를 우선한다.
- 이미지·타일셋·오디오 binary, base64 `data:` URL, `blob:` URL, `file:` 경로는 저장·Publish 금지다.
- 만료되는 서명 URL을 Published GameProject에 넣지 않는다.
- 사용자 업로드를 추가할 때는 Spring이 소유한 안정적인 Asset ID를 저장하고 Runtime이 별도 조회로
  실제 전달 주소를 해석한다. 업로드 API·보존·공개 범위와 `asset://local` 승격은 [Issue #69](https://github.com/kanghyunsoon/ssafesta/issues/69)의 후속 Asset 계약 대상이다.
- catalog metadata는 타일 크기, atlas slicing, 기본 표시 크기를 소유한다. GameProject는 같은 정보를
  중복 저장하지 않는다.
- 기본 catalog는 타일셋, 4방향 캐릭터 애니메이션, 상호작용/액션 오브젝트, 대화 배경과 인물 표정을
  즉시 제공한다. 파일 선택은 `내 이미지로 교체`를 선택한 요소에서만 노출한다.

## 4. Tile과 Object 배치

- TOP_DOWN/PLATFORMER의 `width`, `height`는 셀 수다.
- `tileLayers[].data`는 왼쪽 위에서 오른쪽 아래로 진행하는 row-major 1차원 배열이며 길이는
  `width × height`다. `-1`은 빈 셀이다.
- Layer 배열 순서가 그리기 순서다. MVP 권장 이름은 `FLOOR`, `WALL`, `DECORATION`이지만 이름으로
  충돌이나 동작을 추론하지 않는다.
- World Object의 `position.x/y`는 0부터 시작하는 정수 셀 좌표다.
- Sprite 표시 크기는 `scale 25..400%`, 겹침 순서는 `zIndex 0..20`으로 조정한다. 회전·다중 셀
  Collider footprint는 실제 제작 사례가 확인된 뒤 별도 계약으로 확장한다.
- 여러 Object를 복제할 때 Object/Event ID를 새로 발급하고, 복제 대상 Object가 Trigger인 Event와 복제
  묶음 안의 `SHOW_OBJECT`/`HIDE_OBJECT` 참조를 새 ID로 다시 연결한다. Player Spawn은 복제하지 않는다.
- Object 삭제는 선택 밖 Event가 참조하는 Object와 Player Spawn을 자동 삭제하지 않는다. 자기 자신을
  Trigger로 삼아 숨기는 pickup Event처럼 삭제 대상에 종속된 Event는 Object와 함께 제거한다.
- Scene 크기 변경은 기존 Tile의 좌상단 좌표를 유지해 새 row-major 배열로 재구성한다. 축소 경계 밖
  Object는 새 마지막 셀로 clamp하며, 이 변환도 하나의 undo 가능한 command다.

## 5. Preset은 편집 편의 기능

`DOOR`, `ITEM`, `NPC`, `HAZARD`, `ENEMY`, `TURRET`, `CHECKPOINT`, `SPAWNER` 같은 preset은 사용자가 빠르게 시작하도록 Component와 Event 초안을 만드는
recipe다. Runtime이 preset별 별도 로직을 가져서는 안 된다.

예: 편집기의 `잠김=true`, `필요 아이템=key` 입력은 아래 공통 데이터로 변환한다.

```text
INTERACTABLE Component
+ ON_INTERACT(door)
+ HAS_ITEM(key)
+ SET_VARIABLE / SHOW·HIDE_OBJECT / GO_TO_SCENE
```

Inspector는 생성된 원본 Component/Event를 다시 읽어 편의 속성을 표시해야 한다. 편의 속성과 원본
Event를 이중 저장하지 않으며, recipe 형태를 더 이상 인식할 수 없으면 일반 Event 편집 화면으로 전환한다.

## 6. DIALOGUE 표시 방식

| presentation | 시작 방식 | 종료 방식 |
|---|---|---|
| `OVERLAY` | TOP_DOWN Event의 `SHOW_DIALOGUE` | `CLOSE_DIALOGUE`로 호출 Scene 복귀, 또는 `GO_TO_SCENE`/`COMPLETE_GAME` |
| `FULL_SCREEN` | `startSceneId` 또는 `GO_TO_SCENE` | 다른 Scene 이동 또는 게임 완료 |

- OVERLAY가 열리면 호출 Scene, Player 위치, 변수, inventory, Object visibility를 그대로 유지하고 월드
  입력만 중지한다.
- `CLOSE_DIALOGUE`는 OVERLAY Choice에서만 허용한다.
- OVERLAY Scene을 시작 Scene이나 일반 `GO_TO_SCENE` 대상으로 사용할 수 없다.
- FULL_SCREEN Dialogue에서는 복귀할 호출 Scene이 없으므로 `CLOSE_DIALOGUE`를 사용할 수 없다.
- 대화 Scene과 Node는 각각 선택적 `backgroundAssetId`, `portraitAssetId`를 가지며 표정은 인물 atlas의
  안정 Asset reference로 선택한다. Dialogue는 장르가 아니라 두 World Runtime 위에 재사용하는 연출 계층이다.

## 7. GameProject v1.1 게임 목표

v1.1은 기존 Trigger·Condition·Action을 재해석하지 않고 프로젝트 최상위 `rules`만 추가한다.

```json
{
  "schemaVersion": "1.1.0",
  "rules": {
    "completion": {
      "mode": "ALL",
      "objectives": [
        { "type": "DEFEAT_ENEMIES", "target": 3 },
        { "type": "SURVIVE_SECONDS", "target": 30 }
      ]
    },
    "playerDefeat": "END_GAME"
  }
}
```

- 목표 유형은 `SCORE_AT_LEAST`, `DEFEAT_ENEMIES`, `SURVIVE_SECONDS` 세 가지다. 같은 유형은 한 번만 사용한다.
- `ALL`은 모든 목표, `ANY`는 하나 이상의 목표 달성 시 완료한다. 빈 목표 배열은 자동 완료를 사용하지 않고 기존 `COMPLETE_GAME` Event를 사용한다.
- 생존 시간은 월드가 실제 진행되는 120ms Runtime tick을 누적한다. 대화 Overlay와 일시 중지 상태에서는 시간이 흐르지 않는다.
- `RESPAWN`은 체크포인트/시작점에서 체력을 복구하고, `END_GAME`은 `PLAYER_DEFEATED` 결과와 재도전 UI를 표시한다.
- v1.0은 계속 읽는다. 편집기가 v1.0을 수정하면 `rules` 기본값과 함께 v1.1로 승격하며 원래 필드를 암묵적으로 재해석하지 않는다.
- SHOOTER 기본 템플릿은 적 3명 처치, SURVIVAL은 30초 생존과 체력 0 종료를 사용한다.

## 8. Preview·Save·Publish

- Preview는 현재 편집 snapshot을 복제해 격리 Runtime에서 실행하며 Draft revision을 변경하지 않는다.
- Save는 GameProject JSON과 revision만 영구 저장한다. Editor selection/history는 저장하지 않는다.
- Publish는 구조·참조·Asset 정책·Dialogue presentation을 검증한 뒤 불변 Version을 만든다.
- 목표와 `COMPLETE_GAME`이 모두 없으면 끝낼 수 없는 게임으로 판단해 Publish를 거부한다.
- Preview에서만 보이는 임시 Asset이나 지원하지 않는 recipe가 남아 있으면 Publish를 거부한다.
- Publish blocker는 해당 Asset을 사용하는 Scene 배경, Tile Layer, Object Sprite/투사체/생성 대상, Dialogue 초상화, Item 위치를 함께 표시한다.
- revision 충돌은 로컬 snapshot을 유지하고 JSON 백업 또는 백업 후 서버 최신 Draft 로드 중 하나를 명시적으로 선택하게 한다.
- GameProject JSON은 2,000,000 bytes, Scene 50, Scene당 Object 500/Event 300, Asset 300 상한을 적용하고
  Asset binary는 별도 저장소가 소유한다.
- `VITE_USE_MOCK=true`에서는 브라우저 Draft를 immutable Published snapshot으로 복제하고 version을 증가시켜 일반 `/play` 경로를 검증한다. 운영 API 모드에서는 같은 Publisher/Repository port를 서버 adapter로 교체한다.

## 9. 파트 경계

- Game Studio Frontend: `src/game-studio/` 편집 UX, recipe 변환, local validation, Preview/Runtime Asset resolve.
- FESTA Host Frontend: lazy route, 기존 인증/API client, Game Overlay와 Unity lifecycle.
- Backend: 저장 가능한 Asset reference 정책, Draft/Publish 검증, 업로드를 도입할 경우 Asset 영구 상태.
- Unity: 위 데이터를 소비하지 않으며 Booth Portal trigger만 전달.
- AI: 선택적으로 Asset/Dialogue/Event 초안을 제안할 수 있으나 저장 전 동일 계약으로 변환·검토.
