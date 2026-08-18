# FE 백로그 — spec 005 착수

개인 작업 공간. `.gitignore:23`로 커밋 제외.
최종 갱신: 2026-08-18 — 블록 0 종결

기준 문서: `specs/005-booth-studio-layout/spec.md`, `docs/sdd/parts/FE.md`, `docs/26_팀_결정_필요사항.md`

---

## 현재 상태

- spec 005: `spec.md`만 존재. `plan.md`·`tasks.md` 없음
- spec 006: `plan.md`·`tasks.md` 완료. `T005`·`T006`이 "⛔ 005 Layout 계약 확정 대기"로 차단 중
- 리뷰 4칸: ①②③ 작성 완료(2026-08-14), ④ 왕복 검증 미수행, BE 검토칸 공란
- 013a: Unity 소유로 축소 확정 — FE는 WebGL 호스트·Access Token 전달만

---

## 블록 0 — Architecture 문서 ✅ 종결 (2026-08-18)

근거: `docs/sdd/parts/FE.md:22` — "spec 001/005보다 먼저 이정헌이 Architecture 문서로 확정한다"

- [x] Overlay Platform 구조 — `festa-frontend/src/app/{router,providers}` 스캐폴딩
- [x] `openOverlay(type, payload)` 계약 — `shared/types/overlay.ts`
- [x] Unity→React Event 구조 — `unity/bridge/events.ts`, `BOOTH_LAPTOP_INTERACT { boothId, objectId, url? }`
- [x] DTO Convention — `shared/api/client.ts`의 `ApiError` (`docs/10`§5·`docs/08`§1 정합)

커밋: `dc1e94b`~`9dd61d9` (7개), 전부 `origin/front` 푸시 완료.

검증:
- `npm run build` 오류 0건, 브라우저 콘솔로 3파일 동작 확인
- Codex 독립 검증(gpt-5.6-sol) — BLOCKER 1(`url` 선택 필드 지적)은 `spec 016` FR-009 근거로 반박·기각, MINOR 2건(미사용 `getOverlay` 제거, 일지 과장 표현 수정) 반영
- **[Issue #2](https://github.com/kanghyunsoon/ssafesta/issues/2)에서 강형순(Unity 리드)이 `events.ts` 계약 확인** — "url은 선택값으로 두는 것이 맞다"고 독립 확인, Codex 반박 판단과 일치. 단 Unity 측 `BOOTH_LAPTOP_INTERACT` 송신부는 아직 미구현(Unity 후속 과제, FE 소관 아님)

미완: 김가현(AI) 쪽 `openOverlay` 확인 응답 없음. **블록 0 종결 조건은 아님**(FE 단독 확정 사항) — 별도 추적.

메모: 013a 축소로 아바타 오버레이는 빠졌으나 AI 채팅·설문·상담·016 노트북이 남아 여전히 필요.

---

## 블록 1 — 좌표 왕복 검증 1회 (블록 2 대기 중 병렬)

C-02 규칙은 확정됨 — 미터 / 부스 바닥 중앙 원점 / +Z 정면 / rotationY 0=+Z.
spec 원문: "말로 합의하고 넘어가지 말 것 — 부호 하나는 반드시 틀린다."

- [ ] **선행**: 게임 담당에 Unity canonical 매핑 푸시 요청
      `origin/game`에 아직 `SURVEY_KIOSK`·`CONSULTATION_DESK` 없음 (구 별칭 `Survey`/`ConsultDesk`만)
      확인: `git grep -n "SURVEY_KIOSK" origin/game -- '*.cs'` → 0건
- [ ] 오브젝트 1개짜리 Layout JSON 전송 (편집기 불필요, curl로 충분)
- [ ] 검증 대상에 `SURVEY_KIOSK`·`CONSULTATION_DESK` 포함 — 좌표만 보면 신규 매핑이 실제로 도는지 확인 안 됨
- [ ] Unity에서 같은 위치인지 육안 확인

완료 시: 리뷰 ④칸 ☑, SC-004 근거 확보, 006 `T005`·`T006` 잠금 해제

---

## 블록 2 — 타 파트 결정 (요청은 지금, 회신 대기)

회의 안건 5건을 한 번에 상정. 개별 확인으로 쪼개면 왕복만 늘어남.

### 계약 동결 전 필수 3건

- [ ] **C-03** 오브젝트 크기 조절 — 기획 + Unity. 허용 시 `scale` 추가 → 3파트 재합의. FE 의견: MVP 제외
- [ ] **C-05** 동시 편집 — BE + FE. `PUT /layouts/draft`에 충돌 감지 없음. FE 제안: `version` 낙관적 잠금 + 409
- [ ] **C-06** 템플릿 종수 — 기획. **가장 아픔** — 부스 크기가 정해져야 FR-003 스냅 간격·영역 이탈 검증값이 나옴. 편집기 그리드 코어

### 범위 확정 2건 (docs/26 등록분)

- [ ] **LAPTOP 주소 저장 위치** — `booths`에 URL 컬럼 신규 추가 의견. 016 C-02(여러 개 허용 여부)와 묶어야 함
- [ ] **Facade 저장 계약** — 조회 API·DB 컬럼 4종은 있고 저장 endpoint만 없음. 신규 FR 제안 상태라 005 범위 포함 여부부터

### 착수 안 막음 (후순위)

- C-04 미연결 공개 — publish validation 동작. 후반 작업
- C-07 공개 이력 — BE 소관. FE는 `version` 단조 증가만 유지

---

## 블록 3 — spec 확정 (블록 2 회신 후)

- [ ] BE 검토칸 작성 — 현재 `BE 검토: ______ / 검토일: ______`
- [ ] ④ 왕복 검증 ☑ 기입 (블록 1 결과)
- [ ] SC-002 `[NEEDS CLARIFICATION: 목표 수치]` 해소 — 대응 C-xx가 없음. `/speckit-clarify`로 등록하거나 006 C-01(갱신 트리거)에서 역산
- [ ] 제목 `리뷰 — FE 1차 검토 (확정 아님)` → 확정 상태로 교체

---

## 블록 4 — speckit 파이프라인

- [ ] `.specify/feature.json`을 `specs/005-booth-studio-layout`으로 지정 (추적 파일 아님 — 이 머신에서 재설정 필요)
- [ ] `/speckit-clarify`
- [ ] `/speckit-plan` — `specs/006-booth-runtime/plan.md` 구조 참조
- [ ] `/speckit-tasks`
- [ ] `/speckit-implement`

---

## 순서

```
블록 0 (Architecture 문서 4종) ✅ 종결
      │
      ├─ 블록 1 (왕복 검증)                ← Issue #1 응답 대기 (Unity canonical 매핑)
      └─ 블록 2 요청 (안건 5건 상정)        ← 미착수, 다음 액션

블록 2 회신 ─→ 블록 3 (spec 확정) ─→ 블록 4 (plan/tasks/implement)
```

블록 1·2요청은 동시 출발 가능. 블록 2는 아직 아무 데도 상정 안 함 — 지금 유일하게 손댈 수 있는 다음 액션.

---

## 별건 — 문서 정합 (병합 시점 처리)

- `docs/26` front·game 분기 — front가 최신본. 병합 때 front 채택
- spec 013 vs 헌법 25조 — 25조 문언은 "텍스트 입력·외부 콘텐츠" 한정이라 아바타 편집은 해당 없음. 충돌 아님으로 판정, 조치 없음
- 단 front `specs/013/spec.md`는 여전히 "FE는 창 이관만"·FR-020 웹 이관·C-07 React 이관 방식 기술. `FE.md`만 갱신된 상태 — 013 진행 시 정리 필요
