# 2026-09-14 릴리스 `1da79150` — 빌드·패키징 완료, 배포 대기

## 요약

| | |
|---|---|
| 빌드 | ✅ Succeeded (144 MB, 1228s, 에러 0) |
| 패키징 | ✅ `festa-webgl-release-1da79150.zip` 151,240,309 B |
| 업로드 | ✅ Package Registry 201, **재다운로드 해시 일치** |
| 배포 | ⏸ **대기** — Jenkins 자격증명이 작업공간에 없다 |
| 라이브 | `e9f2067e` (09-12 빌드) 그대로 |

## 빌드

```
sourceCommit   1da79150bd2d2c9a6fc1c9f985b85efba031d51a (develop)
unityVersion   6000.0.78f1 / ec8a99a872be
buildProfile   release
apiEnvironment Prod
compression    brotli+fallback
builtAt        2026-09-13T15:32:06Z
dirty          true
```

`dirty: true` 는 폰트 아틀라스(`ChalkboardKR_SDF`)와 `QualitySettings` 한 줄이 작업 트리에 남은
것이고 [T-259](../../25_트러블슈팅.md) 로 이미 기록된 사안이다. **코드 변경분은 전부 develop 에 있다.**

### 빌드가 한 번 실패했다

첫 시도가 `Mono.Cecil.AssemblyResolutionException: nunit.framework, Version=3.5.0.0` 으로 실패했다
(426 MB 지점, 에러 1). `Tests/EditMode/` 에 asmdef 가 없어 테스트가 기본 어셈블리로 들어가면서
NUnit 이 플레이어 빌드에 끌려온 것이다. `!810` 으로 고친 뒤 성공했다.

## 패키지

```
festa-webgl/1da79150/festa-webgl-release-1da79150.zip
SHA256  cc8bd079b468455b9d9bfe078db895289f31381957a580fc71dec0131e5a00e1
크기     151,240,309 B
```

- 업로드 201, 체크섬 파일 201
- **재다운로드 151,240,309 B / SHA256 동일** — 바이트 일치
- `index.html`·`manifest.json`·`Build/`·`TemplateData/` 포함, `probe.html` 제외

## 배포가 안 된 이유

`GITLAB_PACKAGE_TOKEN`·`JENKINS_URL`·`JENKINS_USER`·`JENKINS_API_TOKEN` 이 이 작업공간에 없다.
패키지 업로드는 `GITLAB_TOKEN` 으로 됐지만 **`festa-webgl-package-deploy` 잡은 못 돌린다.**

인프라에 GitLab #188 로 넘겼다 — `RELEASE_ID=1da79150`,
`ARTIFACT_SHA256=cc8bd079…5a00e1`.

## ⚠ 라이브에 사물함 흰 웅덩이가 있다

현재 배포본 `e9f2067e` 에는 **FXAA 를 켠 커밋(`73409a27`)이 들어 있고 그것을 고친 HDR 수정은 없다.**
즉 지금 데모에서 사물함 벽에 가까이 가면 화면 절반이 하얗게 탄다([T-267](../../25_트러블슈팅.md#t-267)).
`1da79150` 배포가 이 증상을 없앤다 — 배포 우선순위의 근거다.

## 현재 배포본(`e9f2067e`) 실측

크롬(데스크톱 앱 Browser 패널), 게스트 경로.

| 단계 | 결과 |
|---|---|
| 랜딩 → 로그인 화면 | ✅ |
| 게스트로 둘러보기 → `/app/world` | ✅ 캔버스 1920×1080 생성 |
| 월드 로딩 | ✅ "축제장을 불러오고 있어요" → "월드를 준비하고 있습니다" → 진입 |
| 접속·스폰 | ✅ `IsClient=True PlayerObject=True` |
| 이용 안내 오버레이 | ✅ 표시 (`WORLD_GUIDE`), ESC 로 닫힘 |

### 픽셀 검증은 못 했다

창이 뒤로 가면 `requestAnimationFrame` 이 멈춘다. 실제로 rAF 콜백이 3초 동안 **한 번도 오지 않았고**,
그 상태의 캡처는 단색으로만 나온다. **그 그림으로 렌더링을 판정하면 안 된다** — 메모리에 적어 둔
"숨은 탭이 WebGL 을 죽인다" 와 같은 함정이다. 화면 품질 판정은 창을 앞에 둔 상태에서 다시 해야 한다.

### 곁가지 — 런타임 API 오류

```
[HttpBoothApiClient] GET /api/v1/booth-slots/7/layouts/published → HTTP 409
```

게시된 레이아웃이 없는 슬롯의 정상 응답일 수 있어 단정하지 않는다. 다만 클라이언트 콘솔에
에러로 남으므로 BE 와 한 번 맞춰 볼 항목이다.

## 이번 릴리스 내용

| MR | |
|---|---|
| `!798` | 자는 직원 기립(제자리·발 접지·응시) · 안내데스크 말풍선 · 벽 뒤 이름표 가림 |
| `!800` | 사물함 흰 웅덩이 해소(T-267) · develop 컴파일 복구 |
| `!804` | 누운 사람 충돌 범위를 자세에 맞춤 (17.50 → 7.17) |
| `!808` | Linux headless 승인 접속 smoke runner (#185) |
| `!809` | 빌드 어댑터 `linux-smoke` 타깃 |
| `!810` | asmdef 밖 EditMode 테스트가 WebGL 빌드를 깨뜨리던 것 |
