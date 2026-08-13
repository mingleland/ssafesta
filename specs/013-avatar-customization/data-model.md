# Phase 1 Data Model: 캐릭터 커스터마이징

**Spec**: `013-avatar-customization` | **Date**: 2026-08-12

> 카테고리 목록은 R-01(에셋 조사) 산출물이다. 아래는 **구조**를 정의하며, 카테고리 이름은 조사 후 확정한다.

---

## 1. AvatarConfig — 외형 표현 (런타임 값 객체)

한 사용자의 외형 전체를 담는다. **메시·프리팹 참조를 갖지 않는다.**

| 필드 | 타입 | 설명 |
|---|---|---|
| `Gender` | enum | Male / Female |
| `Items` | 카테고리 → itemId 매핑 | 카테고리별 선택 항목 |
| `Colors` | 카테고리 → colorId 매핑 | 카테고리별 선택 색상 |

**불변식**

- 모든 itemId는 Catalog에 존재해야 한다. 없으면 **해당 카테고리의 기본값으로 폴백**한다 (FR-010)
- Gender와 항목의 성별이 맞지 않으면 기본값으로 폴백한다
- 값 객체다 — 조립 결과(GameObject)를 참조하지 않는다

**상태 전이**: 없음 (불변 값). 변경은 새 인스턴스 생성.

---

## 2. AvatarItemDefinition — 항목 정의 (ScriptableObject)

선택 가능한 항목 하나. **새 옷 추가 = 이 에셋 1개 생성 + Catalog 등록.**

| 필드 | 타입 | 필수 | 설명 |
|---|---|:---:|---|
| `itemId` | int 또는 안정 문자열 | ✅ | **안정 식별자.** 배열 인덱스 아님. 한 번 부여하면 재사용·재배치 금지 |
| `displayName` | string | ✅ | UI 표시명 |
| `category` | enum | ✅ | 헤어 / 상의 / 하의 등 (R-01 확정) |
| `gender` | enum | ✅ | Male / Female / Both |
| `visual` | 프리팹 또는 SkinnedMesh 참조 | ✅ | 실제 시각 자산 |
| `thumbnail` | Sprite | | 없으면 이름 텍스트로 대체 |
| `availableColors` | colorId 목록 | | 비면 색상 변경 불가 항목 |
| `hiddenBodyParts` | 신체 부위 목록 | | **착용 시 숨길 부위** (클리핑, R-04) |
| `isDefault` | bool | | 카테고리 기본값 (폴백 대상) |
| `requiresCompanion` | 카테고리 + itemId 조건 | | **조합 규칙** (R-03) — 예: 모자 변형이 특정 헤어에 대응 |
| `shopVisible` / `price` / `unlockType` | | | **상점 대비 자리. 이번엔 사용하지 않는다** |

**검증 규칙**

- `itemId`는 Catalog 내에서 유일해야 한다 (중복 시 에디터에서 오류)
- `visual`이 비면 그 항목은 목록에 노출하지 않는다
- 카테고리별로 `isDefault`가 정확히 하나 있어야 한다

---

## 3. AvatarCatalog — 항목 모음 (ScriptableObject)

**항목 등록의 유일한 지점.** 코드 수정 없이 여기에만 추가한다 (FR-019).

| 필드 | 타입 | 설명 |
|---|---|---|
| `items` | AvatarItemDefinition 목록 | 전체 항목 |
| `colors` | colorId → 색상 값 | 공용 색상 팔레트 |

**제공 기능**

- `GetItem(itemId)` — 없으면 null
- `GetItemsByCategory(category, gender)` — UI 목록용
- `GetDefault(category, gender)` — 폴백용
- `Validate(config)` — **서버 검증에 사용** (FR-011). 카탈로그 범위 밖 ID를 걸러낸다
- `ResolveVisual(config, category)` — 조합 규칙(R-03)을 적용해 실제 시각 자산을 결정

**불변식**: 클라이언트와 서버가 **같은 Catalog**를 갖는다 (같은 빌드 산출물). 그래서 ID만으로 조립이 성립한다.

---

## 4. AvatarPreset — 기본 외형 (ScriptableObject)

게스트·초기값용. 최소 남/녀 각 1종.

| 필드 | 타입 |
|---|---|
| `presetId` | string |
| `config` | AvatarConfig |

**용도**: 게스트 로그인 시(FR-015), 저장값이 없을 때, 폴백이 전부 실패했을 때.

---

## 5. NetworkAvatarConfig — 동기화 표현 (struct)

`INetworkSerializable`을 구현하는 **고정 크기** 구조체. 상세 계약: `contracts/network-avatar-config.md`

| 필드 | 타입 |
|---|---|
| `Gender` | byte |
| 카테고리별 itemId | 고정 개수의 정수 필드 |
| 카테고리별 colorId | 고정 개수의 바이트 필드 |

**설계 근거**: 가변 길이를 쓰지 않으므로 **"길이 초과" 실패 모드가 존재하지 않는다** (T-24 재발 불가).

**동기화 규칙**

- 서버 쓰기 / 전원 읽기
- **Spawn 시점과 변경 시점에만** 값이 바뀐다 (FR-006)
- 서버는 기록 전 `Catalog.Validate()`를 통과시킨다 (FR-011)

---

## 6. 저장 표현 (영속)

| 항목 | 값 |
|---|---|
| 형식 | `카테고리:itemId` 쌍의 짧은 직렬화 문자열 |
| 저장소 | 1차 PlayerPrefs → 이후 Spring `TEXT` 컬럼 |
| 미지의 값 | **무시하고 기본값 폴백** (forward compatible) |

**왜 struct가 아니라 문자열로 저장하나**: 카테고리가 추가돼도 **저장 스키마를 바꾸지 않기 위해서**다.
네트워크는 성능이 중요해 고정 크기, 저장은 진화 가능성이 중요해 가변 문자열 — 목적이 다르다.

---

## 엔티티 관계

```text
AvatarPreset ──제공──> AvatarConfig <──검증── AvatarCatalog
                           │                      │
                           │                      └── AvatarItemDefinition (다수)
                           │                              │
                    ┌──────┴──────┐                       └── hiddenBodyParts
                    │             │                           requiresCompanion (조합)
          NetworkAvatarConfig   저장 문자열
             (네트워크)          (영속)
                    │
                    ▼
              AvatarAssembler ──> 로컬 GameObject (NetworkObject 아님)
```
