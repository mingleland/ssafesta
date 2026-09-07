# Booth Runtime Asset Toolchain

> **STATUS: PROPOSED**
>
> 어떤 build toolchain 으로 Runtime Asset 을 만들 것인가.
> 설계: [`04_prototypes/booth-studio-runtime-asset-compiler.md`](../04_prototypes/booth-studio-runtime-asset-compiler.md) (PROPOSAL)
> 계획: [`05_technical-spikes/booth-studio-2_5d/runtime-asset-compiler-v1-plan.md`](../05_technical-spikes/booth-studio-2_5d/runtime-asset-compiler-v1-plan.md)
>
> 작성: 2026-09-07 · Jira `S15P21A604-479`

---

## 1. 지금 실제로 쓰고 있는 것

이것만 검증됐다. 나머지는 후보다.

| 도구 | 용도 | 검증 |
|---|---|---|
| `three` FBXLoader | Unity FBX 파싱 | `-473` — DisplayBox01·Chair01 파싱, 치수 실측 |
| `three` GLTFExporter | GLB 출력 | `-473` — 52 KB FBX → 26.1 KB GLB (Node `FileReader` shim 1개 필요) |
| 자체 Prefab Resolver | Unity prefab 계층 재현 | `-476` — `tools/assets/unity-prefab.mjs`, 중첩 prefab + `m_Modifications` override |

**신규 의존성 0개로 여기까지 왔다.** three 가 이미 저장소에 있고 로더·익스포터를 같이 갖고 있어서다.

```text
Blender      미설치
FBX2glTF     미설치
assimp       미설치
gltfpack     미설치
KTX2 도구    미설치
```

이 목록은 사실이다. **아직 없는 도구를 "쓰고 있다" 고 쓰지 않는다.**

---

## 2. 다음 후보

| 도구 | 무엇을 맡길 수 있나 | 왜 필요한가 |
|---|---|---|
| **Blender headless** | remesh · UV regeneration · material bake · mesh processing | three 만으로는 remesh·bake 를 못 한다. Compiler 파이프라인 2·3·5·6 단계가 여기 걸린다 |
| **gltfpack / meshoptimizer** | merge · quantize · simplify · meshopt compression | 11·12 단계. GLB 를 실제로 작게 만드는 부분 |
| **KTX2 / Basis** | texture compression | 13 단계. 원본 3.4 MB PNG 를 런타임 크기로 내리는 유일한 현실적 수단 |

셋 다 **아직 채택 결정이 아니다.** Compiler v1 Spike 에서 실제로 필요한 만큼만 도입한다.

---

## 3. 설치 방식 — 개발자 로컬 수동 설치는 안 된다

```text
개발자 로컬마다 수동 설치          ❌
Pinned asset-compiler container   ✅
```

이유:

```text
1. 버전이 갈리면 산출물이 갈린다 — 같은 입력에 다른 GLB 가 나오면 비교가 무의미해진다
2. Blender 는 무겁다. FE 개발자 전원이 설치할 이유가 없다
3. CI 로 옮길 때 로컬 설치 절차가 그대로 걸림돌이 된다
```

컨테이너 안에 도구를 고정 버전으로 넣고, 저장소에는 **호출 인터페이스만** 둔다. 현재 `tools/assets/*.mjs` 가 그 자리다.

```text
tools/assets/
├─ booth-assets.config.mjs      반입 대상 선언
├─ build-booth-assets.mjs       현재: FBX → GLB (three 만 사용)
└─ unity-prefab.mjs             Unity prefab 계층 해석
```

Compiler 단계가 들어오면 이 아래에 컨테이너 호출 계층이 붙는다. **저장소가 도구를 품지 않는다.**

---

## 4. 결정이 필요한 것

```text
컨테이너 이미지 정의 위치     festa-frontend 안인가, 별도인가
버전 고정 방식               이미지 태그 · lock 파일
로컬 실행 편의               도구 없이 돌리면 무엇이 되고 무엇이 안 되는가
CI 연결                      러너가 없다 — 지금은 사람이 돌리는 것이 전제
```

마지막 항목이 현실적 제약이다. `.gitlab-ci.yml` 은 러너가 없어 파이프라인 생성이 정지돼 있다. 그래서 §47 자동화 PASS 7항은 지금 열 수 없고, **수동 실행 + 산출물 미커밋**이 당분간의 운영 형태다.

---

## 5. 이 문서가 하지 않는 것

```text
도구 채택 확정        Compiler v1 Spike 결과로 정한다
설치 스크립트 작성    도구가 정해진 뒤
컨테이너 이미지 작성   위와 같음
CI 파이프라인 설계    러너 확보 후
```
