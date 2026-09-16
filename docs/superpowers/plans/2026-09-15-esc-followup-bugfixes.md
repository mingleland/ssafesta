# ESC 후속 버그수정 (S15P21A604-450 tab 잠금 · S15P21A604-733 전체화면 잔존) Implementation Plan

> Steps use checkbox (`- [ ]`) syntax for tracking. **Part B(-733)는 통상적인 Task 목록이 아니라 조사 계획이다** — 실 Unity WebGL 빌드 환경 없이는 원인을 확정할 수 없어, 재현·관측·판별을 먼저 수행한다. Part A(-450)만 지금 바로 실행 가능한 TDD 태스크다.

**Goal:** (A) 브라우저 Tab 키가 DOM 포커스를 옮겨 Unity 캔버스 focus 를 빼앗는 것을 막아 Unity 자체 미니맵 토글이 정상 동작하게 한다. (B) 전체화면 자동 진입(S15P21A604-733) 이후 캐릭터 커스터마이징 진입 시 하단 잔존 UI·로비 진입 시 안 걷히는 dim 버그의 원인을 좁힌다.

**Architecture:** (A) `WorldPage.tsx`의 기존 window keydown 패턴을 따르되, Tab 기본 동작은 **월드가 주인이고 실제 Unity canvas가 포커스를 쥔 경우에만** 막는다. HUD·오버레이 등 DOM 요소가 포커스를 가진 경우 브라우저의 키보드 탐색을 보존한다. 새 store·새 판정 계층은 만들지 않는다. (B) 전체화면 전후의 DOM 크기, canvas 표시 크기·백버퍼, Unity 화면 크기, 생명주기 신호를 같은 타임라인에서 관측해 수정 위치를 먼저 확정한다.

**Tech Stack:** React 19, TypeScript, Vite, Vitest + @testing-library/react (Part A). Unity 6 WebGL, C# (Part B 조사 대상).

**Spec:** Jira [S15P21A604-450](https://ssafy.atlassian.net/browse/S15P21A604-450)(기존 입력 소유권 작업의 후속 Tab 회귀) · [S15P21A604-733](https://ssafy.atlassian.net/browse/S15P21A604-733)(전체화면, 완료 조건에 P15 acceptance가 남아 있음). 두 티켓 모두 기존 이슈다.

## Global Constraints

- 브랜치: 두 항목을 각자 독립 브랜치로 — `fix/S15P21A604-450-tab-focus-lock`, `fix/S15P21A604-733-fullscreen-artifacts`. develop 머지가 일시 중단돼 있으므로 최신 `gitlab/develop`이 아니라 임시 통합 브랜치 `frontend-hold`(develop 최신에서 분기해 이미 push됨, S15P21A604-798도 여기로 MR !940을 냈다)에서 분기하고, MR 대상도 `frontend-hold`로 한다. develop 재개 후 `frontend-hold`를 develop으로 올리는 건 별도 후속.
- 커밋 메시지: `type(scope): 한국어 요약 (S15P21A604-450)` / `(S15P21A604-733)` — 매 커밋에 이슈 키.
- 테스트: `cd festa-frontend && npx vitest run <path>`. 최종 게이트: `npm test`, `npm run lint`, `npm run build`.
- Part B는 코드를 먼저 고치지 않는다 — 원인 확정 전 추측성 수정은 회귀만 늘린다(systematic-debugging 원칙). Task B1(재현)이 선행 조건이다.
- 매 태스크 커밋 전 `docs/KGH/24_작업일지.md` 갱신, 문제 발견 시(해결 여부 무관) `docs/KGH/25_트러블슈팅.md`에 T-번호 등록(헌법 29조).

## 착수 전 게이트

- [ ] `git fetch gitlab` 후 `frontend-hold`(develop 아님 — develop 머지 일시 중단 중)에서 항목별 독립 브랜치를 만든다.
- [ ] MR 대상이 `frontend-hold`인 채로는 `.gitlab-ci.yml`의 workflow rule이 파이프라인을 만들지 않는다(`develop` 또는 `ai|back|front|game` 정확히 이 이름일 때만 생성 — S15P21A604-798 MR !940에서 이미 확인된 문제). 이 계획의 MR을 열기 전에 그 해결 방법(예: `front`로 retarget, 또는 `.gitlab-ci.yml` 룰 확장)이 정해졌는지 먼저 확인한다 — 안 정해졌으면 착수만 하고 MR은 미루거나 사용자에게 다시 확인한다.
- [ ] Jira -450에 Tab 포커스 탈취의 재현 절차와 완료 조건(미니맵 정상 토글, DOM Tab 탐색 유지)을 코멘트로 남긴다.
- [ ] Part B는 실 Unity WebGL 빌드(`VITE_UNITY_BUILD_BASE` 설정 + 정적 서빙 중인 빌드 산출물)와 실 백엔드가 필요하다 — `VITE_USE_MOCK=true` mock 모드에서는 `WorldSurface`가 `UnityHost`가 아니라 `StaticMockWorldSurface`로 렌더돼 이 버그 자체가 재현되지 않는다(`festa-frontend/src/shared/config/unity.ts:29-32`, `VITE_MOCK_WORLD=false`로 오버라이드해야 실 Unity 캔버스가 뜬다). 이 환경이 없으면 Task B1을 시작하지 않는다.

---

## Part A — S15P21A604-450: Tab 키가 브라우저 포커스를 뺏어 Unity 미니맵을 막는 문제

### Task A1: Tab keydown 잠금

**Files:**
- Modify: `festa-frontend/src/pages/world/WorldPage.tsx` (import 49행, Enter 판정자 effect 다음)
- Test: `festa-frontend/src/pages/world/__tests__/unit/worldPageTabLock.test.tsx` (신규)

**Interfaces:**
- Consumes: `getWorldScreen()` from `features/world/model/worldScreen`(이미 이 파일이 `closeTopScreen`·`openManagement`·`openMenu`로 같은 모듈을 쓰고 있다 — import 목록에 이름만 추가)
- Produces: 없음 (DOM 레벨 부작용만)

- [ ] **Step 1: 실패하는 테스트 작성**

```tsx
// festa-frontend/src/pages/world/__tests__/unit/worldPageTabLock.test.tsx
// @vitest-environment jsdom
// Tab 키 잠금 (S15P21A604-450) — 브라우저 기본 동작(포커스 이동)이 Unity 캔버스 focus 를
// 빼앗으면 Unity 자신의 Tab 미니맵 토글이 keydown 을 못 받는다. 월드가 주인이고 실제 canvas가
// 포커스를 쥔 경우에만 브라우저 기본 동작을 막는다. HUD·오버레이 등 DOM 요소의 Tab 탐색은 보존한다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { closeOverlay } from '../../../../shared/types/overlay';
import { __resetGameClientUiForTests } from '../../../../features/world/model/gameClientUi';
import { getWorldScreen } from '../../../../features/world/model/worldScreen';
import { __resetWorldUiStateForTests } from '../../../../unity/bridge/worldUiState';

const getReadyUnityInstance = vi.fn(() => null);
vi.mock('../../../../unity/host/sessionManager', () => ({
  getReadyUnityInstance: () => getReadyUnityInstance(),
}));
vi.mock('../../../../unity/host/worldUiBridge', () => ({ requestExitWorldUi: vi.fn() }));
vi.mock('../../../../features/world/ui/WorldSurface.select', () => ({
  IS_MOCK_WORLD: true,
  WorldSurface: () => <div data-testid="world-surface" />,
}));
vi.mock('../../../../features/overlay/OverlayHost', () => ({ OverlayHost: () => null }));
vi.mock('../../../../features/booth/ui/BoothManagementOverlay', () => ({
  BoothManagementOverlay: () => <div data-testid="management" />,
}));
vi.mock('../../../../features/booth/ui/ManagementPanelHost', () => ({
  ManagementPanelHost: () => <div data-testid="management-panel" />,
}));
vi.mock('../../../../features/world/ui/WorldHud', () => ({ WorldHud: () => null }));
vi.mock('../../../../features/world/ui/GameMenu', () => ({ GameMenu: () => <div data-testid="game-menu" /> }));

function dispatchTab(target: EventTarget): boolean {
  const event = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true });
  act(() => { target.dispatchEvent(event); });
  return event.defaultPrevented;
}

const pressEscape = () =>
  act(() => { window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' })); });

beforeEach(() => {
  closeOverlay();
  __resetGameClientUiForTests();
  __resetWorldUiStateForTests();
});
afterEach(() => {
  cleanup();
  document.getElementById('unity-canvas')?.remove();
  closeOverlay();
  __resetGameClientUiForTests();
  __resetWorldUiStateForTests();
});

async function renderWorld() {
  const { WorldPage } = await import('../../WorldPage');
  return render(
    <MemoryRouter initialEntries={['/app/world']}>
      <WorldPage />
    </MemoryRouter>,
  );
}

describe('Tab 키 잠금 (-450)', () => {
  it('월드가 주인이고 canvas가 focus를 쥐면 Tab 기본 동작을 막는다', async () => {
    await renderWorld();
    const canvas = document.createElement('canvas');
    canvas.id = 'unity-canvas';
    canvas.tabIndex = -1;
    document.body.appendChild(canvas);
    canvas.focus();
    expect(getWorldScreen()).toBe('world');
    expect(document.activeElement).toBe(canvas);
    expect(dispatchTab(canvas)).toBe(true);
    canvas.remove();
  });

  it('Game Menu 가 열려 있으면 막지 않는다 — 메뉴 안에서는 Tab 으로 항목 이동이 정상이다', async () => {
    await renderWorld();
    const canvas = document.createElement('canvas');
    canvas.id = 'unity-canvas';
    canvas.tabIndex = -1;
    document.body.appendChild(canvas);
    canvas.focus();
    pressEscape();
    expect(getWorldScreen()).toBe('menu');
    expect(dispatchTab(canvas)).toBe(false);
    canvas.remove();
  });

  it('월드가 주인이어도 DOM 버튼의 Tab 탐색은 막지 않는다', async () => {
    await renderWorld();
    const button = document.createElement('button');
    document.body.appendChild(button);
    button.focus();
    expect(dispatchTab(button)).toBe(false);
    button.remove();
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `cd festa-frontend && npx vitest run src/pages/world/__tests__/unit/worldPageTabLock.test.tsx`
Expected: FAIL — 첫 번째 케이스가 `false`(preventDefault 안 됨)를 돌려줘 `toBe(true)`와 어긋난다. 아직 Tab 리스너가 없다.

- [ ] **Step 3: 구현**

`festa-frontend/src/pages/world/WorldPage.tsx:49`의 import에 `getWorldScreen` 추가:

```typescript
import { closeTopScreen, getWorldScreen, openManagement, openMenu } from '../../features/world/model/worldScreen';
```

Enter 판정자 effect(195행 `}, []);` 직후, `return (` 앞에 새 effect 추가:

```tsx
  // Tab 잠금 (S15P21A604-450) — 브라우저 기본 동작은 Tab 에서 다음 포커스 가능 요소로 옮긴다.
  // Unity 6 WebGL 은 키보드 타깃을 canvas 로 잡으므로(captureAllKeyboardInput=false, !279),
  // 포커스가 캔버스를 벗어나면 그 뒤 Tab keydown 이 Unity 에 안 들어가 자체 미니맵 토글이
  // 죽는다. 실제 canvas가 키보드 타깃일 때만 막는다. HUD·오버레이 등 DOM 요소에서는
  // 접근성을 위해 브라우저의 Tab 탐색을 그대로 둔다.
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key !== 'Tab') return;
      if (getWorldScreen() !== 'world') return;
      if (e.target !== document.getElementById('unity-canvas')) return;
      e.preventDefault();
    }
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);
```

- [ ] **Step 4: 통과 확인**

Run: `cd festa-frontend && npx vitest run src/pages/world/__tests__/unit/worldPageTabLock.test.tsx`
Expected: PASS

- [ ] **Step 5: 회귀 확인**

Run: `cd festa-frontend && npx vitest run src/pages/world/__tests__/unit/worldPageEscLayering.test.tsx src/pages/world/__tests__/unit/worldPageTabLock.test.tsx`
Expected: PASS 전부 (ESC·Enter 판정자에 영향 없음을 확인)

Run: `cd festa-frontend && npm test && npm run lint && npm run build`
Expected: 테스트 실패 0, lint 오류 0, build 성공

- [ ] **Step 6: 커밋**

```bash
git add festa-frontend/src/pages/world/WorldPage.tsx festa-frontend/src/pages/world/__tests__/unit/worldPageTabLock.test.tsx docs/KGH/24_작업일지.md
git commit -m "fix(world): Tab 키가 브라우저 포커스를 뺏어 Unity 미니맵을 막던 것 수정 (S15P21A604-450)"
```

- [ ] **Step 7: 실 브라우저 확인 (mock World 로는 재현 불가)**

`VITE_USE_MOCK=true VITE_MOCK_WORLD=false`로 dev 서버를 띄우고(실 Unity 빌드·백엔드 필요, 위 게이트 참고) `/app/world`에서 다음을 확인한다.

1. canvas가 포커스를 가진 상태에서 Tab을 눌러도 브라우저 UI로 포커스가 이동하지 않고 Unity 미니맵이 열린다.
2. HUD 버튼으로 키보드 포커스를 옮긴 뒤에는 Tab·Shift+Tab으로 버튼 사이를 이동할 수 있다.
3. Game Menu·채팅 입력·오버레이에서도 Tab 탐색이 유지된다.

이 환경이 없으면 MR 설명에 미검증 사실을 남기고 `Closes S15P21A604-450`을 사용하지 않는다. Jira 완료는 위 확인 뒤에만 한다.

---

## Part B — S15P21A604-733: 전체화면 진입 이후 잔존 UI·안 걷히는 dim

### 정적 분석으로 확인한 것과 아직 남은 가설

1. `.uh-root`/`.uh-status`는 `position:absolute; inset:0`, `world-scene`은 `position:fixed; inset:0`이라 `100vh` 캐시 가설은 제외한다. 다만 dim이 남는 원인은 CSS가 아니라 `status === 'preparing-world'`가 유지되는 상태 전이일 수 있다.
2. `AvatarCustomizationHud.Awake()`는 `!Application.isEditor`일 때 컴포넌트를 비활성화한다. WebGL 데모·배포 화면의 잔존 UI 후보에서 제외한다.
3. `CharacterLobbyController.UpdateResponsiveLayout()`은 매 프레임 호출되고 화면 크기가 바뀌면 레이아웃을 다시 계산한다. 하지만 Fullscreen 이후 Unity의 `Screen.width/height` 자체가 갱신되지 않는 경우는 아직 배제할 수 없다.
4. `loader.ts`에는 `Screen.SetResolution`과 canvas `width/height` 직접 변경이 각각 백버퍼 반복 재생성·렌더 루프 정지를 일으켰다는 실측 기록이 있다. 이 두 방법은 수정 후보로 사용하지 않는다.

남은 가설은 **브라우저 DOM과 Unity가 관측하는 화면 크기의 불일치**, **`onWorldGateReady` 미수신 또는 React 상태 전이 누락**, **브라우저 자체 전체화면 안내 배너**다. 실 브라우저+실 Unity 빌드에서 구분한다.

### Task B1: 실 환경 재현 + 원인 판별

**전제:** 착수 전 게이트의 실 Unity 빌드·백엔드 확보.

- [ ] **Step 1: 전체화면 자동 진입 경로 재확인**

`festa-frontend/src/unity/host/UnityHost.tsx:158-165`(`subscribeWorldLoadStart` 콜백)를 열어, `enterFullscreen()`이 `preparing-world` 진입과 **같은 tick**에 불린다는 것을 확인한다(이미 코드로 확인함 — 재현 시 타이밍 가설의 기준점).

- [ ] **Step 2: 재현 — 캐릭터 커스터마이징 하단 잔존**

1. 로그인 화면에서 로그인 클릭(전체화면 의도가 여기서 sessionStorage 에 남는다 — `shared/ui/fullscreen.ts:16-22`).
2. 로비에서 '월드 입장' 클릭 — 이 클릭이 Unity `onWorldLoadStart`를 보내는 시점이고, 그 안에서 `enterFullscreen()`이 불린다.
3. 브라우저 개발자 도구를 전체화면 진입 전에 열고 `fullscreenchange`마다 `performance.now()`, `innerWidth/innerHeight`, canvas `clientWidth/clientHeight`, canvas `width/height`, `document.fullscreenElement`를 한 번에 기록한다.
4. 같은 시점의 Unity `Screen.width/Screen.height`를 Development 로그 또는 기존 진단 HUD에서 기록한다. 기존 관측 경로가 없을 때만 임시 진단 로그를 추가하고 제품 수정과 분리한다.
5. 하단 잔존 요소를 캡처한 뒤 `document.elementsFromPoint(x, y)` 결과를 기록하고, DevTools에서 의심되는 DOM 레이어를 하나씩 숨긴다. 요소가 모두 숨겨져도 canvas 그림에 남으면 Unity 렌더링으로 판정한다. Elements의 Inspect만으로 canvas 내부 요소를 식별했다고 판정하지 않는다.
6. `fullscreenchange` 타임스탬프와 잔존 요소 발생 시점을 비교하고, DOM 표시 크기·canvas 백버퍼·Unity Screen 값 중 어디서 처음 불일치가 생기는지 기록한다.

- [ ] **Step 3: 재현 — 로비 진입 시 안 걷히는 dim**

1. 위 흐름에서 '월드 입장' 직후 `.uh-status`(`preparing-world` 단계 dim, "축제장을 불러오고 있어요")가 정상적으로 사라지는지 확인한다. 사라지지 않으면 DevTools Sources에서 `events.ts`가 등록한 `onWorldGateReady` 핸들러와 `UnityHost.tsx`의 `setStatus('ready')`에 logpoint 또는 breakpoint를 건다. 전역 콜백을 교체해 원래 핸들러를 막지 않는다.
2. 신호가 안 오면: Unity WebGL 콘솔(브라우저 콘솔에 Unity 로그가 함께 찍힌다)에서 씬 로드 관련 에러·경고를 확인 — 전체화면 리사이즈가 WebGL 컨텍스트를 잃게 만드는 경우 `WebGL context lost` 류 에러가 남는다.
3. 신호는 오는데 화면만 안 걷히면: React 쪽 상태 버그(`UnityHost.tsx:159-165`, `setStatus`)이므로 `status` state 변화를 React DevTools로 직접 관찰.

- [ ] **Step 4: 판별 결과 기록**

`docs/KGH/25_트러블슈팅.md`에 T-번호로 재현 결과·타임스탬프·DOM/canvas/Unity 화면 크기·신호 수신 여부를 남긴다(해결 못 했어도 등록 — 헌법 29조). 아래 분기 중 확인된 경로를 표시한다.

### Task B2: Task B1 결과에 따른 수정 분기

**분기 A — DOM 크기와 canvas 백버퍼가 불일치:**
`loader.ts`와 Unity WebGL 로더의 지원 경로에서 Fullscreen 전환 시 크기 동기화가 왜 멈췄는지 확인한다. `Screen.SetResolution`이나 canvas `width/height` 직접 변경은 사용하지 않는다. 최소 수정안을 별도 테스트와 함께 작성하고 FE 브랜치에서 검증한다.

**분기 B — 브라우저 값은 정상이나 Unity `Screen.width/height`만 불일치:**
관측 결과와 사용 중인 Unity WebGL 로더 버전을 게임 파트에 공유하고 Jira -733에 후속 범위를 기록한다. Unity 변경이 필요하면 별도 커밋으로 진행하며, C#에서 임의 리사이즈를 넣기 전에 공식 로더 동기화 경로를 확인한다.

**분기 C — `onWorldGateReady`가 오지 않음:**
Unity 씬 로드·브리지 송신 중 처음 끊긴 지점을 고쳐 신호를 복구한다. `UnityHost.tsx`의 장기 대기 안내는 원인 수정과 별개인 안전망으로만 검토하며, 타임아웃으로 `ready`를 강제하지 않는다.

**분기 D — 신호는 오지만 React가 `ready`로 전이하지 않음:**
`events.ts` 구독과 `UnityHost.tsx` 상태 갱신 사이에서 누락되는 최소 지점을 수정하고, `preparing-world → ready` 회귀 테스트를 추가한다.

**분기 E — 브라우저 자체 전체화면 안내 배너:**
제품 DOM이나 Unity UI 결함으로 처리하지 않는다. `onWorldGateReady`로 Fullscreen 요청을 미루지 않는다 — 사용자 제스처가 끝난 뒤라 브라우저가 요청을 거부한다. 자동 진입 요구와 배너가 충돌하면 Jira -733에 실측 결과를 남기고, 월드 준비 후 사용자가 직접 누르는 전체화면 버튼을 대안으로 협의한다.

Task B1 없이 어떤 분기도 코드로 옮기지 않는다. 수정 후에는 동일 브라우저에서 전체화면 ON/OFF 각각 커스터마이징 화면, 월드 진입 dim 해제, 수동 전체화면 토글을 재검증한다.

---

## Self-Review 체크리스트

- **Spec coverage:** Part A 는 canvas 포커스만 보호하고 DOM 키보드 접근성을 보존한다. Part B 는 실제 관측값으로 수정 소유권과 위치를 확정한다.
- **Accessibility:** HUD·오버레이·채팅 입력의 Tab·Shift+Tab 탐색을 차단하지 않는다.
- **Completion gate:** Part A 실 WebGL 미니맵 확인 전에는 Jira -450을 닫지 않는다. Part B는 재현·원인 확정 전 수정 커밋을 만들지 않는다.
- **Type consistency:** `getWorldScreen`은 Part A Task A1 의 import·사용처가 동일 시그니처(`(): WorldScreen`)를 쓴다. Part B 는 코드 변경이 없어 해당 없음.
