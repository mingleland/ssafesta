# FE 백로그 — spec 005

경위·판단 근거는 `24_작업일지.md`, 확정값은 `docs/26` 결정 로그가 원본. **여기는 상태·다음 액션만 둔다.**
완료 항목은 결과 1줄. 경위·근거·검증 내용은 쓰지 않는다.

---

## 지금 할 수 있는 것

- [ ] **`client.ts`의 `ApiError` 갱신** — 봉투 `{code, message, requestId, errors, warnings}`, 원소는 `{rule, objectId?, message}`(`objectId`는 optional 키, 없으면 키 자체가 빠짐). `rule` 값은 계약 문서에 7개뿐이라 분기 대상 늘리지 않고 목록 렌더링 위주로
- [ ] **SSE 타입 + Mock Stream Fixture** — [#32](https://github.com/kanghyunsoon/ssafesta/issues/32)(CLOSED) 합의 완료. discriminated union(`data.type` 판별), `timeoutPhase`(`FIRST_TOKEN`/`TOTAL_RESPONSE`), `retryable` 매핑 17종 반영
- [ ] **Studio 편집기 착수** — C-04·C-06·부스 크기(2.72m)·스냅(0.25m) 전부 확정([#45](https://github.com/kanghyunsoon/ssafesta/issues/45)·[#19](https://github.com/kanghyunsoon/ssafesta/issues/19)). §10 기하 계약(회전 AABB·통행 판정)까지 FE 몫으로 명확. **`/speckit-tasks`는 [#43](https://github.com/kanghyunsoon/ssafesta/issues/43) PR #44 머지 후 `FE/`로 이관하고 착수**
- [ ] **Interaction Dispatcher 배선** — `events.ts`의 `toAiChatPayload`는 있는데 `openOverlay('AI_CHAT')`로 잇는 코드가 없고 `initUnityBridge()` 호출부도 없다(#2). `main.tsx`/`providers`에 배선 필요 — 지금 상태로는 Unity 이벤트가 오버레이로 전달되지 않음

## 막힌 것 — 대기 중

| 항목 | 막는 것 | 상대 |
|---|---|---|
| **#43** spec 산출물 경로 구조 | FE 산출물 develop PR 전체 | 리드 — 채택됨, **PR #44 머지 대기** |
| **#30** refresh 토큰 쿠키 도메인 | 인증 경로 | @Alexjung0115 TLS 결정 |
| **#34** `GAME_PORTAL`이 `schemaVersion` 올리는가 | ObjectType union 확장 시점 | BE (#36에서 질문함) |
| **#35** Game Studio renderer·sandbox | — | @busypark 몫, FE 관여 없음 |
| facade 팔레트 12색 hex | facade 팔레트 UI 구현 | "FE↔BE 구현 트랙에서"(#17) — **FE가 먼저 움직여야 함** |
| `assetCode` 목록 | 편집기 팔레트·자산 분기 종단 검증 | Unity 레지스트리 실측값 제공(#6·#18 — **두 이슈 모두 이걸 기다리다 닫힘**) |

## 회신 완료 — 상대 답변 대기

| 이슈 | 내가 답한 것 | 대기 |
|---|---|---|
| [#36](https://github.com/kanghyunsoon/ssafesta/issues/36) | 6건 수용 + 계약서 모순 1건 지적(409에 Draft 동봉 서술) — geometry 브랜치에서 이미 정정됨(미병합) | BE PR 병합 |
| [#33](https://github.com/kanghyunsoon/ssafesta/issues/33) | Game Studio 정책 4건 전부 동의, 신규 진입 차단 판정 시점 질문 | 리드 |

## 내가 닫아야 할 것

- [ ] **#45 종료 코멘트** — C-04·C-06 spec.md 반영 완료(`spec.md` Status·C-04·C-06 셀), `docs/26` 정정 완료. 리드·BE 둘 다 승인해 완료 조건 충족 — 닫기만 남음
- [ ] **#36에 추가 요청** — `rule` 문자열 12개 미문서화, `themeCode` 4값이 계약 문서에 없음, `errors[].objectId` optional 키인 것 문서 예시 정정

## 미착수 코드 (다이어트 때 백로그에서 유실됐다가 오늘 복원)

- [ ] `events.ts`에 **`onWorldGateReady` 콜백** + Unity WebGL 호스트 컴포넌트 + 1단계 로딩 오버레이(60초 타임아웃, Unity 강제개방 30초) — 리드가 #31에서 FE로 명시 배분. `OverlayType`에는 넣지 않음(호스트 레벨). 계약 원본: `origin/game`의 `specs/002-world-session/spec.md:82-86`(미병합)
- [ ] **Game Studio 소켓 4종**(#20) — `events.ts`의 `BOOTH_GAME_INTERACT` union / `overlay.ts`의 `GAME` 타입(`OverlayType` 현재 4종) / `/app/games/:gameId/edit|play` lazy 라우트 / Host→Unity `OnOverlayStateChanged` 송신부(수신 GameObject명 미확정). `features/game-entry/`는 #21 BE 계약 확정 후
- [ ] **편집기 팔레트 UI** — 미보유 파츠 회색+잠금 배지, 게스트 로그인 유도(#18, spec 012 착수 시)
- [ ] `docs/10_Frontend_설계서.md` §3에 `/app/games/:gameId/edit|play` 라우트 추가 develop PR — 리드는 spec/contracts만 반영, 여기는 FE 몫으로 남음(#20)
- [ ] `docs/26` 브랜치별 결정 분기 정리 제안 — FE가 #18에서 자청

## 내 것 아님

`#24` back·game 잔여 (FE 몫 완료)

---

## 완료

| | 결과 |
|---|---|
| **블록 0** 08-18 | Architecture 4종 — `overlay.ts`·`events.ts`·`client.ts` + `app/{router,providers}` |
| **블록 1** 08-20 | 좌표 왕복 검증 통과 — C-02 실측 확정, SC-004 근거. 수치: `verify/block1-roundtrip.md` |
| **블록 2** 08-20 | 타 파트 결정 5건 소진 |
| **블록 3** 08-20 | 리뷰 4칸+BE 검토칸 서명, SC-002 해소 |
| **블록 4** 08-21 | spec 005 plan 산출물 4종 작성 → 발견된 어긋남 5건 정정(부스 높이·스냅·template·409 revision 파싱 불가·통행 판정 FE 몫) — 단, 산출물 경로 구조 확정 전까지 develop PR 보류 |
| `events.ts` 08-20 | `AI_AGENT_INTERACT` 타입 + `toAiChatPayload` (`d1bbb4a`) — 단 오버레이로 잇는 배선은 미완료 |
| `BOOTH_LAPTOP_INTERACT` | 브라우저 왕복 검증 완료 (PR #25) |
| 문서 develop 통합 | `docs/26`·`FE.md` 완료 |
| **CLOSED** | #1 #2 #5 #6 #14 #17 #18 #19 #20 #21 #22 #31 #32 |

**해소된 미결** — C-02 좌표 / C-03 고정 크기 / C-05 `expectedRevision`(#36) / C-07 보관O·되돌리기 MVP제외 / **부스 6×6×2.72m·스냅 0.25m 확정** / **C-04·C-06 기획 승인**(#45) / 오류 봉투 원소 타입 / SSE payload 전체(#32) / 산출물 경로 구조(#43, 채택·PR #44 대기)

---

## 참고

- **develop 통합 절차**: `develop`에서 브랜치 → `git checkout <자기브랜치> -- <path>` 파일 단위 → `git diff --stat origin/develop -- festa-unity/` 0줄 확인 → PR. **직접 push·전체 merge 금지**(`festa-unity/` 1,462개 삭제)
- **착수 전 `origin/develop` 대조 필수** — 내 브랜치의 공유 문서 사본은 낡았다고 가정
- **013 문서 정합**: front `specs/013/spec.md`가 "FE는 창 이관만"·FR-020·C-07을 기술하는데 `FE.md`만 갱신됨 — 013 진행 시 정리
- **닫힌 이슈 재확인 습관** — 이슈가 CLOSED돼도 후속 확정값·번복이 마지막 코멘트에만 있는 경우가 잦다(오늘 #17·#19·#31 재확인에서 미반영 확정값 다수 발견). 산출물 작성 후 관련 이슈가 새로 닫히면 재대조할 것
