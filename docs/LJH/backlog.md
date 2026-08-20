# FE 백로그 — spec 005 착수

개인 작업 공간(`docs/LJH/`). 2026-08-18부터 git 추적 대상 — `local/` gitignore 시절의 "커밋 제외"는 더 이상 유효하지 않다.
최종 갱신: 2026-08-20 — 블록 1 완결(왕복 검증 통과), C-05 확정, 블록 3 착수 가능

기준 문서: `specs/005-booth-studio-layout/spec.md`, `docs/sdd/parts/FE.md`, `docs/26_팀_결정_필요사항.md`

---

## 현재 상태

- spec 005: `spec.md`만 존재. `plan.md`·`tasks.md` 없음
- spec 006: `plan.md`·`tasks.md` 완료. `T005`·`T006`이 "⛔ 005 Layout 계약 확정 대기"로 차단 중
- 리뷰 4칸: ①②③ 작성 완료(2026-08-14), ④ 왕복 검증 **통과**(2026-08-20, spec.md 기입 대기), BE 검토칸 공란
- 013a: Unity 소유로 축소 확정 — FE는 WebGL 호스트·Access Token 전달만
- Clarification: C-03 확정(고정 크기), C-05 **확정(낙관적 잠금)**, C-06 보류, C-04·C-07 후순위

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

## 블록 1 — 좌표 왕복 검증 1회 ✅ 종결 (2026-08-20)

C-02 규칙은 확정됨 — 미터 / 부스 바닥 중앙 원점 / +Z 정면 / rotationY 0=+Z.
spec 원문: "말로 합의하고 넘어가지 말 것 — 부호 하나는 반드시 틀린다."

- [x] **선행**: Unity canonical 매핑 — `origin/game e8209bc`(2026-08-18, 강형순) "부스 표준 타입 10종 지원"으로 해소
- [x] 오브젝트 2개짜리 Layout JSON 작성 — BE 스켈레톤뿐이라 curl 대상 없음, 대신 Unity `Tools/mock-api` 정적 파일 경로 사용. 사본: `docs/LJH/verify/block1-roundtrip.md`
- [x] 검증 대상에 `SURVEY_KIOSK`·`CONSULTATION_DESK` 포함, x/z 양음·rotationY 0 외 2종까지 4개 모호 축 커버
- [x] **Unity 실측 통과** ([Issue #6](https://github.com/kanghyunsoon/ssafesta/issues/6), 강형순) — 실측 world 좌표·forward 벡터가 기대표와 소수점까지 일치, 부호 오류 0건. 육안 판정을 요청했으나 Unity 측이 숫자로 회신(부호 검증에는 그쪽이 정확하다는 판단). 앵커 `(5,0,5)` 전제도 검증됨
- [x] **부수 성과 — 계약 불일치 3건 전부 Unity 반영** (`origin/game 8852861`)
      ① `objectId` 정식 필드화 + 구 `id` fallback(`ResolvedObjectId`) ② 엔드포인트 복수형 전환(액세스 로그 실증) ③ `assetCode` DTO·Registry 조회 연결 — **FE는 지금부터 `assetCode` 포함 송신 가능**
      ①의 최초 증상 서술 오류(“objectId 공백 전송” → 실제는 가드 조기 반환으로 이벤트 무전송)는 Codex 검증으로 발견·정정 (T-4)

**획득**: C-02 실측 확정, SC-004 근거 확보, spec 006 `T005`·`T006` 잠금 해제 가능

**잔여(블록 1 범위 밖)**: `assetCode` 지정 케이스 미검증(프리팹 카탈로그 확장과 함께), `BOOTH_LAPTOP_INTERACT` 브라우저 왕복은 WebGL 빌드 후 별도 검증. mock 파일 경로 단수 잔존 — 정식 이동 여부 FE 판단 대기 (T-5)

---

## 블록 2 — 타 파트 결정 (2026-08-18 5건 일괄 정리)

- [x] **C-03** 오브젝트 크기 조절 — ✅ **고정 크기 확정.** `scale` 없음, 계약 변경 없음. spec 005·docs/26 반영
- [x] **C-05** 동시 편집 — ✅ **낙관적 잠금 채택.** 황덕(strdeok) 회신([Issue #5](https://github.com/kanghyunsoon/ssafesta/issues/5), 2026-08-20). 제안한 `version` + 409 방향 그대로. 구현 세부(요청 스키마 `version` 위치·409 응답 body 형식)는 BE 착수 시 확정
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
블록 1 (좌표 왕복 검증)                      ✅ 종결 (2026-08-20)
블록 2 (타 파트 결정 5건)                    ✅ 종결 (C-05 회신으로 소진)
      │
      └─ 블록 3 (spec 확정)  ← 착수 가능, 다음 액션
                │
                └─ 블록 4 (plan/tasks/implement)
```

**블록 0~2 전부 종결. 블록 3이 다음 액션이며 선행 대기 없음.**
단 블록 3의 `BE 검토칸`은 BE 담당 작성 사항이라 FE 단독으로 채울 수 없다 — 나머지 3항목 먼저 처리 후 BE에 요청.

---

## 별건 — spec 008 선행 (이 백로그 범위 밖, [Issue #2](https://github.com/kanghyunsoon/ssafesta/issues/2))

2026-08-20 김가현(AI)·강형순(Unity) 회신으로 발생한 FE 액션.

- AI 파트가 `AI_CHAT` payload를 `{ boothId: number, agentId: number }`로 확정 — 블록 0 미완이던 `openOverlay` 확인 해소
- 신규 요청: `AI_AGENT_INTERACT { boothId, objectId, configId }` 이벤트. Unity는 `configId`가 이미 DTO·런타임에 있어 **계약 변경 없이 송신부만 붙이면 됨**(강형순 확인). 기존 `AiNpcInteractable`의 Unity 내부 Mock AI 호출을 브리지 이벤트로 교체하는 것이 원래 예정 경로
- [ ] **FE 결정 대기 — 이벤트 필드명**: `configId`(Unity/Layout 계약 용어와 일치) vs `agentId`(FE 오버레이 용어와 일치). 강형순은 1번 권고, `events.ts` 소유자 판단을 따르겠다고 함
- [ ] 확정 후 `events.ts`에 `AI_AGENT_INTERACT` 타입 추가

---

## 별건 — 문서 정합 (병합 시점 처리)

- `docs/26` front·game 분기 — front가 최신본. 병합 때 front 채택
- spec 013 vs 헌법 25조 — 25조 문언은 "텍스트 입력·외부 콘텐츠" 한정이라 아바타 편집은 해당 없음. 충돌 아님으로 판정, 조치 없음
- 단 front `specs/013/spec.md`는 여전히 "FE는 창 이관만"·FR-020 웹 이관·C-07 React 이관 방식 기술. `FE.md`만 갱신된 상태 — 013 진행 시 정리 필요
