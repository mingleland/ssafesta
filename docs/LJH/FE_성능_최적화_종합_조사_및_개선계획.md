# FE 성능 최적화 종합 조사 및 개선 계획

> **상태**: 조사 정본. 코드 변경 없음(read-only 조사 + 빌드 실측 + 문서).
> **작성** 2026-09-17 · 실측 기준 `origin/develop` `5c408639` (FE 산출물은 `f9c2135c` 이후 변화 없음 — 재빌드 46파일 해시 완전 일치)
> **기준선 변경**: Booth Studio FE 폐기 확정. 사용자가 FE에서 직접 보는 Booth Studio는 삭제 대상이고, 월드를 구성·배포·자동화하는 runtime 계층(Unity WebGL Host · Persistent World · bridge · booth-runtime 파이프라인)은 유지한다.
> **선행 조사**: 1차(2026-09-17 오전, 설정·헤더 실측) → 2차(같은 날, build graph·hash·CDP 실행 비용 실측) → 본 문서(폐기 반영 통합).

---

## Executive Summary

| 항목 | 내용 |
|---|---|
| **현재 가장 큰 병목** | Unity 빌드 3파일(`data.unityweb` 86.4MB · `wasm` 9.4MB · `framework`)이 Cloudflare 엣지에서 캐시되지 않는다(`cf-cache-status: DYNAMIC`). `.unityweb`·`.wasm`이 CF 기본 캐시 확장자 목록에 없어 origin이 매 콜드 진입마다 86MB를 보낸다. World 콜드 진입 48.7s 중 29.6s가 이 한 파일이다. |
| **즉시 고칠 것** | ① CF Cache Rule `/unity/Build/*` (인프라, 코드 0) ② FE nginx: `/assets/` immutable + 없는 해시는 `=404` · `booth-runtime` no-cache + GLB MIME · `index.html` no-cache · gzip (FE MR 1건) ③ `vite:preloadError` 리로드 ④ Landing에서 MP3 4.66MB가 마운트 즉시 내려오는 것 차단 ⑤ Login에서 `WorldPage` chunk 선로드 |
| **Booth Studio 폐기로 사라지는 비용** | lazy chunk 990KB(three 883K + StudioPage 62K + 부속) · CSS 19K · npm 의존 3종(`three` `@react-three/fiber` `@types/three`) · src 39파일 4,214 LOC + 테스트 28파일 · hash churn 4→1~2. **초기 번들은 변하지 않는다** — Studio는 이미 lazy였다. |
| **유지해야 할 runtime** | `unity/host/*`(Host·PersistentWorld·sessionManager·bridge) · `features/interaction/dispatcher` · `boothLayoutBridge.notifyBoothSlotChanged → ReloadBoothSlot` · `tools/assets/*` + `assets:build/verify` + CI `ci/build`·`ci/package` + `public/assets/booth-runtime` + `boothAssetBase` 주입 경로 |
| **예상 체감 개선** | World 콜드: 엣지 HIT 시 origin 왕복 제거(정량은 Cache Rule 적용 후 실측). Landing: 최장 요청(MP3 2.3~3.2s) 제거로 첫 화면 회선 경합 해소. World 진입: WorldPage chunk 대기 1.3s 제거. 배포 안정성: 옛 chunk 요청이 `200 text/html`로 오염되는 경로 차단. |
| **남은 리스크** | HTML TTFB 0.9~2.1s와 API RTT 0.5~1s는 요청이 미국 PoP(SJC/ATL/PDX)를 경유하는 데서 온다 — Cloudflare 플랜/라우팅 소관이라 FE가 못 줄인다. Game 진입·Booth Rental Overlay·회원 세션 warmup은 미실측. |

---

## 1. 조사 배경

FE 성능 조사는 "Webpack 용어를 찾는 것"이 아니라 각 개념(번들링·코드 분할·해시·캐시·압축·타기팅·SSR·로딩·CDN)의 목적이 현재 Vite 8 + Rolldown + Oxc 구조에서 무엇으로 대응되는지 매핑하고 실측하는 것을 목표로 했다. 1차는 설정 파일과 배포본 응답 헤더를, 2차는 build graph·hash churn·CDP 기반 브라우저 실행 비용을 실측했다. 본 문서는 그 위에 Booth Studio FE 폐기라는 새 기준선을 얹어 "최적화할 것"과 "없앨 것"을 가른다.

## 2. 현재 아키텍처와 Booth Studio 폐기 영향

### 2-1. 아키텍처

- React 19 + react-router 7 + TanStack Query 5. entry 1개(`index.html → src/main.tsx`).
- 상주 화면은 Unity WebGL 캔버스(`PersistentWorld`, 라우트 트리 밖). 관리·상담·설문·AI 화면은 월드 위 오버레이다.
- Vite 8.2.1 = Rolldown 번들러 + Oxc minifier. `build.target` 기본값 baseline-widely-available(Chrome 111 / Edge 111 / Firefox 114 / Safari 16.4).
- 배포: `dist` → nginx:alpine 컨테이너(`festa-frontend/docker/nginx.conf`) → 호스트 nginx(`demo.conf.template`, `/unity/*` alias + `/` proxy) → Cloudflare.
- Unity 빌드는 `/srv/festa/webgl/releases/<id>/` + `current`·`previous` symlink로 배치되고 URL은 `/unity/Build/<md5>.<ext>` 고정 base다.

### 2-2. Booth Studio 폐기 영향 분류

기준: A = 폐기로 삭제 대상 · B = 이미 삭제됨 · C = 다른 기능이 공유하므로 유지 · D = WebGL/runtime 자동화가 필요하므로 유지.

| 항목 | 최신 develop 상태 | 분류 | 근거 |
|---|---|---|---|
| `pages/studio/StudioPage.tsx` (505 LOC) | 존재. route `/app/studio/:boothId` lazy | **A** | 관리창 진입 버튼만 제거됐고 deep-link·코드 보존 상태 |
| `features/studio/**` (39파일 4,214 LOC + 테스트 24) | 존재 | **A** | 외부 import 0건(`game-studio/studio`는 이름만 같은 별개 모듈) |
| `features/studio/ui/canvas/R3FBoothRenderer`·`AssetMesh`·`boothAssetCache` | 존재. dyn chunk 904.6KB | **A** | three 소비자 이 3파일뿐 |
| npm `three` `@react-three/fiber` `@types/three` | 존재 | **A** | 위 3파일 외 참조 0 |
| `features/studio/ui/FacadePanel` (테마·대표색·간판·로고) | Studio 안에 있어 함께 숨음 | **A** (기능 이관 필요 시 C) | 이름 편집은 `BoothNameField`로 이미 이관됨 |
| `entities/booth/facadeApi*` | 존재 | **C** | `BoothNameField`·`BoothManagementOverlay`·`laptopHomepage`가 사용 |
| `entities/layout/api.ts`·`types.ts` | 존재 | **C** | `unity/host/boothLayoutBridge`·`features/booth/model/*`가 참조 |
| `entities/layout/geometry`·`objectTypes`·`passage`·`messages`·`api.mock` | 존재 | **A** | Studio 밖 소비자 0 |
| `entities/catalog/**` (5파일) | 존재 | **A** | Studio 카탈로그 전용 |
| `ManagementPanel` `'studio'` 멤버 · `ManagementPanelHost` lazy 분기 · router `/app/studio` | 존재 | **A** | 폐기 시 같이 제거 |
| `features/booth/ui/BoothMiniPreview.tsx` (2.5D 삽화) | 존재하나 **소비자 0** | **A** | `BoothPreview`가 의도적으로 쓰지 않음(주석 명시) |
| `public/booth-preview/default.png` (1.4MB) | 존재 | **C** | 새 관리창 미리보기. Studio와 무관 |
| `public/assets/booth-runtime/` (GLB 10 + WebP 10 + manifest, 365KB) | 존재 | **D** | 런타임 소비자는 현재 `features/studio`·`entities/layout/passage`뿐이다. 폐기 지시에 따라 파이프라인 산출물로 유지하되, **런타임 소비자가 사라진다는 사실**은 §16에 기록 |
| `tools/assets/*` (24파일 2,833 LOC) · `assets:build`·`assets:verify` · CI `ci/build`·`ci/package` | 존재 | **D** | 자동화 파이프라인 |
| npm devDeps `@gltf-transform/*` `meshoptimizer` `sharp` | 존재 | **D** | `tools/assets` 의존 |
| `shared/config/runtime.ts` `boothAssetBase()` + `PUBLIC_BOOTH_ASSET_BASE` 주입 | 존재 | **D** | 런타임 자산 주입 계약 |
| Studio 진입용 preload/prefetch | **없음** | B | 원래부터 route 도달 시 `import()`만 |
| Studio 전용 mock (`layout/api.mock` `catalog/api.mock`) | StudioPage chunk 안에 잔존 | **A** | Studio와 함께 사라짐 |
| Unity WebGL Host · PersistentWorld · sessionManager · bridge · dispatcher · `ReloadBoothSlot` | 존재 | **D** | 유지 핵심 |

## 3. 조사 방법 및 기준선

| 방법 | 대상 | 도구 |
|---|---|---|
| 설정·헤더 실측 | `vite.config.ts` `docker/nginx.conf` `demo.conf.template` `dev.conf` Dockerfile · demo 응답 헤더 | `curl -I` |
| 빌드 그래프 | `vite build --manifest --sourcemap` 산출 `.vite/manifest.json` + sourcemap 소스 귀속 | node 스크립트 |
| 결정성·hash churn | 동일 baseline 2회 + 1줄 변경 3종 + vendor 분리 실험 2종 | 산출 파일명 diff |
| tree shaking | probe 모듈(unused export · barrel · top-level side effect · `@__PURE__`) 임시 삽입 후 빌드 → 문자열 잔존 확인 → 되돌림 | — |
| 브라우저 실행 비용 | 로컬 Chrome headless + CDP(`Profiler.takePreciseCoverage` · `Performance.getMetrics` · resource timing · longtask) | `scratchpad/cdp-perf.cjs` |
| 배포 안정성 | 이전 배포본 chunk URL을 현 demo에 요청 | `curl` |

기준선: 2차 실측은 `f9c2135c`, 본 문서 재빌드는 `5c408639`. 그 사이 커밋은 BE·인프라·dev 게이트웨이(`tools/`)뿐이라 FE 산출물 46파일이 이름까지 동일하다. 따라서 2차 수치를 최신값으로 그대로 쓴다.

## 4. 현재 성능 현황

### 4-1. 시나리오별 실측 (demo, 게스트 세션, Chrome headless)

| 시나리오 | wall | HTML TTFB | load | 요청 수 | transfer | JS execute | task 합 | long task | JS heap | JS unused |
|---|---|---|---|---|---|---|---|---|---|---|
| Landing cold | 7.9~8.1s | 1.4~2.1s | 4.8~4.9s | 22 | 5.4MB (MP3 4.66MB) | 38~55ms | 0.14~0.22s | 0 | 3MB | 75% (109K/441K) |
| Login warm | 7.2s | 0.9s | 4.1s | 23 | 5.5MB | 44ms | 0.13s | 0 | 3MB | 75% |
| World cold | **48.7s** | 5.8s* | 8.7s | 33 | **99.4MB** (Unity 93.7MB) | 3.37s | 5.99s | 1 × 1.39s | 17MB | 75% (168K/666K) |
| World warm(재진입, HTTP 캐시) | 9.0~9.7s | 1.1~1.3s | 1.6~2.0s | 32 | 0 (disk 23건) | 3.3~3.4s | 3.9s | 1 × 1.23s | 18MB | 75% |

\* 게스트 발급 뒤 문서 재로드 시점의 navigation entry. 회원 세션 값은 미실측.

### 4-2. 주요 수치

| 항목 | 값 | 출처 |
|---|---|---|
| Initial JS | 418KB raw / 143KB br, 9 요청, 폭포 없음(전부 동시 시작) | manifest + resource timing |
| Initial CSS | 47KB raw / 13KB br, 3 요청 | 동일 |
| Unity data / wasm / framework / loader | 86,389,600B br / 9,397,312B br / 78,777B br / 115KB(70KB br) — 디코드 합 175.6MB | `curl -I` + resource timing |
| Unity cold 다운로드 | data 29.6s · wasm 7.8s (CF MISS/DYNAMIC → origin) | CDP |
| HTML TTFB | curl starttransfer 1.49s(TLS 0.74s) · 브라우저 0.9~2.1s | curl/CDP |
| API RTT(warm) | `/unity/manifest.json` 0.97s · `/auth/refresh` 0.67s · `/users/me` 0.57s · `/auth/guest` 0.57s | CDP |
| MP3 | 4,770,454B · 2.3~3.2s · Landing·Login 최장 요청 | CDP |
| JS execute | Landing 38~55ms / World 3.3~3.4s (Unity wasm instantiate 포함) | Performance.getMetrics |
| long task | Landing 0 / World 1건 1.2~1.4s(Unity 부팅) | PerformanceObserver |
| request count | 22 / 23 / 33 / 32 | resource timing |
| Cloudflare 삽입 스크립트 | 30KB `/cdn-cgi/...` 1건, 전 화면 | coverage |

### 4-3. 이전 조사와의 차이

| 수치 | 1차 | 2차/최신 | 변화 원인 |
|---|---|---|---|
| 초기 JS | 426KB raw ≈ 130KB br (추정) | 418KB raw / 143KB br (실측) | 실측으로 대체. transfer는 CF br 기준 |
| `returnTo` chunk 구성 | 관리 페이지 5종 (≈90KB) | 관리 3종 27K src + 월드 공유 코드 | sourcemap 귀속으로 정정 |
| stale chunk 응답 | 404 (추정) | **200 text/html** (실측) | `try_files` SPA fallback |
| hash churn | 미측정 | 어느 파일을 바꿔도 4/27 (index·WorldPage·StudioPage·R3F) | 실측 |
| 산출 파일 수 | 46 | 46 (`5c408639` 재빌드 동일) | 변화 없음 |

## 5. Build / Bundle 분석

- **결정성**: 동일 baseline 2회 빌드 → 46파일 이름·해시 완전 일치.
- **tree shaking**: unused export 제거됨. barrel(`export *` 4모듈) 중 미사용 2모듈 제거됨. top-level `Array.from()`이 있는 모듈은 export는 제거되나 호출문이 잔존한다. `/* @__PURE__ */`를 붙이면 제거된다.
- **side effect 실사례**: `.select.ts`의 `import.meta.env.VITE_USE_MOCK === 'true'`는 false로 접히지만, mock 모듈의 top-level 시드 초기화가 side effect로 잡혀 mock 10모듈이 prod에 남는다(≈7KB min). Studio 폐기로 `layout/api.mock`·`catalog/api.mock`은 사라지고 초기 번들의 `leaseApi`·`wallet`·`loader`·`auth`·`admin`·`project`·`channel`·`facade` mock은 남는다.
- **`sideEffects: false` 실험**: mock 13/14 제거, CSS 154KB 동일(Vite가 CSS를 side-effect로 고정), JS 1,743→1,736KB. 순이득 7KB. 무바인딩 import 46건이 전부 CSS라 지금은 안전하지만, side-effect 모듈을 추가하면 조용히 사라지는 회귀 경로가 열린다 — 적용하지 않는다.
- **의존성 `sideEffects` 선언**: react·react-dom·react-router·@tanstack·three 전부 `false`, @react-three/fiber만 `./src/nodes/**`.
- **hash churn**: 어떤 앱 파일을 바꿔도 `index`·`WorldPage`·`StudioPage`·`R3FBoothRenderer` 4개가 바뀐다. `StudioPage`·`R3F`가 entry chunk(`index`)를 import 하고 `WorldPage`가 `StudioPage`를 dyn-import 해 연쇄된다. **Studio 폐기 시 이 연쇄가 끊겨 churn은 `index` 1개(+ 해당 lazy chunk)로 준다.**

## 6. Chunk / Lazy Loading 분석

manifest 기준 그래프(현 develop):

| 진입 | 필요한 chunk (raw) | 폐기 후 |
|---|---|---|
| 초기(모든 경로) | `index` 209K(react-dom·앱 71K src) · `chunk-62JR` 89K(react-router) · `returnTo` 65K · `api` 41K(query-core + booth API) · `runtime` 9K(react) · client/overlay/resolveAssetUrl <2K = **418K raw / 143K br** | 동일 |
| 월드 `/app/world` | + `WorldPage` 121K · `facadeApi.select` · CSS 24K | 동일 |
| Studio | + `StudioPage` 62.6K → dyn `R3FBoothRenderer` 904.6K(three + fiber) · `objectAppearance` 7K · CSS 19K | **삭제** |
| 게임 편집/플레이 | + `EditGamePage` 173.6K · `SpriteAnimationPreview` 33K · `runtimeConfig` 11K · `PublishedGameSurface` 32K · webp 14장 | 동일 |
| 오버레이 | `GameOverlay` 6K | 동일 |

- modulepreload 7개가 동시에 시작한다(1446ms 일괄). 초기 폭포 없음. over-splitting 없음(0.2~0.4K chunk 3개는 rolldown 공유 경계).
- `WorldPage` chunk는 route 도달 후 시작(cold 7353ms)해 1.3s 추가 대기가 생긴다. 기본 목적지가 `/app/world`이므로 Login에서 선로드할 가치가 있다.
- 관리 페이지 5종 lazy 전환: `returnTo`에 있는 Survey 15K·Project 7K·Consultation 5K src만 이동 가능(≈10KB gz). 나머지는 월드가 쓰는 공유 코드다.
- **vendor 분리 실험**: `advancedChunks`로 react 계열을 떼면 `index` 209K→27K, vendor-react 264K(85K gz)가 배포 간 고정된다. 단 그룹 정규식에 `scheduler`를 three 그룹에 넣었을 때 rolldown이 `vendor-three` 885K를 초기 modulepreload로 올리는 것을 실측했다 — 나이브한 그룹핑은 초기 진입을 악화시킨다. Studio 폐기로 three 자체가 사라지면 이 함정도 사라지고, churn 연쇄도 끊기므로 vendor 분리의 실익은 "배포당 85KB gz 재수신 절약" 하나로 준다.

## 7. Hash / Cache / Versioning

| 자산 | URL 구조 | 파일명 hash | 배포 시 URL | purge | immutable 안전 | 현 헤더(demo) | 목표 |
|---|---|---|---|---|---|---|---|
| Vite JS/CSS/이미지 | `/assets/{name}-{hash}` | ✅ | 바뀜 | 불필요 | ✅ | **없음** → CF 기본 `max-age=14400` | `public, max-age=31536000, immutable` |
| Unity 빌드 | `/unity/Build/{md5}.{ext}` (디스크 `releases/<id>` + `current`) | ✅ | 바뀜 | 불필요 | ✅ | `immutable` 1y ✅ / CF `DYNAMIC` ✗ | CF Cache Rule |
| Unity `manifest.json` | 고정 | ✗ | 같음 | 필요 | ✗ | `no-cache` ✅ | 유지 |
| booth-runtime | `/assets/booth-runtime/{CODE}.glb` | ✗ | 같음 | **필요** | ✗ | GLB: 헤더 없음·`application/octet-stream`·CF DYNAMIC → 브라우저 heuristic 캐시. WebP: `max-age=14400` | `no-cache` + `model/gltf-binary`. 소비자가 Studio뿐이므로 hashed filename 전환은 보류 |
| public 고정(`favicon` `cursors` `booth-preview/default.png`) | 고정 | ✗ | 같음 | 필요 | ✗ | `max-age=14400`(CF 기본) | 유지(4h 허용) |
| `runtime-config.js` | 고정 | ✗ | 같음 | — | ✗ | `no-store` ✅ | 유지 |
| `index.html` | 고정 | ✗ | 같음 | — | ✗ | `no-store` | `no-cache`(ETag 304) |

## 8. Static Asset

| 자산 | 크기 | 위치 | 로드 시점 | 초기 영향 | 폐기 영향 | 개선 |
|---|---|---|---|---|---|---|
| `midnight-circus-soft-login.mp3` | 4.77MB | hashed | **Landing 마운트 즉시** — `enterScreen() → unlockAndPlay() → new Audio(url)`, `preload='auto'`. 자동재생이 거부돼도 다운로드는 진행(실측 Landing transfer 4.66MB, 2.3~3.2s, 최장 요청) | 큼 — 로고·배경 webp·loader warmup과 회선 경합 | 없음 | `preload='metadata'`로 두고 `play()` 성공 뒤 `auto`, 또는 pendingGesture 상태에선 element 생성 보류 |
| `world-mock-background.png` | 4.9MB | hashed | `PageShell`·`WorldSurface` `<img>`. 정상 Unity 경로에선 미요청 | 관리 route deep-link·mock 월드만 | 없음 | PNG→WebP(≈10×) |
| `booth-preview/default.png` | 1.4MB | public | 관리창 | 없음 | **유지** (Studio 아님) | WebP |
| landing/login 배경·로고 webp | 234K·328K·240K | hashed | 각 화면 | 있음(1.6~1.7s) | 없음 | AVIF는 +30% 절감, 우선순위 낮음 |
| game-studio webp 14장 | 4.4MB 합 | hashed | EditGame chunk 진입 시 | 없음 | 없음 | — |
| booth-runtime GLB 10 + WebP 10 + manifest | 365KB | public | Studio | 없음 | 런타임 소비자 소멸, 파이프라인 산출물로 유지 | 캐시 헤더·MIME |
| Unity 4파일 | 93.7MB br | 고정 base·hash명 | World | 지배적 | 없음 | CF Cache Rule |
| 폰트 | 0 (`system-ui`) | — | — | — | — | 좋음 |

## 9. Unity WebGL

- 압축: 사전 Brotli(`.unityweb`) + nginx `Content-Encoding: br` + `Vary` 정상. 브라우저는 84.4MB를 받아 175.6MB로 디코드한다.
- 캐시: origin은 `public, max-age=31536000, immutable`을 내리지만 Cloudflare가 `.unityweb`·`.wasm`을 기본 캐시 대상으로 보지 않아 엣지 `DYNAMIC`. `.loader.js`만 `.js` 확장자라 HIT가 된다.
- warmup: `landing`(loader 115K) → `intent`(+framework 79K) → `authenticated`(+wasm 9.2MB, data 84MB). `fetch().arrayBuffer()`로 받아 HTTP 캐시를 채우는 구조라 재진입 시 disk 23건 HIT를 실측했다. heap 17MB로 GC 되고 있어 메모리 문제는 없다.
- 부팅: long task 1.2~1.4s(wasm instantiate). FE가 줄일 수 없는 비용이다.
- 릴리스: 게임 파트 `45e36503`(data 82.4MB)은 미배포. 배포 시 4MB 감소.

## 10. Browser Execution

- Landing/Login JS execute 38~55ms, long task 0. FE 코드 실행 비용은 문제가 아니다.
- World JS execute 3.3~3.4s는 Unity 부팅이 대부분이다.
- JS coverage unused 75%는 경로 미실행 코드(관리·대화상자)로 SPA 평균 수준이다. 추적 가치 없음.
- 재진입 9s의 주성분은 API 왕복(0.5~1s × 여러 건)이다. 캐시가 아니라 지연 문제다.

## 11. CDN / nginx / Origin

- **Cloudflare**: 한국 요청이 SJC/ATL/PDX PoP로 라우팅된다(CF-RAY 실측). MISS면 KR→US→KR 왕복이 두 번 든다. 해시 자산은 origin에 `Cache-Control`이 없어 CF 기본 4h(`max-age=14400`)만 받는다. CF가 텍스트 자산을 엣지에서 br 재압축한다(index JS 215KB→68.8KB). Bot 관련 30KB 스크립트가 전 화면에 삽입된다.
- **FE nginx(`docker/nginx.conf`)**: `gzip` off(공식 이미지 기본), `/assets/` 헤더 없음, `try_files $uri $uri/ /index.html`이 없는 해시 자산에도 SPA shell을 200으로 돌려준다, `index.html` `no-store`, `.glb` MIME 미등록.
- **호스트 nginx(`demo.conf.template`)**: `/unity/*` 캐시·압축 헤더 정상. `/`는 18080 proxy.

## 12. Deployment Stability

실측: 09-16 배포본 chunk `/assets/index-BBS1uinF.js`를 현 demo에 요청 → `200 OK`, `Content-Type: text/html`, `Cache-Control: no-store`, `cf-cache-status: BYPASS`, 본문은 새 `index.html`. 브라우저는 module script MIME 불일치로 거부하고 `import()`가 reject되어 `vite:preloadError`는 발화한다. 그러나 오류 메시지가 MIME 불일치라 원인 추적이 어렵고, CF가 옛 chunk를 4h 안에 EXPIRED 처리하므로(Landing 실측 EXPIRED 6건) 엣지 잔존에 기댈 수 없다.

| 전략 | 비용 | 판정 |
|---|---|---|
| `vite:preloadError` → `location.reload()` | main.tsx 1줄 | 채택 |
| `location /assets/ { try_files $uri =404; }` | nginx 1줄 | 채택 — 원인이 404로 드러나고 html-as-js 오염 차단 |
| CDN retention(`immutable` 1y + Cache Rule) | 설정 | 채택 — 옛 chunk가 엣지에 남아 세션 중 리로드 없이 생존 |
| N-1 asset retention | front.compose·이미지 구조 변경 | 보류 |
| versioned deployment(`/v/<sha>/`) | base·runtime-config·CF 규칙 전부 변경 | 보류 |
| atomic deployment | 이미 이미지 교체 = atomic | 해당 없음 |

## 13. Browser Targeting

| 항목 | 값 |
|---|---|
| Vite `build.target` 기본 | Chrome 111 · Edge 111 · Firefox 114 · Safari 16.4 / iOS 16.4 (`ESBUILD_BASELINE_WIDELY_AVAILABLE_TARGET`, Baseline Widely Available 2026-01-01). 변환은 syntax만, polyfill 없음 |
| tsconfig | `target es2023` · `lib ES2023 + DOM` |
| Browserslist | 없음 |
| 문서상 지원 범위 | 명시 문서 없음. 실측 기록은 Chrome 148~152 Windows. `GameStudioShell` `min-width:760px`로 사실상 데스크톱 전용 |
| 사용 API | `.at()` ×15 · `structuredClone` ×10 · `showModal` ×7 · `AbortSignal.timeout` ×2 · `requestIdleCallback`(Safari 폴백 있음) · `ResizeObserver` · `Intl` · dynamic `import()` · WebSocket · WebGL2 — 전부 target 안. Worker/OffscreenCanvas 사용 0 |
| 사용 CSS | `@container` ×2 · `dvh` ×3 · `backdrop-filter` ×25 · `aspect-ratio` — target 안. `:has()` ×1은 Firefox 121+라 114~120에서 그 규칙 1개만 무시 |
| 판정 | polyfill 불필요 · legacy bundle 불필요. 지원 범위 한 줄을 문서화하는 것만 남음 |

## 14. SSR / Rendering 판단

| 방식 | 판정 | 근거 |
|---|---|---|
| CSR(현재) | 유지 | 상주 화면이 Unity 캔버스, 관리 화면은 오버레이. 서버가 그릴 HTML이 `<div id=root>`뿐 |
| SSR | 적용 안 함 | 인증 후 화면이 전부. Unity 86MB 대기가 지배. Node 서버 운영비 > 이득. hydration이 `PersistentWorld` 마운트 타이밍을 흔든다 |
| SSG(Landing/Login) | 적용 안 함 | HTML은 이미 1.2KB. TTFB는 no-store·origin 왕복 문제라 정적화로 안 줄고 `no-cache` + CF HIT로 준다. 두 화면 모두 세션 판정이 붙어 정적화 이득 0 |
| ISR / Streaming SSR / RSC | 적용 안 함 | 데이터 페이지 없음. SEO 불필요(로그인 뒤 폐쇄 서비스) |
| hydration | 해당 없음 | 유일한 유사 비용은 Unity wasm 1.2~1.4s — 서버 렌더로 못 없앤다 |

## 15. Booth Studio 폐기로 제거되는 항목

| 대상 | 규모 | 산출물 영향 |
|---|---|---|
| `src/features/studio/**` | 39파일 4,214 LOC + 테스트 24 | `StudioPage` 62.6K · `R3FBoothRenderer` 904.6K · `objectAppearance` 7K · CSS 19K 삭제 |
| `src/pages/studio/StudioPage.tsx` + 테스트 3 | 505 LOC | route `/app/studio/:boothId` 삭제 |
| `src/entities/catalog/**` | 5파일 103 LOC + 테스트 1 | `catalog/api.mock` 삭제 |
| `src/entities/layout/{geometry,objectTypes,passage,messages,api.mock}` | 부분 | `layout/api.mock` 삭제. `api.ts`·`types.ts`는 C로 유지 |
| `ManagementPanel` `'studio'` · `ManagementPanelHost` 분기 · `BoothManagementOverlay` 주석 | 소량 | 초기 번들 미미 |
| `features/booth/ui/BoothMiniPreview.tsx` | 소비자 0 | 이미 dead |
| npm `three` `@react-three/fiber` `@types/three` | 3종 | lockfile 축소, `npm ci` 시간 감소 |
| hash churn | 4/27 → 1~2 | `StudioPage`·`R3F`의 `index` import 연쇄 소멸 |

초기 번들(418K raw)은 변하지 않는다. 제거되는 990KB는 전부 lazy chunk라 Studio를 열지 않는 사용자에게는 원래 내려가지 않았다. 폐기의 실제 이득은 의존성·유지보수 범위·churn이지 첫 진입 속도가 아니다.

## 16. 유지해야 할 Runtime / WebGL 구조

| 계층 | 파일 | 역할 |
|---|---|---|
| Unity WebGL Host | `unity/host/UnityHost.tsx` `loader.ts` `resolver.ts` `warmup.ts` `warmupMetrics.ts` | 로더 주입·manifest 해석·단계별 warmup |
| Persistent World | `unity/host/PersistentWorld.tsx` `worldMount.ts` | 라우트 밖 상주 캔버스 |
| bridge / session | `unity/host/sessionManager.ts` `authBridge` `inputBridge` `audioBridge` `profileBridge` `worldUiBridge` `unity/bridge/*` | React↔Unity 계약 |
| runtime event dispatcher | `features/interaction/dispatcher.ts` | `WORLD_*_INTERACT` 수신 |
| 월드 동기화 | `unity/host/boothLayoutBridge.ts` `notifyBoothSlotChanged → ReloadBoothSlot` | 반납·facade 저장 뒤 슬롯 재조회 |
| booth-runtime 파이프라인 | `tools/assets/build-booth-assets.mjs` `compile-runtime-assets.mjs` `verify-runtime-assets.mjs` · `assets:build/verify` · CI `ci/build` `ci/package` · `public/assets/booth-runtime/` · `boothAssetBase()` | Unity 원본 → GLB/WebP/manifest 자동 생성·검증·배포 |

기록해 둘 사실: booth-runtime의 런타임 소비자는 현재 `features/studio`(팔레트·렌더러·검증)와 `entities/layout/passage.ts`뿐이다. Studio가 사라지면 `dist/assets/booth-runtime/` 365KB는 브라우저가 요청하지 않는 산출물이 된다. 폐기 지시에 따라 파이프라인과 산출물은 유지하되, 다음 소비자(예: 관리창 미리보기의 3D화, 관람자 측 부스 상세)가 정해질 때까지 `assets:build`가 `npm run dev`·`build` 앞에 붙는 시간 비용만 인지한다.

## 17. 누적 조사 결과 변경 이력

### 그대로 유지된 결론
- Unity 빌드 3파일 CF 엣지 미캐시가 최대 병목(1차→2차→최신).
- 해시 자산 `immutable` 미적용, `index.html` `no-store`→`no-cache`, origin gzip 저우선.
- MP3 4.66MB Landing 즉시 다운로드(2차 발견, 최신 유지).
- stale chunk 요청이 `200 text/html`로 돌아옴(2차 발견, 최신 유지).
- booth-runtime 고정명 + 캐시 헤더 없음(2차 발견, 최신 유지).
- Vite target 현대 브라우저 4종, polyfill·legacy 불필요. SSR 계열 전부 불필요.
- 초기 JS 418K raw / 143K br, 폭포 없음, over-splitting 없음.

### 2차에서 수정된 결론
- `returnTo` chunk = 관리 5종(90KB) → 관리 3종 27K src + 월드 공유 코드. lazy 이득 ≈10KB gz.
- stale chunk → 404 → 실제는 200 text/html. `=404` 추가 필요.
- `manualChunks` 불필요 → vendor-react 한 그룹은 배포당 85KB gz 절약. 단 잘못된 그룹은 three를 초기에 끌어옴(실측).

### Booth Studio 폐기로 폐기된 결론
- "`three` 904KB lazy 경계 정상, 유지" → 의존성 자체 제거 대상.
- "Studio 청크 분리 검증(P4 MR)" → 불필요.
- "booth-runtime hashed filename 전환(P2)" → 런타임 소비자가 Studio뿐이므로 보류. 파이프라인 산출물 캐시 헤더만 정정.
- "vendor 분리 시 three 그룹 함정" → three가 사라지면 함정도 사라짐. vendor-react 분리는 churn 해소 목적이 없어져 P3.
- "hash churn 4/27" → Studio 제거만으로 1~2로 준다. 추가 조치 불필요.
- "Studio-only mock(`layout`·`catalog`) 정리" → 폐기와 함께 소멸.

### Booth Studio 폐기로 새롭게 중요해진 결론
- 관리창 미리보기 `booth-preview/default.png` 1.4MB가 부스 관리의 유일한 시각 자산이 된다 → WebP 전환 우선순위 상향.
- booth-runtime 파이프라인은 "Studio를 위한 자산"에서 "다음 소비자를 기다리는 자산"이 된다. 소비자 결정은 팀 결정 사항(헌법 30조).
- `entities/layout/api.ts`·`types.ts`와 `facadeApi`는 Studio 밖 소비자가 있어 폐기 MR에서 건드리면 안 된다.

### 아직 확인되지 않은 항목
§21 참조.

## 18. 최종 병목 순위

| 순위 | 계층 | 병목 | 실측 | 체감 | 원인 | 개선 | 소유 | 우선순위 | 검증 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | F CDN | Unity 빌드 엣지 미캐시 | data 29.6s/84MB, `DYNAMIC` | World 콜드 48.7s | `.unityweb`·`.wasm` CF 기본 캐시 확장자 아님 | Cache Rule `/unity/Build/*` Eligible, TTL origin 존중 | 인프라 | P0 | `curl -I` HIT + CDP 재측정 |
| 2 | I 배포 | stale chunk → 200 html | 실측 | 배포 직후 열린 탭에서 lazy 진입 실패, 원인 불명 오류 | `try_files` fallback | `/assets/` `=404` + `preloadError` reload | FE | P0/P1 | 옛 chunk URL 404 확인 |
| 3 | E/G 캐시 | 해시 자산 4h · index no-store · GLB 헤더 없음 | 실측 | 매 4h 재검증, 304 불가, GLB stale | nginx 헤더 부재 | `docker/nginx.conf` 헤더 | FE | P0 | 헤더 실측 |
| 4 | C 자산 | MP3 4.66MB Landing 즉시 | 2.3~3.2s 최장 요청 | 첫 화면 회선 경합 | `preload='auto'` element 마운트 즉시 생성 | `metadata` → 성공 후 `auto` | FE | P1 | Landing transfer 재측정 |
| 5 | A 번들 | WorldPage chunk route 도달 후 시작 | +1.3s | World 진입 지연 | 선로드 없음 | Login에서 `import()` | FE | P1 | waterfall |
| 6 | C 자산 | PNG 4.9MB·1.4MB | 실측 | deep-link·관리창 | 포맷 | WebP | FE | P1 | 크기 |
| 7 | H API | HTML TTFB 0.9~2.1s · API RTT 0.5~1s | 실측 | 모든 화면 | US PoP 경유 | FE 범위 밖. CF 라우팅/플랜 | 인프라 | 기록 | — |
| 8 | A 번들 | react-dom 매 배포 재수신 | 69KB br | 미미 | entry에 vendor 포함 | vendor-react 단일 그룹 | FE | P3 | index.html modulepreload 검증 |
| 9 | B 실행 | Unity 부팅 long task 1.2~1.4s | 실측 | World | wasm instantiate | 없음 | 게임 | 해당 없음 | — |
| 10 | J 자동화 | booth-runtime 런타임 소비자 소멸 | 365KB 미요청 | 없음 | Studio 폐기 | 소비자 결정 대기 | 팀 | 기록 | — |

## 19. 단계별 개선 계획

### Phase 0 — Booth Studio 잔재 제거
- 변경 대상: §15 목록. `features/studio`·`pages/studio`·`entities/catalog`·`entities/layout` Studio 전용 4파일+mock·`BoothMiniPreview`·router·`ManagementPanel` `'studio'`·npm 3종. `entities/layout/api.ts`·`types.ts`·`facadeApi*`·`tools/assets`·`booth-runtime`은 건드리지 않는다.
- 담당: FE. Jira 신규 1키. `refactor/` 브랜치, MR 1건, squash.
- 선행 조건: FacadePanel의 테마·대표색·간판·로고 편집을 어디로 옮길지(또는 버릴지) 결정. 관리창 이름 편집만 이관돼 있다.
- 예상 효과: lazy chunk 990KB·CSS 19K·의존 3종 제거, churn 4→1~2, 테스트 28파일 감소.
- 위험: `entities/layout` 부분 삭제 시 `boothLayoutBridge` 참조 확인 필수. `tools/paletteAssetCodes.test.mjs`가 `visualAssets.ts` 전문을 읽으므로 함께 정리.
- 완료 판정: `npx vitest run`·`tsc`·`build` green, 산출물에 `R3FBoothRenderer`·`StudioPage` chunk 없음, `rg three src` 0건.
- 실측: manifest 재생성, 1줄 변경 빌드로 churn 재측정.

### Phase 1 — CDN / nginx / cache correctness
- 변경 대상: ① Cloudflare Cache Rule `URI Path starts with /unity/Build/` → Eligible for cache, Edge TTL origin 존중. ② `festa-frontend/docker/nginx.conf`:
  `location ^~ /assets/booth-runtime/ { add_header Cache-Control "no-cache" always; }` ·
  `location /assets/ { try_files $uri =404; add_header Cache-Control "public, max-age=31536000, immutable" always; }` ·
  `location = /index.html { add_header Cache-Control "no-cache" always; }` ·
  `types { model/gltf-binary glb; }` · `gzip on; gzip_types text/css application/javascript application/json image/svg+xml; gzip_min_length 1024;`. ③ `main.tsx` `window.addEventListener('vite:preloadError', () => location.reload())`.
- 담당: ① 인프라(`#196`에 근거 첨부) ② ③ FE, Jira 1키, MR 1건.
- 선행 조건: 없음. Phase 0과 독립.
- 예상 효과: World 콜드 origin 왕복 제거, 해시 자산 재검증 0, 옛 chunk 404, GLB stale 차단, 304 복구.
- 위험: `booth-runtime` location이 `/assets/`보다 먼저 매치돼야 한다(`^~`). CF 캐시 파일 크기 상한(플랜별) 확인 — 86MB는 통과 예상이나 적용 후 HIT로 실증.
- 완료 판정: `curl -I`로 `/unity/Build/*.data.unityweb` `cf-cache-status: HIT`, `/assets/*.js` `immutable`, 옛 chunk `404`, `.glb` `model/gltf-binary` + `no-cache`, `index.html` `no-cache` + 304.
- 실측: CDP World cold 재측정(§4-1 대비).

### Phase 2 — 사용자 첫 진입 체감 개선
- 변경 대상: `features/audio/model/screenAudio.ts` `ensureElement`의 `preload`를 `'metadata'`로, `play()` resolve 뒤 `'auto'`. `world-mock-background.png`·`booth-preview/default.png` WebP 변환(sharp 이미 devDeps).
- 담당: FE, Jira 1키, MR 1~2건.
- 선행 조건: 없음.
- 예상 효과: Landing transfer 5.4MB → 0.7MB, 최장 요청 제거. deep-link·관리창 이미지 10×.
- 위험: 첫 재생 시 버퍼링 지연 가능 — `metadata` 후 `play()`가 나머지를 즉시 받으므로 체감은 1초 미만 예상, 실측으로 확인.
- 완료 판정: Landing에서 mp3 요청이 제스처 전에 나가지 않음(CDP), PNG 0건.
- 실측: CDP Landing 재측정.

### Phase 3 — World 진입 개선
- 변경 대상: `LoginPage`(또는 `intent` warmup 자리)에서 `void import('../world/WorldPage')`. 게임 파트 릴리스 `45e36503`(data 82.4MB) 배포는 인프라 차례.
- 담당: FE / 인프라·게임.
- 선행 조건: Phase 1 ①(엣지 HIT)이 있어야 이득이 보인다.
- 예상 효과: WorldPage chunk 대기 1.3s → 0. data 4MB 감소.
- 위험: 없음(실패해도 route 도달 시 다시 받음).
- 완료 판정: waterfall에서 `WorldPage` chunk가 route 전환 전에 완료.
- 실측: CDP World cold/warm 재측정.

### Phase 4 — bundle/hash 안정성 후속
- 변경 대상: (선택) `build.rolldownOptions.output.advancedChunks.groups = [{ name:'vendor-react', test:/node_modules[\\/](react|react-dom|react-router|react-router-dom|scheduler)[\\/]/ }]` 한 그룹.
- 담당: FE.
- 선행 조건: Phase 0 완료(three 제거 후 그룹 함정 소멸). 배포 빈도가 높은 기간에만 가치.
- 예상 효과: 앱 변경 배포 시 재수신 143K → 58K br.
- 위험: 산출 `index.html` modulepreload에 vendor 외 chunk가 끼면 롤백.
- 완료 판정: churn 재측정에서 vendor-react 해시 고정.

### Phase 5 — 선택적 고도화
- 관리 3종 lazy(≈10KB gz) · AVIF · mock 모듈 top-level 시드 정리(7KB) · 지원 브라우저 한 줄 문서화 · booth-runtime 다음 소비자 결정 후 hashed filename.
- 전부 P3. 실익이 작아 다른 작업과 겹칠 때만.

## 20. 검증 계획

| 단계 | 방법 | 기준 |
|---|---|---|
| 빌드 | `vite build --manifest` + 파일 목록 diff | Phase 0 후 chunk 목록에 Studio 계열 0 |
| churn | 동일 baseline 2회 + 1줄 변경 1회 | 변경 chunk ≤ 2 |
| 헤더 | `curl -I` 6종 URL | §19 Phase 1 완료 판정 |
| 브라우저 | `scratchpad/cdp-perf.cjs` Landing/Login/World/World-re | §4-1 대비 wall·transfer·최장 요청 |
| 배포 안정성 | 직전 배포 chunk URL 요청 | 404 |
| 회귀 | `npx vitest run` · `npx tsc --noEmit` · `npm run build` | 기준선 대비 green |

## 21. 미확인 / 후속 조사

| 항목 | 이유 | 필요 조건 |
|---|---|---|
| Game 진입(`/app/games/:id/edit`·`play`) 실행 비용 | 회원 세션·gameId 필요 | 회원 계정으로 CDP 재실행 |
| 최신 Booth Rental Overlay·관리창 비용 | 회원 세션 필요 | 동일 |
| 회원 인증 후 World warmup(`authenticated` 단계) 실효과 | 게스트 경로만 실측 | 동일 |
| CF Cache Rule 적용 후 실제 HIT 성능·파일 크기 상한 | 미적용 | 인프라 적용 후 `curl -I` + CDP |
| API RTT의 Cloudflare 라우팅 원인 | PoP 관측만 있음 | CF 대시보드·플랜 확인(인프라) |
| MP3 `metadata` 전환 후 첫 재생 지연 | 미구현 | Phase 2 후 실측 |
| booth-runtime 다음 소비자 | 팀 결정 | `docs/26_팀_결정_필요사항.md` |

## 22. 최종 결론

FE 번들은 병목이 아니다(초기 143KB br, 실행 55ms, 폭포 없음). 체감을 결정하는 것은 Unity 86MB의 엣지 미캐시, 매 배포마다 되살아나는 4h 재검증, Landing에서 새는 MP3 4.66MB, 그리고 FE가 못 줄이는 US PoP 왕복이다. Booth Studio 폐기는 첫 진입 속도를 바꾸지 않지만 three 의존성·990KB lazy chunk·hash churn 연쇄·유지보수 범위를 한꺼번에 없앤다. 실행 순서는 Phase 1(설정 몇 줄, 효과 최대)을 Phase 0(폐기)과 병렬로 먼저 내고, Phase 2·3으로 첫 화면과 World 진입을 정리한 뒤, Phase 4·5는 필요할 때만 한다.

