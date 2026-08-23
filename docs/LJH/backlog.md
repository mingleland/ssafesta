# FE 백로그

경위·판단 근거는 `24_작업일지.md`, 확정값은 `docs/26` 결정 로그가 원본. **여기는 상태·다음 액션만 둔다.**
완료 항목은 결과 1줄. 경위·근거·검증 내용은 쓰지 않는다.

---

## 📍 세션 인수인계 (2026-08-23 종료 시점)

**브랜치 상태**: spec 005 완결, Block A 계약 회수 완결, 013a WebGL Host 완료, 016 E2E 완료, PR #63 반입 완료, **001 Auth FE 완료**(ASC 세션 구현+감사, T-17 수정 포함), **D-1·D-2 develop 병합 완료**([#64](https://github.com/kanghyunsoon/ssafesta/pull/64)·[#65](https://github.com/kanghyunsoon/ssafesta/pull/65)), **B(SSE 타입)·C(Game Studio 소켓 수신부) 완료**, **공용 문서 develop 재회수 완료**(PR #67 머지분 — `docs/00`·`17`·`26`, `5765bca`).

**013a WebGL Host 완료 범위**: `unity/host/`(types·resolver·loader·loader.mock·loader.select·sessionManager·UnityHost) + `/app/world` 라우트 + `events.ts`(`onWorldGateReady`) + `client.ts`(`getAccessToken`, lifecycle 미연결). single-flight(StrictMode 안전)·retry 직렬화(Quit 완료 후 재생성)·60초 타임아웃 전부 vitest 5개+브라우저 5개 시나리오로 검증.

**credential 전달은 의도적으로 미구현** — `getAccessToken()`을 read boundary로만 두고 어떤 lifecycle에도 안 걸었다. **[#60](https://github.com/kanghyunsoon/ssafesta/issues/60) BE(strdeok) 결정 완료(08-23)**: Access Token 원본 채택(신규 토큰 계층 없음), MEMBER only·GUEST 403, ③ NGO Connection Token과 무관. 계약 문서(`avatar-profile-api.md`) 반영은 strdeok 몫으로 아직 미착수. wiring 구현 자체는 "미착수 코드" 참조.

**016 E2E 완료 범위**: `features/interaction/dispatcher.ts`(LAPTOP·AI_AGENT 라우팅, 미지 type 무시)·`features/overlay/{OverlayHost,LaptopOverlay}.tsx`(URL은 `http`/`https`만 허용, iframe+새 탭+안내 동시 제공 — 차단 "감지"는 구현하지 않음, FR-007 기술 제약 기록)·`WorldPage.tsx`(Dispatcher를 화면 생명주기에 종속). vitest 8개+브라우저 9개 시나리오 검증. **AI_CHAT 등은 공통 fallback뿐** — 008·010·011 실 UI 아님.

**다음 뭘 할지 — 확정 순서**: A(001 Auth FE)·D-1·D-2·B(SSE 타입)·C(Game Studio 소켓 수신부)·G-2(returnTo) 완료 — 남은 "지금 할 수 있는 것"은 F(`docs/05` 팔레트, 소형)→E(P1) 둘. **이 목록 소진 시 FE는 blocked-only** — 잔여 P0는 전부 C-xx 확정(009·016 C-01)·타 파트(008 UI·AI 서버·#60 BE)·인프라 게이트(#30 실빌드·실서버)·Game Studio 체인([#48](https://github.com/kanghyunsoon/ssafesta/issues/48) BE 계약→[#55](https://github.com/kanghyunsoon/ssafesta/issues/55)·[#56](https://github.com/kanghyunsoon/ssafesta/issues/56) FE 몫)에 걸린다.

**추적만, 작업 안 함**: 013·016의 game발 spec 갱신이 develop에 미반영(타 파트 동기화 영역) / FE.md가 인용하는 `game de38269` 커밋이 로컬·원격에 없음 — 016 E2E는 이 SHA 확인 없이 진행했음(계약 텍스트만 필요, Unity 코드 검증 불필요했음), 미해소 그대로 남음, Unity 담당에게 별도 확인 필요 / 013 spec.md의 C-01이 docs/26(V10 TEXT 확정)에서 이미 해소됐는데 리뷰 표는 미결 표기(#59 패턴, Unity 소유라 임의 수정 안 함) / [#62](https://github.com/kanghyunsoon/ssafesta/issues/62) Unity 부스 규격 공지 — 좌표·스케일 데이터 계약 불변이라 **FE 편집기 영향 없음**(`boothId` 경로 유지). BE가 `boothId`로 방 번호를 못 쓴다는 구조를 서버에서 흡수: `GET /api/v1/booth-slots/{slotId}/layouts/published` 신설·URL 확정, 슬롯 7→12(V12) — Unity 몫 URL 교체이고 FE 소비자 없음 / **front·develop의 `docs/14_AI_Server_API_명세서.md`·`specs/008-ai-conversation-rag/spec.md`가 #32 반영 전 상태로 낡음** — 정본은 `origin/ai` 브랜치. AI 파트 소유 문서라 front에 반입하지 않음(#59 정본화 규칙), B는 코드 주석에 정본 위치만 명시하고 참조 / **Game Studio Portal Bridge 계약**(`specs/019-game-studio/contracts/game-portal-bridge.md`)이 아직 `codex/game-studio-docs-sync` 브랜치 Draft v0.3 — develop 미반영. #21 개명 결정(019=game-studio)에 따라 경로가 `feature/game-studio-foundation`의 낡은 `specs/020-game-studio/`(Draft v0.2)에서 정정됨(다른 세션이 발견해 전달, front 세션이 검증 후 코드·기록 인용 3곳 수정). C는 코드 주석에 브랜치 정본 위치만 명시(반입 없음).

상세는 `24_작업일지.md` 08-23 항목.

---

## 지금 할 수 있는 것 — 협의 불요, 권장 순서순 (완료분은 완료 표로 이동)

- [ ] **F. `docs/05` 팔레트 12색 반영 develop PR** — #17 회신에서 내가 맡기로 한 것. `layout-api.md` §6·`docs/08` 확정값을 "색상 미확정" 서술과 교체. 소형·문서 전용
- [ ] **E. (P1) 010 Survey 결과 화면** — spec 명시대로 `SSAFY_FESTA_내부설문_관련_업데이트.md` §2.5 확정 자료를 그대로 입력으로. **응답 UI는 제외**(C-05 게스트 응답 등 미결)

## Game Studio 체인 — 내 배정 2건 (#55·#56)

FE 몫이 backlog에 흩어져 있던 것을 이슈 단위로 모은다. **순서 확정**(#56 코멘트): [#48](https://github.com/kanghyunsoon/ssafesta/issues/48) Published API → BE binding·resolver·whitelist **선배포** → FE overlay adapter → #55 연결.

| 이슈 | FE 몫 | 상태 |
|---|---|---|
| [#56](https://github.com/kanghyunsoon/ssafesta/issues/56) GAME_PORTAL Binding·Booth Host | T052 `BOOTH_GAME_INTERACT` parsing+격리 테스트 · T055 Unity event union 확장 | ✅ **완료 — 아래 완료 표 "C"가 이 둘** |
| | T056 Portal resolver + Game overlay adapter | ⛔ #48 resolver endpoint 대기 |
| | T057 overlay open/close/fail 시 Booth input lifecycle | ⛔ = "미착수 코드"의 `OnOverlayStateChanged` 송신부. `receiverObjectName` 미확정 |
| | T058 browser E2E | ⛔ BE resolver 배포 후 |
| [#55](https://github.com/kanghyunsoon/ssafesta/issues/55) Published Web Runtime 진입·오류 격리 | Published loader(#48 DTO 연결) · 서버 stable `asset://` resolver · 공개/비공개/미발행/손상/미지원 schema별 오류 UI · overlay close/unmount 누수 검증 | ⛔ #48 DTO·#35 renderer 대기. PR #63 반입분(reference Runtime)은 **Local Preview까지이고 Published 완료 아님**(리드 명시) |

**#48 확정 계약**(strdeok 회신, 착수 시 전제) — 저장 모델 1개·Scene 유형 3종(`TOP_DOWN`/`PLATFORMER`/`DIALOGUE`) / Publish 상한 JSON 2,000,000 bytes·Scene 50·Scene당 Object 500·Event 300·Asset 300 / Asset 소스는 `builtin://`과 서버 발급 stable `asset://`만(`binary`·`base64`·`data:`·`blob:`·`file:`·`asset://local` 거부) / **자동 보정 없음**(clamp·unknown field drop·broken reference 치환 안 함 — 005 T-24 원칙) / **상한은 Draft 저장에도 적용**.

## 막힌 것 — 대기 중

| 항목 | 막는 것 | 상대 |
|---|---|---|
| `assetCode` 목록 | 편집기 팔레트·자산 분기 종단 검증 | Unity 레지스트리 실측값 제공(#6·#18 — **두 이슈 모두 이걸 기다리다 닫힘**) |
| 001 T016 — 게스트·refresh·logout 실경로 | real api placeholder 교체·실서버 검증 | BE 계약 문서(endpoint 3종) 회수 |
| Game Studio FE 전량(#55·#56 잔여) | Portal resolver·Published loader·overlay adapter | [#48](https://github.com/kanghyunsoon/ssafesta/issues/48) BE 구현 — 그 선행인 [PR #53](https://github.com/kanghyunsoon/ssafesta/pull/53)(spec 019 계약)이 **CONFLICTING 상태로 정체** |

## 회신 완료 — 상대 답변 대기

| 이슈 | 내가 답한 것 | 대기 |
|---|---|---|
| [#36](https://github.com/kanghyunsoon/ssafesta/issues/36) | 6건 수용 + 계약서 모순 1건 지적. **추가 요청 3건(`rule` 미문서화·`themeCode` 4값·`errors[].objectId` 예시)은 리드가 develop 반영 완료 확인**(PR #57 `b54c7f2`) | 닫기 전 #58 결론 확인 요청받음(리드) — #58이 `rule` 계약을 다시 건드림 |
| [#33](https://github.com/kanghyunsoon/ssafesta/issues/33) | Game Studio 정책 4건 전부 동의, 신규 진입 차단 판정 시점 질문 | 리드 |
| [#58](https://github.com/kanghyunsoon/ssafesta/issues/58) | 오류 봉투 C안 동의 + 근거 정정, §3 `rule`/`field` 분리 방향 제안 | **strdeok C 확정(08-23)** — back PR 머지 대기. FE 후속은 "미착수 코드" 참조 |
| [#17](https://github.com/kanghyunsoon/ssafesta/issues/17) | BE 확정 12색 전부 일치 확인, 확인 요청 2건(docs/05 소관·기존 저장값 미이관) 이견 없음 회신 완료 | **내 후속 1건 남음** — 아래 "내가 닫아야 할 것" `docs/05` 갱신 |
| [#59](https://github.com/kanghyunsoon/ssafesta/issues/59) | develop `docs/26` stale 보고(C-04·C-06이 08-18 미결 서술) + grep 범위에 `docs/26` 포함 제안. front는 `5765bca`로 정정 완료 | strdeok·리드 — develop 정정 주체 회신(내가 소형 PR로 올릴지 여부 포함) |

## 내가 닫아야 할 것

- [ ] **`docs/05` 색상 미확정 서술 갱신**(#17 회신에서 내가 맡음) — 작업 항목은 위 "지금 할 수 있는 것" **F**
- [ ] **#36 닫기 판단** — 추가 요청 3건은 develop 반영 확인됨. 리드가 "닫기 전 #58 결론 확인" 요청 → #58 back PR 머지 후 처리
- [ ] **스냅 0.25 계약 명문화** — 로컬 `FE/research.md` R-04에만 존재. develop `spec.md` FR-003 또는 contracts에 반영 필요(강형순 develop 정정 PR에 포함 예고됨 — #45)

## 미착수 코드

- [ ] **Owner/Staff 라우트 가드**(G-1, 추적 소실 복구) — 기존 router 주석("Owner/Staff Guard는 spec 001 인증 확정 후 추가")을 001 구현이 지우면서 구현·이관 없이 소실됐던 것. 현재 member/guest 2등급뿐이라 `/app/studio/:boothId`가 모든 member에게 열림(서버 FR-012가 최종 차단 — 보안 구멍 아님, UX 가드). 부스 소유 정보(004 `GET /booths/{id}`) 필요 — 001이 아니라 **004/005 접점 후속**
- [ ] **게스트 만료 실관측 경로**(G-3) — `expiresAt` 저장만 하고 소비 없음, 401 반응형 안내(T010)는 API 호출 화면이 생겨야 도달 가능. 실 API 화면 등장 시점에 재검
- [ ] **Game Studio `OnOverlayStateChanged` 송신부** = [#56](https://github.com/kanghyunsoon/ssafesta/issues/56) **T057** — Host→Unity 방향, 수신 GameObject명(`receiverObjectName`) 미확정이라 Unity 협의 후. 수신부(T052·T055)는 C로 완료. 나머지 #55·#56 잔여는 위 "Game Studio 체인" 표 참조
- [ ] **편집기 팔레트 UI** — 미보유 파츠 회색+잠금 배지, 게스트 로그인 유도(#18, spec 012 착수 시)
- [ ] `docs/26` 브랜치별 결정 분기 정리 제안 — FE가 #18에서 자청
- [ ] **오류 봉투 `field` 대응**(#58, back PR 머지 후 착수) — `ApiError.errors[]` 타입에 `field?: string` 추가, mock 3곳 정합, `PublishDialog` key를 인덱스 기반으로 정리, R-12(사전 경고 중복 제거) 해제 후 `rule` 분기 활성화
- [ ] **013a credential 전달 wiring**(#60 결정 08-23로 신규 협의 불요 후보 — 다음 판정 라운드 대상) — `getAccessToken()`을 `UnitySessionManager`/`UnityHost` lifecycle에 연결. AT를 Unity WebGL에 전달(신규 토큰 계층 없음, MEMBER only). BE 계약 문서(`avatar-profile-api.md`) 반영 여부 재확인 후 착수. 부가: AT 30분 만료 401 시 "Unity→host 재요청" 규약은 별도 후속(서버 작업 없음)

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
| **001 Auth FE** 08-23 | ASC 세션 구현 + 감사(결함 T-17 수정). tasks 16/17·quickstart 8/8·vitest 129. 잔여 T016만 Blocked-on-BE |
| **D-1. 005 FE 산출물 develop PR** 08-23 | [PR #64](https://github.com/kanghyunsoon/ssafesta/pull/64) — origin/front@a74fd43에서 7파일 회수(FE/ 5종 + verify 2종), 바이트 동일 확인. **머지 완료**(squash, 자체 병합) |
| **D-2. docs/10 §3 라우트 develop PR** 08-23 | [PR #65](https://github.com/kanghyunsoon/ssafesta/pull/65) — `/auth/callback`·게임 2종 추가 3줄. **머지 완료**(squash, 자체 병합) |
| **B. SSE 타입 + Mock Stream Fixture** 08-23 | [#32](https://github.com/kanghyunsoon/ssafesta/issues/32) 확정분 전사 — `entities/conversation/{stream.types,stream.parser,stream.mock}.ts`. discriminated union 5종·`AiErrorCode` 17종·`DEFAULT_RETRYABLE`(런타임 정본은 서버 payload)·`SseProtocolError`(unknown event strict) 별도 분리. real api·소비자 wiring은 008 UI 착수 시. vitest 146/146·tsc·oxlint 그린 |
| **C. Game Studio 소켓 수신부 2종** 08-23 | #20 확정 계약(`specs/019-game-studio/contracts/game-portal-bridge.md`, codex/game-studio-docs-sync) 전사 — `events.ts` `BOOTH_GAME_INTERACT` union·`overlay.ts` `GAME` 타입·`dispatcher.ts` 라우팅(configId 그대로 전달, 이름 변환 없음). bridge-level 테스트 신설로 런타임 통과 잠금. 송신부는 제외("미착수 코드"). vitest 157/157(신규 11)·tsc·oxlint 그린, 브라우저 실측(GAME fallback 노출) 통과 |
| **G-2. 로그인 후 원래 목적지 복귀(returnTo)** 08-23 | ASC 세션(S-20260823-80) 구현 `cf44e6f` — `features/auth/model/returnTo.ts`(WHATWG URL 파서로 origin 검증, sessionStorage 보관·읽는 즉시 삭제) + `RequireAuth` 저장·`CallbackPage`/`LoginPage` 복귀. vitest 154/154(신규 8), 독립 Verifier 우회 25케이스 재시험 0건. **미결**: 딥링크 흐름 컴포넌트 통합 테스트 부재(vitest include가 `.tsx` 제외 — G-4와 같은 틈) |
| **공용 문서 develop 재회수** 08-23 | PR #67 머지분 — `docs/00`(2종)·`docs/17` verbatim, `docs/26`은 develop 결손(C-04·C-06 stale) 보정 병합(`5765bca`). 결손 자체는 [#59](https://github.com/kanghyunsoon/ssafesta/issues/59)에 보고 |
| `BOOTH_LAPTOP_INTERACT` | 브라우저 왕복 검증 완료 (PR #25) |
| 문서 develop 통합 | `docs/26`·`FE.md` 완료 |

**해소된 미결** — C-02 좌표 / C-03 고정 크기 / C-05 `expectedRevision`(#36) / C-07 보관O·되돌리기 MVP제외 / 부스 6×6×2.72m·스냅 0.25m 확정 / C-04·C-06 기획 승인(#45) / 오류 봉투 원소 타입 / SSE payload 전체(#32) / 산출물 경로 구조(#43 채택·반영 완료) / **refresh 토큰 쿠키 도메인**(#30, TLS·도메인 구조 확정으로 해소) / **GAME_PORTAL configId 범위**(#34, Int32+0금지를 DB CHECK 제약으로 확정 — FE 정수 검증·전송 순서 확인만 #49로 이관, 신규 블로킹 아님) / **013a Unity REST credential 종류**(#60, 08-23 — Access Token 원본 채택 확정, wiring 구현은 "미착수 코드")

---

## 참고

- **develop 통합 절차**: `develop`에서 브랜치 → `git checkout <자기브랜치> -- <path>` 파일 단위 → `git diff --stat origin/develop -- festa-unity/` 0줄 확인 → PR. **직접 push·전체 merge 금지**(`festa-unity/` 1,462개 삭제)
- **착수 전 `origin/develop` 대조 필수** — 내 브랜치의 공유 문서 사본은 낡았다고 가정
- **G-4 — 컴포넌트 테스트 공백(구조적)**: vitest `include`가 `src/**/*.test.ts`라 `.tsx`가 아예 제외되고 컴포넌트 테스트 라이브러리도 미설치(plan 제약). 영향받는 곳이 둘 — `RequireAuth`의 `bootstrapped` 분기(수동 실측뿐, **T-17이 이 틈에서 났음**)와 G-2 딥링크 복귀 흐름(순수 모듈만 테스트됨). 도입 여부는 팀 결정 사안(R-10 방식)
- **닫힌 이슈 재확인 습관** — 이슈가 CLOSED돼도 후속 확정값·번복이 마지막 코멘트에만 있는 경우가 잦다. 산출물 작성 후 관련 이슈가 새로 닫히면 재대조할 것
