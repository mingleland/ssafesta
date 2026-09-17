# 월드에서 웹(DOM) 쪽 Tab 탐색 차단 (S15P21A604-838) Implementation Plan

> S15P21A604-450에서 Tab 잠금을 넣을 때, 실제 Unity canvas가 keydown 타깃일 때만 막도록 좁혀서 HUD 버튼 간 Tab 키보드 이동 접근성을 지켰다. 이번 요청은 그 결정을 뒤집는다 — 월드 화면에서는 **Unity가 Tab을 전담**해야 하고, **웹(DOM) 쪽 Tab 탐색은 막아야** 한다(제품 결정, 2026-09-17 확인).

**Goal:** `WorldPage`가 활성화된 동안에는 keydown 타깃이나 Web 화면 종류와 관계없이 Tab의 브라우저 기본 동작(포커스 이동)을 막는다. Unity 내부의 Tab 입력 동작은 작업 범위에서 제외한다.

**Architecture:** 기존 Tab 잠금 `useEffect`(`WorldPage.tsx`)와 `window` 레벨 리스너를 그대로 재사용하고, Tab 여부 외의 화면·타깃 조건을 제거한다. `WorldPage`가 언마운트되면 기존 cleanup으로 차단도 해제된다 — 새 store·새 판정 계층 없음.

**Tech Stack:** React 19, TypeScript, Vitest + @testing-library/react.

**Spec:** Jira [S15P21A604-838](https://ssafy.atlassian.net/browse/S15P21A604-838).

## Global Constraints

- 브랜치: `fix/S15P21A604-838-tab-web-block`, `gitlab/develop`에서 분기 (develop 머지 재개됨 — frontend-hold 안 씀).
- 커밋 메시지: `fix(world): 한국어 요약 (S15P21A604-838)`.
- 테스트: `cd festa-frontend && npx vitest run <path>`. 최종 게이트: `npm test`, `npx tsc -b`, `oxlint`, `npm run build`.
- 이 변경은 -450이 보존한 DOM Tab 탐색을 의도적으로 제거한다. 기존 기대값을 삭제하지 않고 뒤집고, 메뉴·입력창·언마운트 경계를 추가해 제품 결정을 테스트에 남긴다.
- Unity 미니맵의 열림·닫힘과 키 입력 구현은 게임 파트 소관이며 이 이슈의 완료 조건에 포함하지 않는다.

---

## Task 1: 웹 쪽 Tab 탐색 차단

**Files:**
- Modify: `festa-frontend/src/pages/world/WorldPage.tsx:212-221`(Tab 잠금 `useEffect`)
- Modify: `festa-frontend/src/pages/world/__tests__/unit/worldPageTabLock.test.tsx`(HUD·메뉴·입력창 차단, 비 Tab·언마운트 경계 검증)

**Interfaces:** 변경 없음.

- [ ] **Step 1: 실패하는 테스트로 먼저 바꾼다**

`worldPageTabLock.test.tsx`의 기존 DOM·메뉴 기대값을 차단으로 뒤집고 입력창·비 Tab·언마운트 케이스를 추가한다:

```tsx
it('월드 HUD의 DOM 버튼에서도 Tab 탐색을 막는다', async () => {
  await renderWorld();
  const button = document.createElement('button');
  document.body.appendChild(button);
  button.focus();
  expect(dispatchTab(button)).toBe(true);
  button.remove();
});
```

같은 방식으로 Game Menu와 입력창은 `true`, Enter와 `WorldPage` 언마운트 후 Tab은 `false`를 기대한다.

- [ ] **Step 2: 실패 확인**

Run: `cd festa-frontend && npx vitest run src/pages/world/__tests__/unit/worldPageTabLock.test.tsx`
Expected: FAIL — HUD·Game Menu·입력창의 Tab이 `false`를 돌려줘 `toBe(true)`와 어긋난다.

- [ ] **Step 3: 구현**

`WorldPage.tsx`의 Tab 잠금 effect에서 화면·타깃 조건을 지운다:

```tsx
// Tab 잠금 (S15P21A604-450, S15P21A604-838) — WorldPage가 활성화된 동안에는
// canvas·HUD·메뉴·입력창을 가리지 않고 브라우저의 Tab 기본 동작(포커스 이동)을 막는다.
// Unity 내부의 Tab 입력 처리는 게임 파트 소관이므로 이벤트 전파는 막지 않는다.
useEffect(() => {
  function onKeyDown(e: KeyboardEvent) {
    if (e.key !== 'Tab') return;
    e.preventDefault();
  }
  window.addEventListener('keydown', onKeyDown);
  return () => window.removeEventListener('keydown', onKeyDown);
}, []);
```

- [ ] **Step 4: 통과 확인**

Run: `cd festa-frontend && npx vitest run src/pages/world/__tests__/unit/worldPageTabLock.test.tsx`
Expected: PASS 6건 전부(canvas·Game Menu·HUD·입력창·비 Tab·언마운트).

- [ ] **Step 5: 회귀 확인**

Run: `cd festa-frontend && npx vitest run src/pages/world/__tests__/unit/worldPageEscLayering.test.tsx src/pages/world/__tests__/unit/worldPageTabLock.test.tsx`
Expected: PASS 전부(ESC·Enter 판정자와 화면 소유권에 영향 없음을 확인).

Run: `cd festa-frontend && npm test && npx tsc -b && npm run lint && npm run build`
Expected: 테스트 실패 0, 타입 에러 0, 신규 lint 경고 0, build 성공.

- [x] **Step 6: 실 브라우저 확인**

`/app/world` 진입 후:

1. HUD 버튼에 포커스를 준 상태에서 Tab을 눌러도 다음 버튼으로 넘어가지 않는지 확인한다.
2. Game Menu·채팅 입력·Visitor Overlay에서도 Tab으로 포커스가 이동하지 않는지 확인한다.
3. 월드 밖 페이지에서는 Tab 탐색이 정상인지 확인한다.

Unity 미니맵의 동작은 확인 대상에서 제외한다.

- [ ] **Step 7: 커밋**

```bash
git add festa-frontend/src/pages/world/WorldPage.tsx festa-frontend/src/pages/world/__tests__/unit/worldPageTabLock.test.tsx docs/KGH/24_작업일지.md
git commit -m "fix(world): 월드에서 웹 쪽 Tab 탐색 차단 — Unity가 Tab 전담 (S15P21A604-838)"
```

---

## Self-Review 체크리스트

- **Spec coverage:** `WorldPage`가 활성화된 동안 DOM·canvas·화면 종류와 관계없이 Tab 기본 동작을 막고, 언마운트 후 해제한다.
- **Accessibility 트레이드오프 명시:** 월드의 모든 Web UI에서 Tab 키보드 이동이 사라지는 의도된 제품 결정이다. 마우스 클릭은 그대로 유지한다.
- **회귀 위험:** 변경 범위는 기존 리스너의 조건문 두 줄 삭제뿐. ESC·Enter 판정자와 `worldScreen` 로직은 미변경이다.
- **범위 제한:** Unity 미니맵의 입력·표시 동작은 게임 파트 소관으로 검증하지 않는다.
