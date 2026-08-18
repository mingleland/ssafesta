# FE 백로그 — spec 005 착수

개인 작업 공간. `.gitignore:23`로 커밋 제외.
최종 갱신: 2026-08-18 — LAPTOP 송신부 구현 완료, front→origin 푸시

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
- **[Issue #2](https://github.com/kanghyunsoon/ssafesta/issues/2)에서 강형순(Unity 리드)이 `events.ts` 계약 확인** — "url은 선택값으로 두는 것이 맞다"고 독립 확인, Codex 반박 판단과 일치. Unity 측 `BOOTH_LAPTOP_INTERACT` 송신부는 이후 `origin/game 2ec6a9d`로 구현 완료(아래)

미완: 김가현(AI) 쪽 `openOverlay` 확인 응답 없음. **블록 0 종결 조건은 아님**(FE 단독 확정 사항) — 별도 추적.

추가: Unity 리드가 Issue #1에서 `BOOTH_LAPTOP_INTERACT` 송신부 구현 계획 회신(2026-08-18) — 기존 `window.FestaUnity.onBoothInteract(json)` 콜백 재사용, url 없어도 정상 송신, 커밋 2단위 분리 예고. **양쪽 다 반영 완료**: ① canonical 10종 → `e8209bc` / ② LAPTOP 송신부 → `origin/game 2ec6a9d`(16:04, "노트북 상호작용 웹 브리지 연결") — `BoothInteractBridge.cs:45`에서 계약대로 JSON 조립 확인(`git grep BOOTH_LAPTOP_INTERACT origin/game`). 후속 `756e3b1`(자식 콜라이더 클릭 전달) 별도. spec 006 문서 "표준 타입 10종 완료" 불일치는 Unity 리드가 인지·후속 반영 예정.

메모: 013a 축소로 아바타 오버레이는 빠졌으나 AI 채팅·설문·상담·016 노트북이 남아 여전히 필요.

---

## 블록 1 — 좌표 왕복 검증 1회

C-02 규칙은 확정됨 — 미터 / 부스 바닥 중앙 원점 / +Z 정면 / rotationY 0=+Z.
spec 원문: "말로 합의하고 넘어가지 말 것 — 부호 하나는 반드시 틀린다."

- [x] **선행**: Unity canonical 매핑 — `origin/game e8209bc`(2026-08-18 15:55, 강형순) "부스 표준 타입 10종 지원"으로 해소
      확인: `git grep -n "SURVEY_KIOSK" origin/game -- '*.cs'` → `BoothObjectType.cs:28` 매핑 존재
- [x] 오브젝트 2개짜리 Layout JSON 작성 — BE 스켈레톤뿐이라 curl 대상 없음, 대신 Unity `Tools/mock-api` 정적 파일 경로 사용. 사본: `docs/LJH/verify/block1-roundtrip.md`
- [x] 검증 대상에 `SURVEY_KIOSK`·`CONSULTATION_DESK` 포함, x/z 양음·rotationY 0 외 2종까지 4개 모호 축 커버
- [ ] Unity에서 같은 위치인지 육안 확인 — **[Issue #6](https://github.com/kanghyunsoon/ssafesta/issues/6) 상정, 강형순 지정. 회신 대기**
      부가: 검증 중 발견한 계약 불일치 3건(`id`↔`objectId`, 엔드포인트 단복수, `assetCode` 부재)도 같은 이슈에 포함 — ①번은 `BOOTH_LAPTOP_INTERACT` objectId 공백 버그로 이어질 수 있어 우선 확인 요청

완료 시: 리뷰 ④칸 ☑, SC-004 근거 확보, 006 `T005`·`T006` 잠금 해제

---

## 블록 2 — 타 파트 결정 (2026-08-18 5건 일괄 정리)

- [x] **C-03** 오브젝트 크기 조절 — ✅ **고정 크기 확정.** `scale` 없음, 계약 변경 없음. spec 005·docs/26 반영
- [ ] **C-05** 동시 편집 — **[Issue #5](https://github.com/kanghyunsoon/ssafesta/issues/5)로 상정, 황덕(strdeok) 지정.** `version` 낙관적 잠금 + 409 제안. 기한: BE Draft API 스키마 확정 전. 회신 대기
- [ ] **C-06** 템플릿 종수 — **보류 판단(지금 결정 불요).** FE는 스냅 간격·부스 크기를 설정값으로 두고 확정 후 주입. docs/26 메모 반영
- [ ] **LAPTOP 주소 저장 위치** — 문서 추적 결과 방향 일관: docs/02 PROJECT-05(Owner 등록·수정)·spec 016 FR-001("주소 1개")·Key Entity Booth Homepage(부스 단위)·FE 의견(booths 컬럼, 부스당 1개)·Issue #1 Unity 회신(url은 저장 위치 확정 후 포함) 전부 **부스 단위 1개**로 수렴. 형식상 016 C-01·C-02만 open — BE 컬럼 추가 확인만 남음
- [x] **Facade 저장 계약** — ✅ **spec 005 범위 제외 확정.** FE 외부 설정 화면 착수 전 별도 확정. docs/26 반영

### 착수 안 막음 (후순위)

- C-04 미연결 공개 — publish validation 동작. 후반 작업
- C-07 공개 이력 — BE 소관. FE는 `version` 단조 증가만 유지 (Issue #5에 부가 사항으로 포함됨)

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
블록 0 (Architecture 문서 4종 + LAPTOP 송신부) ✅ 종결
      │
      ├─ 블록 1 (왕복 검증)                ← 선행 해소, 미착수, 다음 액션
      └─ 블록 2 (5건 중 4건 처리, C-05만 잔여) ← Issue #5 회신 대기

블록 2 회신 ─→ 블록 3 (spec 확정) ─→ 블록 4 (plan/tasks/implement)
```

블록 1이 지금 유일하게 손댈 수 있는 실행 항목. 블록 2는 회신 대기.

---

## 별건 — 문서 정합 (병합 시점 처리)

- `docs/26` front·game 분기 — front가 최신본. 병합 때 front 채택
- spec 013 vs 헌법 25조 — 25조 문언은 "텍스트 입력·외부 콘텐츠" 한정이라 아바타 편집은 해당 없음. 충돌 아님으로 판정, 조치 없음
- 단 front `specs/013/spec.md`는 여전히 "FE는 창 이관만"·FR-020 웹 이관·C-07 React 이관 방식 기술. `FE.md`만 갱신된 상태 — 013 진행 시 정리 필요
