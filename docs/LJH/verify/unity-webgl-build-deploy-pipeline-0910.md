# Unity WebGL 빌드·배포 파이프라인 실측 (2026-09-10)

> **이 문서는 LJH 개인 조사 근거다. Infra 공용 정본이 아니다.**
> 공용 계약의 정본은 `specs/infra-001-ci-cd-pipelines/` 와 `infra/` 아래 파일이며, 이 문서는 그것을
> 2026-09-10 시점 실측으로 대조한 기록이다. 여기 적힌 제안은 제안이지 확정이 아니다.
>
> 기준: `origin/develop be2e5312` (09-10 10:00) · GitLab project 1443023 · Jenkins `ci.ssafesta.world`

---

## 0. 한 줄 요약

Jenkins Unity Agent 는 **이미 develop 병합마다 WebGL 을 빌드한다.** 그 산출물이 agent workspace 를
벗어나지 못해 버려지고, 그 자리를 사람이 Windows 에디터 재빌드 → 수동 zip → PAT 업로드 → 이슈 통보 →
Infra 수동 배치로 메우고 있다. 만들 것은 새 시스템이 아니라 **끊어진 통로**다.

---

## 1. 현재 Unity build 실제 절차

**Unity `6000.0.78f1` (revision `ec8a99a872be`)** — `festa-unity/ProjectSettings/ProjectVersion.txt`

빌드 진입점 3개.

| 진입점 | 트리거 | 출력 | 설정 | 용도 |
|---|---|---|---|---|
| `FestaWebBuilder.BuildWeb` | 메뉴 `Festa/부하테스트/WebGL 빌드` | `Builds/web` | Development **ON**, 압축 Disabled | 측정 전용 |
| `FestaReleaseBuilder.BuildRelease` | 메뉴 `Festa/배포/배포 빌드` | `Builds/linux-server` + `Builds/web-release` + Docker 이미지 | Development OFF, Brotli+Fallback, ApiConfig Prod 강제 | 로컬 통합 |
| `CiBuild.Build` | `-batchmode -executeMethod Festa.EditorTools.CiBuild.Build -festaTarget webgl / linux-server / all` | `Builds/webgl` + `Builds/linux-server` | 위와 동일, 모달 없음 | **실사용 경로 (사람·CI 공통)** |

**사람이 실제로 하는 순서** (KHS 작업일지 09-08·09-09 실측):

1. develop 최신 체크아웃 · Play 종료 · 컴파일 에러 0
2. 열린 **Windows** 에디터에서 `CiBuild.BuildWeb` 직접 호출 — `FestaReleaseBuilder` 는 모달이 뜨고 Docker 까지 굽는다
3. 빌드 중 Assets 무접촉 (T-126)
4. 18.2 ~ 61.9분 대기. 최근 3회 29.6 / 25.8 / **18.2**분 — IL2CPP 캐시 재사용 여부가 지배한다
5. 산출물 147~151 MB

`CiBuild` 가 자동으로 잡는 것. 전부 과거에 실제로 터진 사고의 방어물이다.

| 방어 | 사고 |
|---|---|
| 출력 폴더 비우기 | T-241 — 09-07 산출물이 09-08 빌드에 섞였다 |
| WebGL 타깃 **선행** 전환 | `-316` — 안 하면 `Mobile_RPAsset` 누락 |
| ApiConfig Prod 강제 + `finally` 복원 | `-419` Mock 유출 / T-237 Prod 잔류로 측정 한 벌 폐기 |
| Brotli + Decompression Fallback 강제 | `-474` — `#127` 에서 인프라가 반려한 사유 |
| `manifest.json` 생성 | `-417` — 없으면 FE 가 해시 파일명을 못 찾아 404 |
| 산출물 4종 존재 검증 | 파이프라인 실패를 앞당긴다 |

**source SHA 는 WebGL 산출물에 없다.** `FestaReleaseBuilder.SourceStamp()` 가 `<sha>` / `<sha>-dirty`
를 만들지만 그 값은 **Docker 이미지 태그에만** 쓰인다.

### 커밋된 WebGL PlayerSettings (실측)

```
webGLCompressionFormat: 2      # Brotli — 커밋값도 Brotli다
webGLDecompressionFallback: 0  # CiBuild 가 빌드 시 true 로 강제한다
webGLNameFilesAsHashes: 1      # FE 가 파일명을 추측할 수 없는 이유
webGLInitialMemorySize: 256
webGLMaximumMemorySize: 2048
webGLLinkerTarget: 1           # wasm
webGLThreadsSupport: 0
insecureHttpOption: 0          # NotAllowed — §6 의 핵심
```

---

## 2. Package Registry 현황

`festa-webgl` Generic Package Registry, project 1443023. 실측 10건 중 `festa-webgl` 8건.

```
 88  festa-webgl  41107ee2  09-07 14:42
 96  festa-webgl  e4ea8c26  09-08 10:02
100  festa-webgl  e30d8d49  09-08 15:52
103  festa-webgl  d1165eb3  09-09 09:46
104  festa-webgl  664bc74e  09-09 11:16   ← 폐기 요청됨
105  festa-webgl  e6e60774  09-09 14:30   ← 폐기 요청됨
106  festa-webgl  6e69c0b6  09-09 17:57
107  festa-webgl  490bde34  09-10 09:13
```

| 항목 | 실측 |
|---|---|
| naming | `festa-webgl` / version = develop 짧은 SHA 8자 / `festa-webgl-release-<sha>.zip` |
| 업로드 | `curl --upload-file` + **개인 PAT**. 8건 전부 `pipeline: null` → `CI_JOB_TOKEN` 아님 |
| 업로드 주체 | 사람 (게임 담당) |
| script/API | **저장소에 0줄.** manifest 가 가리키는 18 엔트리를 손으로 골라 zip |
| 패키지 내부 | **zip 1개뿐.** manifest·provenance 별도 파일 없음 |
| latest / approved 채널 | **없다** |
| 삭제·보존 정책 | **없다.** 폐기는 이슈 코멘트로 요청한다 |
| FE/QA 수령 | 이슈 코멘트의 `curl` + 본인 PAT |
| 검증 | 업로드 후 재다운로드 → SHA256 대조. 8회 전부 사람이 |

### "이 빌드가 어느 source commit 인가" — 자동 판정 불가

1. **산출물 안에 SHA 가 없다.** version 문자열은 GitLab 이 검증하지 않는 자유 텍스트다
2. **`pipeline: null`** — GitLab 이 커밋을 연결하지 않았다
3. **dirty 판정이 없다.** `SourceStamp` 의 `-dirty` 는 `CiBuild` 경로에 아예 없다
4. 대체 사슬(로더 해시 대조)은 **이미 한 번 깨졌다** — `6e69c0b6` 와 `490bde34` 는 로더 해시가
   `7318f297…` 로 **같았고**(코드 동일·데이터만 변경) 데이터 해시로 손으로 갈라야 했다

---

## 3. Jenkins / GitLab CI 구조

### GitLab CI (`.gitlab-ci.yml`)

stages `validate · test · build · mr-status · sync`.
jobs `jira-key-check`(비활성) · `jira-sync-*` 3종 · `mr-status` · `front-test` · `front-build` ·
`back-test` · `back-build`.

- **Unity job 0 · package registry job 0 · deploy job 0**
- pipeline trigger **0**, deploy token **0**, project CI variable **0**, container registry **미사용**
- runner 1개 `festa-ec2-docker-runner` (project_type, online) — **Jenkins 와 같은 EC2**

즉 GitLab CI 로 Unity 를 옮겨도 컴퓨트는 같은 EC2 다. 새 컴퓨트가 생기지 않는다.

### Jenkins

`Jenkinsfile` → agent `linux-docker` → Agent Preflight → Security Preflight → `develop.groovy`.
**develop push 전용**이고 MR 은 GitLab Runner 가 게이트한다.

`develop.groovy` 흐름:

1. `detect-changed-components.sh` → selection
2. component 별 `component.groovy` → `ci/validate` · `ci/test` · `ci/build` · `ci/package`
   - **`game` 은 `node('unity-6000.0.78f1')` 의 고정 workspace `/home/jenkins/agent/unity/workspaces/develop-game` 에서 돈다**
   - `ci/build` game → `festa-unity/ci/build --target all` → **WebGL + Linux Server 를 실제 batchmode 빌드**
   - `ci/package` game → Linux Server **Docker 이미지만**
   - Evidence 단계 주석: *"WebGL/Linux Server outputs stay on the Unity Agent; only candidate identity crosses workspaces"*
3. `build-release-manifest.sh` → `release-manifest.json`
4. `transfer-local-images.sh --export` → 공유 volume → deploy node 에서 `--import`
5. `Deploy Selected Components` → `deployComponents.findAll { it in ['ai','back','front'] }` — **game 제외**
   - game 단독 빌드는 `NO_OP: game deployment remains on the Phase 3 WebGL path` 로 **배포 전에 return**
6. 배포 env 에 `PUBLIC_UNITY_BUILD_BASE=/unity/` 가 **이미 들어 있다**

### 노드 구성 (`infra/jenkins/casc/jenkins.yaml`)

| node | label | executors | mode |
|---|---|---|---|
| `linux-docker` | `linux docker linux-docker` | 1 | EXCLUSIVE |
| `deploy` | `linux docker deploy` | 1 | EXCLUSIVE |
| `unity` | `linux unity unity-6000.0.78f1` | **1** | EXCLUSIVE |

controller 는 `numExecutors: 0`. lockable resource `deploy-dev-game` 이 **이미 선언돼 있다**.

`infra/jenkins/pipelines/unity.groovy` 는 **어떤 Jenkinsfile 도 load 하지 않는다.** 정적 테스트
2개(`test-foundation.sh`, `us1-part-pipelines.sh`)만 이 파일을 붙들고 있다 — 파트 브랜치 시절 잔재다.

---

## 4. Unity Agent 현황

| 항목 | 실측 |
|---|---|
| 컨테이너 | `infra/jenkins/agents/compose.yaml` `unity-agent` profile |
| Editor | 호스트 `/opt/unity/editors/6000.0.78f1/Editor/Unity` 를 `:ro` 마운트 |
| 모듈 | WebGL Build Support · Linux Dedicated Server Build Support (Infra 확인 완료, `#124`) |
| 라이선스 | **Unity Personal, 활성화 완료.** `/home/jenkins/.config/unity3d/Unity/licenses/UnityEntitlementLicense.xml`, 영속 volume `unity_home` 안 (JSW 작업일지 09-09) |
| 활성화 방법 | `unity-hub --headless license --activate-all --include-personal`. Personal 자격에 `com.unity.editor.headless` 포함 (`#142` 09-07) |
| 실행 유저 | `jenkins` |
| workspace | 고정 재사용 경로. **build 번호별 새 경로를 쓰다 257 GB 를 먹어 root 309 GB 를 포화시킨 사고 뒤 고정으로 바꿨다** (JSW 09-09) |
| 마운트 | `unity_workspace` · `unity_home` · `/opt/unity:ro` · connection token secret `:ro` · `docker.sock` |
| **`image_transfer` volume** | **마운트되어 있지 않다** — linux-docker·deploy 두 agent 에만 있다 |

라이선스는 더 이상 blocker 가 아니다. 남은 blocker 는 §8·§16 이다.

---

## 5. 현재 WebGL 배포 흐름과 끊긴 지점

```
develop merge
  → Jenkins festa-gitlab-develop/develop (Jenkinsfile)
  → detect-changed-components → 'game'
  → node('unity-6000.0.78f1') 고정 workspace
  → ci/validate · ci/test (EditMode) · ci/build --target all
        └ Builds/webgl 완성                          ← 여기까지 이미 자동이다
  → ci/package → Linux Server Docker 이미지만
  X ① WebGL 산출물이 workspace 에서 버려진다
  X ② release-manifest 의 webglArtifactUri 슬롯이 비어 있다
  X ③ Deploy 필터가 game 을 제외한다 (game 단독이면 배포 전에 return)

  ─── 여기부터 전부 사람 ───
  → Windows 에디터에서 같은 것을 다시 빌드 (18~30분, 자리 지킴)
  → manifest 보고 18 엔트리 손으로 zip
  → PAT 로 Registry 업로드 → 재다운로드 → SHA256 대조
  → #142 에 표 붙여 코멘트
  → Infra 가 PAT 로 다운로드 → SHA 대조
  → /srv/festa/webgl/releases/<v> 전개 → current 심볼릭 전환 → nginx
```

**①②③ 중 어느 것도 Unity 나 빌드의 문제가 아니다. 전부 파이프라인 배선이다.**

---

## 6. 환경별 `/unity` 상태

| 환경 | scheme | FE origin | `/unity/` route | WebGL 실체 | 배치 | rollback |
|---|---|---|---|---|---|---|
| Local (게임) | http | — | — | `Builds/webgl` | 본인 | 재빌드 |
| Local (FE) | http `localhost:5173` | vite | vite proxy `VITE_PROXY_UNITY_TARGET` | 각자 푼 zip (`:8000`) | FE 담당자 | 옛 zip 재전개 |
| **Dev** | **http (EC2 공인 IP)** | `http://<ip>/__dev/front/` | **없다** | **없다** | — | — |
| Demo | https | `https://demo.ssafesta.world` | `alias /srv/festa/webgl/current/` | 심볼릭 대상 | Infra 수동 | `current` 재전환 (실측됨) |
| Prod | **환경 정의 자체가 없다** | — | — | — | — | — |

`infra/environments/config/environments/` 에 `dev.env.example`·`demo.env.example` **둘뿐**이다.
`api.ssafesta.world` 는 demo 의 API 다.

### Dev 의 모순 — `PUBLIC_UNITY_BUILD_BASE=/unity/` 는 주입되는데 받는 곳이 없다

주입 지점 실측 5곳:

```
infra/environments/config/environments/dev.env.example:31   PUBLIC_UNITY_BUILD_BASE=/unity/
infra/environments/compose/dev/front.yaml:17                ${PUBLIC_UNITY_BUILD_BASE:-/unity/}
infra/jenkins/agents/compose.yaml:78                        ${PUBLIC_UNITY_BUILD_BASE:-/unity/}
infra/jenkins/pipelines/develop.groovy:142                  'PUBLIC_UNITY_BUILD_BASE=/unity/'
infra/environments/tests/lib/dev-deploy-fixture.sh:77       PUBLIC_UNITY_BUILD_BASE=/unity/
```

받는 곳: `infra/environments/nginx/sites/dev.conf` 는 `/__dev/front` · `/__dev/api` · `/__dev/ai`
**셋뿐**이다. FE 이미지의 `docker/nginx.conf` 에도 `/unity/` location 이 없다.

경로 계산도 어긋난다. Dev FE 는 `/__dev/front/` 아래 있는데 `unityBuildBase()` 가 준 `/unity/` 는
`normalizeAssetBase` 가 **오리진 기준**으로 절대화하므로 `http://<ip>/unity/` 가 된다 — `/__dev/` 접두가
빠진다. route 를 만들더라도 `/__dev/unity/` 인지 root `/unity/` 인지 **먼저 정해야 한다.**
이 축은 `#163`(Dev `/__dev/api` → root `/api` 정렬 여부)과 같은 축이다.

### Dev 에서 Release WebGL 이 월드까지 갈 수 있는가 — **현재 구조로는 못 간다**

```
festa-unity/ProjectSettings/ProjectSettings.asset:940
  insecureHttpOption: 0        # NotAllowed
```

`#156` 실측 (LJH, 09-09, package 105 `e6e60774`):

| 레이어 | 결과 |
|---|---|
| `window.__FESTA_CONFIG__.apiBaseUrl` 주입 | 성공 |
| `FestaUnityBridge.jslib` → `FestaHostApiBaseUrl` | 성공 |
| `HostRuntimeConfig` → `ApiServices.Init` | 성공 |
| 실제 요청 URL 이 바뀜 | 성공 |
| Unity 가 그 요청을 보냄 | **차단 — `Insecure connection not allowed`** |

원격 브랜치 121개 전부 `insecureHttpOption: 0`, 최초 도입 `d1c20071` 이후 무변경, Editor 스크립트가
이 값을 만지는 곳 0건이다.

지금까지 안 드러난 이유: 릴리스 빌드는 `ForceApiEnvironment(Prod)` 로 `https://api.ssafesta.world`
를 쓰므로 https 라 차단에 안 걸렸다. 평문 http 가 실제로 나가는 경로는 로컬 스택과 **Dev** 뿐이다.

**따라서 Dev 는 scheme 문제부터 풀어야 한다.** §11 참조.

---

## 7. 라이선스 / 대형 asset 실측

| 분류 | 실측 |
|---|---|
| A. Git 추적 중 | **831.1 MB / 4,517 파일** — `_Project` 729.3 · `ithappy` 75.5 · `TextMesh Pro` 11.6 · `300Mind` 5.3 · `Hyper casual cartoon castles` 3.2 · `Palmov Island` 3.2 · `danthaigames` 3.1 |
| B. Git LFS | **0건.** endpoint 는 설정돼 있으나 `git lfs ls-files` 가 비어 있다 |
| C. gitignore | `Assets/Synty/` · `ithappy/**/Render_Pipeline_Convert/*.unitypackage`(59 MB) · `300Mind`·`danthaigames` PSD/Scene(76 MB) · `Builds/` |
| D. 담당자 로컬에만 | Synty Sidekick FREE Starter Pack — 아바타 **제작**시에만 필요. 디렉터리 없음, 씬·프리팹 참조 **0건**. 완성 아바타는 `_Project/Experimental/Sidekick/` 에 자기완결 커밋 |
| E. 외부 다운로드 | 위 Synty + UPM `com.coplaydev.unity-mcp` = `github.com/CoplayDev/unity-mcp.git#main` — **버전 미고정 브랜치** |
| F. 빌드에 포함되는데 source 에 없음 | **0건** |

확장자별: `.png` 350.8 MB(387) · `.fbx` 121.5(370) · `.asset` 119.6(211) · `.tga` 87.0(9) ·
**`.skp` 65.7(2)** · `.anim` 17.5 · `.mp3` 8.4 · `.ttf` 8.1 · `.wav` 6.4

저장소 pack **727.21 MB**. "대형 binary 를 Git history 에 넣지 않음"은 지금부터의 조건이지 현 상태가
아니다. `.skp` 2개는 저장소에 남아 있으나 **씬 참조 0** 이다 (FBX 이관 `bbc266a2`, MR !530).

### 라이선스 근거 (저장소 기록 기준 — 법적 판정 아님)

- 라이선스 **파일** 3건뿐: `TextMesh Pro/…/Roboto-Bold - License.txt` · `_Project/Models/Curtain/license.txt` · `_Project/Resources/Fonts/NotoSansCJK-LICENSE.txt`
- `.meta` 의 `AssetOrigin` 블록에 상품 식별 메타데이터가 100% 있다 — `#148` 회신 대조 결과 ExpoKit = Stand Expo Pack(114974) 22/22 · `ithappy/Casino_Free` = 393074 95/95 · Laptop Free(90315) 1/1, 혼재·누락 0
- FE `tools/assets/source-packs.lock.json` 은 3팩 모두 `licenseClass: UNKNOWN` / `REVIEW_REQUIRED` → `emission: localSpikeOnly`

**기술적으로 분리해야 할 자산 0건.** 그 게이트는 FE 의 **GLB 재배포 경로**에만 걸린다. Unity WebGL
빌드는 이미 demo 에 나가 있고 그 판정은 사용자 명시 PASS 다.

### 다른 PC / CI 에서 재현 가능한가

**구조적으로 가능하다.** 근거:

1. 벤더팩 전량이 Git 에 있다. 빌드에 필요한데 없는 파일 0건
2. Linux CI 가 실제로 Unity 를 돌려 EditMode 45개 중 44 통과했다 (`#155`) — 라이선스·에디터·모듈은 이미 산다
3. 유일한 Windows 종속(`.skp` 임포터)은 FBX 이관으로 제거됐다 — 씬 `.skp` 참조 0

남은 재현 위험 2건:

| 위험 | 상태 |
|---|---|
| `com.coplaydev.unity-mcp` 가 `#main` | 버전 미고정. 임포트 시점마다 달라질 수 있다 |
| FBX 이관 후 Linux CI 결과 | §8 |

---

## 8. Jenkins 최신 빌드 실측

Jenkins UI 는 외부에서 `403` 이라 직접 열지 못했다. **GitLab commit status** 로 판정했다
(`jenkinsci/branch`, target `ci.ssafesta.world/job/festa-gitlab-develop/job/develop/<n>`).
component selection 은 저장소의 `detect-changed-components.sh` 를 로컬에서 같은 입력으로 돌려 얻었다.

| build | head | 선택된 component | 결과 |
|---|---|---|---|
| 29 | `bbc266a2` (**FBX 이관 병합**) | game | **canceled** — 17:42 새 push 로 superseded |
| 33·34 | `6a038721` · `688b9200` | front · front | canceled |
| 35 | `74622fb5` | (없음 — NO_OP) | **success** |
| 36 | `a8aa7d23` | front | **failed** |
| 37~40 | `783d29ed` … `1c1e30a8` | back x4 | **failed** |
| 41 | `7df11f89` | front | **failed** |
| 42 | `b7930a94` | **game** | **failed** |
| 43 | `13bc5123` | front | canceled |
| 44 | `54adf82c` | **game** | canceled |
| 45~51 | … | front / 없음 | canceled |
| 52 | `be2e5312` | **game** | 조사 시점 running |

**판정: FBX 이관 이후 game component 가 끝까지 성공한 Jenkins 빌드는 관측되지 않았다.**

- `bbc266a2`(FBX 이관) 자체는 **취소**됐다. 09-09 17:42 에 새 push 가 들어와 superseded 됐고,
  게임 파트가 `#155` 에서 요청한 "develop 최신으로 다시 돌려 주세요" 는 **회신되지 않았다**
  (`#155` 는 09-09 16:36 이후 코멘트 0, state opened)
- build 36~42 는 component 종류와 무관하게 전부 실패했다. game 전용 결함이라 부를 근거가 없다
- game 단독 빌드(42·44·52)는 `deployComponents` 가 비어 `Deploy` 이전에 return 하므로,
  42 의 실패 지점은 **game CI · Candidate Manifest · Deploy Candidate Receipt** 중 하나다.
  어디인지는 Jenkins 로그 없이 판정할 수 없다

**이 문서는 F안의 전제 ①을 PASS 로 기록하지 않는다.** §16 의 선행조건으로 남긴다.

---

## 9. 후보 A~F 비교

| | 안 | 내용 |
|---|---|---|
| A | 현행 + 업로드 스크립트화 | 빌드는 손으로, zip·업로드·해시대조만 script 1개 |
| B | Unity Editor `Build & Publish` 버튼 | Build → zip → manifest/hash → Registry → CI trigger |
| C | 로컬 build + publisher CLI 가 CI 를 깨움 | B 와 유사, Unity 는 build/package 까지만 |
| D | 전용 runner 완전 자동 | merge → runner → hydrate → batchmode → Registry → deploy |
| **F** | **CI 가 이미 만든 WebGL 을 그 자리에서 release 로 승격** | hydrate·Registry·신규 토큰 전부 불필요 |
| E | Unity Build Automation / DevOps | 외부 SaaS |

**E 배제** — Unity **Personal** 이다. Build Automation 은 Personal 자격에 없다. 831 MB 소스를 외부
SaaS 에 올려야 하고 남은 기간 대비 도입비가 최악이다.
**Git LFS 배제** — 산출물 전달 문제를 안 푼다. history 는 이미 727 MB 라 되돌리는 비용만 크다.
**MinIO/S3 배제** — Registry 가 있고 승격 상태는 `infra/deploy/state/` 가 이미 관리한다.
**Unity Version Control 배제** — SCM 교체는 이 문제와 무관하다.

| 축 | A | B | C | D | **F** | E |
|---|---|---|---|---|---|---|
| Unity 담당자 작업량 | 높음 | 중(버튼 1) | 중(버튼 1) | 0 | **0** | 0 |
| 초기 구현비용 | 낮음 | 중 | 중 | 높음 | **낮음** | 매우 높음 |
| 운영비용 | 높음 | 중 | 중 | 낮음 | **낮음** | 중(구독) |
| 신규 인프라 | 없음 | trigger·token | trigger·token | 없음 | **없음** | SaaS |
| source↔artifact 추적성 | **✗ 사람 라벨** | 중 | 중 | 높음 | **높음** (`scm.commit` 40자) | 중 |
| CI/CD 연계 | 없음 | trigger 필요 | trigger 필요 | 완전 | **이미 연결됨** | webhook |
| FE 자동 소비 | ✗ | 부분 | 부분 | ✅ | **✅** | 부분 |
| rollback | 사람 | 사람 | 부분 | 심볼릭 | **심볼릭(초 단위)** | 부분 |
| credential | 개인 PAT | Project Token | Project Token+trigger | 없음 | **없음** | SaaS 계정 |
| 실패 진단성 | 콘솔+수동 | Unity 콘솔에 갇힘 | 나음 | Jenkins | **Jenkins** | 외부 UI |
| 현재 구조 재사용률 | 20% | 40% | 50% | 85% | **95%** | 5% |

### 비용 대비 제거되는 실제 수작업 (09-09 하루 실측 기준)

| 안 | 제거 | 남음 |
|---|---|---|
| A | zip·업로드·해시대조 (약 10분 x 4회) | **빌드 실행 40분 x 4**, 이슈 코멘트, Infra 배치 |
| B/C | 위 + 통보 자동화 | **빌드 실행 40분 x 4** — 자리를 지켜야 하는 시간이 그대로다 |
| **F** | 위 전부 + 빌드 실행 시간 전부 + Infra 배치 | 릴리스 확인(브라우저 실측)만 |

---

## 10. F안 추천 근거

**B(원클릭)는 가장 비싼 항목을 안 없앤다.** 09-09 에 실제로 든 비용은 zip·업로드가 아니라
**하루 4회의 40분 대기**였고 그것은 버튼을 달아도 그대로다. 사용자가 지적한 대목도 그것이다 —
*"빌드를 몇번이나 하는거야;; 한번에 좀 해 … 한번할때마다 40분씩 날라가는데"*.

**D(전용 runner 완전 자동)는 이미 85% 되어 있다.** runner 는 있고, 라이선스는 활성화됐고,
WebGL 은 실제로 빌드된다. F 는 D 의 남은 15% 다.

기존 자산 재사용 판정:

| 자산 | 판정 |
|---|---|
| `CiBuild.Build` batchmode | 그대로 사용 |
| `festa-unity/ci/{build,test,package}` | 그대로 사용 |
| `component.groovy` game 분기 | 그대로 사용 — 이미 Unity agent 에서 WebGL 을 빌드한다 |
| Jenkins `unity-agent` + Personal 라이선스 영속 | 그대로 사용 |
| `image_transfer` volume + `transfer-local-images.sh` 패턴 | **패턴 재사용** (`.tmp` → `mv -f`, import 후 `rm -f`) |
| release-manifest **`webglArtifactUri`** | **그대로 사용 — 스키마·생성기에 이미 있고 아무도 안 채운다** |
| `package-local-image.sh` (`image-metadata.json`) | 조금 확장 |
| `demo.conf.template` `/srv/festa/webgl/current` | 그대로 사용 |
| `promote-release.sh`·`rollback-release.sh`·`freshness.sh`·`verify-component.sh`·`deploy-component.sh` | 그대로 사용 |
| `deploy-dev-batch.sh` | 그대로 사용 — 이미 `game` 을 유효 component 로 받는다 |
| lockable resource `deploy-dev-game` | **이미 선언돼 있다** |
| `deploy-webgl-release.sh` | **없다 — 신설.** 단 **T022C 로 이미 계획돼 있다** |
| dev nginx `/unity/` | 없다 — 신설 |
| `unity.groovy` | 대체 필요 — 아무도 load 하지 않는다 |
| `FestaReleaseBuilder` 메뉴 | 배포 경로로는 불필요. 로컬 통합 검증용 유지 |
| GitLab pipeline trigger / deploy token | 0개. 안 B/C 를 고를 때만 필요 |

---

## 11. Dev scheme — 근본 해결 비교

Dev 에서 Release WebGL 을 돌리려면 §6 의 `insecureHttpOption: 0` 을 넘어야 한다. 네 축을 비교한다.

| 안 | 효과 | 비용 | 부작용 |
|---|---|---|---|
| **Dev 를 HTTPS 로** | **근본.** Dev 가 Demo/Prod 와 같은 scheme 이 되어 insecure·mixed-content 축이 통째로 사라진다 | Cloudflare DNS 1개 + origin cert. **`world-dev.${ROOT_DOMAIN}` 이 이미 같은 패턴으로 서 있다** | 없음 |
| `insecureHttpOption: 2` (AlwaysAllowed) | Dev 는 통과 | 작음 | **출시 산출물 전체가 평문 http 를 허용하게 된다** |
| `insecureHttpOption: 1` (DevelopmentOnly) | **효과 없음** | — | 릴리스 빌드는 Development OFF 다 |
| same-origin route 만 추가 | **효과 없음** | — | scheme 을 안 바꾼다 |

**판정: Dev HTTPS 가 근본 해결이다. PlayerSettings 는 건드리지 않는다.**
같은 EC2 에 `world-dev.${ROOT_DOMAIN}` TLS vhost 가 이미 있으므로 패턴·인증서 경로가 증명돼 있다.

부차 결정 1건 — Dev 의 WebGL base 를 `/__dev/unity/` 로 둘지 root `/unity/` 로 둘지.
`#163` 의 `/__dev/api` → root `/api` 정렬 질문과 **같은 축**이므로 함께 답하는 것이 맞다.

---

## 12. F안 architecture (제안)

```
develop merge
  → detect-changed-components → 'game'
  → node('unity-6000.0.78f1')                                   [기존]
     ├ ci/validate · ci/test (EditMode)                          [기존]
     ├ ci/build --target all → Builds/webgl + linux-server        [기존]
     └ ci/package
         ├ Linux Server Docker 이미지                             [기존]
         └ Builds/webgl → tar → 공유 volume                       [신규 ①]
              /var/lib/festa-image-transfer/webgl/<releaseId>/webgl.tar
              (.tmp → mv -f, transfer-local-images.sh 와 같은 패턴)
  → manifest.json 에 provenance 필드                              [신규 ②]
  → image-metadata.json.webglArtifactUri + artifactSha256         [신규 ③]
  → build-release-manifest.sh → components[].webglArtifactUri     [기존 슬롯]
  → node('deploy')
     └ deploy-webgl-release.sh                                    [신규 ④ = T022C]
         tar 검증 → releases/<releaseId>/ 전개
         → 구조 검사 → manifest 검사 → sourceCommit 대조 → HTTP smoke
         → PASS → current 심볼릭 원자 전환 → tar 삭제
         → FAIL → current 유지 · known-good 유지 · 신규 release 격리
  → promote-release.sh → target-state.json                        [기존]
  → dev nginx /unity/ (또는 /__dev/unity/)                        [신규 ⑤]

Registry: QA·외부 공유·milestone 아카이브 (주 배포 경로 아님)
```

**신규 파일 1개(`deploy-webgl-release.sh`) · 신규 인프라 0 · 신규 토큰 0 · 기존 구조 재사용 95%.**

### artifact transport 실측 근거

| 항목 | 실측 |
|---|---|
| unity-agent ↔ deploy-agent | **같은 EC2**, 같은 Docker 호스트. named volume 공유 가능 |
| `image_transfer` volume | `image-transfer-init` one-shot 이 `jenkins:jenkins` `0770` 으로 초기화. linux-docker·deploy 에만 마운트 |
| 권한 | unity-agent 는 `jenkins` (쓰기 가능), deploy-agent 는 `root` (읽기 가능) |
| 필요한 변경 | unity-agent volumes 에 `image_transfer` 한 줄 추가 |
| 이동 비용 | tar 147~156 MB. `.unityweb` 가 이미 brotli 라 재압축 이득이 없다 → **무압축 tar** |
| 원자성 | 같은 파일시스템 → `mv -f` rename 이 원자적. 기존 스크립트와 동일 패턴 |
| 동시성 | unity node `numExecutors: 1` `EXCLUSIVE` → Unity 빌드 동시 실행 없음. `<releaseId>` 하위 경로로 세대 격리 |
| workspace cleanup | game CI workspace 는 **고정 재사용 경로**(257 GB 포화 사고 뒤 정책). tar 는 import 후 즉시 `rm -f` |
| 디스크 | 그 volume 은 root fs 에 있다. 사고 이력이 있으므로 **retention 없이 쌓지 않는다** |

### `webglArtifactUri` 사용 가능성

- `specs/infra-001-ci-cd-pipelines/contracts/release-manifest.schema.json` → `components[].webglArtifactUri: {type: string}` 로 **선언돼 있다.** required 는 아니고 `additionalProperties: false` 아래에 명시 등재돼 있다
- `build-release-manifest.sh:41` → component metadata 에 있으면 **그대로 통과시킨다**
- 생산자: **없다** (`package-local-image.sh` 가 안 쓴다)
- 소비자: **없다**
- 기존 소비자들은 `name`·`imageRef`·`contentId`·`sourceCommit` 을 **이름으로** 읽는다
  (`deploy-environment.sh:39-45`, `transfer-local-images.sh:45-62`) → 필드 추가가 기존 경로를 깨지 않는다

**판정: 단순 스키마 잔재가 아니라 의도적으로 열어 둔 슬롯이고, 채우는 것이 안전하다.**

### provenance chain

```
releaseId        release-manifest.releaseId = develop-<sha40>-<buildNumber>
sourceCommit     release-manifest.scm.commit (정규식 ^[0-9a-f]{40}$ 검증)
                 = manifest.json.sourceCommit  (배포 검증에서 대조)
Jenkins build    release-manifest.jenkins.{job,buildNumber,buildUrl}
WebGL artifact   components[game].webglArtifactUri + artifactSha256
deployed current /srv/festa/webgl/current -> releases/<releaseId>
                 target-state.json.currentReleaseId
provenance       provenance.jsonl (release·verification·deployment 3자 releaseId 일치 검증)
```

양방향 판정이 성립한다 — "이 커밋이 어디에 떴나" / "이게 어느 커밋인가".

---

## 13. manifest / provenance 제안

**기존 `manifest.json` 을 확장한다.** 파일을 둘로 나누면 FE 가 둘 다 읽어야 한다.

| 필드 | 자동 취득 | 신뢰 | 검증에서 쓰나 | 판정 |
|---|---|---|---|---|
| `schemaVersion` | 상수 | — | 파서 분기 | 유지 |
| `loaderUrl`/`dataUrl`/`frameworkUrl`/`codeUrl` | `WriteManifest` 기존 | 높음 | FE 소비 + 구조 검사 | 유지 |
| `sourceCommit` | `git rev-parse HEAD` (**40자**) | 높음 | **release-manifest.scm.commit 과 대조** | **필수** |
| `sourceBranch` | `git rev-parse --abbrev-ref` 또는 CI env | 중 | 기록 | 유지 |
| `dirty` | `git status --porcelain` 이 비었는지 | 높음 | **배포 경로는 `true` 면 거부** | **필수** |
| `unityVersion` | `Application.unityVersion` | 높음 | 기록 | 유지 |
| `unityRevision` | `ProjectVersion.txt` 파싱 | 높음 | 기록 (`#124` 재임포트 사고) | 유지 |
| `buildProfile` | `EditorUserBuildSettings.development` 로 판정 | 높음 | **`release` 아니면 배포 거부** | **필수** |
| `apiEnvironment` | `ApiConfig.activeEnvironment` | 높음 | 기록 (`-419`·T-237) | 유지 |
| `compression` | `PlayerSettings.WebGL.*` | 높음 | 기록 (`-474`) | 유지 |
| `builtAt` | `DateTime.UtcNow` | 높음 | 기록 | 유지 |
| ~~`artifactSha256`~~ | — | — | — | **manifest 에서 제외** (아래) |
| ~~`registryHash`~~ | — | — | — | **제거** — 정의가 없고 `artifactSha256` 과 중복 |
| ~~`assetSetHash`~~ | — | — | — | **제거** — 831 MB 해싱 비용 대비 `sourceCommit`+`dirty` 가 같은 답을 준다 |

**`artifactSha256` 계산 범위**: manifest 는 자기 자신을 담은 산출물의 해시를 담을 수 없다.
그래서 해시는 **CI 가 만드는 `webgl.tar`** 에 대해 계산하고 `image-metadata.json` →
`release-manifest` 쪽에 싣는다. `manifest.json` 에는 넣지 않는다.

**dirty 정책**: 로컬 빌드는 `dirty: true` 를 허용하되 **배포 경로는 거부**한다. CI 는 detached
checkout 이라 정상 상황에서 항상 `false` 이므로, `true` 면 workspace 오염 신호다.

`sourceCommit` 은 **40자**로 통일한다. Registry version 의 8자 관행이 §2 의 판정 불가를 만들었다.

---

## 14. 배포 검증 / rollback

```
artifact export
→ releases/<releaseId>/ 전개
→ 구조 검사        index.html · manifest.json · Build/ · TemplateData/
→ manifest 검사    loader/data/framework/code 4종이 실제 존재
→ source SHA 검사  manifest.sourceCommit == release-manifest.scm.commit, dirty == false
→ HTTP smoke      manifest 200 + Content-Type application/json
                  .wasm.unityweb 200 + Content-Encoding: br
                  index.html 200
→ PASS → current 원자 전환 (같은 파일시스템 symlink swap)
```

FAIL 시:

```
current 유지 · known-good 유지 · 신규 release 는 releases/ 에 격리 보존 · tar 는 남기지 않는다
```

rollback 은 `current` 를 이전 `releases/<id>` 로 재전환하는 것이다. **초 단위**이고 demo 에서 이미
실측된 동작이다 (JSW 작업일지 09-07). `rollback-release.sh` 의 재시도 금지 marker 와
`decide-recovery.sh` 판정이 그대로 붙는다.

retention 제안 — **새 상태 저장소를 만들지 않는다.** 상태의 정본은 이미 있는
`target-state.json`(`currentReleaseId` / `knownGoodReleaseId` / `previousKnownGoodReleaseId`)이다.

```
releases/ 에 남기는 것: current · known-good · previous-known-good · 그 밖 최근 2개 (총 5개 이하)
transfer tar: import 후 즉시 삭제
```

디스크 사고 이력(`unity_workspace` 257 GB)이 있으므로 retention 은 선택이 아니다.

---

## 15. NOW / NEXT / LATER

### NOW (선행조건 충족 시 1~2일)

| # | 작업 | 소유 |
|---|---|---|
| 1 | `unity-agent` 에 `image_transfer` volume 마운트 | Infra |
| 2 | game `ci/package` 가 `Builds/webgl` 를 `<releaseId>` 하위로 tar export | Infra |
| 3 | `deploy-webgl-release.sh` 신설 (= **T022C**) | Infra |
| 4 | `image-metadata.json` 에 `webglArtifactUri` + `artifactSha256` | Infra |
| 5 | `CiBuild.BuildWeb` → `manifest.json` provenance 확장 | **Game** |
| 6 | Dev `/unity/` route + **Dev HTTPS** | Infra |
| 7 | `develop.groovy` 의 game 필터를 WebGL 경로로 해제 (verify 통과 시에만 `current` 전환) | Infra |

### NEXT

8. `Festa/배포/Registry 업로드` — QA·외부 공유·CI 정지 시 **fallback** 으로만. Node helper + Project Access Token(`write_package_registry`)
9. FE 로컬 `VITE_PROXY_UNITY_TARGET` → Dev `/unity/` (Dev HTTPS 선결)
10. `releases/` retention · rollback 스크립트화
11. `com.coplaydev.unity-mcp` 커밋 고정
12. `unity.groovy` 정리 (삭제 또는 develop 경로 흡수)

### LATER

13. Registry 를 아카이브/외부 공유 전용으로 격하
14. Addressables — **지금은 이득이 없다.** 빌드 18~30분의 지배 요인은 IL2CPP 이고 Addressables 는 그것을 줄이지 않는다. `.data` 149.5 MB 를 원격으로 빼면 현재 진입 5.2초가 깨진다. 부스 콘텐츠는 이미 `BoothRuntime` 로 런타임 조립돼 분리가 필요한 축은 이미 분리돼 있다
15. `.skp` 2개(65.7 MB) history 정리 — 지금 건드리면 위험하다

---

## 16. 미확정 전제

**F안을 실행하기 전에 반드시 닫아야 하는 것.**

| # | 전제 | 상태 | 닫는 방법 |
|---|---|---|---|
| ① | FBX 이관 후 Linux Jenkins 가 game CI 를 **끝까지 성공**시킨다 | **미확정.** FBX 병합 빌드(#29)는 superseded 로 취소됐고 이후 game 빌드 42 는 실패, 44 는 취소. `#155` 회신 0 | Jenkins 에서 develop 최신으로 game 빌드 1회 완주 — Infra 만 할 수 있다 |
| ② | build 36~42 의 공통 실패 원인이 Unity 가 아니다 | **미확정.** component 종류와 무관하게 전부 실패한 정황은 있으나 Jenkins 로그를 못 봤다 (외부 403) | Jenkins 로그 — Infra |
| ③ | Dev 에서 Release WebGL 이 월드까지 간다 | **현재 구조로는 불가.** `insecureHttpOption: 0` + Dev 평문 HTTP | Dev HTTPS (§11) |
| ④ | Dev WebGL base 를 `/__dev/unity/` 로 할지 root `/unity/` 로 할지 | **미결.** `#163` 의 `/api` 정렬 질문과 같은 축 | Infra 결정 |
| ⑤ | `image_transfer` volume 을 WebGL 에도 쓸지, 전용 volume 을 팔지 | **미결** | Infra 결정 |
| ⑥ | `releases/` 와 transfer tar 의 retention 값 | **미결** | Infra 결정 |
| ⑦ | Registry 를 fallback 으로 남길지 완전 폐기할지 | **미결.** 이 문서는 **남기는 쪽**을 권고한다 | 팀 합의 |

**이 문서는 ①~③이 닫히기 전에는 F안을 착수 가능 상태로 선언하지 않는다.**

---

## 17. 근거 목록

| 근거 | 위치 |
|---|---|
| Unity 빌드 진입점 | `festa-unity/Assets/_Project/Scripts/Editor/{CiBuild,FestaReleaseBuilder,FestaWebBuilder}.cs` |
| CI adapter | `festa-unity/ci/{build,test,package}` · `ci/{build,package,lib.sh}` |
| Jenkins | `Jenkinsfile` · `infra/jenkins/pipelines/{develop,component,unity,provenance}.groovy` · `infra/jenkins/casc/jenkins.yaml` · `infra/jenkins/agents/compose.yaml` |
| 배포 스크립트 | `infra/deploy/scripts/*.sh` · `infra/jenkins/scripts/*.sh` |
| 계약 스키마 | `specs/infra-001-ci-cd-pipelines/contracts/release-manifest.schema.json` |
| 기존 계획 | `specs/infra-001-ci-cd-pipelines/tasks.md` T022A~T022F |
| nginx | `infra/environments/nginx/sites/{dev.conf,demo.conf.template,world-dev.conf.template}` |
| PlayerSettings | `festa-unity/ProjectSettings/ProjectSettings.asset:795-830, 940` |
| Registry 실측 | GitLab API `projects/1443023/packages` |
| Jenkins 빌드 실측 | GitLab API `repository/commits/<sha>/statuses` + 로컬 `detect-changed-components.sh` |
| 협업 병목 | `docs/KHS/24_작업일지.md` 09-08~09-10 · GitLab `#127`·`#142`·`#148`·`#155`·`#156` |
| Infra 이력 | `docs/JSW/24_작업일지.md` 09-07~09-09 |
