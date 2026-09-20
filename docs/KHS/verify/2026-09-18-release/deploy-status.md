# 2026-09-18 릴리스 `eb2e0874` — 빌드·패키징·배포 완료

## 요약

| | |
|---|---|
| 빌드 | ✅ Linux 서버 149 MB(1.1분) + WebGL 91.6 MB(20.3분), 총 22.4분, 에러 0 |
| 작업 트리 | ✅ `dirty=false` — 지난 릴리스의 오염이 이번에는 없다 |
| 패키징 | ✅ `festa-webgl-release-eb2e0874.zip` 95,716,845 B |
| 업로드 | ✅ Package Registry 201, **재다운로드 바이트·해시 일치** |
| 배포 | ✅ Jenkins `festa-webgl-package-deploy` **#25 SUCCESS** |
| 라이브 | ✅ `demo.ssafesta.world/unity/manifest.json` = `eb2e0874` |
| 전용 서버 | ⏸ 이미지 업로드까지 완료, **적용은 인프라 대기** (#142) |

## 빌드

```
sourceCommit   eb2e0874d35abf84c10410c26b715b872b449d2f (develop)
sourceBranch   develop
unityVersion   6000.0.78f1 / ec8a99a872be
buildProfile   release
apiEnvironment Prod
compression    brotli+fallback
dirty          false
builtAt        2026-09-17T16:54:26Z
```

빌드 전에 작업 워크트리를 `test/S15P21A604-196-final-webgl-integration`(develop −1,300 커밋)에서 `develop` 으로 되돌리고 추적 파일을 정본에 맞췄다. 그래서 이번에는 `dirty=false` 다.

## 산출물

```
festa-webgl/eb2e0874/festa-webgl-release-eb2e0874.zip
  95,716,845 B  sha256 6e9b9ffb27526cfdf38a68877b54544ace61760288a4a820437baae641193520
  엔트리 18개 — index.html · manifest.json · Build/ · TemplateData/ (probe.html 없음)

festa-world/eb2e0874/festa-world-eb2e0874.tar.gz
  97,455,189 B  sha256 f3908d4f80543113be62350469490379ac942bd7b3e76da82ba23953232586da
  image id sha256:e4a95b39242a7eb4e0bbda30e304cb569137682acf0f8601aca0602577d16a8a
```

재다운로드 95,716,845 B / SHA256 동일 — 바이트 일치.

## 번들 크기 — #196 이 달라졌다

| | 이전 `e9f2067e` | 이번 `eb2e0874` |
|---|---:|---:|
| `.data.unityweb` | 142,059,056 B | **86,395,313 B** |
| `.wasm.unityweb` | — | 9,427,899 B |
| framework + loader | — | 196,865 B |
| 합계 | — | **96,020,577 B (91.6 MB)** |

`.data` 기준 39% 줄었다. **다만 이번 릴리스에 에셋 감축을 목적으로 넣은 변경은 없다** — 그 사이 병합된 다른 작업의 부수 효과로 보이지만 원인은 특정하지 않았다. 단정하지 않는다.

25 Mbps 회선에서 `.data` 전송 시간이 약 45초 → 약 28초로 내려간다. 30초 게이트에 여전히 여유가 크지 않다 — 엣지 캐시(#226)가 붙어야 두 번째 방문부터 사라지는 문제다.

## 이번 릴리스 내용 (game)

| 이슈 | |
|---|---|
| S15P21A604-884 | 부스 전시 패널 영상 재생(전광판) — 시제품을 정식 반영 |
| S15P21A604-886 | 아바타 표시 상한 — 먼 아바타 렌더러를 꺼 드로우콜 절감 |
| S15P21A604-892 | 표시 상한을 프레임 여유에 맞춰 자동 조절 (기본은 제한 없음) |
| S15P21A604-890 | 커스터마이징 저장 칸 겹침 해소 · 칸에 외형 미리보기 |

직전 develop 에 있던 09-16~17 게임 작업(말풍선 패널, 부스 점유 판정, 서버 부하 계측, 미니맵 hold, 로그인 문구 제거, 하이스트라이커 이동 잠금, 부스 미리보기 등)도 이 빌드에 처음 실린다.

## 배포 경로

```
zip → Package Registry (festa-webgl/eb2e0874/)
    → Jenkins festa-webgl-package-deploy (RELEASE_ID, ARTIFACT_SHA256)
    → demo.ssafesta.world/unity/
```

`index.html` · `manifest.json` · loader · data · framework · wasm · `TemplateData/style.css` 전부 HTTP 200 확인.

## 남은 확인

- **전용 서버를 같은 커밋으로 올려야 한다.** 09-14 에 두 컴포넌트가 어긋나 이틀간 증상이 남은 전례가 있다 (#142 에 인수 요청 게시).
- 아바타 표시 상한·자동 조절의 40인 실측은 이 빌드에서 해야 한다. 상태 문자열에 현재 상한과 평활 프레임 시간이 나온다.
- 전광판은 WebGL 에서만 보인다. 부스 프로젝트에 `videoUrl` 이 등록돼 있어야 재생된다.
- 화면 품질 판정은 **창을 앞에 둔 상태**에서 한다. 숨은 탭에서는 `requestAnimationFrame` 이 멈춰 캡처가 단색으로 나온다.

