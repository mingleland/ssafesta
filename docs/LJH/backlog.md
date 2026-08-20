# FE 백로그 — spec 005

기준 문서: `specs/005-booth-studio-layout/spec.md`, `docs/sdd/parts/FE.md`, `docs/26_팀_결정_필요사항.md`
경위·판단 근거는 `24_작업일지.md`, 확정값은 `docs/26` 결정 로그가 원본. 여기는 상태·다음 액션만 둔다.

---

## 현재 상태 (2026-08-20)

- spec 005: 리뷰 ①②③④+BE 검토칸 전부 완료. 제목 `FE 검토 완료 (C-04 기획 승인 대기)` — 전체 "확정"은 아님
- spec 006: `plan.md`·`tasks.md` 완료. `T005`·`T006`은 왕복 검증 통과로 **잠금 해제 가능**
- Clarification: C-03·C-05 확정, C-06 보류, C-07 후순위, **C-04만 진짜 미결**
- 013a: Unity 소유로 축소 — FE는 WebGL 호스트·Access Token 전달만
- Issue #1·#2·#5·#6 전부 CLOSED

---

## 남은 액션

### 타 파트 결정 대기

- [ ] **C-04** 콘텐츠 미연결 오브젝트의 공개를 막는가 — 기획 승인. FE 의견은 "막지 않고 경고".
      **spec 005 전체 확정의 마지막 조건**
- [ ] **C-05 잔여** 요청 스키마의 `version` 위치 — BE 착수 시 확정
- [ ] **LAPTOP 주소 저장 위치** — BE `booths` URL 컬럼 추가 확인. 방향은 부스 단위 1개로 이미 수렴
- [ ] **C-06** 템플릿 종수 — 보류 판단(지금 결정 불요). FE는 스냅 간격·부스 크기를 설정값으로 두고 확정 후 주입

### FE 착수 가능

- [ ] `events.ts`에 `AI_AGENT_INTERACT { boothId, objectId, configId }` 타입 추가 →
      `configId`를 `agentId`로 매핑해 `openOverlay('AI_CHAT', { boothId, agentId })` 연결.
      계약은 3파트 확정 완료(Issue #2)
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

## 별건 — 문서 정합 (병합 시점 처리)

- `docs/26` front·game 분기 — front가 최신본, 병합 때 front 채택
- spec 013 vs 헌법 25조 — 충돌 아님으로 판정, 조치 없음
- front `specs/013/spec.md`가 "FE는 창 이관만"·FR-020 웹 이관·C-07 React 이관 방식 기술.
  `FE.md`만 갱신된 상태 — 013 진행 시 정리 필요
