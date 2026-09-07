# Runtime Asset Compiler v1 — 구현 Spike 계획

> **STATUS: PLAN** — 아직 착수하지 않았다.
> 설계 정본: [`04_prototypes/booth-studio-runtime-asset-compiler.md`](../../04_prototypes/booth-studio-runtime-asset-compiler.md) (PROPOSAL)
> 현황 정본: [`README.md`](./README.md)
>
> 작성: 2026-09-07 · Jira `S15P21A604-479`

---

## 1. 기준 브랜치

```text
TARGET BASE   feat/S15P21A604-476-prefab-assembly
```

**이 문서는 향후 구현의 기준을 적을 뿐이다.** 현재 docs 브랜치를 `-476` 과 합치지 않는다.

착수 시점에 `-473` 의 `resolveAssetUrl` 정리(§6)가 먼저 들어가면 그 갱신본 위의 `-476` 이 기준이 된다.

---

## 2. 대표 입력 3종

| 입력 | 왜 이것인가 |
|---|---|
| `SURVEY_KIOSK` | Nested Prefab case — `-476` 이 이미 조립까지 확인한 유일한 실물 |
| `PlasticWhite` | common material case — 가장 널리 쓰이는 재질(`Plastic_m.png` ~3.4 MB) |
| `Chair02b` | heavy texture + normal map worst case (~10.9 MB 세트) |

전 타입 변환을 하지 않는다. 대표로 파이프라인을 닫고 확장은 License Gate 뒤다.

---

## 3. Spike 범위

```text
Prefab resolve
  → Intermediate
  → Runtime remesh / simplify
  → Material / Texture bake
  → metadata strip
  → compressed Runtime Asset
  → thumbnail
  → manifest
  → R3F render
  → source / runtime visual comparison
```

앞의 두 단계(`Prefab resolve`·`Intermediate`)는 `-476`·`-473` 에서 동작이 확인된 부분이다. **새로 만드는 것은 그 뒤**다.

---

## 4. PASS 조건 — 23항

D-05 canonical 15 Gate 와 **다른 Gate 다.** 섞지 않는다.

```text
D-05 canonical 15   R3F 를 제품 렌더러로 채택할 수 있는가
Compiler v1 23      Runtime Asset 파이프라인이 성립하는가
```

| # | 조건 |
|---|---|
| 1 | Nested Prefab 입력 가능 |
| 2 | Prefab transform semantics 보존 (`m_Modifications` = override) |
| 3 | 기존 axis / unit / pivot 정규화 유지 |
| 4 | 원본과 topology 가 다른 runtime mesh 생성 |
| 5 | 외형 근사 유지 |
| 6 | 필요 시 geometry 감소 |
| 7 | common material 실제 반영 (`PlasticWhite`) |
| 8 | worst-case normal texture 반영 (`Chair02b`) |
| 9 | 원본 texture 대비 runtime bytes 유의미하게 감소 |
| 10 | original / vendor metadata 제거 |
| 11 | thumbnail 자동 생성 |
| 12 | R3F 에서 자유 Y rotation 정상 |
| 13 | selection / move / bounds / Inspector 회귀 없음 |
| 14 | `OBJECT_LOCAL_BOUNDS` 불변 |
| 15 | raw source / intermediate 의 production 배포 0 |
| 16 | main bundle 오염 없음 |
| 17 | Studio lazy 경계 유지 |
| 18 | `npm run build` green |
| 19 | vitest 전체 green |
| 20 | tsc green |
| 21 | oxlint 신규 경고 0 |
| 22 | before / after geometry · texture · runtime 수치 보고 |
| 23 | visual parity 결과 기록 |

4·5·9 는 서로 당긴다 — 그 균형이 이 Spike 가 실제로 재는 것이다.

---

## 5. 착수 전 정해야 할 것

```text
도구      Blender headless / gltfpack / KTX2 중 무엇을 쓸지
          → 01_tooling/booth-runtime-asset-toolchain.md (PROPOSED)

경로      .generated/intermediate/ · .generated/runtime/ 의 실제 위치
          → 현재 repo 구조 조사 후

라이선스   source-packs.lock.json 의 초기 상태
          → ExpoKit · ithappy 등 실제 취득 경로 확인 필요 (사람 판단)
```

세 번째가 실질적 선행 조건이다. **`BLOCK` 인 패키지를 compile 하면 안 되므로, lock 파일이 비어 있으면 Compiler 는 아무것도 만들지 못해야 한다.**

---

## 6. 선행 코드 정리 — `resolveAssetUrl`

Compiler 작업 전에 처리한다.

```text
현재
  shared/assets/resolveAssetUrl.ts      canonical asset URL seam (S15P21A604-427)
  boothAssetManifest.ts 의 assetUrl()   자체 구현, import.meta.env.BASE_URL 사용

결과는 같지만 규칙이 둘이다. 배포 base 가 CDN 으로 바뀌면 갈라진다.

처리
  -473  boothAssetManifest 의 assetUrl 제거 → shared resolveAssetUrl 사용 → 회귀 테스트 → push
  -476  갱신된 -473 위로 재정렬 → 검증 → push
```

강제 push 는 `--force-with-lease` 외에 쓰지 않는다.

---

## 7. 이 Spike 가 끝나도 남는 것

```text
전 타입 확장          License Gate + Compiler v1 둘 다 통과 후
Runtime Pack / 암호화  다음 단계
CI 자동화             §47 자동화 PASS 7항
Unity + R3F 동시 상주  Persistent GameShell 이후
D-05 채택 결정        gate-matrix.md 의 경로대로
```
