# FE 백로그 — spec 005

기준 문서: `specs/005-booth-studio-layout/spec.md`, `docs/sdd/parts/FE.md`, `docs/26_팀_결정_필요사항.md`
경위·판단 근거는 `24_작업일지.md`, 확정값은 `docs/26` 결정 로그가 원본. 여기는 상태·다음 액션만 둔다.

---

## 현재 상태 (2026-08-20)

- spec 005: 리뷰 ①②③④+BE 검토칸 전부 완료. 제목 `FE 검토 완료 (C-04 기획 승인 대기)` — 전체 "확정"은 아님
- spec 006: `plan.md`·`tasks.md` 완료. `T005`·`T006`은 왕복 검증 통과로 **잠금 해제 가능**
- Clarification: C-03·C-05 확정, C-07 후순위, **C-04 미결(기획 승인)**, **C-06 재점화(#19 footprint)**
- Unity 신규 계약 요청 3건(#17 색상 / #18 파츠 잠금 / #19 template·footprint) — **FE 회신 완료, 재회신 대기**
- 013a: Unity 소유로 축소 — FE는 WebGL 호스트·Access Token 전달만
- Issue #1·#2·#5·#6 CLOSED / #14·#17·#18·#19 OPEN
- `docs/26` develop 통합 관리 확정(팀장 승인) — 병합 PR #23 리뷰 대기

---

## 남은 액션

### 타 파트 결정 대기

- [ ] **C-04** 콘텐츠 미연결 오브젝트의 공개를 막는가 — 기획 승인. FE 의견은 "막지 않고 경고".
      **spec 005 전체 확정의 마지막 조건**
- [ ] **C-05 잔여** 요청 스키마의 `version` 위치 — BE 착수 시 확정
- [ ] **LAPTOP 주소 저장 위치** — BE `booths` URL 컬럼 추가 확인. 방향은 부스 단위 1개로 이미 수렴
- [ ] **C-06** 템플릿 종수 — 보류 판단이었으나 **[#19](https://github.com/kanghyunsoon/ssafesta/issues/19)로 재점화**.
      Unity가 셸을 6×6m 임의값으로 만들어 둔 상태라 footprint 확정이 필요해짐. FE는 여전히 설정값 주입 구조 유지

### Unity 신규 이슈 3건 — FE 회신 완료, 재회신 대기 (2026-08-20)

- [x] **[#19](https://github.com/kanghyunsoon/ssafesta/issues/19) template·footprint** — 회신 완료.
      **footprint는 서버 SSOT 제안**(BE가 영역 검증에 필요 + 편집기는 Unity 없이 뜸). `template → 셸 프리팹 1:1`·MVP 1종 동의.
      **6×6m 확정 전 파츠 12개 실배치 확인 요청** — 헌법 22조 상한 12개는 되돌리기 비쌈.
      → 회신 오면 **C-06 닫히고 FR-003 스냅 간격 착수 가능**
- [x] **[#17](https://github.com/kanghyunsoon/ssafesta/issues/17) 색상 계약** — 회신 완료.
      팔레트 방식 동의 + **저장은 hex 문자열, 입력만 팔레트로 제한** 제안(나중에 자유 입력 열어도 데이터 층 불변).
      **Facade `primaryColor`는 저장 endpoint 부재로 지금 불가** — `docs/26` Facade 저장 계약 미결이 선행.
      오브젝트 단위 확장 비용 표로 제시(가장 비싼 건 Layout 계약 재개방 = FR-013 3파트 재합의)
- [x] **[#18](https://github.com/kanghyunsoon/ssafesta/issues/18) 파츠 잠금** — 회신 완료.
      BE(황덕) 선회신과 **3건 일치 확인** — 검증 시점(Draft+Publish), 거부 형식(부분 거부 + id 배열), 카탈로그 SSOT(Spring).
      BE 질문(스태프 인벤토리 개인/부스 단위)에 **부스 단위 권고** — 개인 단위면 스태프 이탈 시 공개된 부스가 SC-004 위반으로 전환.
      FE UX: 미보유는 회색+잠금 배지(숨기지 않음), 게스트는 로그인 유도 분기

### FE 착수 가능

- [x] `events.ts`에 `AI_AGENT_INTERACT { boothId, objectId, configId }` 타입 추가 ✅ 08-20 (`d1bbb4a`) —
      discriminated union으로 확장 + `toAiChatPayload`(`configId`→`agentId`) 추가. `npm run build` 오류 0건.
      담당 선 확인: 역할분담 §2.3·§5.2가 Bridge·Dispatcher를 이정헌으로 규정(`AIChatOverlay` 화면은 김가현 소관이라 제외)
- [ ] **블록 4 speckit 파이프라인** — `.specify/feature.json` 지정 → `/speckit-clarify` → `plan` → `tasks` → `implement`.
      `plan` 구조는 `specs/006-booth-runtime/plan.md` 참조. C-04 확정 전 착수하면 잠정 상태

### 선행 조건 대기

- [ ] FE가 쓸 `assetCode` 목록 제공 → Unity 카탈로그 확장 후 "서로 다른 자산이 실제로 선택되는지" 종단 검증.
      목록 제공 시 last-wins 주의사항 있음 (`verify/block1-roundtrip.md`)
- [ ] `BOOTH_LAPTOP_INTERACT` 브라우저 왕복 — WebGL 빌드 후 (에디터는 로그만)

---

## 완료

- **블록 0** Architecture 4종 ✅ 08-18 — `overlay.ts`·`events.ts`·`client.ts` + `app/{router,providers}` 스캐폴딩
- **블록 1** 좌표 왕복 검증 통과 ✅ 08-20 — C-02 실측 확정, SC-004 근거 확보,
      부수로 Unity 계약 불일치 3건 정합. 수치·재현 절차: `verify/block1-roundtrip.md`
- **블록 2** 타 파트 결정 5건 소진 ✅ 08-20 — C-03 고정 크기 / C-05 낙관적 잠금 / C-06 보류 /
      LAPTOP 부스 단위 수렴 / Facade는 005 범위 제외
- **블록 3** FE·BE 몫 완료 ✅ 08-20 — 리뷰 4칸+BE 검토칸 서명, SC-002 해소, 제목 정정

---

## 절차 — 문서 서명 (develop PR 방식, 08-20 확정)

`develop`에서 브랜치 생성 → `git checkout <owner-branch> -- <path>`로 **파일 단위** sync →
`git diff --stat origin/develop -- festa-unity/` 0줄 확인 → `docs(sdd):` 커밋 → PR.

- **직접 push 금지** (`docs/17` §2) — T-7에서 이 실수를 자체 발견·정정
- **전체 merge 금지** — `front`/`back`/`ai`의 `festa-unity/` 삭제 이력 때문에 1,462개 파일이 조용히 삭제됨.
  근본 해결 제안 2건은 미결: `docs/26` ①표 16·17번

---

## 별건 — `docs/26` 3-way 병합 ([PR #23](https://github.com/kanghyunsoon/ssafesta/pull/23) 리뷰 대기)

`docs/26`이 브랜치별로 각자 갱신되어 서로의 결정을 못 보던 상태. **MM 채널 팀장 승인으로 develop 통합 관리 확정**(2026-08-20), PR #23이 그 첫 실행.

**실증 사례**: `back`에만 있던 08-19 코인 정책(**현금 충전 영구 제외** 등)을 모른 채 #18 회신을 쓸 뻔했다. 파츠 유료화 전제를 바꾸는 내용이라 게시 직전 발견해 반영.

- [x] 세 버전 병합 — 공통 조상 `c08c4b0` 하나, 공동 수정 행은 1개("Booth Layout 스키마", front 채택). front 베이스 + back 4행 + game 3행. `game` 중복 행 1건 정리. 커밋 `dcf3e8a`
- [x] 검증 — 유실 0건(대체된 구버전만 제외), 문구 완전 일치 7/7, 중복 0. **정렬 결함 1건 발견·수정**(`662dfa1`, T-8)
- [ ] **PR #23 리뷰·병합 대기** — 각 파트가 자기 결정 이관 확인
- [ ] 병합 후 `front`의 `docs/26`을 develop 통합본으로 교체 (현재 front엔 back·game 결정이 없음)

**재분기 방지 규칙** (PR #23 본문에 명시): 새 결정은 각자 브랜치가 아니라 `develop` PR로. 가져올 때는 `git checkout origin/develop -- docs/26_팀_결정_필요사항.md`

---

## 별건 — 문서 정합 (병합 시점 처리)

- spec 013 vs 헌법 25조 — 충돌 아님으로 판정, 조치 없음
- front `specs/013/spec.md`가 "FE는 창 이관만"·FR-020 웹 이관·C-07 React 이관 방식 기술.
  `FE.md`만 갱신된 상태 — 013 진행 시 정리 필요
