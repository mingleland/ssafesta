# Runtime Asset Visual Parity — 기록 서식

> **STATUS: REVIEW TEMPLATE / PROPOSED**
>
> Source Unity Asset 과 SSAFESTA Runtime Asset 을 같은 환경에서 비교한 결과를 여기 남긴다.
> 설계: [`04_prototypes/booth-studio-runtime-asset-compiler.md`](../04_prototypes/booth-studio-runtime-asset-compiler.md) §9
>
> 작성: 2026-09-07 · Jira `S15P21A604-479`

---

## 0. 지금 상태 — 비교 결과가 아직 없다

Runtime Asset Compiler 가 구현되지 않았으므로 **Source vs Runtime 비교는 존재하지 않는다.** 이 문서는 서식과 절차, 그리고 현재까지 확보된 evidence 만 담는다.

없는 비교를 지어 쓰지 않는다.

### 현재까지 확보된 것 (Compiler 이전)

`-473`·`-476` 이 만든 것은 **Intermediate GLB** 다. 원본 topology 를 거의 그대로 갖고 있어 "근사" 를 논할 단계가 아니다. 다만 치수는 계약과 대조됐다.

| assetCode | 원본 | Intermediate GLB | tri | 실측 크기 (m) | 계약 AABB | 축별 오차 |
|---|---|---|---|---|---|---|
| `DECORATION_DEFAULT` | DisplayBox01.FBX 52 KB | 26.1 KB | 240 | 0.6 × 1.6085 × 0.6 | ±0.3 × 1.61 × ±0.3 | < 1.5 mm |
| `FURNITURE_CHAIR01` | Chair01.FBX 55.9 KB | 118.2 KB | 1212 | 0.3774 × 0.45 × 0.3774 | (타입 기본과 다른 자산) | — |
| `SURVEY_KIOSK_DEFAULT` | SurveyKiosk.prefab (조립) | 35.8 KB | 286 | 0.62 × 0.9255 × 0.3195 | ±0.31 × 0.93 × ±0.16 | X 0.000 · Y 0.0045 · Z 0.0005 |

텍스처는 아직 반입하지 않았다 — 재질은 런타임이 계약 색으로 채운다.

---

## 1. 비교 조건

두 대상을 **같은 조건**에서 렌더한다. 조건이 다르면 비교가 아니라 인상이다.

```text
카메라        canonical thumbnail camera (고정 orthographic, 동일 거리)
조명          canonical lighting
배경          단색 (그림자 판정을 위해 바닥면 유지)
해상도        동일
후처리        없음
```

## 2. 각도 sample

```text
0 · 45 · 90 · 135 · 180 · 225 · 270 · 315
```

**런타임 회전 제한이 아니다.** 실제 `rotationY` 는 `[0,360)` 자유값이며 이 8방향은 QA 표본일 뿐이다.

---

## 3. 기록 서식

에셋 하나마다 아래 블록을 채운다.

```text
assetCode
source package
runtime compiler version

source triangle count
runtime triangle count

source texture size
runtime texture size

source render          (8각도)
runtime render         (8각도)

silhouette result
material result
thumbnail result

known differences
PASS / PARTIAL / FAIL
```

### 판정 어휘

| 값 | 뜻 |
|---|---|
| `PASS` | 8각도 전부에서 실루엣·주요 형상·재질 인상이 원본과 구분하기 어렵다 |
| `PARTIAL` | 일부 각도 또는 일부 항목에서 눈에 띄는 차이가 있다. `known differences` 에 적는다 |
| `FAIL` | 형상이 달라 보이거나 재질 인상이 무너졌다 |

`known differences` 를 비워 둔 `PASS` 는 쓰지 않는다 — 차이가 없다면 "없음" 이라고 적는다.

---

## 4. 비교 항목

```text
silhouette              윤곽선이 같은가
width / height / depth impression   비율이 같아 보이는가
major shape             주요 형상 요소가 남아 있는가
color                   기본 색이 같은가
material impression     무광/유광/금속 인상이 유지되는가
normal expression       표면 요철 표현이 남아 있는가
shadow footprint        바닥 그림자 형태가 같은가
thumbnail quality       작은 크기에서도 무엇인지 알아볼 수 있는가
```

마지막 항목은 팔레트 썸네일이 같은 runtime asset 에서 파생되기 때문에 함께 본다.

---

## 5. 정량 metric — 이번에 확정하지 않는다

이미지 기반 metric(SSIM · 실루엣 IoU 등)을 나중에 붙일 수 있다. 다만 **실측 전에 임계값을 정하면 근거 없는 기준**이 되므로, 첫 비교 결과가 나온 뒤에 후보를 검토한다.

---

## 6. 기록 (비어 있음)

Compiler v1 Spike 착수 후 채운다.

```text
(아직 없음)
```
