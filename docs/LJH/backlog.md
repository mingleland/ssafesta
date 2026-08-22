# FE 백로그

경위·판단 근거는 `24_작업일지.md`, 확정값은 `docs/26` 결정 로그가 원본. **여기는 상태·다음 액션만 둔다.**
완료 항목은 결과 1줄. 경위·근거·검증 내용은 쓰지 않는다.

---

## 📍 세션 인수인계 (2026-08-23 종료 시점)

**브랜치 상태**: `front` = `origin/front`. spec 005 완결, Block A(001·013a·016·002 계약 회수) 완결, 013a WebGL Host 완료, **016 E2E(Bridge→Dispatcher→OverlayHost→iframe) 완료**.

**013a WebGL Host 완료 범위**: `unity/host/`(types·resolver·loader·loader.mock·loader.select·sessionManager·UnityHost) + `/app/world` 라우트 + `events.ts`(`onWorldGateReady`) + `client.ts`(`getAccessToken`, lifecycle 미연결). single-flight(StrictMode 안전)·retry 직렬화(Quit 완료 후 재생성)·60초 타임아웃 전부 vitest 5개+브라우저 5개 시나리오로 검증.

**credential 전달은 의도적으로 미구현** — 종류(AT vs 단수명 token)·시점·방식 전부 미결이라 `getAccessToken()`을 read boundary로만 두고 어떤 lifecycle에도 안 걸었다. Unity 담당 확인 요청 [#60](https://github.com/kanghyunsoon/ssafesta/issues/60) 응답 대기.

**016 E2E 완료 범위**: `features/interaction/dispatcher.ts`(LAPTOP·AI_AGENT 라우팅, 미지 type 무시)·`features/overlay/{OverlayHost,LaptopOverlay}.tsx`(URL은 `http`/`https`만 허용, iframe+새 탭+안내 동시 제공 — 차단 "감지"는 구현하지 않음, FR-007 기술 제약 기록)·`WorldPage.tsx`(Dispatcher를 화면 생명주기에 종속). vitest 8개+브라우저 9개 시나리오 검증. **AI_CHAT 등은 공통 fallback뿐** — 008·010·011 실 UI 아님.

**다음 뭘 할지 — 확정 순서**: #60 응답 대기 중 **001 Auth FE** 착수(BE 구현 이미 완료, front spec.md는 Block A로 회수됨) → 010 Survey → 009 Exhibition.

**추적만, 작업 안 함**: 013·016의 game발 spec 갱신이 develop에 미반영(타 파트 동기화 영역) / FE.md가 인용하는 `game de38269` 커밋이 로컬·원격에 없음 — 016 E2E는 이 SHA 확인 없이 진행했음(계약 텍스트만 필요, Unity 코드 검증 불필요했음), 미해소 그대로 남음, Unity 담당에게 별도 확인 필요 / 013 spec.md의 C-01이 docs/26(V10 TEXT 확정)에서 이미 해소됐는데 리뷰 표는 미결 표기(#59 패턴, Unity 소유라 임의 수정 안 함).

상세는 `24_작업일지.md` 08-23 항목.

---

## 지금 할 수 있는 것

- [ ] **`#43` FE 산출물 develop PR** — 경로 구조 채택·PR #44 머지 완료로 반영 가능 상태. `specs/005/FE/` 4종, 미착수
- [ ] **SSE 타입 + Mock Stream Fixture** — [#32](https://github.com/kanghyunsoon/ssafesta/issues/32)(CLOSED) 합의 완료. discriminated union(`data.type` 판별), `timeoutPhase`(`FIRST_TOKEN`/`TOTAL_RESPONSE`), `retryable` 매핑 17종 반영

## 막힌 것 — 대기 중

| 항목 | 막는 것 | 상대 |
|---|---|---|
| **#30** refresh 토큰 쿠키 도메인 | 인증 경로(001 착수 시) | @Alexjung0115 TLS 결정 |
| **#34** `GAME_PORTAL`이 `schemaVersion` 올리는가 | ObjectType union 확장 시점 | BE (#36에서 질문함) |
| `assetCode` 목록 | 편집기 팔레트·자산 분기 종단 검증 | Unity 레지스트리 실측값 제공(#6·#18 — **두 이슈 모두 이걸 기다리다 닫힘**) |

## 회신 완료 — 상대 답변 대기

| 이슈 | 내가 답한 것 | 대기 |
|---|---|---|
| [#36](https://github.com/kanghyunsoon/ssafesta/issues/36) | 6건 수용 + 계약서 모순 1건 지적(409에 Draft 동봉 서술) — geometry 브랜치에서 이미 정정됨(미병합) | BE PR 병합 |
| [#33](https://github.com/kanghyunsoon/ssafesta/issues/33) | Game Studio 정책 4건 전부 동의, 신규 진입 차단 판정 시점 질문 | 리드 |
| [#58](https://github.com/kanghyunsoon/ssafesta/issues/58) | 오류 봉투 C안 동의 + 근거 정정, §3 `rule`/`field` 분리 방향 제안 | strdeok(C 확정·구현)·ghkim1632(FastAPI 정합) |
| [#17](https://github.com/kanghyunsoon/ssafesta/issues/17) | 팔레트 반영 주체를 BE로 제안. 12색 hex 값은 FE 보유 — 주체 확정 시 제공 | BE 확인 |
| [#59](https://github.com/kanghyunsoon/ssafesta/issues/59) | 리드가 §3-1~4 전부 채택, 반영안 초안 PR [#61](https://github.com/kanghyunsoon/ssafesta/pull/61) 게시 완료(`docs/00`§2·`docs/17`§2-1·§21-2) | 리드 PR 리뷰·머지 |
| [#60](https://github.com/kanghyunsoon/ssafesta/issues/60) | 013a — 빌드 URL 4종 제공 방식·Spring REST credential 정본 2건 질의 | Unity(kanghyunsoon) |

## 내가 닫아야 할 것

- [ ] **#36에 추가 요청** — `rule` 문자열 미문서화 잔여, `themeCode` 4값이 계약 문서에 없음, `errors[].objectId` optional 키인 것 문서 예시 정정. (#58 결론 나오면 함께 처리 — #58이 `rule` 문서화·objectId 서술과 겹침)
- [ ] **스냅 0.25 계약 명문화** — 로컬 `FE/research.md` R-04에만 존재. develop `spec.md` FR-003 또는 contracts에 반영 필요(강형순 develop 정정 PR에 포함 예고됨 — #45)

## 미착수 코드

- [ ] **Game Studio 소켓 3종**(#20) — `events.ts`의 `BOOTH_GAME_INTERACT` union / `overlay.ts`의 `GAME` 타입(`OverlayType` 현재 4종) / Host→Unity `OnOverlayStateChanged` 송신부(수신 GameObject명 미확정). `features/game-entry/`는 #21 BE 계약 확정 후. lazy 라우트 2종은 PR #47로 이미 존재
- [ ] **편집기 팔레트 UI** — 미보유 파츠 회색+잠금 배지, 게스트 로그인 유도(#18, spec 012 착수 시)
- [ ] `docs/10_Frontend_설계서.md` §3에 `/app/games/:gameId/edit|play` 라우트 추가 develop PR — 리드는 spec/contracts만 반영, 여기는 FE 몫으로 남음(#20)
- [ ] `docs/26` 브랜치별 결정 분기 정리 제안 — FE가 #18에서 자청

## 내 것 아님

`#24` back·game 잔여 (FE 몫 완료) / **#35** Game Studio renderer·sandbox — @busypark 몫

---

## 완료

| | 결과 |
|---|---|
| **spec 005** 08-18~22 | 전체 완결 — Architecture 4종·좌표 왕복 검증·리뷰 서명·plan 산출물 4종·US1~US4·Polish(T022~T026)·quickstart 인수검사·vitest 37개·구조 진단(RED 0). 상세는 작업일지·`verify/` |
| **Block A** 08-23 | 001·013a·016·002 계약 front 회수(develop·game 정본 대조) |
| **013a WebGL Host** 08-23 | 위 인수인계 참조 |
| **016 E2E** 08-23 | 위 인수인계 참조 |
| `BOOTH_LAPTOP_INTERACT` | 브라우저 왕복 검증 완료 (PR #25) |
| 문서 develop 통합 | `docs/26`·`FE.md` 완료 |

**해소된 미결** — C-02 좌표 / C-03 고정 크기 / C-05 `expectedRevision`(#36) / C-07 보관O·되돌리기 MVP제외 / 부스 6×6×2.72m·스냅 0.25m 확정 / C-04·C-06 기획 승인(#45) / 오류 봉투 원소 타입 / SSE payload 전체(#32) / 산출물 경로 구조(#43 채택·반영 완료)

---

## 참고

- **develop 통합 절차**: `develop`에서 브랜치 → `git checkout <자기브랜치> -- <path>` 파일 단위 → `git diff --stat origin/develop -- festa-unity/` 0줄 확인 → PR. **직접 push·전체 merge 금지**(`festa-unity/` 1,462개 삭제)
- **착수 전 `origin/develop` 대조 필수** — 내 브랜치의 공유 문서 사본은 낡았다고 가정
- **닫힌 이슈 재확인 습관** — 이슈가 CLOSED돼도 후속 확정값·번복이 마지막 코멘트에만 있는 경우가 잦다. 산출물 작성 후 관련 이슈가 새로 닫히면 재대조할 것
