# FE 백로그 — spec 005

기준 문서: `specs/005-booth-studio-layout/spec.md`, `docs/sdd/parts/FE.md`, `docs/26_팀_결정_필요사항.md`
경위·판단 근거는 `24_작업일지.md`, 확정값은 `docs/26` 결정 로그가 원본. 여기는 상태·다음 액션만 둔다.
**작성 규칙**: 완료 항목은 결과·파급만 1~2줄. 경위·근거·검증 내용은 쓰지 않는다(작업일지·이슈·PR이 원본).

---

## 현재 상태 (2026-08-21)

- spec 005: 리뷰 ①②③④+BE 검토칸 전부 완료. 제목 `FE 검토 완료 (C-04 기획 승인 대기)` — 전체 "확정"은 아님
- spec 006: `plan.md`·`tasks.md` 완료. `T005`·`T006`은 왕복 검증 통과로 **잠금 해제 가능**
- Clarification: C-03·C-05 확정, C-07 후순위, **C-04 미결(기획 승인)**, **C-06 재점화(#19 footprint)**
- 013a: Unity 소유로 축소 — FE는 WebGL 호스트·Access Token 전달만. `FE.md` develop 완전 동기화 완료
- `BOOTH_LAPTOP_INTERACT` 브라우저 왕복 — ✅ 검증 완료(PR #25)
- Issue #1·#2·#5·#6·#18 CLOSED / #14·#17·#19·#20·#21·#22·#24 OPEN
- **Game Studio** (#20 front / #21 back / #22 ai) — `specs/019`는 `feature/game-studio-foundation` 브랜치 확인.
  **수직 구현 담당자 @busypark 신설** — FE는 소켓(진입·인증·Overlay·Bridge)만, Studio 내부는 이관. #20 회신 완료
- 문서 develop 통합 관리 확정(팀장 승인, 문서 전반으로 확대) — `docs/26`·`FE.md` 완료, 나머지 순차 진행

---

## 남은 액션

### 타 파트 결정 대기

- [ ] **C-04** 콘텐츠 미연결 오브젝트의 공개를 막는가 — 기획 승인. FE 의견은 "막지 않고 경고".
      **spec 005 전체 확정의 마지막 조건**
- [ ] **C-05 잔여** 요청 스키마의 `version` 위치 — BE 착수 시 확정
- [ ] **LAPTOP 주소 저장 위치** — BE `booths` URL 컬럼 추가 확인. 방향은 부스 단위 1개로 이미 수렴
- [ ] **C-06** 템플릿 종수 — 보류 판단이었으나 **[#19](https://github.com/kanghyunsoon/ssafesta/issues/19)로 재점화**.
      Unity가 셸을 6×6m 임의값으로 만들어 둔 상태라 footprint 확정이 필요해짐. FE는 여전히 설정값 주입 구조 유지

### Unity 이슈 — 재회신 대기 / 신규

- [x] [#19](https://github.com/kanghyunsoon/ssafesta/issues/19) template·footprint — **회신 오면 C-06 닫히고 FR-003 스냅 간격 착수 가능**
- [x] [#17](https://github.com/kanghyunsoon/ssafesta/issues/17) 색상 — `docs/26`의 Facade 저장 계약 미결이 선행 조건
- [x] [#18](https://github.com/kanghyunsoon/ssafesta/issues/18) 파츠 잠금 — ✅ CLOSED, 4건 확정. 구현 계약은 spec 012 착수 시
- [x] **[#20](https://github.com/kanghyunsoon/ssafesta/issues/20) Game Studio** — 회신 완료(9개 항목 전부).
      **핵심 답**: 앱은 `festa-frontend` 내부 lazy 라우트(인증 공유가 결정적) / 경로는 `/app/games/:gameId/edit|play`
      (제안된 `/studio/:gameId`가 `/app/` 규약 이탈 + Booth Studio와 이름 충돌) / lifecycle은 `OnOverlayStateChanged` 단일 진입점.
      renderer·asset resolver·validation 시점·Studio 화면은 @busypark 이관. **`specs/019` contracts 편집 주체 확인 대기**

### FE 착수 가능

- [x] `events.ts` `AI_AGENT_INTERACT` 타입 + `toAiChatPayload` 매핑 ✅ 08-20 (`d1bbb4a`)
- [ ] **블록 4 speckit 파이프라인** — `.specify/feature.json` 지정 → `clarify` → `plan` → `tasks` → `implement`.
      C-04 확정 전 착수하면 잠정 상태
- [ ] **Game Studio 소켓 4종** — #20 승인 후 착수. `events.ts`에 `BOOTH_GAME_INTERACT` union 멤버 /
      `overlay.ts`에 `GAME` 타입 / 라우터 lazy 연결부 / Host→Unity lifecycle 송신부.
      `features/game-entry/`(`configId → gameId`)는 [#21](https://github.com/kanghyunsoon/ssafesta/issues/21) BE 계약 확정 후

### 선행 조건 대기

- [ ] FE가 쓸 `assetCode` 목록 제공 → Unity 카탈로그 확장 후 "서로 다른 자산이 실제로 선택되는지" 종단 검증.
      목록 제공 시 last-wins 주의사항 있음 (`verify/block1-roundtrip.md`)

---

## 완료

- **블록 0** Architecture 4종 ✅ 08-18 — `overlay.ts`·`events.ts`·`client.ts` + `app/{router,providers}` 스캐폴딩
- **블록 1** 좌표 왕복 검증 통과 ✅ 08-20 — C-02 실측 확정, SC-004 근거 확보,
      부수로 Unity 계약 불일치 3건 정합. 수치·재현 절차: `verify/block1-roundtrip.md`
- **블록 2** 타 파트 결정 5건 소진 ✅ 08-20 — C-03 고정 크기 / C-05 낙관적 잠금 / C-06 보류 /
      LAPTOP 부스 단위 수렴 / Facade는 005 범위 제외
- **블록 3** FE·BE 몫 완료 ✅ 08-20 — 리뷰 4칸+BE 검토칸 서명, SC-002 해소, 제목 정정

---

## 절차 — 문서 develop 통합 (2026-08-20 확정, MM 팀장 승인)

**방식**: 각 파트가 자기 변경분만 develop PR로 올려 누적. 남의 변경분을 대신 옮기지 않는다.

`develop`에서 브랜치 생성 → `git checkout <자기브랜치> -- <path>` 파일 단위 sync →
`git diff --stat origin/develop -- festa-unity/` 0줄 확인 → `docs(sdd):` 커밋 → PR.
가져올 때는 `git checkout origin/develop -- <path>`.

- **직접 push 금지**(`docs/17` §2, T-7) / **전체 merge 금지** — `festa-unity/` 1,462개 파일이 조용히 삭제됨(PR #3 실측). 근본 해결 2건은 `docs/26` ①표 16·17번
- [x] `docs/26` — [PR #23](https://github.com/kanghyunsoon/ssafesta/pull/23) 병합(`develop aeccede`), front 사본 교체
- [x] `docs/sdd/parts/FE.md` — ✅ 완료. PR [#25](https://github.com/kanghyunsoon/ssafesta/pull/25)(강형순, spec 016 브리지+브라우저 왕복 검증) + PR [#26](https://github.com/kanghyunsoon/ssafesta/pull/26)(013a 담당 경계) 둘 다 병합, front도 완전 동기화
- [ ] back·game 몫(`09`·`27`·`README`)은 [Issue #24](https://github.com/kanghyunsoon/ssafesta/issues/24)로 안내 — 파트별 현황표도 그쪽

---

## 별건 — 문서 정합 (병합 시점 처리)

- spec 013 vs 헌법 25조 — 충돌 아님으로 판정, 조치 없음
- front `specs/013/spec.md`가 "FE는 창 이관만"·FR-020 웹 이관·C-07 React 이관 방식 기술.
  `FE.md`만 갱신된 상태 — 013 진행 시 정리 필요
