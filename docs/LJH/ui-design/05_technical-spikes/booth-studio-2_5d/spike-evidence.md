# 2.5D Spike Evidence

> 상태 정본: [`README.md`](./README.md) · Gate 대조: [`gate-matrix.md`](./gate-matrix.md)
> 갱신: 2026-09-07 · Jira `S15P21A604-479`

여기 적힌 것은 **기술 evidence** 다. 세 브랜치 모두 merge 전이며, **merge 여부와 무관하게** 무엇이 확인됐는지를 남긴다. 브랜치가 나중에 rebase 되거나 폐기돼도 측정값은 그대로 유효하다.

---

## S15P21A604-470 — R3F renderer Spike

```text
branch   feat/S15P21A604-470-r3f-spike
commit   e0d1054f
base     origin/develop abdb39fd
```

### 무엇을 했나

`TemporaryIsoRenderer`(SVG 아이소메트릭 직접 투영)를 대체 가능한 R3F 렌더러 프로토타입을 만들었다. 실제 에셋은 쓰지 않고 계약의 `OBJECT_LOCAL_BOUNDS` 에서 파라메트릭 박스를 생성했다.

```text
R3F + three          도입
drei                 설치했다가 제거 — 쓰는 API 가 하나도 없었다
                     gridHelper 는 three 기본이고 그림자는 실광원으로 해결된다

고정 Orthographic isometric 카메라
파라메트릭 geometry (바닥·벽·트러스·오브젝트)
실광원 (directional 1 + spot 3) · 그림자 · PBR material
3축 gizmo (X 빨강 / Z 파랑 잡을 수 있음, Y 초록은 표시만 — 계약상 고정)
Studio 라우트 lazy + R3F lazy chunk
```

기존 editor 계약은 그대로다 — selection · move · snap · rotation · bounds · Inspector.

### 번들

| 청크 | before | after |
|---|---|---|
| main `index` | 427.02 kB / gzip 131.87 | **339.46 kB / gzip 106.54** |
| `StudioPage` | (main 에 포함) | 53.30 kB / gzip 16.19 |
| `R3FBoothRenderer` | — | 859.37 kB / gzip 228.21 |

main 이 **줄었다** — Studio 가 빠졌기 때문이다. 프로덕션 `index` 청크 문자열 검사에서 `THREE`·`WebGLRenderer`·`react-three`·`BoxGeometry` 0건.

### 런타임

```text
World → Studio → World  3왕복
  canvas             항상 1개 — R3F 컨텍스트 누적 없음
  context lost       0
  Unity 구동 중 두 번째 WebGL2 컨텍스트 생성  성공, 양쪽 lost 없음
```

**단, Unity 와 R3F 가 동시에 살아 있는 상태는 아니다.** 현 구조에서 Studio 진입 시 Unity 가 내려간다(`PlayerConnection::Cleanup`). 동시 상주는 Persistent GameShell 이후에만 실측할 수 있다.

### 성능

```text
Studio 진입 heap    SVG 222.7 MB / R3F 237.4 MB   (+14.7 MB)
드래그 1회 처리      0.1 ms (median) · 0.4 ms (max)
프레임              10.7 ms (median)
```

`World` 재진입마다 heap 이 ~200 MB 씩 증가하는 현상이 관측됐다(1070 → 1461 → 1471 MB). **R3F 와 무관하다** — Studio 를 한 번도 열지 않는 `World ↔ /app/booths` 왕복에서 같은 폭으로 재현됐다. Unity 인스턴스 lifecycle 의 별건이다.

### 품질 게이트

`npm run build` exit 0 · vitest 679/679 · tsc 0 · oxlint 신규 경고 0

---

## S15P21A604-473 — Unity FBX → GLB → R3F

```text
branch   feat/S15P21A604-473-glb-pipeline
commit   b4d77ebf  (+ oxlint 정리 904d43b2)
base     feat/S15P21A604-470-r3f-spike e0d1054f
```

### 파이프라인

```text
Unity 원본 FBX
  → three FBXLoader
  → axis / unit / pivot 정규화
  → three GLTFExporter
  → GLB
  → manifest
  → R3F
```

**외부 도구를 쓰지 않았다.** Blender·FBX2glTF·assimp 는 이 장비에 설치돼 있지 않고, three 가 `FBXLoader` 와 `GLTFExporter` 를 이미 갖고 있다. Node 용 `FileReader` shim 1개만 필요했다.

### 대표 2종

| assetCode | 원본 | GLB | tri | 정규화 후 크기 |
|---|---|---|---|---|
| `DECORATION_DEFAULT` | `Stands/DisplayBox01.FBX` 52 KB | 26.1 KB | 240 | 0.6 × **1.6085** × 0.6 m |
| `FURNITURE_CHAIR01` | `Furniture/Chair01.FBX` 55.9 KB | 118.2 KB | 1212 | 0.3774 × 0.45 × 0.3774 m |

`SURVEY_KIOSK` 는 이 회차에서 **의도적으로 제외**했다 — 단일 메시가 아니라 조립체라 별도 단계(`-476`)로 갈랐다.

### 계약 대조 — 이것이 파이프라인 정확도의 판정이다

```text
runtime    0.6 × 1.6085 × 0.6
contract   ±0.3 × 1.61 × ±0.3     (OBJECT_LOCAL_BOUNDS.DECORATION)
오차       약 1.5 mm
```

단위(inch → m, 0.0254)·축(Z-up → Y-up)·피벗(바닥 중앙)이 **동시에** 맞았다는 뜻이다. 셋 중 하나라도 틀리면 이 숫자가 나오지 않는다.

의자는 계약 `FURNITURE`(1.5 × 0.75 × 1.72)보다 작다. 그 AABB 는 `Furniture.prefab`(테이블 1 + 의자 3) 조립체의 것이고 의자 단품은 다른 자산이다 — **어긋남이 아니라 assetCode 가 타입 기본과 다르다는 뜻**이다.

### 런타임 검증

```text
바닥 정렬          min.y = 0
회전               rotationY 90° 에도 중심이 (-0.75, -0.75) 에서 안 움직인다
mesh picking       동작
depth picking      앞 오브젝트가 가리면 그것이 먼저 잡힌다 (정상)
manifest           200
GLB                200
[BoothAsset] 오류   0
렌더러             30 draw calls · 2216 triangles · heap 261 MB
```

### 설계 판단 2건

```text
Domain truth    OBJECT_LOCAL_BOUNDS
Visual model    ≠ Domain truth
```

선택 윤곽·이탈 판정은 계약 AABB 기준이며 모델 치수를 따라가지 않는다.

```text
typeDefault     선언으로만 정한다
```

처음엔 "그 타입 후보가 하나면 그것" 규칙을 썼다가 뺐다. 두 번째 자산이 들어오는 순간 무관한 모델이 기본 자리를 차지한다 — Unity 쪽에서 같은 방식으로 터진 것이 T-148 이다.

### 라이선스 취급

GLB 산출물을 커밋하지 않았다. `.gitignore` + `.dockerignore` 양쪽에서 막았다. CI 는 git 체크아웃에서 빌드하므로 이미지에 GLB 가 0개다.

### 품질 게이트

`npm run build` exit 0 · vitest 689/689 · tsc 0 · oxlint 신규 경고 0

---

## S15P21A604-476 — Nested Unity Prefab Assembly

```text
branch   feat/S15P21A604-476-prefab-assembly
base     feat/S15P21A604-473-glb-pipeline 904d43b2
```

### 대상

```text
SurveyKiosk.prefab
├─ Tablet      (PrefabInstance)  localPos (0, 0.9233265, 0.02)
└─ Counter01   (PrefabInstance)  localPos (0, ~0, 0)
     └─ 내부 FBX 4종 — Counter01 · Counter01Top · Counter01Cloth01 · CounterShelf
```

중첩 prefab 이라 "child prefab 참조를 재귀로 따라갈 수 있는가" 가 함께 걸린다.

### 결과

```text
SURVEY_KIOSK_DEFAULT   35.8 KB GLB · 286 tri · 0.62 × 0.9255 × 0.3195 m

contract (OBJECT_LOCAL_BOUNDS.SURVEY_KIOSK)   ±0.31 × 0.93 × ±0.16
오차   X 0.000 · Y 0.0045 · Z 0.0005
```

자식 transform · 중첩 prefab · 단위가 전부 제자리라는 뜻이다. 브라우저에서도 카운터 위에 태블릿이 올라간 형태로 그려지고 Inspector 는 계약 치수 `0.62 / 0.93 / 0.32` 를 그대로 보여 준다.

### 한 번 틀린 것 — `m_Modifications` 의미

```text
잘못 이해   원본 Transform 에 곱해지는 delta
실제        원본 Transform 을 덮어쓰는 override
```

감싸는 그룹에 인스턴스 transform 을 넣고 원본 루트 회전을 그대로 뒀더니 −90° 가 두 번 걸려 키오스크가 누웠다(높이 1.2127 m · 깊이 0.9431 m). 원본 루트에 직접 덮어쓰도록 고쳐 위 수치가 됐다. → LJH `T-49`

### 지원 밖은 조용히 빠지지 않는다

```text
경고 2건   SurveyKiosk 루트의 MonoBehaviour
           (BoothRuntimeObject · BoothInteractionTarget)
```

런타임 스크립트라 재현 대상이 아니지만, 이름과 함께 보고된다. 지원 범위는 정적 구조뿐이다 — GameObject/Transform 트리, MeshFilter 의 FBX 참조, child prefab 인스턴스의 local position/rotation/scale.

### 품질 게이트

`npm run build` exit 0 · vitest 690/690 · tsc 0 · oxlint 신규 경고 0
번들 변화 없음 — 도구가 빌드타임이라 런타임에 들어가지 않는다.

---

## 세 회차가 공통으로 지킨 것

```text
1. 도메인 불변       OBJECT_LOCAL_BOUNDS 가 정본. 모델 치수로 계약을 바꾸지 않았다
2. seam 뒤에서만     BoothCanvasViewport 밖의 Shell·Feature 코드는 건드리지 않았다
3. 되돌릴 길 유지     VITE_R3F_CANVAS=false 로 SVG 렌더러로 즉시 복귀 가능
4. 원본 미배포       GLB 산출물은 저장소·이미지 어디에도 들어가지 않았다
5. 실패를 드러낸다    manifest 부재는 정상 경로, 그 밖의 실패는 로그 + 화면 표식
```

## 아직 아닌 것

```text
merge         세 브랜치 모두 MR 없음
adoption      D-05 는 PASS_CANDIDATE 이지 ADOPTED 가 아니다
전 타입 확장   라이선스 게이트 통과 전에는 하지 않는다
텍스처        지오메트리만 가져왔다
동시 상주      Persistent GameShell 이후
```
