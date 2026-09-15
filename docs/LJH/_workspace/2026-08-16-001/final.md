# FE 백로그 — spec 005 착수

개인 작업 공간. `.gitignore:23`로 커밋 제외.
최종 갱신: 2026-08-16

기준 문서: `specs/005-booth-studio-layout/spec.md`, `docs/sdd/parts/FE.md`, `docs/26_팀_결정_필요사항.md`

---

## 현재 상태

- spec 005: `spec.md`만 존재. `plan.md`·`tasks.md` 없음
- spec 006: `plan.md`·`tasks.md` 완료. `T005`·`T006`이 "⛔ 005 Layout 계약 확정 대기"로 차단 중
- 리뷰 4칸: ①②③ 작성 완료(2026-08-14), ④ 왕복 검증 미수행, BE 검토칸 공란
- 013a: Unity 소유로 축소 확정 — FE는 WebGL 호스트·Access Token 전달만

---

## 블록 0 — Architecture 문서 (FE 단독, 대기 없음) ⬅ 착수점

근거: `docs/sdd/parts/FE.md:22` — "spec 001/005보다 먼저 이정헌이 Architecture 문서로 확정한다"

- [ ] Overlay Platform 구조
- [ ] `openOverlay(type, payload)` 계약
- [ ] Unity→React Event 구조 — 첫 소비자는 016 `BOOTH_LAPTOP_INTERACT { boothId, objectId, url }`
- [ ] DTO Convention

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
지금 ─┬─ 블록 0 (Architecture 문서 4종)    ← FE 단독, 대기 없음
      ├─ 블록 1 (왕복 검증)                ← Unity 푸시만 받으면 됨
      └─ 블록 2 요청 (안건 5건 상정)        ← 던져놓고 위 둘 진행

블록 2 회신 ─→ 블록 3 (spec 확정) ─→ 블록 4 (plan/tasks/implement)
```

블록 0·1·2요청은 동시 출발 가능. 블록 3부터가 실제 대기.

---

## 별건 — 문서 정합 (병합 시점 처리)

- `docs/26` front·game 분기 — front가 최신본. 병합 때 front 채택
- spec 013 vs 헌법 25조 — 25조 문언은 "텍스트 입력·외부 콘텐츠" 한정이라 아바타 편집은 해당 없음. 충돌 아님으로 판정, 조치 없음
- 단 front `specs/013/spec.md`는 여전히 "FE는 창 이관만"·FR-020 웹 이관·C-07 React 이관 방식 기술. `FE.md`만 갱신된 상태 — 013 진행 시 정리 필요

<!-- HUMANIZE-SUMMARY v1.6.1
run_id: 2026-08-16-001
mode: monolith / 강도=보수 / genre=report(개인 백로그)
metrics:
  char_in: 3146
  char_out: 3146
  change_rate: 0.0%
  self_check: 6/6
  grade: A
  risk_band: low (사전 점수 2, route_hint=light)
categories:  # before → after
  A-7/A-8/A-9 번역투·피동: 0 → 0
  A-16 대명사 직역: 0 → 0
  A-19 이중 조사: 0 → 0
  C-11 연결어미 뒤 쉼표: 0 → 0  (ending_comma_rate 0.00)
  D-1~D-7 AI 관용구: 0 → 0
  H-1/H-3 접속사·메타 진입: 0 → 0
  I-1~I-4 형식명사·권고형: 0 → 0
  J-1 볼드 강조: 7 (보존 — 백로그 항목 라벨·식별자, 문장 내 수사적 강조 아님)
  J-3 대시 부가설명: 다수 (보존 — 원문 고유 리듬, quick-rules J-3 예외 조항)
self_check:
  - 고유명사·수치·인용 100% 보존: ✅ (무편집)
  - 변경률 30% 이하: ✅ (0.0%)
  - 장르 이탈 없음: ✅ (표·체크박스·코드블록·헤딩 원형)
  - register 보존: ✅ (명사종결 개조식 그대로)
  - S1 잔존 0건: ✅
  - 인공 표현 추가 없음: ✅ (신규 삽입 0)
highlights: (없음 — 편집 0건)
residual_findings: (없음)
edit_rationale: >
  개조식 개인 백로그. 서술 문장이 전체의 20% 미만이고, 그마저
  "개별 확인으로 쪼개면 왕복만 늘어남" / "가장 아픔" 같은 압축 구어체라
  quick-rules S1·S2 어느 패턴에도 매핑되지 않음. 보수 강도 지침
  ("확신 없는 구간은 그대로 둔다")에 따라 무편집 통과.
grade_reason: "A — S1 0건, S2 0건, 자체검증 6항 통과. 변경률 0%는 A 기준(10~25%) 하한 미만이나, 이는 입력이 이미 사람 글이기 때문이며 과소 윤문이 아님."
-->
