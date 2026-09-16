# 전체화면 자동진입이 만든 잔존 UI (S15P21A604-733) — 원인 분석 및 수정 계획

> 이전 조사(`docs/superpowers/plans/2026-09-15-esc-followup-bugfixes.md` Part B, `fix/S15P21A604-450-tab-focus-lock` 브랜치, frontend-hold 대상)는 "Task B1(실 Unity WebGL 빌드 환경 재현) 없이는 어떤 분기도 코드로 옮기지 않는다"는 게이트로 막혀 있었다. 이 문서는 그 Task B1을 실제로 완료하고 얻은 확정 원인과, 그에 따른 Part B2(수정) 계획이다.

**Goal:** 로그인 → 캐릭터 커스터마이징 → "월드 입장" 클릭 직후, 화면 하단(정확히는 커스터마이징 좌우 패널 전체)에 방금 있던 커스터마이징 UI가 로딩 dim 뒤로 1~2초간 비쳐 보이는 잔존 현상을 없앤다.

**Spec:** Jira [S15P21A604-733](https://ssafy.atlassian.net/browse/S15P21A604-733) — "전체화면, 완료 조건에 P15 acceptance가 남아 있음". P7(전체화면 자동 진입, S15P21A604-733 자체 구현)이 만든 회귀로 보고됨.

## 1. 재현 증거

2026-09-16, 실 데모(`https://demo.ssafesta.world`)에서 Google 로그인 실 계정으로 재현. 사용자가 녹화한 화면 녹화(`화면 녹화 중 2026-09-16 105057.mp4`, 20초)를 `ffmpeg`(scratchpad에 `ffmpeg-static` npm 패키지로 확보)로 15fps 프레임 추출해 프레임 단위로 확인했다 — 로컬에 실 Unity WebGL 빌드가 없어(`.env`의 `VITE_UNITY_BUILD_BASE` 공백) 이 데모가 유일한 실 환경이었다.

타임라인(영상 기준 9.3초~15.2초 구간):

1. 커스터마이징 화면에서 "월드 입장" 클릭.
2. **같은 프레임에서** 브라우저가 실제 Fullscreen API로 전환됨(브라우저 주소창·탭바 사라짐, "demo.ssafesta.world · 전체 화면을 종료하려면 Esc 키를 누르세요" 배너 노출 — 즉 `enterFullscreen()`이 이번엔 실제로 성공했다. 이전 시도들에서 관찰한 "API can only be initiated by a user gesture" 실패와 달리, 실 브라우저·실 로그인 클릭 경로에서는 gesture 판정을 통과했다).
3. 그 직후 React 쪽 로딩 dim("축제장을 불러오고 있어요")이 뜨는데, **배경에 방금 있던 커스터마이징 좌우 패널(의상 선택 / 나만의 캐릭터)이 그대로 비쳐 보인다.** 이 상태가 최소 1~2초 지속.
4. 이후 Unity 로고 → 로딩 화면 → 실제 로비 복도로 정상 전환. dim 총 지속 시간 약 5~6초, 최종적으로는 정상적으로 걷힘(무한정 안 걷히는 것은 이번 녹화에서는 확인 안 됨 — 제보의 두 증상 중 "하단 잔존 UI"만 이번에 확정 재현했고, "로비 진입 시 안 걷히는 dim"은 이번 1회 재현에서는 5~6초 후 정상 종료됐다. 별도로 더 오래 걸리거나 안 걷히는 사례가 재현되면 이 문서에 추가한다).

## 2. 원인

- `festa-unity/Assets/_Project/Scripts/World/Avatar/Lobby/CharacterLobbyController.cs:819` `EnterWorld()`가 `Festa.Integration.WorldLoadSignal.NotifyWorldLoadStart()`(React 쪽 fullscreen 자동 진입 트리거, `festa-frontend/src/shared/ui/fullscreen.ts`의 `consumeFullscreenIntent()`+`enterFullscreen()` 경로를 깨움)와 `SceneManager.LoadScene(...)`를 같은 함수·같은 tick에서 연달아 부른다.
- `festa-frontend/src/unity/host/unityHostStatus.css:17` `.uh-status`(로딩 dim)는 `background: rgba(10, 14, 28, 0.72)` — **의도적으로 완전 불투명이 아니다**(주석: "진행 표시는 월드 클릭을 가로채지 않는다"). 평소(이미 월드에 들어와 있는 상태에서의 로딩)엔 그 뒤로 비치는 것이 진행 중인 같은 화면이라 문제가 없다.
- 그런데 `requestFullscreen()`이 실제로 성공하면 브라우저가 OS 레벨 전체화면 전환(창 크기·레이아웃 변경)을 처리하는 동안 **Unity WebGL의 렌더 루프(`requestAnimationFrame` 기반)가 잠깐 멎는다.** 이 사이 WebGL 캔버스에는 마지막으로 그려진 프레임 — 즉 아직 언로드되지 않은 **커스터마이징 씬의 마지막 프레임** — 이 그대로 남아 있고, 그 위에 반투명 dim이 얹히면서 "커스터마이징 UI가 잔존한다"는 인상을 만든다.
- P7(전체화면 자동 진입) 도입 전에는 "월드 입장" 시점에 이런 리사이즈 자체가 없었으므로 렌더 루프가 멎지 않았고, 이 잔존 프레임이 노출될 틈이 없었다 — 그래서 이번 작업이 "회귀"로 보고된 것과 정합된다.
- 계획 문서(`2026-09-15-esc-followup-bugfixes.md`)가 세워뒀던 5개 분기 중 **분기 A(DOM 크기와 canvas 백버퍼 불일치)에 가장 가깝다** — 다만 정확히는 "크기 불일치"가 아니라 "전체화면 전환 중 렌더 루프 정지로 인한 프레임 정체"다.

## 3. 수정 방침과 배제한 대안

- **`Screen.SetResolution`이나 canvas `width`/`height` 직접 변경은 쓰지 않는다** — `loader.ts`에 이미 백버퍼 반복 재생성·렌더 루프 정지를 일으켰다는 실측 기록이 있다(기존 계획 문서 인용).
- **fullscreen 요청 자체를 씬 로드 완료 후로 미루는 것은 채택하지 않는다** — Fullscreen API는 사용자 제스처 안에서만 허용되고, 그 유효 구간이 매우 짧다(이전 조사에서 "API can only be initiated by a user gesture" 실패를 실측). 씬 로드가 끝난 뒤(수 초 후)로 미루면 제스처 유효기간을 벗어나 fullscreen 자체가 실패할 확률이 커진다 — 그러면 지금보다 더 나쁜 회귀(원래 하던 자동 전체화면이 아예 안 됨)를 만든다.
- **채택: `.uh-status`가 `preparing-world` 단계일 때만 배경을 완전 불투명으로 바꾼다.** 이 단계는 아직 사용자가 월드를 클릭할 수 있는 상태가 아니다(월드 자체가 아직 없다 — Unity가 씬을 막 전환하는 중) — 그러니 기존 주석이 말한 "월드 클릭을 가로채지 않는다"는 설계 의도와 충돌하지 않는다. 다른 단계(`booting`, `waiting-gate`, `failed`)는 지금 그대로 반투명 유지.

## 4. Part B2 — 구현 태스크

**Files:**
- Modify: `festa-frontend/src/unity/host/unityHostStatus.css` (`.uh-status` 규칙 또는 `preparing-world` 전용 modifier 클래스 추가)
- Modify: `festa-frontend/src/unity/host/UnityHost.tsx` (상태별 className 분기 — 이미 `status` state가 있으면 그 값을 클래스에 반영하는 지점만 확인, 없으면 최소 추가)
- Test: 기존 `UnityHost` 관련 단위 테스트 디렉터리에 렌더 결과 className 검증 케이스 추가(실 브라우저 fullscreen/렌더 루프 정지 자체는 jsdom으로 재현 불가 — 이 테스트는 "preparing-world일 때 불투명 클래스가 붙는다"는 것만 고정한다)

### Task 1: `preparing-world` dim 불투명화

- [ ] **Step 1**: `UnityHost.tsx`에서 `status === 'preparing-world'`일 때 `.uh-status`에 `uh-status--opaque`(가칭) 클래스가 추가되는지 확인하는 실패하는 테스트를 먼저 작성한다.
- [ ] **Step 2**: 테스트 실패 확인.
- [ ] **Step 3**: `unityHostStatus.css`에 `.uh-status--opaque { background: #0a0e1c; }`(또는 기존 색상의 알파 1.0 버전) 추가, `UnityHost.tsx`에서 해당 단계일 때 클래스를 붙인다.
- [ ] **Step 4**: 테스트 통과 확인.
- [ ] **Step 5**: 회귀 확인 — `cd festa-frontend && npx vitest run` 관련 스위트, `npm run build`.
- [ ] **Step 6**: 실 브라우저(데모 또는 실 Unity 빌드) 재확인 — 로그인→커스터마이징→월드입장에서 잔존 UI가 더는 안 비치는지, 다른 단계(booting 등)의 반투명 동작은 그대로인지.
- [ ] **Step 7**: 커밋 — `docs/KGH/24_작업일지.md` 갱신 후 `fix(unity): 월드 전환 중 로딩 dim을 불투명하게 — 잔존 UI 제거 (S15P21A604-733)`.

### Task 2: "안 걷히는 dim" 잔여 조사 (조건부)

이번 재현(1회)에서는 dim이 5~6초 후 정상적으로 걷혔다 — "무한정 안 걷힘"은 재현하지 못했다. Task 1 배포 후에도 이 증상이 다시 제보되면, `UnityHost.tsx`의 `onWorldGateReady` 구독과 `setStatus('ready')` 전이를 실측 로그(`docs/KGH/25_트러블슈팅.md` T-24 참고)로 다시 조사한다 — 지금 단계에서는 코드를 만지지 않는다(재현 없는 추측성 수정 금지 원칙 유지).

## Self-Review 체크리스트

- **Spec coverage**: 잔존 UI 증상만 확정 재현·수정 대상으로 삼았다. "안 걷히는 dim"은 미재현 상태로 명시하고 별도 조건부 태스크로 분리했다 — 증거 없는 두 증상을 뭉뚱그려 고치지 않는다.
- **회귀 위험**: 변경 범위는 CSS 클래스 하나 + 상태 분기 하나. Unity C# 미변경, fullscreen 트리거 타이밍 미변경 — 이미 동작하는 자동 전체화면 자체를 건드리지 않는다.
- **검증 한계**: 렌더 루프 정지·실제 fullscreen 전환은 jsdom 단위 테스트로 재현 불가 — 최종 확인은 실 브라우저(데모 또는 실 Unity 빌드) 필수. 이 게이트를 통과하기 전에는 Jira 완료 처리하지 않는다.
