# FE 백로그

경위·판단 근거는 `24_작업일지.md`, 확정값은 `docs/26` 결정 로그·spec이 원본. **여기는 상태·다음 액션만 둔다.**
완료 항목은 결과 1줄. 경위·근거·검증 내용은 쓰지 않는다.

---

## 현재 상태

**front = origin/front, clean.** spec 005·Block A·013a·016·001 Auth·D-1·D-2·B·C·G-2 완료. 상세는 완료 표.

**원격은 GitLab이고 완료 경로는 `develop` 하나다**(2026-08-26 개정). 이 문서의 `github.com` 링크는 이관 전 GitHub PR 번호이며, 이슈 번호는 GitLab에서 보존됐다. 내 MR 현황은 `## MR 현황` 표.

**08-26 계약 라운드로 대기 5건이 한꺼번에 닫혔다** — [#48](https://github.com/kanghyunsoon/ssafesta/issues/48)·[#59](https://github.com/kanghyunsoon/ssafesta/issues/59)·[#60](https://github.com/kanghyunsoon/ssafesta/issues/60)·[#78](https://github.com/kanghyunsoon/ssafesta/issues/78)·[#81](https://github.com/kanghyunsoon/ssafesta/issues/81). 각 결론은 아래 해당 절로 옮겼고 대기 표에서는 뺐다.

⚠️ **Asset 업로드 계약이 develop 에 없다.** MR !44(`S15P21A604-255`)가 08-26 17:34 머지(`3d97dd9`)됐으나 **9분 뒤 `81bdf39`(AI `-93` sync 커밋 revert)가 `game-asset-upload.md` 242줄과 `game-api.md` §Asset Boundary 포인터를 함께 되감았다.** 원격 어느 브랜치에도 이 파일이 없다(전 origin 브랜치 `cat-file -e` 전수 확인). 내용은 커밋 `3d97dd9` 안에 온전하다. #69 에 보고했고 복구는 계약 소유자 몫으로 뒀다 — **`-116`·`-187` 은 선행 미충족으로 대기 유지**.

**P0 코드 몫** — 004 Lease·003 Wallet·G-1 완료(완료 표). 남은 P0 블로킹은 009(BE)·016 C-01·008 UI(AI 서버)·인프라(#30 실빌드·실서버). 즉시 착수 가능은 `-116`·`-187`·010 Survey(P1).

## MR 현황

08-27 09:06 강형순(게임 파트)이 **열린 6건 전부에 리뷰**를 남겼다. GitLab approval 은 0건(`approved_by=[]`)이고, 본인이 **범위를 한정**했다 — *"게임 파트에 영향이 없고 반입이 안전하다"* 는 뜻이지 FE 구현 품질 승인이 아니며, FE 내부 로직·React 관용구·상태 관리 설계는 판정하지 않았다. **FE 로직 리뷰는 여전히 공백**이고, FE 담당자 간 리뷰로 봐 달라는 요청이 붙어 있다.

| MR | 대상 | 상태 |
|---|---|---|
| !46 `-90` Auth 실서버 결선 | develop | mergeable. `Closes` 없음(D4 미확정) |
| !43 `-247` LJH 기록 develop 정합 | develop | mergeable |
| !42 `-254` FE Docker·runtime config | develop | mergeable |
| !37 `-153` 컴포넌트 테스트 환경 | develop | mergeable |
| !20 `-171` Lease 확인 모달 | **front** | **conflict** — 완료 경로 개정으로 대상 브랜치 재조준 필요 |
| !13 `-179` 상한 guard 이관 | **front** | mergeable이나 대상이 구 경로 |

## 내 액션 필요

- [ ] **[#69](https://github.com/kanghyunsoon/ssafesta/issues/69) §3① presigned URL 정책 — BE가 내 판단을 지명 요청**(08-26 17:30). 019는 서버가 302로 대신 열어 주고(`<img src="/api/v1/games/{g}/assets/{a}/content">`) FE resolver는 문자열 치환 하나다. 007은 presigned URL 을 FE 로 내보내 만료·재발급을 FE 가 다룬다. **007 쪽으로 통일하면 만료 관리가 FE 몫이 된다** — 유지/통일 중 택해 회신
- [ ] **!20 conflict 해소 + !13·!20 대상 브랜치 재조준** — 완료 경로가 `develop` 하나로 개정됐는데 이 둘만 `front` 행이다

## 착수 가능 — 협의 불요, 권장 순서순

**현재 없다.** 08-27 실측으로 이 절에 있던 후보가 전부 계약 미비로 내려갔다. 새 코드를 만들 자리가 없다는 뜻이 아니라, **만들면 근거 없는 구현이 된다**는 뜻이다(헌법 30조).

| 후보 | 왜 아닌가 |
|---|---|
| `-116`·`-187` Asset | 계약이 develop 에서 revert 로 사라졌다(위 현재 상태) |
| `-91` 013a AT wiring | `client.ts:38` `TODO(013a-AT)` 가 미결 축을 4개로 적어 뒀고 **[#60](https://github.com/kanghyunsoon/ssafesta/issues/60) 이 푼 것은 token type 하나**(= Access Token 원본)다. **receiver**(Unity 수신 GameObject·메서드)·**timing**·**refresh 반영**이 남아 있고, receiver 는 #56 T057 `receiverObjectName` 과 같은 미결이다 |
| `-133`·`-194` Survey | `docs/08` §9 `GET /surveys/{surveyId}/results` 가 **제목 한 줄짜리 stub** 이다 — 응답 DTO 가 없다. 집계 화면을 만들려면 내가 형식을 지어내야 한다 |
| `-195` YouTube 임베드 | 016 C-01(주소 저장 위치)이 **3파트 합동 미결** |
| `-134` Project 전시 | 009 BE 계약도 endpoint 제목만 있다 |
| `-86`·`-87`·`-88`·`-89` 실 API 결선 | real adapter(`facadeApi.ts`·`leaseApi.ts`·`wallet/api.ts`·`layout/api.ts`)는 **이미 구현돼 있다.** 남은 것은 실서버 왕복 검증인데 그 경로가 [MR !46](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/merge_requests/46)(`TokenResponse`·`status` 의존 제거)에 들어 있고 아직 머지 전이다 |

**즉, FE 병목은 코드가 아니라 ① 계약 stub ② 내 MR 6건이 머지되지 않고 쌓인 것이다.**

## 대기

| 항목 | 대기 대상 | 상대 |
|---|---|---|
| [#56](https://github.com/kanghyunsoon/ssafesta/issues/56) T056 Portal resolver·overlay adapter / T058 E2E | **계약은 완결**(#48 종료 — `contracts/game-api.md` v1.0 MR !1 머지). 남은 것은 **BE 구현 배포** — Jira `-111`(스키마·패키지)·`-112`(Draft API)·`-157`(Publish·Published 조회). 경계 2건도 확정: **Draft 없음 = 204 No Content**, 재시도 코드는 서버 `INTERNAL_ERROR` 유지(FE 판정만 정렬). T054 `objectId` 미계약은 [PR #82](https://github.com/kanghyunsoon/ssafesta/pull/82)가 답 | strdeok |
| [#56](https://github.com/kanghyunsoon/ssafesta/issues/56) T057 `OnOverlayStateChanged` 송신부 | 수신 GameObject명(`receiverObjectName`) 확정 | Unity |
| [#55](https://github.com/kanghyunsoon/ssafesta/issues/55) 잔여 E2E | **서버 비의존 FE 범위는 [PR #72](https://github.com/kanghyunsoon/ssafesta/pull/72)(리드)가 선구현**(Published loader·schema guard·상태별 오류 UI·error boundary). 잔여 = BE 구현 배포 후 실 endpoint + `-116` stable resolver 연결 뒤 browser E2E | strdeok·리드 |
| 001 T016 회원 refresh 실서버 왕복 | **D5 OAuth 자격증명 6종.** 게스트·logout 구간은 [MR !46](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/merge_requests/46)으로 결선 완료. 회원 `refresh_token` 최초 발급처가 `OAuthCompletionController` 하나뿐이라 성공·만료·재사용 탐지를 실행하지 못했다 | BE·리드 |
| `assetCode` 목록 | Unity 레지스트리 실측값(#6·#18 — 둘 다 이걸 기다리다 닫힘) | Unity |
| `-117` Published 게임 세션 포트 실 API 교체 | **Jira 담당자 미배정.** 규약은 확정([#81](https://github.com/kanghyunsoon/ssafesta/issues/81) 종료) — 019는 idempotency·잔액·동시성을 직접 구현하지 않고 **spec 003 `WalletService.spend()` 에 위임**, 게스트는 FR-023대로 무료 플레이 가능, 서버 권위 adapter 전까지 **무료·무보상 유지**, 교체 지점은 `GameSessionPort` seam 3개(start/complete/exit) | 리드(배정) |

## 예정 작업 — 트리거 충족 시 착수

- [ ] **#73 검증 게이트(release gate)** — 트리거: [PR #72](https://github.com/kanghyunsoon/ssafesta/pull/72) 머지(검증 대상이 그 구현). **FE 몫**: 활성 탭 성능 실측(최대 상한 fixture에서 편집 p95 100ms·플레이 55fps), 키보드 전용 조작(저장/undo/redo/레이어/1칸 이동·focus), 복구 UX 4종(손상·schema 미지원·Draft 409·로컬 Asset 차단). **사람 몫(대행 불가)**: 비개발 참가자 5명 모집·20분 세션 진행(4/5 완주·중앙값 15분) — 기록지 `specs/019-game-studio/FE/usability-test.md`. **완료 판정·정리는 내 몫**: 원자료·영상 이슈 첨부, P0/P1 문제 분리 발행 후 닫기. 리드가 자동 검증분 보강([PR #80](https://github.com/kanghyunsoon/ssafesta/pull/80), 38파일 204 tests·브라우저 QA 통과)해 **잔여는 사람 20분 세션·활성 탭 지속 FPS 실측**으로 좁혀짐
- [ ] **013a credential 전달 wiring — 트리거 충족, 착수 가능**(Jira `-91`). [#60](https://github.com/kanghyunsoon/ssafesta/issues/60) 종료로 확인 2건이 다 답을 받았다. **Spring REST credential 은 Access Token 원본**(NGO Connection Token 과 별개). 게임 파트가 빌드 루트에 **`manifest.json`** 을 생성한다 — `{ loaderUrl, dataUrl, frameworkUrl, codeUrl }`, 값은 빌드 base 상대 경로(예: `Build/a5b510….loader.js`), FE 는 `<빌드 base URL>/manifest.json` 을 읽는다. ⚠️ **Unity 는 재빌드 시 이전 해시 파일을 지우지 않는다**(T-212) — 빌드 폴더에 잔재가 공존하므로 **디렉터리 추측 금지, manifest 만 신뢰**. #76 타입 필드(`GET /users/me` 의 `avatarCode`) 후속을 같은 라운드에 흡수. `getAccessToken()` 을 `UnitySessionManager`/`UnityHost` lifecycle 에 연결(AT 원본, MEMBER only). 부가: AT 만료 401 시 Unity→host 재요청 규약은 별도 후속
- [ ] **게스트 만료 실관측 경로**(G-3) — 트리거: T016 실경로 연결 또는 실 API 소비 화면(008 등) 첫 머지. `expiresAt` 소비자 현재 0, 401 반응형 안내는 T010(`specs/001-auth-user/FE/tasks.md`)
- [ ] **편집기 팔레트 UI**(미보유 파츠 잠금·게스트 유도) — 트리거: spec 012 착수(#18)
- [ ] **스냅 0.25 계약 명문화** — 트리거: 강형순 develop 정정 PR(#45 예고) 확인. 현재 로컬 `FE/research.md` R-04에만 존재
- [ ] **`docs/26` 브랜치별 결정 분기 정리 제안 — 트리거 충족.** [#59](https://github.com/kanghyunsoon/ssafesta/issues/59) 가 **정본화 규칙을 팀 규칙으로 채택하고 종료**했다(08-26): ①한 사실은 한 곳에만 서술 ②공용 규약 문서의 정본은 develop(MR !24 시행) ③개정 시 그 파일 안 전수 grep. `docs/jira-gitlab-workflow.md` §12 규칙 7 에 반영됨. #18에서 자청한 것
- [ ] **develop `layout-api.md` 예시 2곳(`:215`·`:266`) `#1677C8` 잔존 보고** — 트리거: 원격 복귀. #17이 예시값 `#3B82F6` 교체로 합의·CLOSED했는데 이 파일 예시가 누락 — **예시대로 보내면 400 나는 자기모순**(팔레트 표는 정상). §21-2 grep 2층의 ②층(같은 파일 내 잔존) 실사례로 #59 참조 가치

## 추적 — 내 액션 없음

- [#78](https://github.com/kanghyunsoon/ssafesta/issues/78) GameProject v1.1 **확정·종료**(08-26): schemaVersion **1.1.0**(1.0 읽기 유지·편집 시 명시 승격), `completion.mode ALL|ANY`, objectives **0~5개·type 중복 금지**(`SCORE_AT_LEAST`|`DEFEAT_ENEMIES`|`SURVIVE_SECONDS`), `playerDefeat RESPAWN|END_GAME`. **objectives 빈 배열 = completion 판정 비활성**(거부가 아니라 의미 정의 — golden path 테스트에 포함), **target 상한 정본은 FE type별 상한**(`gameProject.ts:326`). 활성화 게이트 유지(#104 C절). FE 구현은 Jira `-155`(박준우), 성능 실측 `-156`(박준우)
- 013·016의 game발 spec 갱신 develop 미반영 / FE.md 인용 `game de38269` 커밋이 로컬·원격에 없음(016 E2E는 계약 텍스트만 필요해 무관) — Unity 확인 필요
- 013 spec.md C-01이 docs/26(V10 TEXT)로 해소됐는데 리뷰 표는 미결 표기 — Unity 소유, 임의 수정 안 함
- `docs/14`·spec 008이 front·develop에서 #32 미반영 stale — 정본 `origin/ai`. AI 소유라 미반입(#59 규칙), B는 주석에 정본 위치만 명시
- [PR #82](https://github.com/kanghyunsoon/ssafesta/pull/82)(리드)가 **#56 §3(`objectId` 산출 방식 미계약)을 해소** — 서버 응답 필수값에서 `objectId` 제거, `configId + boothId`만 일치 검증. `objectId`는 Overlay 생명주기·진입 위치용 로컬 문맥으로만 유지
- [#62](https://github.com/kanghyunsoon/ssafesta/issues/62) 부스 규격: FE 편집기 영향 없음 — BE가 `GET /booth-slots/{slotId}/layouts/published` 신설로 흡수, 슬롯 7→12
- GAME_PORTAL configId(Int32·0 금지, #34) FE 정수 검증·전송 순서 — #49(busypark)로 이관
- [PR #72](https://github.com/kanghyunsoon/ssafesta/pull/72)(리드, Game Studio 실사용 보강 35파일) 미머지 **→ 3단 스택으로 확대**: #72(→`front`)·[#79](https://github.com/kanghyunsoon/ssafesta/pull/79)(Maker 자유 편집 캔버스)·[#80](https://github.com/kanghyunsoon/ssafesta/pull/80)(local publish loop + v1.1 goals)·[#82](https://github.com/kanghyunsoon/ssafesta/pull/82)(portal 계약 수정) **4단**. **최종 base가 전부 `front`** — 머지되면 내 브랜치가 아래에서 바뀐다. API 어댑터는 `VITE_GAME_STUDIO_API_ENABLED=false` 기본(플래그 인지)
- [PR #77](https://github.com/kanghyunsoon/ssafesta/pull/77)(strdeok, #62 슬롯 기준 published 경로 + 슬롯 12 시드) OPEN — FE 편집기 영향 없음 결론 불변
- [#58](https://github.com/kanghyunsoon/ssafesta/issues/58) §5 `CURRENT_REVISION`: **십진수 문자열 ⑧ 유지 확정**(strdeok 재확정 + 리드가 019 `game-api.md` 대조로 일치 확인). **CLOSED 08-24 00:59** — 결론은 십진수 문자열 ⑧ 유지
- [#33](https://github.com/kanghyunsoon/ssafesta/issues/33) 확정(08-21 종결): 신규 진입 REST 차단·loaded 세션 무보상 완료 허용·일반 soft/탈퇴 hard delete·Published 이력 유지·Ranking P1 절연. 정본은 [PR #53](https://github.com/kanghyunsoon/ssafesta/pull/53)의 `specs/019-game-studio/`
- `layout-api.md` `CURRENT_REVISION` 행: develop 판보다 `back`의 PR #74 정정판이 정확(#58 §5 재확정 반영, 결론 동일·문장 정밀도 차이). #59 규칙대로 미반입 — back→develop 반영은 strdeok 몫
- `auth/api.mock.ts:13` `apiError()`도 `errors: []` 고정 — spec 001 소유라 팔레트 라운드 밖. T016 실경로 착수 시 함께
- #24 back·game 잔여, #35 renderer·sandbox — 타 파트 몫

## 완료

| | 결과 |
|---|---|
| **004 Lease + 003 Wallet FE + G-1** 08-24 | 전체 완결(커밋 6개 C1~C5+fix, 오프라인). `/app/booths` 슬롯 목록·임대·카운트다운·WalletBadge·거래 내역 + `useOwnerGate` StudioPage 편입. vitest 200/200(+33)·수동 실측 9종·codex 3관점(적중 3건 반영: null 직렬화·ALREADY_LEASED sentinel·만료 후 INACTIVE). real api 미검증 — BE 실서버 연결은 원격 복귀 후 |
| **FE 팔레트·field 라운드** 08-24 | 전체 완결(`field?` 타입 2곳·mock `FIELD_INVALID` 봉투·R-12 종결·12색 스와치·하이드레이션 차단·mock 팔레트 검증). 커밋 `3452395`·`9a1dc76`, vitest 167/167(+10)·브라우저 실측 3종·codex 3관점 CONFIRMED |
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
- **오프라인 가용**: `git fetch --all` 후 origin ref 23개가 로컬 객체에 있어 `git checkout origin/develop -- <path>` 계약 회수가 네트워크 없이 동작한다. 로컬 기준선 = **vitest 157/157(29 files)·build ✅·oxlint 무경고** (2026-08-24 `npm ci` 후 실측, [T-21](25_트러블슈팅.md))
- **G-4 컴포넌트 테스트 공백**: vitest include가 `.tsx` 제외 + 라이브러리 미설치. `RequireAuth` bootstrapped 분기(T-17이 난 틈)·G-2 딥링크 흐름이 수동 실측뿐 — 도입은 팀 결정 사안
- **원격 = GitLab**(`lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604`, origin 교체·GitHub은 `github` 별명). 이슈 번호 보존 이관, PR은 `gh:PR` 라벨 이슈. **작업 관리는 Jira**(`ssafy.atlassian.net` S15P21A604, 내 배정 34건 — dev/main 머지에 Jira 키 필수). push 10커밋·github 링크 치환 보류 중(2026-08-24)
- **닫힌 이슈 재확인 습관** — CLOSED 후 마지막 코멘트에만 확정·번복이 남는 경우가 잦다. 관련 이슈가 새로 닫히면 재대조
