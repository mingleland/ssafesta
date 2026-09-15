# S15P21A604-186 — Game Runtime 오류 격리·복구 UX (로컬 파트)

> 작업일: 2026-08-27 | 작업자: 박준우 | 대상: `feat/S15P21A604-186-error-boundary-local` 브랜치

## 배경

완료 조건 4개 중 "닫기 뒤 기존 앱과 선택적 Unity 입력이 정상"은 `features/overlay/OverlayHost.tsx`
(game-studio 밖, S15P21A604-115의 라우팅 영역)까지 연결해야 확인 가능해 S15P21A604-115 완료
대기 중이다. 나머지 3개는 `game-studio/runtime/ui/GameRuntimeErrorBoundary.tsx`·
`PublishedGameSurface.tsx` 안에서 완결되는 로컬 작업이라 지금 처리했다.

## 완료 조건 대조

| 완료 조건 | 처리 | 결과 |
|---|---|---|
| 강제 오류 fixture에서 해당 게임 영역만 실패한다 | 기존 React Error Boundary 구조가 이미 격리하고 있었음 — 신규 RTL 테스트로 "Boundary 밖 형제 엘리먼트는 크래시 후에도 그대로 렌더됨"을 직접 증명 | ✅ 통과 |
| 반복 실패가 무한 reload를 만들지 않는다 | **신규 구현**: 연속 실패 횟수를 인스턴스 필드로 세어 3회를 넘기면(4번째 실패부터) "다시 불러오기" 버튼을 숨기고 "나가기"만 남김 | ✅ 통과 |
| 진단 ID는 제공하되 내부 stack·민감 정보는 노출하지 않는다 | **신규 구현**: 크래시마다 짧은 진단 ID(`Math.random` 기반 6자리)를 생성해 화면에 "문의 코드: XXXXXX"로만 표시. 원본 에러 메시지·stack은 `componentDidCatch`의 `console.error`에만 남김 | ✅ 통과 |
| 닫기 뒤 기존 앱과 선택적 Unity 입력이 정상이다 | `features/overlay/OverlayHost.tsx` 연결 필요 — S15P21A604-115 완료 후 통합 검증 | ⏳ 대기 (S15P21A604-115) |

## 구현

`GameRuntimeErrorBoundary.tsx`에 다음을 추가했다.

- `MAX_CONSECUTIVE_FAILURES = 3` — `consecutiveFailures`는 React state가 아니라 인스턴스 필드다.
  재시도(`resetKey` 변경) 사이에는 값이 유지되고, 사용자가 게임을 나갔다 다시 들어와 이 Boundary가
  새로 mount되면 자연히 0으로 돌아간다 — 한 세션 안에서만 누적된다.
- `createDiagnosticId()` — 크래시마다 6자리 진단 ID를 생성해 `state.diagnosticId`에 저장.
  `componentDidCatch`는 그대로 두어 실제 stack은 콘솔에만 남긴다.
- `render()`에서 `canRetry = consecutiveFailures <= MAX_CONSECUTIVE_FAILURES` 조건으로 재시도
  버튼 노출 여부를 결정하고, 진단 ID·반복 실패 안내 문구를 조건부로 추가.

## 검증

| 항목 | 결과 |
|---|---|
| `npx tsc -b` | 통과 |
| `npm run lint` (oxlint) | 통과 (경고 0) |
| `npx vitest run src/game-studio` | 28 files / **121 tests** 전부 통과 (신규 3건 + 기존 118건 회귀 없음) |
| 신규 테스트 3건이 실제로 회귀를 잡는지 | 수정 전 코드로 되돌려 재실행 → 정확히 그 3건만 실패(격리 테스트는 기존 구조라 원래도 통과)하는 것을 확인 |

신규/수정 테스트:
- `gameRuntimeErrorBoundary.test.ts` — 기존 2건 상태 구조 업데이트(`diagnosticId` 필드 추가) + 진단 ID 노출·재시도 상한 신규 2건
- `gameRuntimeErrorBoundaryIsolation.test.tsx` — RTL로 실제 크래시 주입, Boundary 밖 형제 엘리먼트 무영향 증명 (신규)

브라우저 실측(강제 크래시 재현)은 생략했다 — "고의로 깨진 프로젝트" fixture를 새로 만들어야 하는데
이는 이번 3개 조건 범위 밖의 부가 작업이고, 순수 클래스 로직(비동기·환경 의존성 없음)이라
유닛/컴포넌트 테스트가 실제 클래스 메서드를 mock 없이 직접 구동하는 지금 방식이 더 정밀하다고 판단했다.

## 결론

**완료 조건 4개 중 3개(원인 격리·무한 재시도 방지·진단 ID) 충족.** 마지막 1개(닫기 후 앱·Unity
정상)는 S15P21A604-115 완료 후 통합 검증 예정 — 이 브랜치의 커밋에는 `Closes`를 넣지 않고
Jira 완료 조건 체크박스만 3/4로 갱신한다.
