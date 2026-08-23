# FE 백로그

경위·판단 근거는 `24_작업일지.md`, 확정값은 `docs/26` 결정 로그·spec이 원본. **여기는 상태·다음 액션만 둔다.**
완료 항목은 결과 1줄. 경위·근거·검증 내용은 쓰지 않는다.

---

## 현재 상태

**front = origin/front, clean.** spec 005·Block A·013a·016·001 Auth·D-1·D-2·B·C·G-2 완료, 공용 문서 develop 재회수 완료(PR #67 머지분). 상세는 완료 표.

착수 가능 목록 소진 시 **FE는 blocked-only** — 잔여 P0는 전부 C-xx 확정(009·016 C-01)·타 파트(008 UI·AI 서버)·인프라(#30 실빌드·실서버)·Game Studio 체인(#48→#55·#56)에 걸린다.

## 착수 가능 — 협의 불요, 권장 순서순

- [ ] **F. `docs/05` 팔레트 12색 반영 develop PR** — #17 회신에서 내가 맡은 것. `layout-api.md` §6·`docs/08` 확정값으로 "색상 미확정" 서술 교체. 소형·문서 전용
- [ ] **E. (P1) 010 Survey 결과 화면** — `SSAFY_FESTA_내부설문_관련_업데이트.md` §2.5 확정 자료를 입력으로. **응답 UI 제외**(C-05 게스트 응답 등 미결)

## 대기

| 항목 | 대기 대상 | 상대 |
|---|---|---|
| [#56](https://github.com/kanghyunsoon/ssafesta/issues/56) T056 Portal resolver·overlay adapter / T058 E2E | [#48](https://github.com/kanghyunsoon/ssafesta/issues/48) BE resolver·whitelist 배포 — 선행 [PR #53](https://github.com/kanghyunsoon/ssafesta/pull/53)(spec 019 계약)이 CONFLICTING 정체 | strdeok·리드 |
| [#56](https://github.com/kanghyunsoon/ssafesta/issues/56) T057 `OnOverlayStateChanged` 송신부 | 수신 GameObject명(`receiverObjectName`) 확정 | Unity |
| [#55](https://github.com/kanghyunsoon/ssafesta/issues/55) Published loader·`asset://` resolver·상태별 오류 UI | #48 DTO·#35 renderer. PR #63 반입분은 Local Preview까지 — Published 완료 아님(리드 명시) | strdeok·busypark |
| 001 T016 게스트·refresh·logout 실경로 | BE 계약 문서(endpoint 3종) 회수 | BE |
| `assetCode` 목록 | Unity 레지스트리 실측값(#6·#18 — 둘 다 이걸 기다리다 닫힘) | Unity |
| [#36](https://github.com/kanghyunsoon/ssafesta/issues/36) 닫기 | #58 back 구현 PR 머지 후(리드 요청 — C안 결정은 났고 rule/field 구현·문서 확정이 남음. 추가 요청 3건은 PR #57로 develop 반영 확인 완료) | strdeok |
| [#33](https://github.com/kanghyunsoon/ssafesta/issues/33) 신규 진입 차단 판정 시점 답변 | 리드 회신 | 리드 |
| [#58](https://github.com/kanghyunsoon/ssafesta/issues/58) C 확정(08-23) | back 구현 PR 머지 → FE 후속은 예정 작업 | strdeok |
| [#59](https://github.com/kanghyunsoon/ssafesta/issues/59) develop `docs/26` stale(C-04·C-06) 보고 | develop 정정 주체 회신(내 소형 PR 여부 포함) | strdeok·리드 |
| [#60](https://github.com/kanghyunsoon/ssafesta/issues/60) AT 채택 확정(08-23) | `avatar-profile-api.md` 계약 문서 반영 → wiring 착수는 예정 작업 | strdeok |

## 예정 작업 — 트리거 충족 시 착수

- [ ] **오류 봉투 `field` 대응** — 트리거: #58 back PR 머지. `ApiError.errors[]`에 `field?: string`, mock 3곳, `PublishDialog` key 인덱스화, R-12 해제 후 `rule` 분기
- [ ] **013a credential 전달 wiring** — 트리거: #60 계약 문서 반영 확인. `getAccessToken()`을 `UnitySessionManager`/`UnityHost` lifecycle에 연결(AT 원본, MEMBER only). 부가: AT 만료 401 시 Unity→host 재요청 규약은 별도 후속
- [ ] **Owner/Staff 라우트 가드**(G-1) — 트리거: 004 `GET /booths/{id}` 부스 소유 정보. 현재 member/guest 2등급이라 `/app/studio/:boothId`가 전 member에 열림(서버 FR-012가 최종 차단 — UX 가드일 뿐)
- [ ] **게스트 만료 실관측 경로**(G-3) — 트리거: T016 실경로 연결 또는 실 API 소비 화면(008 등) 첫 머지. `expiresAt` 소비자 현재 0, 401 반응형 안내는 T010(`specs/001-auth-user/FE/tasks.md`)
- [ ] **편집기 팔레트 UI**(미보유 파츠 잠금·게스트 유도) — 트리거: spec 012 착수(#18)
- [ ] **스냅 0.25 계약 명문화** — 트리거: 강형순 develop 정정 PR(#45 예고) 확인. 현재 로컬 `FE/research.md` R-04에만 존재
- [ ] **`docs/26` 브랜치별 결정 분기 정리 제안** — 트리거: #59 회신 도착(정본화·grep 범위 결론이 이 제안의 방향을 정함). #18에서 자청한 것

## 추적 — 내 액션 없음

- 013·016의 game발 spec 갱신 develop 미반영 / FE.md 인용 `game de38269` 커밋이 로컬·원격에 없음(016 E2E는 계약 텍스트만 필요해 무관) — Unity 확인 필요
- 013 spec.md C-01이 docs/26(V10 TEXT)로 해소됐는데 리뷰 표는 미결 표기 — Unity 소유, 임의 수정 안 함
- `docs/14`·spec 008이 front·develop에서 #32 미반영 stale — 정본 `origin/ai`. AI 소유라 미반입(#59 규칙), B는 주석에 정본 위치만 명시
- Game Studio Portal Bridge 계약 정본 = `origin/codex/game-studio-docs-sync`의 `specs/019-game-studio/`(Draft v0.3, 019 개명은 #21) — develop 미반영
- [#62](https://github.com/kanghyunsoon/ssafesta/issues/62) 부스 규격: FE 편집기 영향 없음 — BE가 `GET /booth-slots/{slotId}/layouts/published` 신설로 흡수, 슬롯 7→12
- GAME_PORTAL configId(Int32·0 금지, #34) FE 정수 검증·전송 순서 — #49(busypark)로 이관
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
| **공용 문서 재회수** 08-23 | PR #67 머지분 docs/00·17·26(`5765bca`). develop 결손은 #59 보고 |
| `BOOTH_LAPTOP_INTERACT` | 브라우저 왕복 검증(PR #25) |

## 참고

- **develop 통합 절차**: develop에서 브랜치 → 파일 단위 checkout → `git diff --stat origin/develop -- festa-unity/` 0줄 확인 → PR. 직접 push·전체 merge 금지
- **착수 전 `origin/develop` 대조 필수** — 내 브랜치의 공유 문서 사본은 낡았다고 가정
- **G-4 컴포넌트 테스트 공백**: vitest include가 `.tsx` 제외 + 라이브러리 미설치. `RequireAuth` bootstrapped 분기(T-17이 난 틈)·G-2 딥링크 흐름이 수동 실측뿐 — 도입은 팀 결정 사안
- **닫힌 이슈 재확인 습관** — CLOSED 후 마지막 코멘트에만 확정·번복이 남는 경우가 잦다. 관련 이슈가 새로 닫히면 재대조
