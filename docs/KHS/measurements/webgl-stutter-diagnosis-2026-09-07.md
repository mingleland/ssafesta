# SSAFESTA 영상 끊김 진단 — 2026-09-07

> 게임 파트 로컬 진단 기록. 관련 이슈: S15P21A604-484.
> 영상 관찰·현재 소스 관찰·타 작업의 병합 상태를 구분한다. 코드 개선 성과를 대신 보고하는 문서가 아니다.

## 문서화 시점의 후속 확인

FE DPR 1.5 상한은 [MR !398](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/merge_requests/398)로 **2026-09-07 14:00:20 KST develop에 병합**됐다. GitLab API에서 병합 상태와 `loader.ts` diff를 확인했다. Squash SHA는 `d5c8cba67369e9f0754e4a922201c53c94f7b20d`다. 아래 DPR=2 관찰은 진단 당시 로컬 실행에 관한 것으로, 최신 develop에 상한 구현이 없다는 뜻이 아니다. 다음 작업은 해당 변경이 적용된 FE로 재측정하는 것이다. 이 문서화 과정에서 소스를 동기화하거나 빌드하지 않았다.

문서화 도중 별도 작업의 정정 커밋 `0a653378`에서 공용 작업일지와 트러블슈팅 T-133의 GPU 시간 오독 정정을 확인했다. 이후 공유 작업공간의 브랜치가 바뀌었으므로 현재 체크아웃에 그 커밋이 포함됐다고 가정하지 않는다. 이번 정정의 상세 근거는 [KHS T-236](../25_트러블슈팅.md#t-236)에 고정한다. 백버퍼 픽셀 수와 URP 내부 렌더 픽셀 수는 다르다. renderScale 0.8이 적용된 경우 내부 렌더 면적은 백버퍼 대비 약 0.64배이므로, DPR=2의 백버퍼 4배를 모든 렌더 패스가 4배라는 뜻으로 해석하지 않는다.

판정: 클라이언트 프레임 불안정이 확인된다. 렌더 부하와 반복 GC가 우선 조사 대상이며, 카메라 추적 방식이 체감 가감속을 키울 수 있다. 네트워크 단독 원인 또는 GPU 단독 병목으로 확정할 근거는 없다. 코드·씬·설정은 직접 수정하지 않았다. Unity Play 검사 후 Edit Mode로 복귀했다.

## 이번에 확인한 근거

- 제공 MP4: 2860×1522, 30 FPS 녹화, 20.97초, 디코딩 629프레임. 원본 30프레임 간격으로 HUD를 읽었다. 녹화 해상도는 Unity 내부 렌더 해상도와 동일하다고 가정하지 않았다.
- HUD의 오른쪽 ms 값은 GPU 시간이 아니라 **최근 1초 창의 최악 프레임 시간**이다. `PerfHud.cs:204`에서 `_worstMs`를 출력하며, 이 클래스에는 GPU 시간 계측이 없다. 기존 작업일지의 “GPU 49→27ms”가 이 HUD를 근거로 했다면 항목 이름을 정정해야 한다. 별도 GPU trace가 있다면 그것으로 재검증해야 한다.
- 영상의 FPS·평균 ms는 FE의 ‘조작 안내’ 버튼에 가려져 읽을 수 없다. `3961.0ms`는 영상 전체에서 변하지 않는 세션 누적 최악값이며, 이 영상 중 4초 정지가 발생했다는 증거가 아니다.

| 영상 시각 | 1초 창 최악 프레임 | 해당 순간 드로우콜 | 삼각형 | RTT | GC/1s |
|---|---:|---:|---:|---:|---:|
| 4초 | 50ms | 833 | 1,366,012 | 43ms | 0 |
| 6초 | 50ms | 727 | 1,307,264 | 25ms | 0 |
| 9초 | 31ms | 580 | 815,318 | 51ms | 1 |
| 11초 | 26ms | 541 | 711,603 | 40ms | 1 |
| 16초 | 42ms | 299 | 463,551 | 41ms | 0 |
| 18초 | 50ms | 1,401 | 1,278,104 | 36ms | 0 |

최악 프레임과 드로우콜은 집계 시점이 다르다. 위 표로 둘의 정확한 상관계수를 계산하거나 GPU 병목을 확정하면 안 된다. 다만 방향과 위치에 따라 렌더 작업량이 크게 달라지고, 낮은 RTT에서도 긴 프레임이 발생함을 확인할 수 있다. GC/1s=1이 여러 구간에서 반복된다. GC=0인 구간에도 50ms가 있어 GC 하나로 모두 설명할 수 없다.

녹화 프레임의 중앙 ROI를 축소해 비교하면 628개 인접 쌍 중 129개가 평균 절대 RGB 차이 0.2/255 미만이었다. 약 4.50–4.60초, 6.40–6.50초, 14.23–14.33초에는 화면 갱신 간격이 약 100ms인 구간이 잡힌다. 이는 **녹화에 담긴 화면 정체**이며, 녹화기·합성기 영향이 포함되므로 Unity FPS로 환산하지 않았다.

## 현재 실행 환경과 코드

- 진단 당시 소스 HEAD: `2fc538d5`, 브랜치 `perf/S15P21A604-484-additional-lights`. 영상의 정확한 빌드 SHA는 미확인. 문서화 시점에는 별도 작업의 문서 정정 커밋 `0a653378`이 관찰됐으므로 두 시점의 HEAD를 혼동하지 않는다.
- 현재 열린 브라우저: CSS canvas 597×506, 실제 canvas 1194×1012, DPR=2. 화면 면적 대비 백버퍼 픽셀 4배. 로더는 `a9dc734ab27ed22f0e164d5bc675e3ff.loader.js`이다.
- 진단 당시 로컬 `festa-frontend/src/unity/host/loader.ts:40`의 createUnityInstance config에는 devicePixelRatio 상한이 없었다. 최신 develop의 MR !398에는 상한이 있다.
- Unity MCP Play 관찰: 활성 실시간 Light 104개, Lightmap 0장, WorldLightBudget 컴포넌트 0개, 활성 Renderer 1,153개. 이 값은 현재 Editor main 씬 값이며 과거 문서의 233개와 다르다.
- WebGL 기본 Quality는 Mobile. 현재 Mobile_RPAsset은 PerVertex/추가광원 4개/renderScale 0.8로 **이미 변경되어 있다**. Editor는 PC, PerPixel/4개/renderScale 1.0을 사용하므로 Editor FPS로 WebGL 개선을 판정할 수 없다. 영상 빌드의 실제 파이프라인 적용값은 별도 확인이 필요하다.
- 현재 로컬 게임 서버 `festa-world:834ea911`: Docker CPU 29.79%, 메모리 313.9MiB 한 번 관찰. 서버 포화 증거는 없으나 프레임별 spike 또는 네트워크 지터를 배제하는 측정은 아니다.
- Network tick=30. 이동 NetworkTransform은 client authoritative, 보간 켬. Owner 이동은 서버 응답을 기다리지 않고 `PlayerMovement.Update` → `CharacterController.Move(velocity * Time.deltaTime)`로 실행한다. 영상의 자기 캐릭터 끊김을 원격 위치 보간 문제로 곧바로 분류하면 안 된다.
- `PlayerCameraFollow.cs:176,193`은 `Lerp(..., k * deltaTime)`을 사용한다. 프레임이 16.7→50ms로 늘 때 follow 계수 k=8 기준 0.134→0.4로 변한다. 같은 속도의 이동도 더 큰 간격으로 표시되고 카메라 추적량도 달라져 느려졌다 따라잡는 느낌이 날 수 있다. 정확한 기여도는 위치·카메라 trace가 필요하다.

## 해결 우선순위

1. **측정 표시부터 바로잡기.** FPS/평균/p95/p99/최악/CPU/GPU/GC/RTT를 분리한다. HUD와 조작 안내가 겹치지 않게 하고, 빌드 SHA·백버퍼·품질·측정 장소를 기록한다. 현재 0.0KB/s 표시는 실제 통신 없음의 증거로 사용하지 않는다.
2. **병합된 FE DPR 상한의 실제 적용과 효과를 시험하기.** MR !398의 1.5 상한이 포함된 FE인지 확인하고 동일 조건에서 DPR 2/1.5/1.0을 비교한다. DPR 2→1.5는 같은 화면에서 백버퍼 픽셀 수 43.75% 감소, 2→1은 75% 감소다. 이는 픽셀 감소율이지 FPS 증가율이 아니다. 현재 renderScale 0.8도 함께 기록하고 중복으로 과도하게 낮추지 않는다. Screen.SetResolution을 반복 호출하는 방식은 사용하지 않는다.
3. **Development HUD/할당 분리하기.** PerfHud는 표시 중 매 OnGUI마다 AppendFormat과 ToString을 실행한다. 텍스트를 통계 갱신 시점에만 만들고 표시에서는 캐시를 사용한다. F2 패널·Development Console 비용도 분리한다. WebGL에서는 incremental GC를 켜는 것만으로 해결할 수 없다. 실제 GC pause와 allocation call stack으로 기여도를 확인한다.
4. **무거운 방향의 월드 렌더 비용 줄이기.** 영상의 16→18초에서 회전에 따라 드로우콜이 299→1,401로 달라진다. 같은 각도에서 Frame Debugger/CPU trace로 부스 장식·원거리 나무·재질 분할·투명 이펙트 비용을 확인한 뒤 LOD/거리 컬링/배칭을 적용한다. 현재 부착되지 않은 WorldLightBudget은 추가 전후 밝기·깜빡임을 검증할 후보이지 바로 켜도 되는 확정 해결책이 아니다. PerVertex/4 설정은 이미 있으므로 동일 변경을 다시 제안하지 않는다. 개활지 전체 오클루전 베이크는 우선순위가 낮다.
5. **프레임 불안정을 줄인 후 카메라 추적 다듬기.** 추적 Lerp의 계수를 `1 - exp(-k * dt)` 같은 시간 기준 보간으로 바꾸는 후보를 비교한다. 충돌 시 즉시 당김·최종 시야선 검사·텔레포트 Snap은 보존한다. 이동을 smoothDeltaTime으로 바꾸거나 최대 delta를 낮춰 느리게 만드는 방식은 해결책으로 삼지 않는다.
6. **원격 캐릭터가 따로 끊기면 네트워크 검사.** 로컬 프레임이 안정된 상태에서 2클라이언트로 수신 간격·서버 tick·RTT·위치 보정 횟수를 기록한다. 로컬 도보와 상대 캐릭터를 동시에 비교한다. 지금 바로 tick 30→60 변경이나 보간 비활성화를 하는 것은 근거가 부족하다.

## 변경 후 합격 판정 방법

- 동일 WebGL 빌드·카메라·위치·이동 경로로 A→B→A. 각 조건은 10초 워밍업 뒤 60초 기록한다.
- 순서: HUD on/off → DPR 2/1.5/1 → 렌더 후보 하나씩 → 2클라이언트 → 10/40클라이언트. 변수는 한 번에 하나만 바꾼다.
- 제안 기준: 목표 60FPS라면 평균뿐 아니라 p95 프레임 20ms 이하, p99 33.3ms 이하, 100ms 이상 정지 없음. 기기별로 충족 불가하면 안정적인 30FPS 품질 단계를 별도로 합의한다. 이 기준은 이번 제안이며 기존 팀 합격선을 임의 변경한 것이 아니다.
- 브라우저가 보이는 상태에서도 프레임이 약 1초에 한 번이면 측정을 무효 처리한다. 이번 live HUD on/off 검사에서도 첫 값 1008ms와 도중 위치 변경이 섞여 **A/B 성능 개선 결론에서 제외**했다. HUD 표시 상태는 복원했다.
- 진단 실행 중에는 코드 변경, Unity 빌드, 게시 부스 데이터 변경, Jira/GitLab 갱신을 하지 않았다. 후속 문서 정리는 별도 작업이다. 정식 CPU/GPU trace와 통제된 개선 A/B는 아직 없다. ‘DPR 한 줄이면 60FPS 보장’, ‘GPU만 문제’, ‘네트워크 완전 정상’이라고 단정할 단계가 아니다.

## 근거 파일과 공식 문서

- 사용자 원본 영상: `20260907-0451-09.5012907.mp4`. 원본 영상은 저장소에 복제하지 않고 진단에 사용한 추출 증거를 보관한다.
- 영상 HUD: [video-hud-exact.png](../verify/2026-09-07-stutter/video-hud-exact.png)
- 주요 시각 HUD: [video-hud-keyframes.png](../verify/2026-09-07-stutter/video-hud-keyframes.png)
- 전체 동선: [video-contact.jpg](../verify/2026-09-07-stutter/video-contact.jpg) — 1fps 축약, 각 시각은 근사값. 수치 표는 위 exact 추출 기준.
- 프레임 차이 원자료: [video-differences.json](../verify/2026-09-07-stutter/video-differences.json)
- [Unity 6 Web Canvas DPI 설정](https://docs.unity3d.com/6000.0/Documentation/Manual/webgl-canvas-size.html)
- [Unity 6 Web 메모리](https://docs.unity3d.com/6000.0/Documentation/Manual/webgl-memory.html)
- [Unity 6 Incremental GC 지원 범위](https://docs.unity3d.com/6000.0/Documentation/Manual/performance-incremental-garbage-collection.html)
