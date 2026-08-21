# FE 백로그 — spec 005

경위·판단 근거는 `24_작업일지.md`, 확정값은 `docs/26` 결정 로그가 원본. **여기는 상태·다음 액션만 둔다.**
완료 항목은 결과 1줄. 경위·근거·검증 내용은 쓰지 않는다.

---

## 📍 세션 인수인계 (2026-08-21 종료 시점)

**브랜치 상태**: `front` = `origin/front`(`b9be65e`), 워킹트리 clean. push 밀린 것 없음.

**진행률**: `specs/005-booth-studio-layout/FE/tasks.md` **20/25** (US1~US3 완료). 남은 건 US4(facade, T020~021)·Polish(T022~025)·**T026(중간 검증 결함 수정, 신설)**.

**⚠️ 다음 세션 시작 전 필수** — 착수 전 `origin/develop` 대조(메모리 규칙). `specs/005/spec.md`·`docs/26`이 develop보다 20줄 뒤처짐(PR #57 반영분) — `git checkout origin/develop -- specs/005-booth-studio-layout/spec.md docs/26_팀_결정_필요사항.md`로 동기화 먼저.

**다음 뭘 할지 — 추천 순서**:
1. **T026 상위 2건** — 미지 ObjectType 크래시(spec SC-005 위반) / mock publish 스냅샷 미복사(US1 시나리오6 위반, SC-003 검증 불가) — spec 요구사항과 직접 배치되는 것부터
2. T026 나머지(편집 소실·공개 dirty 미확인·충돌 UI 소실·pointer capture)
3. US4(facade) → Polish(§10 기하)

**막힌 것 아님, 그냥 안 한 것**: `#43` FE 산출물(`specs/005/FE/`) develop PR — 언제든 올릴 수 있음, 미착수.

상세 근거는 아래 "🔴 중간 검증에서 나온 미수정 결함" 섹션과 `24_작업일지.md` 08-21 항목.

---

## 지금 할 수 있는 것

- [x] ~~`client.ts`의 `ApiError` 갱신~~ — ✅ 08-21 완료. `ApiErrorDetail{rule, objectId?, message}` + fallback 배열 보정 + `isApiError` 가드. `ApiErrorDetail.java`(NON_NULL) 직접 대조로 검증(Codex 미가용)
- [ ] **`#43` FE 산출물 develop PR** — PR #44 머지됨(08:17). `specs/005/FE/` 4종 develop 반영 가능해짐, 아직 미착수
- [ ] **SSE 타입 + Mock Stream Fixture** — [#32](https://github.com/kanghyunsoon/ssafesta/issues/32)(CLOSED) 합의 완료. discriminated union(`data.type` 판별), `timeoutPhase`(`FIRST_TOKEN`/`TOTAL_RESPONSE`), `retryable` 매핑 17종 반영
- [x] ~~**Studio 편집기 MVP(US1) 착수**~~ — ✅ 08-21 완료. `FE/tasks.md` T001~T013, 배치→저장→새로고침 복원→공개 완결. 커밋 `dea2779`·`cc4b8eb`·`10fbb2f`
- [x] ~~**Studio 편집기 US2(콘텐츠 연결)**~~ — ✅ 08-21 완료. `FE/tasks.md` T014~T016. PropertiesPanel(위치·회전·configId·assetCode)·사전 경고(precheck)·PublishDialog 통합. 브라우저 실측(configId 연결 시 precheck 1건 감소·저장 후 유지·공개 응답 warnings 일치) 통과
- [x] ~~**Studio 편집기 US3(실수 방지)**~~ — ✅ 08-21 완료. `FE/tasks.md` T017~T019. lease-expired 전면 차단(draftQuery·save·publish 3경로 판정)·저장 오류 상세+objectId 자동 선택·재임대 "비공개" 표시. 브라우저 실측(boothId=999 sentinel로 만료 확인, boothId=42로 신규 부스 비공개 확인) 통과
- [ ] **Studio 편집기 US4·Polish** — `FE/tasks.md` T020~T025. facade 폼·§10 기하 실시간 검증(회전 AABB·통행 판정). ⚠️ PR #50 병합으로 §9·§10 계약 develop 정본화됨 — Polish 문서 각주 갱신 필요. configId 입력에 Int32 범위(1~2,147,483,647, 0 금지) 검증 없음(#34·PR #57) — Polish에서 보강
- [ ] **Interaction Dispatcher 배선** — `events.ts`의 `toAiChatPayload`는 있는데 `openOverlay('AI_CHAT')`로 잇는 코드가 없고 `initUnityBridge()` 호출부도 없다(#2). `main.tsx`/`providers`에 배선 필요 — 지금 상태로는 Unity 이벤트가 오버레이로 전달되지 않음

## 🔴 중간 검증에서 나온 미수정 결함 (08-21, 지시 범위 밖이라 보류)

우선순위순. 상세 근거는 `24_작업일지.md` 중간 검증 항목.

- [ ] **미저장 편집 소실** — `QueryClient`가 옵션 없이 생성돼 `refetchOnWindowFocus` 기본 true. 편집 중 탭 왕복하면 refetch→`LOAD_DRAFT`가 작업을 덮어씀. 저장 직후 드래그도 레이스로 되돌아가고 `dirty:false`가 돼 저장 불가. `useLayoutMutations`가 invalidate 경로만 막았고 focus 경로가 열려 있음
- [ ] **미지 ObjectType 하나로 편집기 영구 크래시** — `validate.ts:13`·`PropertiesPanel.tsx:21`이 `OBJECT_TYPE_INFO[type]`을 옵셔널 조회 없이 사용. 서버가 타입 추가 시(`GAME_PORTAL` 예정) 그 부스는 새로고침해도 계속 터짐. **spec SC-005("미지 타입이 있어도 나머지 정상 표시")와 정면 배치**
- [ ] **공개 버튼이 `dirty` 미확인** — `publish`는 본문 없이 **서버 draft**를 공개하는데 미리보기는 로컬 state 기준. 저장 안 한 삭제 후 공개하면 삭제한 오브젝트가 공개됨
- [ ] **충돌 UI가 편집하면 사라짐** — 편집 액션이 `saveStatus`를 `dirty`로 덮어써 conflict 안내·재로드 버튼 소실. `baseRevision`은 낡아 저장 실패 루프
- [ ] **드래그 눌어붙음** — `setPointerCapture`·`onPointerCancel` 부재. 창 밖에서 버튼 떼고 돌아오면 안 누른 채 따라다님. `touch-action: none`도 없어 터치는 스크롤에 인계됨
- [ ] **mock이 실 BE와 다름 3건** — publish가 스냅샷 미복사(**spec US1 시나리오 6을 mock이 반증**, SC-003 검증 불가) / draft 저장 응답에 `CONFIG_NOT_LINKED` warning(계약은 공개 시점만) / `BOOTH_LEASE_EXPIRED`를 draft GET·PUT에서 던짐(계약에 없는 경로, 내가 만든 sentinel에 프로덕션 코드가 맞춰진 형태)
- [ ] **quickstart §5 재현 수단 부재** — [T-12](25_트러블슈팅.md). mock에 강제 충돌 훅 추가 또는 `localStorage` 전환 필요
- [ ] **로컬 `front`에 계약서 파일 없음** — 코드 주석이 `contracts/layout-api.md` §1·§10-1을 인용하는데 레포에 그 파일이 없다(develop에만). 검증 가능한 상태가 아니라 동기화 필요
- [ ] **스냅 0.25가 계약 정본에 근거 없음** — 로컬 `FE/research.md` R-04에만 존재. develop `spec.md` FR-003 또는 contracts에 명문화 필요
- [ ] **`client.ts:9`·`FE/research.md` R-12 주석 무효화** — "rule 전체 목록이 계약 문서에 없다"가 근거였는데 PR #57이 19종을 명문화해 해소됨. 코드 동작은 여전히 안전(보수적)하나 근거 서술이 사실과 다름

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
| **블록 5** 08-21 | spec 005 Studio 편집기 MVP(US1) — 계약 전사·API·mock·좌표·편집기 본체. 브라우저 실측 검증(quickstart §2·§3·§4·§6) 통과. 검증 중 conflict 상태가 자동 refetch로 덮이는 버그 발견·수정 |
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
