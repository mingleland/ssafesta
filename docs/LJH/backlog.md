# FE 백로그

경위·판단 근거는 `24_작업일지.md`, 확정값은 `docs/26` 결정 로그·spec이 원본. **여기는 상태·다음 액션만 둔다.**
완료 항목은 결과 1줄. 경위·근거·검증 내용은 쓰지 않는다.

---

## 현재 상태

**front = origin/front, clean.** spec 005·Block A·013a·016·001 Auth·D-1·D-2·B·C·G-2 완료, 공용 문서 develop 재회수 완료(PR #67 머지분). 상세는 완료 표.

**08-24 새벽 변동** — [PR #71](https://github.com/kanghyunsoon/ssafesta/pull/71) 머지로 팔레트·field 라운드 트리거 발화. [#17](https://github.com/kanghyunsoon/ssafesta/issues/17)·[#36](https://github.com/kanghyunsoon/ssafesta/issues/36) CLOSED. 리드 Game Studio **3단 스택**([PR #72](https://github.com/kanghyunsoon/ssafesta/pull/72)→[#79](https://github.com/kanghyunsoon/ssafesta/pull/79)→[#80](https://github.com/kanghyunsoon/ssafesta/pull/80))이 **`front`를 base로** 대기. 신규 이슈 3건([#76](https://github.com/kanghyunsoon/ssafesta/issues/76)·[#78](https://github.com/kanghyunsoon/ssafesta/issues/78)·[#81](https://github.com/kanghyunsoon/ssafesta/issues/81)) 전부 나 배정.

잔여 P0 중 **004 Lease·003 Wallet은 develop 계약 회수만으로 착수 가능**하다 — BE 구현 머지 완료(PR #10·#9), C-xx 미결 0, 004는 BE 리뷰 3칸 확정(08-19). 나머지 P0는 009(BE PR 미존재)·016 C-01·008 UI(AI 서버)·인프라(#30 실빌드·실서버)·Game Studio 체인(#48→#55·#56)에 걸린다.

## 착수 가능 — 협의 불요, 권장 순서순

- [ ] **(P0) FE 팔레트·field 라운드** — 트리거 충족([PR #71](https://github.com/kanghyunsoon/ssafesta/pull/71) 머지 08-24 01:09). ⑴ 계약 대응 먼저: `ApiError.errors[]`에 `field?: string`·mock 3곳·`PublishDialog` key 인덱스화·R-12 해제 후 `rule` 분기·`client.ts:10` 낡은 주석 정리 ⑵ 팔레트: 12색 스와치 UI(자유 입력 제거)·`facadeApi.mock.ts` 팔레트 소속+대문자 정규화 정합·하이드레이션 값이 팔레트 밖이면 안내(strdeok 지적 함정 — 간판만 고쳐도 400 방지). ⑵는 규모 보고 같은 라운드 또는 분리
- [ ] **(P0) 004 Lease + 003 Wallet FE** — 계약 정본은 `origin/develop`의 `specs/004-booth-slot-lease/contracts/lease-api.md`·`specs/003-wallet-coin/contracts/wallet-api.md`(착수 시 front 회수). 소비 endpoint: `GET /booth-slots`·`POST /booth-slots/{slotId}/leases`·`GET /booths/mine`·`GET /booths/{boothId}`·`GET /wallets/me`·`GET /wallets/me/transactions`. 003을 묶는 이유 — 임대 확인 UI가 잔액·차감 후 잔액을 함께 표시해야 해서 분리하면 두 번 손댄다. **#81(Coin 차감) 무영향** — `wallet-api.md:121`이 차감 API를 의도적 부재로 명시(차감은 기능 서버 로직 소관), 소비 계약은 조회 2종뿐
- [ ] **(P0) Owner/Staff 라우트 가드**(G-1) — `GET /booths/mine`·슬롯 목록 `mine` 필드로 트리거 충족. 현재 member/guest 2등급이라 `/app/studio/:boothId`가 전 member에 열림(서버 FR-012가 최종 차단 — UX 가드일 뿐)
- [ ] **(P1) 010 Survey 결과 화면** — `SSAFY_FESTA_내부설문_관련_업데이트.md` §2.5 확정 자료를 입력으로. **응답 UI 제외**(C-05 게스트 응답 등 미결)

## 대기

| 항목 | 대기 대상 | 상대 |
|---|---|---|
| [#56](https://github.com/kanghyunsoon/ssafesta/issues/56) T056 Portal resolver·overlay adapter / T058 E2E | [#48](https://github.com/kanghyunsoon/ssafesta/issues/48) BE resolver·whitelist 배포. 선행 [PR #53](https://github.com/kanghyunsoon/ssafesta/pull/53) **머지 완료**(08-24, develop `64d544e` — `specs/019-game-studio/` 전량 도달). **남은 체인은 #48 배포 하나.** strdeok가 제기한 T054 `objectId` 미계약(#56 §3)은 [PR #82](https://github.com/kanghyunsoon/ssafesta/pull/82)가 답 | strdeok |
| [#56](https://github.com/kanghyunsoon/ssafesta/issues/56) T057 `OnOverlayStateChanged` 송신부 | 수신 GameObject명(`receiverObjectName`) 확정 | Unity |
| [#55](https://github.com/kanghyunsoon/ssafesta/issues/55) 잔여 E2E | **서버 비의존 FE 범위는 [PR #72](https://github.com/kanghyunsoon/ssafesta/pull/72)(리드)가 선구현**(Published loader·schema guard·상태별 오류 UI·error boundary). 잔여 = #48 실 endpoint + #69 stable resolver 연결 후 browser E2E | strdeok·리드 |
| 001 T016 게스트·refresh·logout 실경로 | BE 계약 문서(endpoint 3종) 회수 | BE |
| `assetCode` 목록 | Unity 레지스트리 실측값(#6·#18 — 둘 다 이걸 기다리다 닫힘) | Unity |
| [#78](https://github.com/kanghyunsoon/ssafesta/issues/78) GameProject v1.1 승리 규약 | BE·AI 합의. 리드가 FE 후보 구현 선점([PR #80](https://github.com/kanghyunsoon/ssafesta/pull/80) `02a1d76`) — `completion.mode ALL/ANY`·`SCORE_AT_LEAST`/`DEFEAT_ENEMIES`/`SURVIVE_SECONDS`·`RESPAWN`/`END_GAME` | 리드·strdeok·AI |
| [#81](https://github.com/kanghyunsoon/ssafesta/issues/81) Published 플레이 세션·Coin 차감 | BE 계약 7건. **FE 인수조건 명시됨** — `createPublishedGameSessionPort`를 API 어댑터로 교체(Editor·Runtime 무변경), 차감 사전 고지, 2계정 1회 차감·idempotency E2E | strdeok·리드 |
| [#59](https://github.com/kanghyunsoon/ssafesta/issues/59) 마무리 | **§21-2 grep 2층 개정 1건만 잔존**(develop `docs/17:546` 아직 3단계, 문구 초안 작성자가 리드라 리드 몫). `:134` 화살표는 [PR #83](https://github.com/kanghyunsoon/ssafesta/pull/83)으로 반영 완료 | 리드 |
| [#60](https://github.com/kanghyunsoon/ssafesta/issues/60) AT 채택 + [#76](https://github.com/kanghyunsoon/ssafesta/issues/76) 아바타 저장 API | 둘 다 `avatar-profile-api.md` 반영이 [PR #75](https://github.com/kanghyunsoon/ssafesta/pull/75)에 있는데 **base가 `back`(스택)이라 develop 미도달** — develop `:41`은 아직 "형태도 가능하다" 제안형. #76이 나에게 요청한 FE 타입 필드(`GET /users/me`의 `avatarCode`)는 front 참조 **0건** 실측. 착수는 예정 작업(013a wiring)에서 함께 | strdeok |
| [#69](https://github.com/kanghyunsoon/ssafesta/issues/69) Asset 업로드 | **경계 확정**(나 = `GameAssetRepository` 원격 구현·`shared/api`·`studio/ports` / busypark = 에디터 UI ①~④). [PR #72](https://github.com/kanghyunsoon/ssafesta/pull/72)가 **주입 경계·Publish preflight 선반영** — 내 원격 구현이 꽂힐 자리 마련됨. BE 업로드 계약(API 형태·상태 DTO·오류 코드)만 대기 | strdeok |

## 예정 작업 — 트리거 충족 시 착수

- [ ] **#73 검증 게이트(release gate)** — 트리거: [PR #72](https://github.com/kanghyunsoon/ssafesta/pull/72) 머지(검증 대상이 그 구현). **FE 몫**: 활성 탭 성능 실측(최대 상한 fixture에서 편집 p95 100ms·플레이 55fps), 키보드 전용 조작(저장/undo/redo/레이어/1칸 이동·focus), 복구 UX 4종(손상·schema 미지원·Draft 409·로컬 Asset 차단). **사람 몫(대행 불가)**: 비개발 참가자 5명 모집·20분 세션 진행(4/5 완주·중앙값 15분) — 기록지 `specs/019-game-studio/FE/usability-test.md`. **완료 판정·정리는 내 몫**: 원자료·영상 이슈 첨부, P0/P1 문제 분리 발행 후 닫기. 리드가 자동 검증분 보강([PR #80](https://github.com/kanghyunsoon/ssafesta/pull/80), 38파일 204 tests·브라우저 QA 통과)해 **잔여는 사람 20분 세션·활성 탭 지속 FPS 실측**으로 좁혀짐
- [ ] **013a credential 전달 wiring** — 트리거: #60 계약 문서의 **develop 도달** 확인(PR #75 스택 머지). #76 타입 필드 후속을 같은 라운드에 흡수. `getAccessToken()`을 `UnitySessionManager`/`UnityHost` lifecycle에 연결(AT 원본, MEMBER only). 부가: AT 만료 401 시 Unity→host 재요청 규약은 별도 후속
- [ ] **게스트 만료 실관측 경로**(G-3) — 트리거: T016 실경로 연결 또는 실 API 소비 화면(008 등) 첫 머지. `expiresAt` 소비자 현재 0, 401 반응형 안내는 T010(`specs/001-auth-user/FE/tasks.md`)
- [ ] **편집기 팔레트 UI**(미보유 파츠 잠금·게스트 유도) — 트리거: spec 012 착수(#18)
- [ ] **스냅 0.25 계약 명문화** — 트리거: 강형순 develop 정정 PR(#45 예고) 확인. 현재 로컬 `FE/research.md` R-04에만 존재
- [ ] **`docs/26` 브랜치별 결정 분기 정리 제안** — 트리거: #59 종결(§21-2 grep 2층 개정 반영 확인 — 그 결론이 이 제안의 방향을 정함). #18에서 자청한 것

## 추적 — 내 액션 없음

- 013·016의 game발 spec 갱신 develop 미반영 / FE.md 인용 `game de38269` 커밋이 로컬·원격에 없음(016 E2E는 계약 텍스트만 필요해 무관) — Unity 확인 필요
- 013 spec.md C-01이 docs/26(V10 TEXT)로 해소됐는데 리뷰 표는 미결 표기 — Unity 소유, 임의 수정 안 함
- `docs/14`·spec 008이 front·develop에서 #32 미반영 stale — 정본 `origin/ai`. AI 소유라 미반입(#59 규칙), B는 주석에 정본 위치만 명시
- [PR #82](https://github.com/kanghyunsoon/ssafesta/pull/82)(리드)가 **#56 §3(`objectId` 산출 방식 미계약)을 해소** — 서버 응답 필수값에서 `objectId` 제거, `configId + boothId`만 일치 검증. `objectId`는 Overlay 생명주기·진입 위치용 로컬 문맥으로만 유지
- [#62](https://github.com/kanghyunsoon/ssafesta/issues/62) 부스 규격: FE 편집기 영향 없음 — BE가 `GET /booth-slots/{slotId}/layouts/published` 신설로 흡수, 슬롯 7→12
- GAME_PORTAL configId(Int32·0 금지, #34) FE 정수 검증·전송 순서 — #49(busypark)로 이관
- [PR #72](https://github.com/kanghyunsoon/ssafesta/pull/72)(리드, Game Studio 실사용 보강 35파일) 미머지 **→ 3단 스택으로 확대**: #72(→`front`)·[#79](https://github.com/kanghyunsoon/ssafesta/pull/79)(Maker 자유 편집 캔버스)·[#80](https://github.com/kanghyunsoon/ssafesta/pull/80)(local publish loop + v1.1 goals)·[#82](https://github.com/kanghyunsoon/ssafesta/pull/82)(portal 계약 수정) **4단**. **최종 base가 전부 `front`** — 머지되면 내 브랜치가 아래에서 바뀐다. API 어댑터는 `VITE_GAME_STUDIO_API_ENABLED=false` 기본(플래그 인지)
- [PR #77](https://github.com/kanghyunsoon/ssafesta/pull/77)(strdeok, #62 슬롯 기준 published 경로 + 슬롯 12 시드) OPEN — FE 편집기 영향 없음 결론 불변
- [#58](https://github.com/kanghyunsoon/ssafesta/issues/58) §5 `CURRENT_REVISION`: **십진수 문자열 ⑧ 유지 확정**(strdeok 재확정 + 리드가 019 `game-api.md` 대조로 일치 확인). 이슈 OPEN이나 내 액션 없음
- [#33](https://github.com/kanghyunsoon/ssafesta/issues/33) 확정(08-21 종결): 신규 진입 REST 차단·loaded 세션 무보상 완료 허용·일반 soft/탈퇴 hard delete·Published 이력 유지·Ranking P1 절연. 정본은 [PR #53](https://github.com/kanghyunsoon/ssafesta/pull/53)의 `specs/019-game-studio/`
- #24 back·game 잔여, #35 renderer·sandbox — 타 파트 몫

## 완료

| | 결과 |
|---|---|
| **spec 005** 08-18~22 | 전체 완결(US1~US4·Polish·인수검사·vitest 37). 상세는 작업일지·`verify/` |
| **Block A** 08-23 | 001·013a·016·002 계약 front 회수 |
| **013a WebGL Host** 08-23 | `unity/host/` 7종+`/app/world`. credential wiring만 예정 작업으로 잔류 |
| **016 E2E** 08-23 | dispatcher·overlay 2종·WorldPage. AI_CHAT 등은 공통 fallback뿐(실 UI 아님) |
| **001 Auth FE** 08-23 | ASC 구현+감사(T-17 수정). tasks 16/17 — 잔여 T016만 Blocked-on-BE |
| **D-1** 08-23 | 005 FE 산출물 develop 이관(#43 구조), [PR #64](https://github.com/kanghyunsoon/ssafesta/pull/64) 머지 |
| **D-2** 08-23 | docs/10 §3 라우트 정합, [PR #65](https://github.com/kanghyunsoon/ssafesta/pull/65) 머지 |
| **B. SSE 타입+Mock Fixture** 08-23 | #32 전사 — `entities/conversation/` 3파일. real api·wiring은 008 UI 착수 시 |
| **C. Game Studio 소켓 수신부** 08-23 | = #56 T052·T055(#20 확정 계약). `BOOTH_GAME_INTERACT` union·`GAME` 오버레이·라우팅 |
| **G-2. returnTo 복귀** 08-23 | ASC 구현(`cf44e6f`) — 저장·검증·소비 3곳. 통합 테스트 공백은 G-4 참조 |
| **정본화 라운드 PR #70** 08-23 | #59 owner 몫(docs/26·spec.md 7곳) + #17(docs/05·예시값 2파일). 검증서 codex 적중 1건(:203 인접 모순) 수정 포함. **머지 완료**, front 5파일 동기화 완료 |
| **공용 문서 재회수** 08-23 | PR #67 머지분 docs/00·17·26(`5765bca`). develop 결손은 #59 보고 |
| `BOOTH_LAPTOP_INTERACT` | 브라우저 왕복 검증(PR #25) |

## 참고

- **develop 통합 절차**: develop에서 브랜치 → 파일 단위 checkout → `git diff --stat origin/develop -- festa-unity/` 0줄 확인 → PR. 직접 push·전체 merge 금지
- **착수 전 `origin/develop` 대조 필수** — 내 브랜치의 공유 문서 사본은 낡았다고 가정
- **G-4 컴포넌트 테스트 공백**: vitest include가 `.tsx` 제외 + 라이브러리 미설치. `RequireAuth` bootstrapped 분기(T-17이 난 틈)·G-2 딥링크 흐름이 수동 실측뿐 — 도입은 팀 결정 사안
- **닫힌 이슈 재확인 습관** — CLOSED 후 마지막 코멘트에만 확정·번복이 남는 경우가 잦다. 관련 이슈가 새로 닫히면 재대조
