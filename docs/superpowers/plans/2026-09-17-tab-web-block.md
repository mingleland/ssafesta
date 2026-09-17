# 월드에서 웹(DOM) 쪽 Tab 탐색 차단 (S15P21A604-838) Implementation Plan

> S15P21A604-450에서 Tab 잠금을 넣을 때, 실제 Unity canvas가 keydown 타깃일 때만 막도록 좁혀서 HUD 버튼 간 Tab 키보드 이동 접근성을 지켰다. 이번 요청은 그 결정을 뒤집는다 — 월드 화면에서는 **Unity가 Tab을 전담**해야 하고, **웹(DOM) 쪽 Tab 탐색은 막아야** 한다(제품 결정, 2026-09-17 확인).

**Goal:** 월드가 화면 주인(`getWorldScreen() === 'world'`)인 동안에는 keydown 타깃이 무엇이든 Tab의 브라우저 기본 동작(포커스 이동)을 막는다. ESC 메뉴 등 다른 화면이 주인일 때는 그대로 둔다.

**Architecture:** 기존 Tab 잠금 `useEffect`(`WorldPage.tsx`)의 세 조건 중 `e.target !== unity-canvas` 하나만 지운다. 나머지 구조(같은 `useEffect`, `getWorldScreen()` 판정, `window` 레벨 리스너)는 그대로 재사용 — 새 store·새 판정 계층 없음.

**Tech Stack:** React 19, TypeScript, Vitest + @testing-library/react.

**Spec:** Jira [S15P21A604-838](https://ssafy.atlassian.net/browse/S15P21A604-838).

## Global Constraints

- 브랜치: `fix/S15P21A604-838-tab-web-block`, `gitlab/develop`에서 분기 (develop 머지 재개됨 — frontend-hold 안 씀).
- 커밋 메시지: `fix(world): 한국어 요약 (S15P21A604-838)`.
- 테스트: `cd festa-frontend && npx vitest run <path>`. 최종 게이트: `npm test`, `npx tsc -b`, `oxlint`, `npm run build`.
- 이 변경은 -450이 의도적으로 만든 "월드가 주인이어도 DOM Tab 탐색은 보존" 동작을 되돌린다 — 기존 테스트 케이스 하나(`worldPageTabLock.test.tsx`의 "DOM 버튼의 Tab 탐색은 막지 않는다")의 기대값 자체를 바꿔야 한다. 삭제하지 않고 기대값과 설명을 뒤집어서, 이 결정이 테스트에도 드러나게 한다(-798 작업 때 팀 결정 번복을 테스트로 드러낸 방식과 동일).
- 실 Unity WebGL 브라우저 확인 없이는 Jira 완료 처리하지 않는다(-450 때와 같은 이유 — Unity 쪽에서 Tab이 실제로 미니맵을 여는지는 mock 환경에서 재현 불가).

---

## Task 1: 웹 쪽 Tab 탐색 차단

**Files:**
- Modify: `festa-frontend/src/pages/world/WorldPage.tsx:212-221`(Tab 잠금 `useEffect`)
- Modify: `festa-frontend/src/pages/world/__tests__/unit/worldPageTabLock.test.tsx`(기존 3번 케이스 기대값 반전 + 설명 문구 정정)

**Interfaces:** 변경 없음 — `getWorldScreen()` 하나만 계속 소비.

- [ ] **Step 1: 실패하는 테스트로 먼저 바꾼다**

`worldPageTabLock.test.tsx`의 세 번째 케이스를 다음으로 바꾼다(이름·기대값 반전):

```tsx
it('월드가 주인이면 DOM 버튼에 포커스가 있어도 Tab을 막는다 — Unity가 Tab을 전담한다', async () => {
  await renderWorld();
  const button = document.createElement('button');
  document.body.appendChild(button);
  button.focus();
  expect(getWorldScreen()).toBe('world');
  expect(dispatchTab(button)).toBe(true);
  button.remove();
});
```

파일 상단 주석(1~4행)도 "HUD·오버레이 등 DOM 요소의 Tab 탐색은 보존한다" → "월드가 주인인 동안에는 DOM 요소든 canvas든 Tab을 막는다 — Unity가 Tab을 전담한다"로 정정한다.

- [ ] **Step 2: 실패 확인**

Run: `cd festa-frontend && npx vitest run src/pages/world/__tests__/unit/worldPageTabLock.test.tsx`
Expected: FAIL — 세 번째 케이스가 `false`(현재 구현은 DOM 타깃이면 안 막음)를 돌려줘 `toBe(true)`와 어긋난다.

- [ ] **Step 3: 구현**

`WorldPage.tsx`의 Tab 잠금 effect에서 canvas 타깃 조건을 지운다:

```tsx
// Tab 잠금 (S15P21A604-450, 웹 쪽 차단은 S15P21A604-838) — 브라우저 기본 동작은 Tab 에서
// 다음 포커스 가능 요소로 옮긴다. Unity 6 WebGL 은 키보드 타깃을 canvas 로 잡으므로
// (captureAllKeyboardInput=false, !279), 포커스가 캔버스를 벗어나면 그 뒤 Tab keydown 이
// Unity 에 안 들어가 자체 미니맵 토글이 죽는다. 월드가 주인인 동안에는 Unity가 Tab을
// 전담한다 — DOM 쪽 Tab 탐색(HUD 버튼 간 이동 포함)은 막는다(제품 결정, S15P21A604-838).
useEffect(() => {
  function onKeyDown(e: KeyboardEvent) {
    if (e.key !== 'Tab') return;
    if (getWorldScreen() !== 'world') return;
    e.preventDefault();
  }
  window.addEventListener('keydown', onKeyDown);
  return () => window.removeEventListener('keydown', onKeyDown);
}, []);
```

- [ ] **Step 4: 통과 확인**

Run: `cd festa-frontend && npx vitest run src/pages/world/__tests__/unit/worldPageTabLock.test.tsx`
Expected: PASS 3건 전부(canvas 케이스·Game Menu 케이스·바뀐 DOM 버튼 케이스).

- [ ] **Step 5: 회귀 확인**

Run: `cd festa-frontend && npx vitest run src/pages/world/__tests__/unit/worldPageEscLayering.test.tsx src/pages/world/__tests__/unit/worldPageTabLock.test.tsx`
Expected: PASS 전부(ESC·Enter 판정자, myInfo 오버레이 등 다른 판정에 영향 없음을 확인).

Run: `cd festa-frontend && npm test && npx tsc -b && npm run build`
Expected: 테스트 실패 0, 타입 에러 0, build 성공.

- [ ] **Step 6: 실 브라우저 확인 (mock World 로는 재현 불가)**

실 Unity 빌드 환경(데모 또는 실 백엔드+실 Unity)에서 `/app/world` 진입 후:

1. HUD 버튼(전체화면 토글 등)에 마우스로 포커스를 준 상태에서 Tab을 눌러도 다음 버튼으로 넘어가지 않는지 확인(전에는 넘어갔다 — 이제는 막혀야 한다).
2. canvas가 포커스를 가진 상태에서 Tab을 누르면 Unity 미니맵이 정상적으로 뜨는지 확인(-450에서 이미 확인된 동작, 회귀 없어야 한다).
3. Game Menu(ESC)·채팅 입력·오버레이가 열려 있을 때는 Tab 탐색이 그대로 되는지 확인(월드가 주인이 아니므로 안 막혀야 한다).

이 환경이 없으면 MR 설명에 미검증 사실을 남기고 `Closes S15P21A604-838`을 사용하지 않는다.

- [ ] **Step 7: 커밋**

```bash
git add festa-frontend/src/pages/world/WorldPage.tsx festa-frontend/src/pages/world/__tests__/unit/worldPageTabLock.test.tsx docs/KGH/24_작업일지.md
git commit -m "fix(world): 월드에서 웹 쪽 Tab 탐색 차단 — Unity가 Tab 전담 (S15P21A604-838)"
```

---

## Self-Review 체크리스트

- **Spec coverage:** 월드가 주인일 때 DOM·canvas 구분 없이 Tab을 막는다. 월드가 주인이 아닐 때(ESC 메뉴 등)는 손대지 않는다.
- **Accessibility 트레이드오프 명시:** 이 변경으로 월드 화면에서 HUD 버튼 간 Tab 키보드 이동이 죽는다 — 의도된 제품 결정이며 테스트(Step 1)에 그대로 드러난다. 마우스 클릭으로는 그대로 접근 가능.
- **회귀 위험:** 변경 범위는 조건문 한 줄 삭제뿐. ESC·Enter 판정자, myInfo 오버레이, worldScreen 판정 로직은 미변경.
- **검증 한계:** Unity 쪽에서 Tab이 실제로 미니맵을 여는지는 mock 환경(jsdom, `IS_MOCK_WORLD`)으로 재현 불가 — 최종 확인은 실 브라우저 필수.
