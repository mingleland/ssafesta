# SSAFESTA Runtime Asset Compiler — 설계

> **STATUS: PROPOSAL**
> **NOT ADOPTED**
>
> 이 문서는 제안이다. DECISION 이 아니다 — 확정은 [`00_context/implementation-decisions.md`](../00_context/implementation-decisions.md) 에서만 이뤄진다.
> 현재 2.5D 상태 정본: [`05_technical-spikes/booth-studio-2_5d/README.md`](../05_technical-spikes/booth-studio-2_5d/README.md)
>
> 작성: 2026-09-07 · Jira `S15P21A604-479`

---

## 1. 왜 필요한가

`-473`·`-476` 으로 **Unity 원본 → GLB → R3F** 가 성립한다는 것은 확인됐다. 그런데 그 GLB 는 원본 topology 를 거의 그대로 담고 있고, 텍스처는 아예 가져오지 않았다. 이 상태로는 두 가지가 막힌다.

```text
1. 텍스처를 넣는 순간 무게가 감당이 안 된다
   사용 재질 원본 합 ~24 MB (Plastic_m 3.4 MB · Aluminium_m 3.4 MB · Chair02b 세트 10.9 MB)

2. 원본이 그대로 나가는 형태는 벤더 에셋 배포로 읽힌다
   ExpoKit · ithappy · Synty · Rukha93 — 라이선스 파일 0건
```

둘 다 "GLB 를 더 잘 만들면" 풀리는 문제가 아니라, **원본과 다른 산출물을 만드는 단계**가 필요하다는 뜻이다.

---

## 2. 목표

```text
ONE SOURCE                Unity Project Assets
ONE COMPILER              SSAFESTA Runtime Asset Compiler
ONE RUNTIME VISUAL ASSET  SSAFESTA 경량 3D asset
ONE VISUAL SOURCE         Canvas 와 Asset Library thumbnail 이 같은 runtime asset 에서 나온다
```

성질:

```text
겉모습      ≈ Unity 실제 에셋
내부 구조   ≠ Unity 원본
성능        원본 직접 배포보다 가볍다
도메인 계약  기존 OBJECT_LOCAL_BOUNDS 유지
```

### 자유 Y 회전은 유지한다

```text
15° snap      기본 편의 기능
rotationY     세밀 값 지원 유지
```

24방향 sprite 같은 방식으로 회전을 이산화하지 않는다. 그 방식은 계약(`rotationY` 는 `[0,360)` 실수)을 렌더러 사정으로 좁히는 것이라 D-06 에 어긋난다.

---

## 3. 파이프라인

```text
                        PRIVATE BUILD ZONE

  Unity Project
  ├─ BoothObjectRegistry
  ├─ Prefab
  ├─ Nested Prefab
  ├─ FBX
  ├─ Material
  └─ Texture
         │
         ▼
  Source Resolver
         │
         ├─ Prefab hierarchy resolve
         ├─ PrefabInstance resolve
         ├─ m_Modifications override
         └─ Transform flatten
         │
         ▼
  Normalize          axis · unit · pivot · transform
         │
         ▼
  Intermediate Scene / GLB          ◀── BUILD-TIME ONLY
         │
         ▼
  SSAFESTA Runtime Asset Compiler
         │
         ├─  1. Mesh flatten / merge
         ├─  2. Remesh
         ├─  3. Visual-error-aware simplify
         ├─  4. Normal regenerate
         ├─  5. UV regenerate
         ├─  6. Material bake
         ├─  7. Texture atlas
         ├─  8. Texture downscale
         ├─  9. PBR channel optimization
         ├─ 10. Metadata strip
         ├─ 11. Quantization
         ├─ 12. Mesh compression
         ├─ 13. Texture compression
         ├─ 14. Runtime manifest generation
         └─ 15. Thumbnail generation
         │
         ▼
                        PUBLIC RUNTIME

  SSAFESTA Runtime Asset
  ├─ lightweight mesh
  ├─ baked / compressed textures
  ├─ assetCode
  ├─ pivot metadata
  ├─ visual metadata
  └─ thumbnail
         │
         ▼
  React / R3F
```

`Source Resolver` 와 `Normalize` 는 `-476`·`-473` 에서 **이미 동작이 확인된 부분**이다. 새로 설계되는 것은 그 아래 Compiler 단계다.

---

## 4. Geometry 정책

단순 `decimate percentage` 방식을 쓰지 않는다.

```text
목적이 삼각형 수 감소 자체        ❌
원본과 외형 근사를 유지하면서
필요한 최소 runtime geometry 생성  ✅
```

순서:

```text
flatten → remesh → simplify → normal regenerate → new UV
```

`remesh` 는 원본 topology 와 runtime topology 의 직접 대응을 없애는 단계다. 결과적으로 산출물이 원본 mesh 의 복사본이 아니게 된다.

### 다만 무조건 돌리지 않는다

`SURVEY_KIOSK_DEFAULT` 는 이미 286 tri 다. 이런 입력에 고비용 remesh 를 강제하면 얻는 것 없이 파이프라인만 느려지고 외형만 나빠진다. **Compiler 가 입력별로 단계 적용 여부를 판단할 수 있게 설계한다.**

판단 근거 후보: 삼각형 수 · 재질 수 · UV 상태 · 목표 예산.

---

## 5. Material / Texture 정책

대표 source:

```text
PlasticWhite   Plastic_m.png ~3.4 MB          가장 널리 쓰이는 재질
Chair02b       texture set ~10.9 MB           normal map 포함, worst case
```

경로:

```text
Unity Material
  → runtime PBR channels
  → new UV / atlas
  → downscale
  → KTX2 / Basis 계열 압축
```

**원본 vendor texture 를 runtime 에 그대로 복사하지 않는다.**

비교 지표:

```text
original bytes / runtime bytes
base color similarity
normal detail
roughness · metalness impression
zoom quality
actual Studio appearance
```

수치 임계값은 여기서 확정하지 않는다 — 실측 전에 정하면 근거 없는 기준이 된다.

---

## 6. Metadata Strip 정책

Runtime Asset 에 남길 수 있는 최소 정보:

```text
assetCode
runtime resource id
pivot
visual dimensions
thumbnail id
필요한 runtime flags
```

지우는 것:

```text
Unity 원본 경로 · FBX filename · Prefab path
Vendor name · Asset Store path
original node names · original material names · original texture names
불필요한 hierarchy
authoring metadata
```

`-473`·`-476` 의 현재 GLB 는 원본 mesh 이름(`DisplayBox01`·`Chair01`·`Counter01Top`)을 그대로 담고 있다. Compiler 단계에서 이것이 사라진다.

**`OBJECT_LOCAL_BOUNDS` 를 visual asset 내부로 옮기지 않는다.** 도메인은 계약에 남는다(§7).

---

## 7. Domain / Visual 완전 분리

```text
OBJECT_LOCAL_BOUNDS   Domain Truth
Runtime Asset         Visual Representation
```

runtime mesh 가 원본과 미세하게 달라져도 다음은 흔들리지 않는다.

```text
Inspector 표시
저장 계약
이동
bounds clamp
snap
rotation contract
```

`-473`·`-476` 이 이미 이 규칙 아래에서 검증됐다(계약 AABB 와 mm 단위 대조). Compiler 가 들어와도 같다.

---

## 8. Thumbnail 자동 생성

좌측 Asset Library 썸네일까지 같은 파이프라인에 넣는다.

```text
Runtime Asset
  → canonical thumbnail camera
  → canonical lighting
  → render
  → thumbnail (WebP / AVIF)
```

출력:

```text
assetCode
├─ runtime 3D resource
├─ thumbnail
└─ manifest entry
```

썸네일과 중앙 Canvas 가 **서로 다른 visual source 를 갖지 않게** 한다. 현재는 팔레트 썸네일이 CSS 도형이라 캔버스와 무관하다 — 에셋이 늘어날수록 둘이 갈라진다.

사람이 에셋마다 수작업 캡처하는 구조를 기본으로 하지 않는다. 필요하면 asset metadata 로 다음 정도만 override 한다.

```text
thumbnail camera preset
thumbnail yaw
thumbnail crop
```

---

## 9. Visual Parity Gate

Source 와 Runtime 을 같은 조건에서 여러 방향으로 렌더해 비교한다.

```text
0 · 45 · 90 · 135 · 180 · 225 · 270 · 315
```

**이 각도는 런타임 회전 제한이 아니라 QA sample 이다.** 실제 `rotationY` 는 계속 자유값이다(§2).

비교 항목:

```text
silhouette · width · height · depth impression
major shape
color · material impression · normal expression
shadow footprint
thumbnail quality
```

기록 서식과 절차는 [`06_visual-review/booth-studio-runtime-asset-parity.md`](../06_visual-review/booth-studio-runtime-asset-parity.md).

이미지 기반 정량 metric 은 나중에 추가할 수 있으나 **이번 문서에서 임계값을 확정하지 않는다.**

---

## 10. Source Package License Gate

에셋마다 "Chair01 은? DisplayBox 는? Kiosk 는?" 을 반복 판정하는 구조를 폐기한다. **Source Package 단위 정책**으로 간다.

개념 파일(실제 경로는 구현 시 repo 구조에 맞춘다):

```text
tools/assets/source-packs.lock.json
```

상태:

```text
ALLOW_RUNTIME_COMPILE
REVIEW_REQUIRED
BLOCK
```

package metadata:

```text
packageId
vendor
acquisitionSource
licenseClass
evidence / reference
runtimeCompilePolicy
```

Compiler 동작:

```text
asset
  → source package lookup
      ├─ ALLOW      → compile
      └─ 그 외      → build fail 또는 명시적 review
```

현재 관련 패키지: `ExpoKit` · `ithappy/Casino_Free` · `Synty` · `Rukha93` · `300Mind` · `Palmov Island` · `danthaigames` · `Hyper casual cartoon castles`. 라이선스 파일은 저장소에 없다.

---

## 11. 라이선스 · 보호 정책

```text
Raw FBX          production 배포 금지
Unity Prefab     production 배포 금지
Original Texture production 배포 금지
Intermediate GLB production 배포 금지
Runtime Asset    배포 후보
```

중요:

```text
Remesh / Simplify / Encryption   ≠ 새로운 라이선스 생성

기술 가공         원본 복원 난이도 감소 · 제품 최적화 · 보호
법적 사용 가능 여부  Source Package License Gate 가 정한다
```

**기술적 난독화로 라이선스를 "우회했다" 고 표현하지 않는다.** 가공은 최적화와 보호의 수단이지 권리의 근거가 아니다.

---

## 12. Intermediate 와 Runtime 분리

`-473` 이 만든 GLB 의 역할을 재정의한다.

| | Intermediate GLB | Runtime Asset |
|---|---|---|
| 무엇 | Unity source → Compiler 전달용 | Compiler 결과 |
| 원본 topology | 유지 | 제거 |
| git commit | 금지 | 정책에 따름 |
| Docker production context | 금지 | 정책에 따름 |
| production serving | 금지 | 후보 |

경로 개념(실제 경로는 구현 시 repo 구조 조사 후 확정):

```text
.generated/intermediate/
.generated/runtime/
```

현재 `-473` 산출물은 `festa-frontend/public/assets/booth/` 에 놓이고 `.gitignore`·`.dockerignore` 로 막혀 있다. Compiler 단계에서 이 경로 구분이 정리된다.

---

## 13. Runtime Pack 은 다음 단계

방향은 기록하되 **v1 Spike 범위에 넣지 않는다.**

```text
multiple Runtime Assets
  → runtime pack / container
  → hash / resource id
  → optional access control
  → optional encryption
```

v1 은 다음까지만 본다.

```text
runtime model · texture · thumbnail · manifest
```

custom pack 과 암호화를 한 번에 넣지 않는다.

---

## 14. 이 문서가 하지 않는 것

```text
D-05 채택 결정              → gate-matrix.md 의 조건 + 별도 결정
도구 선정 확정              → 01_tooling/booth-runtime-asset-toolchain.md (PROPOSED)
정량 임계값 확정            → 실측 후
구현 계획                   → 05_technical-spikes/booth-studio-2_5d/runtime-asset-compiler-v1-plan.md
도메인 계약 변경            → 하지 않는다 (D-06)
```
