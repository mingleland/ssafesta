# Booth 에셋 공급 사슬 — 전체 구조 실측

> 2026-09-08 · 조사자 이정헌(FE) · 정본 `origin/develop` **`c9960d41`**
> 근거는 전부 저장소 실측이다. 코드·커밋·registry 를 문서 주장보다 우선했다.
> **조사만 했다** — 이 회차에 코드·registry·lock 은 손대지 않았다.

## 0. 왜 이 문서가 있나

`-480` 시각 acceptance 를 마친 뒤 *"실 에셋을 도입한다면?"* 이라는 질문이 나왔고, 그 답이 FE 안에서 나오지 않았다. 그래서 front 만 보지 않고 **원본 3D → Unity → Spring → FE → 빌드 → R3F** 전 구간을 한 번 실측했다.

결론부터: **막고 있는 것은 하나다 — 벤더 에셋의 웹 변환·재배포 권리 증빙.** 나머지는 이미 결정돼 있거나 이미 구현돼 있다.

---

## 1. End-to-end 흐름 (실측)

```text
원본 3D          festa-unity/Assets/_Project/Art/Booth/ExpoKit/Models/*.FBX
                 + ithappy/Casino_Free + _Project/Models/Laptop     [수동 반입 · 라이선스 파일 0]
  ↓ 생성:사람   Unity importer (.FBX + .meta guid)
                 ※ com.unity.cloud.gltfast 6.19.0 설치돼 있으나 Assets 내 사용처 0건
Unity prefab     ExpoKit/Prefabs/** → _Project/Prefabs/Booth/*.prefab (중첩 조립, 11종)
  ↓ 생성:사람   Unity Editor 수동
Registry         BoothObjectRegistry.asset — 11 엔트리, prefab guid 전부 실재
  ↓ 소비        BoothObjectFactory.GetPrefab(type, assetCode)
Spring catalog   catalog_items — AVATAR_PART 97행. 부스 장식 0행. 스키마도 아바타형(equip_slot)
  ↓ 소비        FE useCatalogItems('BOOTH_DECOR') → 항상 빈 배열
FE assetCode     booth-assets.config.mjs(손으로 4종) · visualAssets.ts 팔레트(목업 7종)
  ↓ 생성:수동   node tools/assets/build-booth-assets.mjs → compile-runtime-assets.mjs
generated        .generated/runtime/{*.glb,*.webp,manifest.json}        [.gitignore:35]
  ↓ 배포        ✗ 미연결 — 의도된 게이트(§3-2)
R3F consumer     boothAssetCache.loadGlb (URL 단위 lazy) → AssetMesh
```

각 단계의 **생성 주체가 전부 사람이고 자동 동기화 코드가 없다.** 자산이 2~4종인 지금은 손으로 맞춰지지만, 늘어나면 어긋나고 어긋나면 Unity 가 경고만 내고 타입 기본으로 떨어진다(`BoothObjectRegistry.cs:55`).

---

## 2. 부스가 실제로 무는 벤더 팩 — 3개뿐

부스 prefab 11종의 **중첩 prefab 을 재귀로 따라가** 도달하는 leaf FBX 의 소속을 집계했다. 직접 `.FBX` 참조만 세면 0으로 보인다(`#146` 회신이 그렇게 읽혔다).

| prefab | 도달하는 팩 |
|---|---|
| `BoothShell` · `VideoScreen` · `ProjectPanel` · `SurveyKiosk` · `RecruitmentBoard` · `ConsultationDesk` · `LikeVote` · `Furniture` · `Decoration` | `_Project/Art/Booth/ExpoKit` |
| `AiAgent` | `ithappy/Casino_Free` |
| `Laptop` | `ExpoKit` + `_Project/Models/Laptop` |

```text
BOOTH_PACKS = ExpoKit | ithappy/Casino_Free | _Project/Models(Laptop)
```

**범위 밖으로 확정된 것**: `CarnivalKit`(재귀 추적 0건 — `#146` 회신의 "월드 축제존 전용" 이 맞다) · `Palmov Island` · `Hyper casual cartoon castles` · `danthaigames`.

---

## 3. 항목별 판정

### 3-1. 최종 3D 에셋 제공 포맷 — **CROSS_PART** (단, 지금은 성립하지 않는 질문)

| 근거 | 내용 |
|---|---|
| `specs/006/plan.md:68` | *"에셋 교체는 마지막: 임시 도형으로 로직을 완성한 뒤 프리팹만 바꾼다"* |
| `specs/006/tasks.md:37-39` | `[x] T016` · `[x] T017` · `[x] T018` — **교체는 이미 수행됨** |
| `#146` 회신 (2026-09-08) | *"커밋 안 된 부스용 prefab·FBX 는 현재 없습니다"* |

**"앞으로 실 에셋을 새로 받는다" 는 기록이 저장소에 없다.** 이 질문은 가정이 만든 것이다.

포맷을 말한 문장은 있으나 결정 지위가 아니다 — `05_technical-spikes/booth-studio-r3f-asset-pipeline-plan.md:61` 의 *"Unity는 기존 FBX / Texture / Prefab 등 원본 에셋만 제공"* 은 같은 문서 헤더가 **"확정 결정도 아니다"** 라고 스스로 부정한다. 결정 정본 `00_context/implementation-decisions.md:51` D-05 = `PASS_CANDIDATE — 채택 아님`.

FE 단독 불가 사유: Unity 도 같은 원본을 소비한다. 포맷이 GLB 로 바뀌면 Unity 쪽 `gltfast` 배선(현재 사용처 0건)이 필요하다.

### 3-2. 웹 변환·최적화·재배포 권리 — **CROSS_PART · 현재 유일한 블로커**

전수 확인 결과 **근거 0건**이다.

```text
LICENSE/NOTICE/ThirdPartyNotices   벤더 3D 팩에 하나도 없음
                                   (있는 3건은 폰트 2 + Sketchfab Curtain 1 — 부스 미사용)
source-packs.lock.json             ExpoKit·ithappy 모두 licenseClass UNKNOWN · evidence null
선언조차 안 된 팩                   Palmov·castles·danthaigames → compile: false
커밋 이력                          라이선스 확인을 기록한 커밋 0건 (git log --all --grep 전수)
반대 방향 기록                     docs/24:220 — "공개 저장소에 그대로 올리면 재배포 문제가 된다"
```

그리고 코드가 이 판단을 구현자에게 금지한다 — lock 파일 `reviewGuidance`: *"사람이 확인해 evidence 를 채워야 한다. AI 가 임의로 올리지 않는다."*

**프로덕션 미배포는 누락이 아니라 이 게이트의 결과다.** `runtime.ts:53-60` 이 그 경계를 주석으로 적어 두었고, `canEmitProduction()` 이 `false` 인 동안 산출물은 `.generated/` 밖으로 나가지 않는다.

→ **GitLab `#148`** 로 출처·라이선스 근거를 요청했다(2026-09-08).

### 3-3. 기존 벤더 자산 폐기 vs 병행 — **ALREADY_DECIDED**

`specs/006` Phase 4(T016~T018) 완료 + registry 11 엔트리 전부 prefab guid 실재 + `#146` 회신 = **현재 벤더 자산이 곧 실사용 자산이다.** 임시/mock/reference 로 규정한 문장이 없고, 프리미티브(`CreatePlaceholder`)는 `prefab == null` 일 때만 도는 fallback 으로 밀려나 있다(`BoothObjectFactory.cs:39-41`).

폐기·병행은 **대체할 신규 자산 계획이 없어 대상 자체가 없다.**

### 3-4. Booth Catalog SSOT — **ALREADY_DECIDED (결정 유효 · 구현만 미완)**

```text
docs/26:108   ✅ 5건 확정 (2026-08-21, #18 종료) — ⑷ 카탈로그 SSOT는 Spring
blame         32737ca5 strdeok 2026-08-23. 이후 이 행을 뒤집는 기록 없음
specs/012     Status: Draft — 아바타 파츠 범위 BE 구현 완료, **장식 범위 검토 대기** | P1 (2차 MVP)
```

**미구현은 결정 부재가 아니라 명시적 파킹이다.** 필요한 것은 새 합의가 아니라 BE 구현 backlog. FE 가 manifest/registry 로 SSOT 를 옮기면 확정 결정을 뒤집는 것이 된다.

부수 결함(기록만) — `BOOTH_DECOR` 는 **FE 목업 어휘**다. 도입 커밋 `72d076be [-405][FE] … Creator Workspace 목업`, backend 전체 grep 0건. `InventoryService:47` 이 whitelist 없이 조회해 **400 이 아니라 200 + 빈 배열**로 조용히 지나간다. BE 가 장식 범위를 구현할 때 타입 문자열을 확정하면 FE 가 맞춘다.

### 3-5. `specs/006` T016 "(라이선스 확인)" 의 실제 근거 — **근거 없음으로 확정**

```text
blame  tasks.md:37   ff3c19702  강형순  2026-09-06 01:05
       원문 작성      47e5ed28   강형순  2026-08-13   ← "(라이선스 확인)" 은 이때 쓰인 task 문구

커밋 메시지 ff3c19702:
  "spec 002·006·014 의 tasks.md 를 develop 코드 기준으로 대조했다
   (작성 이후 한 번도 체크되지 않아 전부 미체크였다)"
변경 파일: tasks.md 3 + docs/KHS 2.  라이선스 증거 0.
```

**그 `[x]` 는 "에셋이 코드에 들어와 있다" 는 코드 대조 결과다.** 다운로드 가능·상업 이용·커밋 가능·웹 재배포 중 무엇도 기록되지 않았다. 대상은 `source-packs.lock.json` 이 `UNKNOWN` 으로 잡아 둔 바로 그 팩들 — **동일 대상인데 두 기록이 정반대다.** `#148` 에 사실만 적어 spec 소유자 판단으로 넘겼다.

---

## 4. 이미 있어서 만들 필요가 없는 것

실 에셋이 오든 안 오든 **다시 짓지 않는다.**

| | 상태 | 근거 |
|---|---|---|
| Runtime Asset Compiler | 있음 (FBX + 중첩 prefab → GLB) | `tools/assets/` · `-473`·`-476`·`-480` |
| lazy-load | 있음 — **URL 단위 on-demand + Promise 캐시** | `boothAssetCache.ts:11-26`. 렌더러도 `lazy()` |
| thumbnail 생성 | 있음 — 256px webp, manifest 에 실림 | `pipeline.mjs` `renderToRaw`+`encodeWebp` |
| thumbnail 소비 | **없음** — 팔레트가 `data-kind` CSS 아트 | `AssetPalette.tsx` |
| production URL seam | 있음 — CDN override + 테스트 | `runtime.ts:53` · `boothAssetUrl.test.ts:22` |
| dist 배포 단계 | **없음(의도)** | §3-2. `PUBLIC_BOOTH_ASSET_BASE` 주입도 미연결 |

`dist/assets/booth-runtime/` 에 떨어뜨리면 dev 라우트와 같은 경로라 **nginx 변경 0** 이다(`nginx.conf:14` `try_files $uri`). 선례도 있다 — `docs/26:143` 은 manifest URL 해석을 *"FE 가 base 기준으로 해석한다 (2026-09-05, **FE 판단·구현 완료**)"* 로 닫았다.

---

## 5. 남은 것

```text
막힌 것 1건    벤더 3팩의 출처·라이선스 근거          → GitLab #148 (대기)
구현 backlog   Spring 장식 카탈로그                  → spec 012 장식 범위 (P1, 2차 MVP)
FE 판단 가능   dist 배포 단계 · PUBLIC_BOOTH_ASSET_BASE 주입 · thumbnail 연결
               ※ 단 canEmitProduction() 이 false 인 동안 배포 단계는 붙이지 않는다
기술적 유효    -527 다중 재질 보존 — FBX/prefab 을 계속 쓰는 한 필수
```

**`#148` 답변이 오면**: 팩별 `ALLOW / REVIEW / BLOCK` 재판정 → `evidence` 기입 → production emission 재개 여부와 `-527` 착수 순서를 그때 정한다. 답변 전까지 lock 을 승격하지 않는다.
