# Booth Studio 실제 Unity 에셋 근사 렌더링 최종 설계안

> **STATUS: PROPOSAL / SPIKE PLAN**
> **IMPLEMENTATION: DEFERRED**
> **TARGET: R3F + GLB + FE Asset Pipeline**
> **TRIGGER: Booth Studio 실제 에셋 렌더링 고도화 착수 시**
>
> 반입일 2026-09-03. 본문은 원본 그대로이며 이 블록만 덧붙였다.
> 이 문서는 확정 결정이 아니다 — `00_context/implementation-decisions.md` 의 D-05(R3F 1순위 후보,
> 채택 아님)는 그대로이고, 이 계획은 그 spike 가 열릴 때의 실행 설계다.
>
> 착수 전제(순서대로 충족돼야 한다):
> ```text
> User Flow / UX 정본화 완료
> → !240 Presentation 재구성 완료
> → Booth Management / Booth Studio 진입 구조 안정
> → Booth Studio 기존 기능 회귀 안정
> → 실제 에셋 렌더 고도화 우선순위 도달
> → P0 DisplayBox Spike 착수
> ```
>
> 유지할 원칙: 기존 Booth Studio UX·Domain 계약 유지 / Renderer seam 만 교체 대상 /
> Unity 신규 개발 0 목표 / FBX = Spike 용, GLB = 제품 포맷 후보 / `assetCode` 계약 유지 /
> Backend 는 Unity GUID·GLB URL 을 알지 않는다 / 실제 Mesh 와 Domain Bounds 분리 /
> 고정 Orthographic Camera / Current Layout 우선 Load + 나머지 Lazy Load /
> Static Prefab 만 제한 지원 / Unity + R3F 동시 성능은 실제 적용 단계에서 반드시 검증.


> 목적: 현재 Booth Studio의 **React 기반 편집 UX와 좌표/회전/저장 계약은 유지**하면서,  
> 단순 SVG 박스 표현을 **실제 Unity 원본 에셋의 모델링에 근접한 3D 표현**으로 교체한다.
>
> 최종 방향: **Unity 신규 개발 0 + FE 에셋 파이프라인 + React Three Fiber(R3F) + GLB**
>
> 작성 기준: 2026-09-03

---

## 0. 최종 결론

현재 요구사항에서는 **Unity와 React를 실시간으로 연결하여 Unity Prefab을 직접 렌더링하는 구조까지 갈 필요가 없다.**

기존 Booth Studio의 다음 요소는 이미 충분히 사용할 수 있다.

- React UI
- Asset Library
- Inspector
- 선택 구조
- 드래그 이동
- 현재 회전 UX
- 15° 회전 Snap
- 0.25m 이동 Snap
- 월드 좌표 계약
- 부스 영역 이탈 판정
- 저장/게시 데이터 구조
- `BoothStudioShell → BoothCanvasViewport` 경계

따라서 최적안은 다음 한 문장으로 정리된다.

> **Unity는 기존 FBX / Texture / Prefab 등 원본 에셋만 제공하고 신규 작업은 하지 않는다. FE가 원본 에셋을 가져와 Web용 GLB 및 조합 메타데이터로 자동 가공하고, 현재 `TemporaryIsoRenderer`만 R3F 기반 실제 모델 렌더러로 교체한다.**

```text
[기존]

React Booth Studio
└─ BoothCanvasViewport
   └─ TemporaryIsoRenderer
      └─ SVG polygon / box


[개선]

React Booth Studio
└─ BoothCanvasViewport
   └─ R3FBoothRenderer
      ├─ Floor / Wall / Truss
      └─ Unity 원본 기반 GLB 실제 모델
```

사용자 입장에서 Booth Studio는 새 3D 툴로 바뀌는 것이 아니다.

```text
기존 UX

선택
→ 드래그
→ 회전
→ Snap
→ Inspector
→ 저장
```

를 그대로 유지하면서 **보이는 모델만 원본 모델링에 가깝게 바뀌는 작업**이다.

---

# 1. 현재 구현 상태

현재 Booth Studio 2.5D는 별도 3D 라이브러리를 사용하지 않는다.

```text
React
+
순수 SVG
+
직접 구현한 아이소메트릭 투영
```

렌더러는 `TemporaryIsoRenderer.tsx`이며 대략 다음 역할을 한다.

- 월드 `(x, y, z)` → SVG 화면 좌표 투영
- Floor / Wall / Truss / Object를 `<polygon>`으로 표현
- `x + z` 기반 깊이 정렬
- 면 방향별 단순 명도 적용
- SVG `viewBox` 기반 Zoom
- 포인터 → 바닥 월드 좌표 역투영
- 0.25m Snap
- Booth Boundary Clamp
- 월드 평면 기준 회전 계산
- 15° Snap
- Pointer Capture

즉 **조작과 좌표 구조 자체는 이미 검증되어 있다.**

현재 문제는 다음과 같다.

```text
DISPLAY_BOX
→ 직육면체

CHAIR
→ 직육면체

TABLE
→ 직육면체

COUNTER
→ 직육면체
```

`TemporaryIsoRenderer`는 `assetCode`, transform, bounds를 알아도 **실제 Mesh를 갖고 있지 않기 때문에 원본 모델 형상을 표현할 수 없다.**

따라서 문제의 핵심은:

> SVG 투영 정확도 부족이 아니라 **실제 3D Asset Representation 부재**

이다.

---

# 2. 목표 수준

이번 작업의 목표는 Unity 결과와 픽셀 단위로 동일한 렌더링이 아니다.

우선순위는 다음과 같다.

1. **모델 형상**
2. **원본 비율**
3. **크기 관계**
4. **Prefab의 정적 배치 구조**
5. Texture / 기본 Material
6. 간단한 조명 / 그림자

반대로 다음은 우선 목표가 아니다.

- Unity URP 조명의 완전 재현
- Unity 전용 Shader의 동일 실행
- Post Processing 완전 재현
- Unity Runtime Component 실행
- Animator / Particle / Script 동작 복제
- Unity와 실시간 Scene 동기화

즉 사용자가 기존 화면에서 보던:

```text
박스형 의자
박스형 카운터
박스형 쇼케이스
```

를:

```text
실제 Chair 형상
실제 Counter 형상
실제 DisplayBox 형상
```

으로 바꾸는 것이 핵심이다.

---

# 3. 실제 첨부 에셋 확인 결과

## 3.1 `DisplayBox01.FBX`

첨부된 `DisplayBox01.FBX`는 실제 FBX 바이너리 모델 파일이다.

파일에서 확인되는 주요 정보:

- Binary FBX
- FBX SDK/Plugin 계열로 생성
- 3ds Max 원본 이력 존재
- 파일 크기 약 52 KB

따라서 이 파일은 **FE가 직접 읽거나 변환할 수 있는 실제 3D 원본**이다.

초기 Spike에서는:

```text
DisplayBox01.FBX
       ↓
Three.js FBXLoader
       ↓
R3F Scene
```

로 바로 검증할 수 있다.

Three.js 공식 `FBXLoader`는 FBX 7.0 이상 ASCII 또는 Binary 6400 이상을 대상으로 한다.

### 지위

- **초기 검증:** 매우 적합
- **제품 런타임 최종 포맷:** GLB 권장

---

## 3.2 `Furniture.prefab`

첨부된 `Furniture.prefab`은 단일 3D 모델이 아니라 **Unity Prefab 조립 정보**다.

루트:

```text
Furniture
scale ≈ 1.30065
```

내부에는 별도의 Unity Prefab들이 Child로 연결되어 있다.

확인된 구성:

```text
Furniture
├─ Chair01_orange
├─ Chair01_white
├─ TableRound
└─ Chair01_blue
```

각 Child는 Unity GUID를 이용해 원본 Prefab을 참조한다.

예를 들어 파일 내부에는:

```text
m_SourcePrefab:
  guid: ...
```

형태의 외부 참조가 존재한다.

따라서:

```text
Furniture.prefab 파일 하나
```

만 React에 넘긴다고 의자와 테이블의 실제 Mesh가 생성되지는 않는다.

필요한 것은:

```text
Furniture.prefab
+
TableRound 원본 FBX/Prefab
+
Chair01 원본 FBX/Prefab
+
필요 Texture
```

이다.

### 중요한 의미

Unity 신규 작업을 0으로 만들려면 FE가 **Unity Prefab 전체 기능을 복제하는 것이 아니라**, Booth Asset에 필요한 정적 구조만 지원하면 된다.

초기 지원 범위:

- Child Prefab 참조
- Local Position
- Local Rotation
- Local Scale
- 단순 Parent/Child Group

초기 미지원 범위:

- MonoBehaviour 실행
- Runtime Script
- Animator
- Particle
- 복잡한 Variant Override
- Unity Runtime 전용 Component

현재 `Furniture.prefab`처럼 **정적인 가구 배치 조립체**에는 이 수준이면 충분하다.

---

# 4. 최적 기술 구조

## 4.1 최종 구조

```text
                    기존 Unity Project
                           │
                    기존 Asset 원본
              FBX / Texture / Static Prefab
                           │
                      [신규 Unity 작업 없음]
                           │
                           ▼
                    FE Asset Pipeline
             ┌─────────────┼─────────────┐
             │             │             │
         FBX → GLB    Prefab 분석    Metadata 생성
             │             │             │
             └─────────────┼─────────────┘
                           ▼
                   Asset Manifest
                           │
                           ▼
                      React / R3F
                           │
                           ▼
                  BoothCanvasViewport
                           │
                           ▼
                   R3FBoothRenderer
```

### 책임 분리

**Unity**

- 기존 Asset 보유
- FBX / Texture / Prefab 정본 유지

**신규 Unity 작업: 없음**

---

**Frontend**

- Unity 원본 Asset 확보
- FBX → GLB
- Static Prefab Transform 해석
- Web Asset Registry / Manifest
- R3F Renderer
- 모델 Load / Cache
- Position / Rotation 적용
- Material 대응
- Asset 최적화
- CI 자동화

---

**Backend**

기존 Layout 계약 유지:

```text
objectId
assetCode
position
rotation
size
properties
```

3D 파일 경로나 Unity GUID를 알 필요가 없다.

---

# 5. 왜 R3F + GLB인가

## 5.1 React 구조를 그대로 유지 가능

현재:

```text
BoothStudioShell
   ↓
BoothCanvasViewport
   ↓
TemporaryIsoRenderer
```

를:

```text
BoothStudioShell
   ↓
BoothCanvasViewport
   ↓
R3FBoothRenderer
```

로 바꿀 수 있다.

즉 Renderer 경계가 이미 확보되어 있으므로:

- Shell
- Toolbar
- Inspector
- Feature
- Save/Publish

등의 구조를 크게 수정하지 않아도 된다.

---

## 5.2 R3F는 React 안에서 Three.js를 사용

R3F의 장점:

- React Component 기반 Scene 구성
- Three.js Mesh / Camera / Material 사용
- React State와 Transform 연결 용이
- `Suspense` 기반 로딩 처리
- Loader 캐싱
- Preload 지원
- 실제 Depth / Occlusion 처리

따라서 기존 SVG처럼 3D Depth를 직접 계산하는 코드를 계속 확장할 필요가 없다.

---

## 5.3 GLB를 제품 포맷으로 사용

Web Runtime에서는 GLB를 최종 기본 포맷으로 권장한다.

GLB는 한 파일 안에 다음을 포함할 수 있다.

- Mesh
- Node Hierarchy
- Material
- Texture
- Transform
- Animation 정보

Three.js `GLTFLoader`는 glTF 2.0과 여러 Web 최적화 확장을 지원한다.

예:

- Draco Mesh Compression
- Meshopt Compression
- KTX2 / Basis Texture
- WebP Texture
- GPU Instancing 관련 확장

따라서 장기적으로:

```text
FBX
 ↓
GLB
 ↓
Mesh / Texture 최적화
 ↓
Web 배포
```

구조가 가장 관리하기 쉽다.

---

# 6. FBX 직접 로딩과 GLB의 역할 구분

두 방법을 경쟁안으로 볼 필요가 없다.

## FBX 직접 로딩

목적:

> **빠른 Feasibility Spike**

```text
DisplayBox01.FBX
→ FBXLoader
→ 현재 Booth 위치에 표시
```

검증 항목:

- 브라우저에서 정상 로딩
- Axis
- Scale
- Pivot
- 현재 Position 적용
- 현재 Rotation 적용
- 15° Snap
- 모델 외형 품질
- 렌더링 비용

---

## GLB

목적:

> **제품 운영용 Asset 포맷**

```text
FBX
→ FE Asset Build
→ GLB
→ R3F
```

따라서:

```text
FBX 직접 로딩 = P0 검증
GLB = 최종 운영
```

으로 구분한다.

---

# 7. 현재 회전/이동 구조 처리

이번 작업에서는 기존 조작을 최대한 유지한다.

## 7.1 이동

현재:

```text
Pointer
→ SVG 좌표
→ 바닥 역투영
→ World (x, z)
→ 0.25m Snap
→ Boundary Clamp
```

개념은 그대로 사용한다.

R3F에서도 최종 State:

```text
position = { x, y, z }
```

만 실제 Mesh Transform에 적용하면 된다.

---

## 7.2 회전

기존 회전 구조가 충분하므로 재설계하지 않는다.

현재:

```text
Pointer
→ 월드 평면 atan2
→ 15° Snap
→ rotation
```

계속 사용한다.

R3F에서는:

```text
React State rotation
        ↓
R3F Model rotation
```

로 연결한다.

### 하지 않는 것

- Blender식 Gizmo 강제 도입
- 자유 3축 회전 UI 재설계
- 새로운 TransformControls UX 강제

현재 UI가 필요한 수준을 충족하므로 **실제 Model Renderer 도입과 조작 UX 개편을 분리**한다.

---

# 8. 크기 / 실물 근사

실제 3D Mesh 기반이면 원본 크기 관계를 훨씬 정확하게 표현할 수 있다.

권장 방식은 단순 `scale = 1.2`보다는 **기준 Physical Size**를 두는 것이다.

예:

```text
DisplayBox01

Width  = 0.6m
Height = 1.0m
Depth  = 0.6m
```

사용자에게는 필요 시 실제 단위를 표시하고 내부적으로:

```text
scaleX = desiredWidth / baseWidth
scaleY = desiredHeight / baseHeight
scaleZ = desiredDepth / baseDepth
```

로 변환한다.

단, 이번 MVP에서 모든 Asset에 자유 크기 조절을 새로 구현할 필요는 없다.

---

# 9. Asset별 Resize 정책

실물 근사를 위해 모든 Mesh에 Free Scaling을 허용하는 것은 좋지 않다.

권장 정책:

| Asset 종류 | 기본 정책 |
|---|---|
| Chair | FIXED |
| Table | FIXED |
| Display Box | FIXED |
| Plant / 소품 | FIXED |
| Counter | FIXED 또는 UNIFORM |
| Graphic Panel | ASPECT_RATIO |
| Wall | WIDTH_HEIGHT |
| Floor | WIDTH_DEPTH |
| Truss | MODULE / LENGTH_LIMIT |

MVP 우선순위:

```text
실제 모델 형상
+
원본 크기
+
현재 회전
```

을 먼저 구현한다.

Resize 정책 고도화는 후속으로 분리할 수 있다.

---

# 10. Pivot 정규화

실제 FBX/GLB를 사용하면 가장 흔한 문제가 Pivot이다.

잘못된 Pivot:

```text
오브젝트가 제자리에서 회전하지 않고
큰 원을 그리며 회전
```

권장 기준:

### 바닥형 가구

```text
Pivot = Bottom Center
```

### 벽 부착물

```text
Pivot = Back Face Center
```

### 복합 Furniture Set

```text
Pivot = Group 기준 바닥 중심 또는 기존 Prefab Root
```

이 정규화는 Unity에서 할 필요 없이 **FE Asset Preprocessing 단계에서 처리 가능**하다.

---

# 11. Ground Alignment

모델마다 원점 위치가 다르면:

- 바닥에 파묻힘
- 공중에 뜸

문제가 발생한다.

권장 방식:

```text
Geometry BoundingBox 계산
       ↓
minY 확인
       ↓
Booth Floor Y와 정렬
```

즉 기본 배치 시 모델의 최하단이 Floor에 맞도록 보정한다.

---

# 12. Axis / Unit 정규화

FBX 원본마다 제작 툴과 Export 설정이 달라질 수 있다.

가능한 차이:

- Y-up / Z-up
- cm / m
- Root Rotation
- Scale Factor

따라서 FE Asset Build 단계에서 다음을 정규화한다.

```text
Axis        → Web Scene 표준
Unit        → meter
Ground      → Y = 0
Pivot       → Asset 정책
Orientation → +Z Booth Front 계약과 정합
```

이 과정을 런타임에 매번 수행하지 않고 **Asset Build 시점에 한 번 처리**한다.

---

# 13. Material / Texture 현실적인 범위

FE가 원본 Texture를 함께 확보하면 일반적인 PBR 재질은 상당 부분 재현 가능하다.

지원 목표:

- Base Color
- Normal
- Roughness
- Metallic
- Alpha
- Emissive 일부

GLB와 Three.js의 PBR Material을 사용한다.

### 완전히 동일하지 않을 수 있는 부분

- Unity URP 전용 Shader
- Shader Graph
- Custom Shader
- Unity Lighting
- Post Processing
- 특수 Transparency 설정

현재 요구사항은 **모델링 형상 근사**이므로 이 차이는 수용 가능하다.

우선순위:

```text
형상           매우 중요
비율           매우 중요
Texture        중요
기본 Material  중요
Shader 동일성  낮음
조명 동일성    낮음
```

---

# 14. Booth Bounds 계약

실제 Mesh를 도입한다고 현재 배치 판정까지 즉시 바꾸지 않는다.

기존:

- `OBJECT_LOCAL_BOUNDS`
- `worldAABB`
- `isAreaOutOfBounds`

를 우선 유지한다.

역할을 분리한다.

```text
GLB Mesh
→ 시각적 Representation

OBJECT_LOCAL_BOUNDS
→ 게임/편집 규칙 Representation
```

실제 Mesh Bounds와 계약 Bounds가 크게 다를 때만 별도 검증 후 계약을 조정한다.

장점:

- 기존 테스트 유지
- Backend 영향 최소
- 렌더러 교체와 Domain Rule 변경 분리

---

# 15. 중앙 Renderer는 R3F로 통일

다음 구조는 피한다.

```text
SVG Floor / Wall
+
R3F Furniture
```

이유:

- Depth 기준이 다름
- 벽 뒤 가림(Occlusion) 어려움
- Camera Sync 필요
- Zoom Sync 필요
- Picking 좌표계 이중화
- Layer 정렬 문제

따라서 최종 중앙 Booth Canvas에서는 다음을 하나의 R3F Scene으로 그리는 것을 권장한다.

```text
R3F Scene
├─ Floor
├─ Wall
├─ Truss
├─ Furniture
├─ Display
├─ Counter
└─ 기타 실제 Asset
```

주변 UI는 React DOM을 그대로 유지한다.

---

# 16. 카메라

자유 3D Editor를 만드는 것이 아니므로 Perspective + Orbit 기반 UX로 갈 필요가 없다.

권장:

> **고정 Orthographic Camera**

현재 SVG의 2.5D 아이소메트릭 시점을 최대한 재현한다.

장점:

- 기존 화면 인상 유지
- 기존 사용자 학습 비용 최소
- 거리 Perspective 왜곡 없음
- 2.5D Booth Layout 가독성 유지
- 실제 Mesh Depth/Occlusion은 얻을 수 있음

필요 시 Zoom만 Orthographic Camera scale/zoom으로 처리한다.

---

# 17. 선택 / 하이라이트

기존 선택 UX는 그대로 유지할 수 있다.

가능한 표현:

- Bounding Box
- Outline
- 약한 Emissive
- Rotation Guide

권장 초기 방식:

```text
Selected
→ Bounding Box + 기존 회전 가이드
```

복잡한 Post-processing Outline은 필요 시 후속으로 추가한다.

---

# 18. 조명 / 그림자

MVP에서는 Unity 조명을 재현하지 않는다.

최소 권장:

```text
Hemisphere / Ambient Light
+
Directional Light
+
간단한 Shadow
```

목표:

> 실제 모델의 형태와 면이 쉽게 읽히도록 하는 것

즉 조명은 사실감을 위한 고급 작업보다 **형상 인지 보조**에 집중한다.

---

# 19. Asset Code 계약

React가 Unity 파일 이름이나 GUID를 Domain ID로 사용하면 안 된다.

공통 식별자는 기존처럼 `assetCode`를 사용한다.

예:

```text
DISPLAY_BOX_01
CHAIR_01
TABLE_ROUND_01
FURNITURE_SET_01
COUNTER_GRAPHIC_01
TRUSS_02
```

Backend Layout:

```json
{
  "objectId": "object-001",
  "assetCode": "DISPLAY_BOX_01",
  "position": { "x": 1.25, "y": 0, "z": -0.75 },
  "rotation": { "x": 0, "y": 45, "z": 0 }
}
```

FE는 Registry를 통해:

```text
DISPLAY_BOX_01
     ↓
display-box-01.<hash>.glb
```

로 해결한다.

Unity GUID 변경이나 파일 경로 변경이 Backend 계약에 전파되지 않는다.

---

# 20. FE Asset Registry / Manifest

권장 Manifest 정보:

```json
{
  "version": "build-hash",
  "assets": {
    "DISPLAY_BOX_01": {
      "kind": "MODEL",
      "url": "/booth-assets/models/display-box-01.abcd1234.glb",
      "thumbnail": "/booth-assets/thumbs/display-box-01.webp",
      "baseSize": {
        "width": 0.6,
        "height": 1.0,
        "depth": 0.6
      },
      "rotation": {
        "axis": "Y",
        "snapDeg": 15
      },
      "resize": "FIXED"
    }
  }
}
```

조립 Asset은:

```json
{
  "FURNITURE_SET_01": {
    "kind": "GROUP",
    "children": [
      {
        "assetCode": "TABLE_ROUND_01",
        "position": [0, 0, 0],
        "rotation": [0, 0, 0],
        "scale": [1, 1, 1]
      },
      {
        "assetCode": "CHAIR_ORANGE_01",
        "position": [-0.35, 0, -0.6]
      }
    ]
  }
}
```

처럼 표현할 수 있다.

---

# 21. Prefab 처리 전략

## 원칙

Unity Prefab Parser를 범용 Unity Runtime 대체기로 만들지 않는다.

지원 목적:

> **Booth에서 사용하는 Static Prefab의 정적 배치 재현**

### 처리 흐름

```text
Furniture.prefab
     ↓
YAML 분석
     ↓
Child GUID 추출
     ↓
.meta GUID ↔ Source Asset 매핑
     ↓
Local Transform 추출
     ↓
Web Group Metadata
```

그다음 Runtime:

```text
FurnitureGroup
├─ Table GLB
├─ Chair GLB
├─ Chair GLB
└─ Chair GLB
```

를 생성한다.

### 지원하지 않는 요소를 만났을 때

자동으로 무시하지 말고 CI에서 경고/실패하도록 한다.

예:

```text
UNSUPPORTED_PREFAB_COMPONENT:
Animator found in Booth/Furniture.prefab
```

그러면 “지원 가능한 정적 Asset”과 “Unity Runtime 종속 Asset”을 명확히 구분할 수 있다.

---

# 22. Unity 신규 작업 범위

최종 목표에서는 Unity 팀의 신규 개발을 **0**으로 둔다.

## 요구하지 않는 작업

- `UnityAssetBridge`
- React ↔ Unity `SendMessage`
- Addressables 신규 설정
- BoothEditorCamera
- Unity Raycast Selector
- Unity Transform Editor
- Unity Inspector 연동
- Unity GLB Export Tool
- 별도 WebGL Editor Scene
- Unity Scene State Synchronization

## Unity에서 필요한 것

새로운 개발 작업이 아니라 **기존 Asset 접근 가능성**뿐이다.

FE가 필요한 항목:

```text
FBX
Texture
Static Prefab
.meta 또는 GUID 매핑 정보
```

---

# 23. 에셋 준비: 일회성 vs 실시간

## 일회성 / Asset 변경 시

```text
FBX 확보
→ GLB 변환
→ Axis / Unit 정규화
→ Pivot 확인
→ Ground Alignment
→ Material 처리
→ 압축
→ Manifest 생성
```

사용자가 Booth Studio를 열 때 수행하지 않는다.

---

## 사용자 실행 중 실시간

```text
GLB Loading
Scene Rendering
Selection
Drag
Rotation
Resize(허용 시)
Highlight
```

이다.

즉:

> **무거운 포맷 변환은 Build/CI 시점, 사용자는 이미 준비된 Web Asset을 받기만 한다.**

---

# 24. 사용자 입장에서의 최적 로드 시점

모든 Booth Asset을 로그인 시 받는 것은 피한다.

최적 방식은 **3단계 점진 로딩**이다.

---

## Stage A — 일반 서비스 이용

```text
로그인
월드
홈
기타 화면
```

Booth Studio용 GLB는 **로드하지 않는다.**

이유:

- Studio를 사용하지 않는 사용자도 존재
- 기존 Unity WebGL 자체가 무거울 수 있음
- 불필요한 초기 네트워크 사용 방지
- LCP / 초기 응답 영향 최소화

---

## Stage B — Booth Studio 진입 가능성이 높아졌을 때 Prefetch

예:

- Booth 관리 메뉴 열림
- Booth Studio 메뉴가 표시됨
- Booth Studio Route navigation 직전

이때 가볍게:

```text
R3F code chunk
+
asset-manifest.json
+
Asset Library thumbnail
```

을 미리 가져올 수 있다.

아직 전체 GLB는 받지 않는다.

---

## Stage C — Booth Studio 실제 진입

가장 먼저 Layout을 조회한다.

```text
Layout
 ↓
현재 사용 중 assetCode 추출
 ↓
필요한 GLB 목록 계산
 ↓
병렬 Load
```

예:

```text
현재 부스:
- DISPLAY_BOX_01
- TABLE_ROUND_01
- CHAIR_01
```

이라면 이 세 모델부터 받는다.

전체 Asset Library의 모든 3D 모델을 다운로드하지 않는다.

---

# 25. Asset Library 모델은 Lazy Load

아직 사용하지 않는 모델은 thumbnail만 표시한다.

```text
Asset Library

Chair        [thumbnail]
Display Box  [thumbnail]
Plant        [thumbnail]
Counter      [thumbnail]
```

사용자가 `Plant`를 처음 추가할 때:

```text
Plant 선택
    ↓
plant.glb 자동 요청
    ↓
로드 완료
    ↓
Canvas에 생성
```

한다.

사용자에게 `Import`, `Download`, `Unity Asset Load` 같은 버튼을 보여줄 필요가 없다.

---

# 26. Background Preload

현재 Layout 모델이 표시되어 편집 가능 상태가 된 후, 자주 사용하는 모델을 여유 시간에 미리 로드할 수 있다.

예:

```text
현재 부스 렌더 완료
        ↓
Idle / 여유 네트워크
        ↓
Chair / Counter / Table 등 preload
```

단:

> **전체 Asset Library 일괄 preload는 피한다.**

R3F의 `useLoader`는 URL을 cache key로 사용해 로드 결과를 기본 캐시할 수 있으며, preload 기능도 제공한다.

---

# 27. 캐시 정책

같은 GLB를 같은 세션에서 여러 번 사용하는 경우 다시 다운로드하지 않는다.

예:

```text
chair.glb 1회 Load
     ↓
Chair object 10개 배치
     ↓
동일 Geometry / Asset 재사용
```

방향을 권장한다.

주의:

- 복제 Object마다 Scene node transform은 분리
- Geometry / Material은 가능한 재사용
- Asset dispose를 잘못해서 공유 캐시를 깨지 않도록 주의

---

# 28. 사용자 UX 최종 흐름

```text
사용자 월드 플레이
        ↓
Booth 관리 진입
        ↓
Booth Studio 버튼 노출
        ↓
Manifest / UI Code Prefetch
        ↓
Booth Studio 클릭
        ↓
현재 Layout Load
        ↓
현재 사용 중 GLB 우선 Load
        ↓
Booth 즉시 편집
        ↓
Asset Library에서 새 모델 선택
        ↓
필요 GLB 자동 Lazy Load
        ↓
기존 방식대로 이동 / 회전 / 저장
```

사용자는:

- FBX
- GLB
- Unity Asset
- Asset Conversion

이라는 개념을 전혀 알 필요가 없다.

---

# 29. 에셋 반영 자동화 목표

최종 목표:

> **Unity 원본 Asset이 변경되면 FE 담당자가 수동으로 GLB를 다시 만드는 작업을 없앤다.**

권장:

```text
Unity 기존 Asset 변경
        ↓
Git push
        ↓
CI가 Asset 경로 변경 감지
        ↓
FE Asset Build 자동 실행
        ↓
GLB 재생성
        ↓
검증 / 최적화
        ↓
Manifest 자동 갱신
        ↓
FE 배포
```

---

# 30. GitLab CI 변경 감지

GitLab CI의 `rules:changes`를 사용하면 특정 파일이 변경된 Pipeline에서만 Asset Build를 실행할 수 있다.

개념 예시:

```yaml
booth-assets:
  script:
    - npm run assets:build

  rules:
    - changes:
        - "Unity/Assets/**/Booth/**/*.fbx"
        - "Unity/Assets/**/Booth/**/*.prefab"
        - "Unity/Assets/**/Booth/**/*.meta"
        - "Unity/Assets/**/Booth/**/*.{png,jpg,jpeg,tga}"
```

장점:

```text
일반 FE 코드 변경
→ 3D Asset 변환 Job 생략

Booth Asset 변경
→ Asset Job 실행
```

따라서 CI 비용과 시간을 줄일 수 있다.

주의:

- MR Pipeline과 Branch Pipeline의 비교 기준 차이를 확인
- 필요한 경우 `compare_to` 지정
- `.meta` 변경도 Asset 참조에 영향을 줄 수 있으므로 고려

---

# 31. FBX → GLB 자동 변환

Unity 신규 Export Tool을 만들지 않고 FE/CI에서 처리한다.

권장 후보:

> **Blender Headless + Python Script**

Blender는 UI 없이 `--background`로 실행 가능하다.

개념:

```text
blender --background --python convert_assets.py
```

Python Script:

```text
FBX Import
→ Axis/Scale 보정
→ Material 정리
→ GLB Export
```

Blender 공식 glTF exporter는 `.glb` 생성을 지원하며 Mesh, Material, Texture 등 일반적인 glTF 요소를 내보낼 수 있다.

### 장점

- Unity 실행 불필요
- CI 서버에서 자동화 가능
- 사람이 Blender UI를 열 필요 없음
- 변환 규칙 코드화 가능

---

# 32. GLB 최적화

Raw GLB를 그대로 배포하기 전에 Web 최적화를 적용할 수 있다.

후보:

> **glTF Transform**

예:

```text
raw.glb
    ↓
gltf-transform optimize
    ↓
optimized.glb
```

가능한 최적화:

- Geometry 최적화
- 중복 제거
- Mesh Compression
- Texture Compression
- WebP
- Draco 등

주의:

처음부터 최고 압축을 강제하지 않는다.

먼저 실제 모델을 띄워:

- 품질
- 용량
- 로드 시간
- FPS

을 측정한 뒤 압축 정책을 결정한다.

---

# 33. Content Hash 및 자동 캐시 갱신

Asset 파일명에 Content Hash를 넣는다.

예:

```text
display-box-01.a81c3f.glb
```

원본이 변경되면:

```text
display-box-01.f927dd.glb
```

로 URL이 바뀐다.

그러면 과거 모델이 브라우저/CDN에 캐시되어 있어도 새 Manifest가 새 URL을 가리키므로 자동으로 최신 버전을 받는다.

사용자에게:

```text
Ctrl + F5
Cache 삭제
```

를 요구하지 않아도 된다.

Vite도 Build Graph에 포함된 Static Asset에 hashed filename 전략을 사용한다. Booth Asset Pipeline 역시 동일한 개념을 적용한다.

---

# 34. Manifest 자동 생성

사람이 다음 Mapping을 수동으로 계속 작성하지 않는다.

```text
DISPLAY_BOX_01 → ...
CHAIR_01 → ...
```

Asset Build에서 자동 생성한다.

예:

```json
{
  "version": "20260903-abc123",
  "assets": {
    "DISPLAY_BOX_01": {
      "url": "/booth-assets/display-box-01.a81c3.glb",
      "bytes": 84231,
      "hash": "a81c3",
      "baseSize": [0.6, 1.0, 0.6]
    }
  }
}
```

수동으로 유지할 값과 자동 추출할 값을 분리한다.

### 자동 생성 가능

- URL
- Hash
- File Size
- Geometry Bounds
- Version

### 사람이 정책으로 관리할 가능성이 높은 것

- `assetCode`
- Category
- Resize Policy
- Rotation Snap
- Display Name
- Business Rule

---

# 35. 큰 바이너리 Asset 관리

FBX / TGA / 큰 PNG가 많아지면 일반 Git History가 빠르게 커질 수 있다.

필요 시 Git LFS 사용을 검토한다.

GitLab 공식 Git LFS는 큰 바이너리를 저장소 본문 대신 별도 Object Storage에 저장하고 Git에는 Pointer를 둔다.

검토 대상:

```text
*.fbx
*.tga
큰 *.png / *.psd
```

단:

> 현재 Unity Repository에서 이미 Asset 관리 정책이 안정적으로 운영 중이라면 이번 작업 때문에 즉시 LFS 전환할 필요는 없다.

---

# 36. 권장 FE Asset 디렉터리

예시:

```text
tools/
└─ booth-assets/
   ├─ convert.py
   ├─ prefab-parser.ts
   ├─ build-manifest.ts
   └─ validate.ts

asset-source/
├─ fbx/
├─ textures/
├─ prefabs/
└─ meta/

public/
└─ booth-assets/
   ├─ models/
   │  ├─ display-box-01.<hash>.glb
   │  ├─ chair-01.<hash>.glb
   │  └─ table-round.<hash>.glb
   ├─ thumbnails/
   └─ manifest.json
```

실제 Repository 정책에 맞게 Source Asset은 별도 경로 / LFS / CI Artifact / Object Storage로 분리할 수 있다.

---

# 37. `npm run assets:build`

개발자 관점 최종 UX는 다음 정도가 이상적이다.

```bash
npm run assets:build
```

내부:

```text
Source Asset 검색
        ↓
변경 Asset 판단
        ↓
FBX → GLB
        ↓
Static Prefab 분석
        ↓
Axis / Unit / Pivot 검증
        ↓
GLB Optimize
        ↓
Manifest 생성
        ↓
Validation
```

CI도 동일 명령을 실행한다.

즉 로컬과 CI의 Asset Build 경로를 하나로 맞춘다.

---

# 38. Asset Validation

자동화가 안정적으로 동작하려면 Convert보다 Validation이 중요하다.

CI에서 검사할 항목:

### 기본

- Source File 존재
- GLB 생성 성공
- 파일 크기 0 아님
- Manifest URL 존재
- 중복 `assetCode` 없음

### 3D

- Geometry 존재
- Bounding Box 유효
- NaN Transform 없음
- 극단적 Scale 없음
- Ground Offset 허용 범위
- Texture 누락 검사

### Prefab

- Child GUID resolve 가능
- 지원되지 않는 Component 감지
- Recursive Prefab cycle 방지
- Source Asset 누락 검출

---

# 39. 로딩 실패 UX

런타임 GLB Load가 실패해도 Booth Studio 전체가 죽으면 안 된다.

권장:

```text
특정 Asset Load 실패
        ↓
해당 Object만 Placeholder
        ↓
Toast / Error 상태
        ↓
나머지 Booth 편집 가능
```

Placeholder는 기존 SVG Box 또는 간단한 R3F Box를 재사용할 수 있다.

즉 `TemporaryIsoRenderer`를 완전히 제거한 뒤에도:

```text
MissingModelFallback
```

은 유지할 가치가 있다.

---

# 40. 로딩 우선순위

권장 순서:

### Priority 0

- Booth Studio JS/R3F chunk
- Layout
- Manifest

### Priority 1

현재 Layout에 실제로 배치되어 있고 화면에 보이는 GLB

### Priority 2

현재 Layout의 나머지 GLB

### Priority 3

자주 사용하는 Asset

### Priority 4

사용자가 Asset Library에서 선택한 기타 Asset

불필요한 전체 사전 다운로드를 피한다.

---

# 41. 성능 검증 항목

R3F 전환 후 반드시 실측한다.

### Network

- Studio 진입 시 다운로드 용량
- First Booth Render까지 시간
- 개별 Asset Lazy Load 시간

### Rendering

- FPS
- Draw Calls
- Triangles
- Texture Memory
- GLB Parse Time

### Device

최소:

- 개발 PC
- 일반 노트북
- 모바일/태블릿 브라우저 후보

특히 SSAFY FESTA는 Unity WebGL이 후단에 존재할 수 있으므로:

> **Unity + R3F 동시 실행 시 GPU / Memory**

를 반드시 측정한다.

---

# 42. 성능 최적화 우선순위

문제가 있을 때 다음 순서로 대응한다.

1. 필요 Asset만 Lazy Load
2. Texture Size 축소
3. 동일 Mesh/Material 재사용
4. 불필요 Shadow 감소
5. Geometry 최적화
6. Meshopt / Draco 검토
7. KTX2 / WebP Texture
8. LOD
9. 저사양 Rendering 옵션

처음부터 모든 최적화를 넣지 않는다.

---

# 43. TOP 3 후보 최종 비교

## 1위 — **R3F + FE 관리 GLB**

```text
Unity 기존 Asset
→ FE 변환
→ R3F
```

### 장점

- Unity 신규 작업 0
- 기존 React Booth Studio 최대 재사용
- 현재 회전 UX 유지
- 실제 모델 형상 사용 가능
- FE 단독 반복 개발 가능
- 런타임 Unity Bridge 불필요
- Web 전용 최적화 가능
- 점진 로딩 쉬움

### 단점

- Asset Conversion Pipeline 필요
- Prefab 정적 조합 처리 필요
- Unity 전용 Shader는 동일 재현 어려움

### 판정

**현재 요구 기준 최적안**

---

## 2위 — Unity 실제 Prefab 직접 렌더링

```text
React UI
+
Unity Editor Camera / Prefab
```

### 장점

- Unity와 모델 일치도 최고
- Prefab / Material / Shader 그대로 사용 가능

### 단점

- Unity 신규 개발 필요
- React ↔ Unity Bridge 필요
- Selection / Transform 동기화 필요
- Booth Editor Camera 필요
- FE 독립성 감소
- 기존 요구에 비해 구현이 무거움

### 판정

> 향후 “Studio와 Unity 인게임 렌더를 사실상 동일하게 해야 함”이 필수 조건이 될 때 재검토

현재는 과도하다.

---

## 3위 — FBX 직접 R3F 로딩

```text
FBX
→ FBXLoader
→ R3F
```

### 장점

- 가장 빠른 검증
- Unity 작업 0
- 별도 변환 없이 실제 Mesh 확인 가능

### 단점

- Web 제품 Asset 관리에는 GLB보다 불리
- Prefab 조립 처리 불가
- 원본별 Axis/Material 차이 관리 필요

### 판정

> **P0 Spike용**

---

# 44. 제외 / 비추천안

## SVG 고도화

실제 Mesh를 SVG Triangle로 투영할 수는 있지만:

- Polygon 수 폭증
- Texture mapping 직접 구현
- Depth sorting 복잡
- Lighting 직접 구현

결국 Three.js보다 부족한 3D Renderer를 자체 개발하게 된다.

**비추천.**

---

## 사전 렌더 Sprite

Unity/Blender에서 8/16방향 Sprite를 생성해 2.5D로 표시 가능.

장점:

- 가벼움
- 기존 SVG와 결합 쉬움

단점:

- 자유 회전 품질 제한
- Occlusion 제한
- Material/Color 변경 제약
- 원본 3D 모델 활용성이 떨어짐

Fallback / 저사양 모드 후보이지 주력안은 아니다.

---

## Unity WebGL 인스턴스 추가

기존 Unity 월드와 별도로 Booth Editor Unity Runtime을 하나 더 띄우는 방식.

- 메모리 중복
- 초기 Load 증가
- Runtime 관리 복잡

**비추천.**

---

# 45. 단계별 실행 계획

## P0 — 가장 작은 Feasibility Spike

대상:

```text
DisplayBox01.FBX
```

목표:

```text
현재 SVG Display Box
        ↓
동일 위치에서 실제 FBX 표시
```

검증:

- R3F 설치/격리 가능
- Orthographic Camera
- Position
- Rotation
- 15° Snap
- Scale
- Model Visibility
- 성능

### 성공 조건

> 기존 UI와 조작을 바꾸지 않고 Display Box 외형만 실제 모델로 교체 가능

---

## P1 — 제품 Asset Format

```text
DisplayBox01.FBX
→ Blender Headless
→ DisplayBox01.glb
→ GLTFLoader
```

FBX 직접 로딩과 결과 비교.

검증:

- Shape
- Material
- Axis
- Size
- Load Size
- Parse Time

---

## P2 — Asset Registry / Manifest

```text
assetCode
→ GLB URL
→ Metadata
```

구축.

기존 Layout `assetCode`와 연결한다.

---

## P3 — Furniture Prefab 정적 조합

필요 원본 Asset 확보:

- Table
- Chair
- Texture
- `.meta`

`Furniture.prefab`에서:

- GUID
- position
- rotation
- scale

을 추출해 R3F Group으로 재현한다.

---

## P4 — 중앙 Renderer 통합

현재:

```text
TemporaryIsoRenderer
```

에서:

```text
R3FBoothRenderer
```

로 전환.

- Floor
- Wall
- Truss
- Objects

를 하나의 3D Scene에서 렌더링한다.

---

## P5 — Load 정책

구현:

- Manifest Prefetch
- Current Layout 우선 Load
- Lazy Load
- Cache
- Background Preload
- Fallback

---

## P6 — Asset Build 자동화

```text
npm run assets:build
```

구현.

- FBX → GLB
- Validation
- Optimize
- Manifest
- Hash

---

## P7 — CI 자동화

GitLab:

```text
rules:changes
```

기반으로 Asset 변경 시에만 변환.

---

## P8 — 성능 / 회귀

검증:

- 기존 Layout 저장 계약
- Drag
- Rotation
- Snap
- Boundary
- Undo/Redo
- Published 결과
- Unity + R3F 동시 성능

---

# 46. MVP 범위

현재 요구에 맞는 MVP는 다음 정도로 제한하는 것이 좋다.

## 반드시 포함

- 실제 GLB 모델
- 고정 Orthographic View
- 기존 Position
- 기존 Rotation
- 15° Snap
- 선택
- 기존 Layout 저장
- Asset Lazy Load
- Model Cache
- Missing Asset Fallback

## 있으면 좋음

- 간단 Shadow
- Base Texture
- Manifest 자동 생성
- Content Hash

## 후속 가능

- 자유 Resize 고도화
- OBB Collision
- 고급 Wall Snap
- Modular Truss
- PBR 세부 조정
- KTX2
- LOD
- 저사양 모드

---

# 47. 성공 기준

기술 Spike와 최종 적용의 성공 조건을 분리한다.

## Spike PASS

1. `DisplayBox01.FBX` 또는 변환 GLB가 R3F에서 정상 표시된다.
2. 현재 Booth 월드 좌표의 원하는 위치에 정확히 놓인다.
3. 현재 회전값을 그대로 적용할 수 있다.
4. 15° Snap UX가 깨지지 않는다.
5. 기존 React Shell / Inspector 계약 변경이 최소다.
6. 기존 SVG 박스보다 원본 모델링과 명확히 유사하다.

---

## MVP PASS

1. 주요 Booth Asset이 실제 모델 형상으로 표시된다.
2. 기존 이동/회전/저장 구조가 유지된다.
3. 사용자에게 Asset Import 작업이 노출되지 않는다.
4. 현재 Layout Asset을 우선 자동 로드한다.
5. 나머지는 Lazy Load한다.
6. 동일 모델은 Cache/재사용한다.
7. Asset 하나 실패해도 전체 Editor가 중단되지 않는다.
8. Unity 신규 개발이 없다.
9. Unity 원본 Asset 변경을 FE Pipeline에서 재생성할 수 있다.

---

## 자동화 PASS

1. Unity Asset 변경을 CI가 감지한다.
2. FBX가 GLB로 자동 변환된다.
3. Static Prefab 변환 가능 범위는 자동 분석된다.
4. 지원하지 않는 Prefab 구조는 CI에서 명시적으로 실패/경고한다.
5. Manifest가 자동 갱신된다.
6. GLB URL에 Hash가 적용된다.
7. 새 배포 후 사용자가 Cache를 수동 삭제하지 않아도 최신 모델을 받는다.

---

# 48. 핵심 리스크와 대응

| 리스크 | 대응 |
|---|---|
| Unity FBX Axis/Unit 불일치 | Asset Build에서 정규화 |
| Pivot 이상 | Bottom Center 등 정책화 |
| Material 차이 | Web PBR로 근사, 형상 우선 |
| Prefab 참조 누락 | `.meta` GUID Resolve Validation |
| 복잡한 Prefab | Static Prefab 지원 범위 명시 |
| GLB 대용량 | Lazy Load + Optimize |
| Unity와 R3F 동시 GPU 부담 | 실제 기기 실측 |
| 캐시 Stale | Content Hash |
| Asset Load 실패 | Placeholder + 부분 실패 격리 |
| 기존 Bounds 불일치 | 기존 계약 유지 후 별도 검증 |

---

# 49. 의사결정 기준

향후 아래 조건이 새로 생기면 Unity 직접 렌더링 방식을 다시 검토한다.

### Unity 방식 승격 조건

- Studio와 인게임 Renderer 결과가 반드시 동일해야 함
- Unity Custom Shader가 핵심 기능임
- Runtime Script / Animator가 Studio Preview에 필요
- Prefab 구조가 너무 복잡해 FE Static Parser로 감당 불가
- R3F + Unity 동시 실행 성능이 허용 범위를 벗어남

그 전까지는:

> **R3F + FE Asset Pipeline**

을 유지한다.

---

# 50. 최종 사용자 관점

사용자가 느끼는 변화는 매우 단순해야 한다.

### 현재

```text
Booth Studio 진입
→ 네모난 임시 모델
→ 이동 / 회전
```

### 개선 후

```text
Booth Studio 진입
→ 현재 부스의 실제 모델 자동 로딩
→ 원본과 유사한 의자 / 테이블 / 쇼케이스 표시
→ 기존 방식 그대로 이동 / 회전
→ 새 Asset 클릭 시 자동 로드
```

사용자는:

- Unity
- FBX
- GLB
- Asset Pipeline
- Conversion

을 신경 쓰지 않는다.

---

# 51. 최종 개발 관점

```text
Unity Team
────────────────────────
기존 Asset 유지
신규 작업 0


Frontend
────────────────────────
Source Asset 확보
        ↓
FBX → GLB 자동화
        ↓
Static Prefab → Group Metadata
        ↓
Manifest
        ↓
R3F Renderer
        ↓
기존 Booth Transform State 재사용
        ↓
Lazy Load / Cache
        ↓
CI 자동 반영


Backend
────────────────────────
기존 Layout 계약 유지
```

---

# 52. 최종 최적안 한 줄

> **기존 React Booth Studio의 이동·회전·Snap·Inspector·저장 구조는 유지하고, Unity는 기존 원본 에셋만 제공한다. FE가 FBX/Texture/Static Prefab을 GLB 및 조합 메타데이터로 자동 변환하고, `TemporaryIsoRenderer`를 고정 아이소메트릭 R3F Renderer로 교체한다. 사용자에게는 현재 Layout 모델 우선 자동 로드 + 나머지 Lazy Load를 제공하며, 원본 변경은 GitLab CI에서 자동 감지·재변환·Manifest 갱신·Content Hash 배포까지 처리한다.**

---

# 53. 권장 착수 순서 요약

```text
1. DisplayBox01.FBX를 현재 Box 위치에 R3F로 표시
        ↓
2. 기존 회전/이동값 그대로 연결
        ↓
3. FBX → GLB 자동 변환
        ↓
4. Asset Registry / Manifest
        ↓
5. Furniture.prefab Static Group 재현
        ↓
6. R3FBoothRenderer 전체 교체
        ↓
7. Current Layout 우선 Load + Lazy Load
        ↓
8. npm run assets:build
        ↓
9. GitLab rules:changes 자동화
        ↓
10. Unity + R3F 동시 성능 실측
```

---

# 54. 웹 조사 근거

아래 공식 문서를 기준으로 기술 가능성과 자동화 방향을 검토했다.

1. **Three.js — FBXLoader**
   - FBX 로더 및 지원 버전 조건
   - https://threejs.org/docs/pages/FBXLoader.html

2. **Three.js — GLTFLoader**
   - glTF 2.0 로딩
   - Draco / Meshopt / KTX2 / WebP 등 확장 지원
   - https://threejs.org/docs/pages/GLTFLoader.html

3. **React Three Fiber — useLoader**
   - Suspense 기반 로딩
   - URL 기반 기본 캐시
   - Preload
   - https://github.com/pmndrs/react-three-fiber/blob/master/docs/API/hooks.mdx

4. **Blender Manual — glTF 2.0**
   - GLB Export
   - Mesh / Material / Texture 지원
   - https://docs.blender.org/manual/en/latest/addons/scene_gltf2.html

5. **Blender Manual — Command Line Arguments**
   - `--background` Headless 실행
   - https://docs.blender.org/manual/en/4.0/advanced/command_line/arguments.html

6. **GitLab CI/CD YAML — `rules:changes`**
   - 특정 경로 변경 시 Job 실행
   - `compare_to` 지원
   - https://docs.gitlab.com/ci/yaml/

7. **glTF Transform**
   - CLI 기반 GLB 검사/최적화/압축
   - https://gltf-transform.dev/

8. **Vite — Static Asset Handling**
   - Build Asset Graph
   - Hashed File Name
   - https://vite.dev/guide/assets.html

9. **GitLab — Git LFS**
   - 큰 Binary Asset을 Pointer + 외부 Object Storage 방식으로 관리
   - https://docs.gitlab.com/topics/git/lfs/

---

## 문서 지위

이 문서는 현재 Booth Studio 요구인:

> **“기존 회전/조작 구조는 나쁘지 않으며, 모델만 실제 Unity 원본 모델링에 근접하면 된다.”**

를 기준으로 한 **구현 최적안 및 자동화 설계 정리본**이다.

추후 요구가 **Unity 인게임과 Studio 렌더의 완전 동일성**으로 변경될 경우, `React UI + Unity BoothEditorCamera` 방식은 별도 대안으로 재평가한다.
