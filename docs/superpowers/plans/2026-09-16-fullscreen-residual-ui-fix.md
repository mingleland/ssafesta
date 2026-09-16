# 전체화면 전환 구간의 잔존 UI (S15P21A604-733) — 원인 분석 및 수정 계획

> 이전 조사(`docs/superpowers/plans/2026-09-15-esc-followup-bugfixes.md` Part B)는 실 Unity WebGL 재현 전에는 코드를 수정하지 않는다는 게이트를 세웠다. 이 문서는 실 데모 영상으로 잔존 UI를 재현하고, 영상과 현재 코드에서 직접 확인되는 범위만 원인으로 확정한 Part B2 계획이다.

**Goal:** 로그인 → 캐릭터 커스터마이징 → "월드 입장" 클릭 직후, 로딩 dim 뒤로 커스터마이징 UI가 약 1.4초간 비쳐 보이는 현상을 없앤다.

**Spec:** Jira [S15P21A604-733](https://ssafy.atlassian.net/browse/S15P21A604-733) — P1·P6·P7·P8을 함께 다루며 P15 자동 진입 acceptance가 남아 있다. 이번 계획은 P7 경로에서 발견된 잔존 UI 회귀만 수정하며, 이 수정만으로 Jira 전체를 완료 처리하지 않는다.

## 1. 재현 증거

2026-09-16 실 데모(`https://demo.ssafesta.world`)를 Google 로그인 실 계정으로 녹화한 원본 영상(20.20초, 2880×1704, 30fps)을 직접 확인했다. 원본은 저장소 밖에 두고, 리뷰 가능한 대표 프레임과 메타데이터를 아래에 보존한다.

- 근거: [`docs/KGH/verify/2026-09-16-fullscreen-residual-ui/README.md`](../../KGH/verify/2026-09-16-fullscreen-residual-ui/README.md)
- 전환 직전 10.4초: 브라우저 창 상태에서 커스터마이징 화면
- 10.8초: 전체화면 안내 배너와 `preparing-world` dim이 나타났지만 그 아래에는 직전 커스터마이징 프레임이 남아 있음
- 12.2초: 같은 dim 아래의 canvas가 다음 씬 프레임으로 바뀜
- 이후 Unity 로딩 화면과 실제 월드로 정상 전환하며, 이번 영상에서는 dim이 무한정 남는 증상은 재현되지 않음

영상으로 확정할 수 없는 것:

- Fullscreen API가 Unity의 `requestAnimationFrame`을 중단했는지
- 전체화면 자동 진입이 없을 때도 같은 잔존 프레임이 보이는지

따라서 rAF 정지나 Fullscreen과의 단독 인과관계는 확정 원인으로 쓰지 않는다. 필요하면 같은 배포본에서 fullscreen intent 유무를 바꾼 A/B 재현과 프레임 간격 계측으로 별도 확인한다. 이 추가 조사는 아래 수정의 선행 조건이 아니다.

## 2. 확정 원인

현재 코드와 영상으로 확인되는 시각적 누출 경로는 다음과 같다.

1. `CharacterLobbyController.EnterWorld()`가 `WorldLoadSignal.NotifyWorldLoadStart()`를 호출한 직후 `SceneManager.LoadScene(...)`를 실행한다.
2. React는 `onWorldLoadStart`를 받으면 즉시 `status`를 `preparing-world`로 바꾸고 `.uh-status`를 canvas 위에 렌더한다.
3. `.uh-status` 배경은 `rgba(10, 14, 28, 0.72)`라 완전히 가리지 않는다.
4. Unity가 다음 씬의 첫 프레임을 그리기 전까지 canvas에는 직전 커스터마이징 프레임이 남는다. 영상의 10.8초와 12.2초 프레임 비교로 dim은 유지된 채 배경 canvas만 바뀌는 것이 확인된다.

즉 직접 수정할 원인은 **전환 전용 `preparing-world` 상태가 이전 씬을 보여줄 필요가 없는데도 공용 반투명 상태층을 사용해 직전 canvas 프레임을 노출하는 것**이다. 전체화면 전환이 노출 시간을 늘렸을 가능성은 남지만 이번 영상만으로는 확정하지 않는다.

## 3. 수정 방침과 배제한 대안

- `Screen.SetResolution`이나 canvas `width`/`height` 직접 변경은 사용하지 않는다. `loader.ts`에 백버퍼 반복 재생성·렌더 루프 정지 실측 기록이 있고, 이번 증상은 크기 조작 없이 상태층에서 차단할 수 있다.
- fullscreen 요청을 씬 로드 완료 뒤로 미루지 않는다. Fullscreen API의 사용자 제스처 조건을 벗어나 자동 진입 자체를 깨뜨릴 수 있다.
- **채택:** `preparing-world` 상태층만 완전 불투명하게 만든다. 이 상태는 이전 씬을 계속 보여주는 제품 상태가 아니라 다음 월드를 준비하는 전환 상태다.
- 배경 불투명도와 `pointer-events:none`은 별개다. 기존 클릭 통과 동작과 실패·재시도 버튼의 pointer-events 규칙은 변경하지 않는다.
- `booting`, 재접속, 실패 상태의 기존 반투명 표현은 유지한다.

## 4. Part B2 — 구현 태스크

**브랜치 게이트:** 현재 `docs/S15P21A604-733-fullscreen-root-cause`는 계획 문서 전용이다. 이 문서가 develop에 반영된 뒤 최신 `gitlab/develop`에서 `fix/S15P21A604-733-fullscreen-residual-ui`를 새로 분기해 구현한다.

**Files:**

- Modify: `festa-frontend/src/unity/host/unityHostStatus.css`
- Modify: `festa-frontend/src/unity/host/UnityHost.tsx`
- Test: `festa-frontend/src/unity/host/__tests__/unit/unityHostPreparing.test.tsx`

### Task 1: `preparing-world` dim 불투명화

- [ ] **Step 1 — 실패 테스트:** 기존 `unityHostPreparing.test.tsx`의 로드 시작 테스트에 `uh-status--opaque` 클래스 적용을 검증한다. booting 상태에는 해당 클래스가 없다는 케이스도 고정한다.
- [ ] **Step 2 — 실패 확인:** `cd festa-frontend && npx vitest run src/unity/host/__tests__/unit/unityHostPreparing.test.tsx`
- [ ] **Step 3 — 최소 구현:** `preparing-world` 분기의 `<div>`에만 `className="uh-status uh-status--opaque"`를 지정하고, CSS에 `.uh-status--opaque { background: #0a0e1c; }`를 추가한다. 공용 상태 매퍼나 새 컴포넌트는 만들지 않는다.
- [ ] **Step 4 — 대상 테스트:** `npx vitest run src/unity/host/__tests__/unit/unityHostPreparing.test.tsx`
- [ ] **Step 5 — 전체 회귀:** `npm test`, `npm run lint`, `npm run build`를 모두 통과한다.
- [ ] **Step 6 — 실 브라우저 검증:** 동일 동선에서 커스터마이징 UI가 dim 뒤로 보이지 않는지 확인한다. booting·재접속·실패 상태의 기존 표현과 수동 전체화면 토글도 함께 확인한다. 수정 후 대표 프레임을 기존 verify 폴더에 추가한다.
- [ ] **Step 7 — 기록·커밋:** `docs/24_작업일지.md`를 갱신하고 `fix(front): 월드 전환 중 이전 씬이 비치지 않도록 dim 불투명화 (S15P21A604-733)`로 커밋한다.

### Task 2: "안 걷히는 dim" 잔여 조사 (조건부)

이번 영상에서는 dim이 정상적으로 해제됐다. 이후 무한 대기가 다시 재현될 때만 별도 문제로 다룬다.

- `onWorldGateReady` 송신 → `events.ts` 수신 → `UnityHost.tsx`의 `setStatus('ready')` 순서에서 처음 끊긴 지점을 실측한다.
- 재현 즉시 `docs/25_트러블슈팅.md`에 새 T-번호로 증상·원인 조사 상태를 등록한다.
- 재현 전에는 타임아웃이나 강제 `ready` 전이를 추가하지 않는다.

## Self-Review 체크리스트

- **증거 수준:** 영상이 보여 주는 이전 canvas 프레임 노출만 확정 원인으로 삼고, rAF 정지와 Fullscreen 단독 인과관계는 미확정으로 분리했다.
- **범위:** CSS 클래스 하나와 상태 분기 하나만 변경한다. Unity C#과 fullscreen 타이밍은 건드리지 않는다.
- **검증:** 클래스 단위 테스트와 전체 FE 게이트 뒤 실 브라우저 시각 검증을 수행한다.
- **Jira 완료:** 회귀 검증 결과는 Jira -733에 기록하되, P15를 포함한 이슈 전체 acceptance가 끝나기 전에는 `Closes S15P21A604-733`을 사용하지 않는다.
