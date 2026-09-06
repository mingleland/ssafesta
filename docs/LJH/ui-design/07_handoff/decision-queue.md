# Decision Queue — 팀 합의 대기 항목

- 문서 종류: DECISION 대기 목록 (2026-09-01). 전부 **Unity 코드 수정 수반 → 자체 처리 불가, 팀 합의 대상**.
- Foundation / Landing / 일반 Overlay 디자인을 막지 않는다. **실제 Unity integration 전에 반드시 해결.**

## Unity 항목 4건 (2026-08-31 Audit 실측, 상태: OPEN)

| # | 항목 | 실측 상태 | 결정할 것 |
|---|---|---|---|
| 1 | TimerStopGameHud vs React GameOverlay | Unity 가 풀스크린 uGUI(sortingOrder 500)로 미니게임을 직접 열고 BOOTH_GAME_INTERACT 를 송신하지 않음. React 는 수신부·GameOverlay 완성 — 같은 기능 이중 구현 | 게임 부스 F 가 어느 쪽을 여는가 (3파트) |
| 2 | Unity 송신 확인 토스트 | develop 의 @InteractHint 토스트가 릴리즈 게이트 없이 그려짐 — Toast 는 React 소관(Post-MVP) 확정과 충돌 | React Toast 로 이관 or dev 게이트 |
| 3 | AvatarCustomizationHud 잔존 POC | 릴리즈 WebGL 에서 C 키로 열림(게이트 없음). 정식 소유권은 React Profile | 게이트 추가/제거 시점 |
| 4 | Overlay open 시 Input Lock/복구 계약 | **FE 층은 확정·구현됨** — Overlay 단일성·ESC 우선순위·focus 소유권은 `fe-contract-boundary.md` §G-8-1, 닫기 후 focus 복구는 `S15P21A604-428`(!269, develop). Unity 쪽은 여전히 `captureAllKeyboardInput` 0건 | Unity 소관 3건만 남음 — ① `captureAllKeyboardInput=false` 설정 주체 ② input lock/unlock 브리지 API 이름·시그니처 ③ pointer ownership 경계. **GitLab #132 로 게시(2026-09-05)** |

## SSAFY Login Provider — 도입 여부 결정 종료 (D-07 ADOPTED)

도입 여부는 더 이상 OPEN 항목이 아니다 — `implementation-decisions.md` D-07 로 확정(SSAFY/Google/Kakao/Guest 4종).
구현 상태는 NO_PROGRESS: 실제 SSAFY OAuth BE/FE 계약(URL·DTO·redirect)은 별도 구현 작업으로 남아 있으며 디자인 단계에서 발명하지 않는다.
