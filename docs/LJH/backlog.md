# FE 백로그 — spec 005 착수

개인 작업 공간(`docs/LJH/`). 2026-08-18부터 git 추적 대상 — `local/` gitignore 시절의 "커밋 제외"는 더 이상 유효하지 않다.
최종 갱신: 2026-08-20 — spec 005 BE 검토 완료(PR #13), 리뷰 4칸+BE 서명 전부 종결

기준 문서: `specs/005-booth-studio-layout/spec.md`, `docs/sdd/parts/FE.md`, `docs/26_팀_결정_필요사항.md`

---

## 현재 상태

- spec 005: `spec.md`만 존재. `plan.md`·`tasks.md` 없음
- spec 006: `plan.md`·`tasks.md` 완료. `T005`·`T006`이 "⛔ 005 Layout 계약 확정 대기"로 차단 중
- 리뷰 4칸: ①②③ 작성 완료(2026-08-14), ④ 왕복 검증 **통과·기입 완료**(2026-08-20), **BE 검토칸만 잔여 — Issue #5 서명 가이드 게시, 회신 대기**
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

**잔여(블록 1 범위 밖)** — 2026-08-20 추가 회신으로 일부 해소:

- [x] `assetCode` 지정 케이스 — **검증 통과**. 전달 경로·fallback·대소문자 무시·`type:assetCode` 복합키 전부 확인. 상세: `docs/LJH/verify/block1-roundtrip.md`
- [x] mock 파일 경로 복수형 이동 — FE는 `Tools/mock-api` 미사용 확인 후 이동 승인 (T-5)
- [x] 잘못된 `assetCode` 무경고 대체 — **Unity가 `Debug.LogWarning` 추가로 처리.** FE 편집기 검증은 추가하지 않기로 회신
- [ ] **FE가 쓸 `assetCode` 목록 제공** — 카탈로그 확장 시 Unity가 그 값으로 등록. 편집기에서 쓸 자산 종류를 FE가 정한 뒤 전달. 이게 선행돼야 "서로 다른 자산이 실제로 선택되는지"를 검증할 수 있음(현재 타입당 프리팹 1개라 원리적으로 구분 불가)
- [ ] `BOOTH_LAPTOP_INTERACT` 브라우저 왕복 — WebGL 빌드 후 별도 검증

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

- [x] C-05 확정 반영 — Clarifications 표 취소선 처리, `spec.md` 커밋(`b050026`)
- [x] ④ 왕복 검증 ☑ 기입 — 통과 결과·sign-off(FE 이정헌/Unity 강형순) 반영, 비고에 계약 정합 3건 확인 추가 (`b050026`)
- [x] **BE 검토칸 작성** — ✅ **완료.** 황덕이 [Issue #5](https://github.com/kanghyunsoon/ssafesta/issues/5) 정정 가이드대로 `develop` 기준 PR [#13](https://github.com/kanghyunsoon/ssafesta/pull/13)으로 서명(`BE 검토: 황덕 / 검토일: 2026-08-20`). 서명과 동시에 C-05 잔여였던 409 응답 body 형식도 확정 — `{code, message, requestId}`(`docs/08` §1.3, `D-BE-01`, 계약 테스트로 구현·고정됨). `develop`·`front`의 `spec.md` diff 0 재확인. **spec 005 리뷰 4칸+BE 검토칸 전부 완료**
- [ ] SC-002 `[NEEDS CLARIFICATION: 목표 수치]` 해소 — 대응 C-xx가 없음. `/speckit-clarify`로 등록하거나 006 C-01(갱신 트리거)에서 역산
- [ ] 제목 `리뷰 — FE 1차 검토 (확정 아님)` → 확정 상태로 교체 — 이제 착수 가능(리뷰 실질 완료)
- [ ] C-05 잔여 — 요청 스키마 `version` 위치만 미결 (BE 착수 시 확정 예정)

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

**블록 0~2 전부 종결. 블록 3은 4항목 중 2건 완료, 2건 잔여(BE 검토칸 회신 대기·SC-002 해소는 FE 착수 가능).**
`BE 검토칸`은 BE 담당 작성 사항이라 FE 단독으로 채울 수 없다 — Issue #5에 브랜치 전환 서명 가이드 게시 완료, 회신 대기.

---

## 별건 — spec 008 선행 ([Issue #2](https://github.com/kanghyunsoon/ssafesta/issues/2) ✅ 종결, FE 구현만 잔여)

2026-08-20 김가현(AI)·강형순(Unity) 회신으로 계약 확정. **이슈는 "계약 리뷰 요청" 범위라 확정 시점에 종결**했고, 뒤따르는 구현은 별도 추적.

확정 내용:
- `AI_CHAT` payload `{ boothId: number, agentId: number }` — 블록 0 미완이던 `openOverlay` 확인 해소
- **`AI_AGENT_INTERACT { boothId: number, objectId: string, configId: number }`** — Unity는 `configId`로 송신(Layout 계약·DTO 필드명과 일치), `agentId`라는 이름은 Unity에서 쓰지 않음. **`configId` → `agentId` 매핑은 FE가 `AI_CHAT` payload를 만들 때 처리**
- Unity 구현 계획(미착수): `BoothInteractBridge`를 `type` 인자 받도록 일반화, `AiNpcInteractable`의 내부 Mock AI 직접 호출을 브리지 송신으로 교체(ADR 결정 4 — 텍스트 입력 UI는 React 담당)

FE 잔여:
- [ ] `events.ts`에 `AI_AGENT_INTERACT` 타입 추가 — 필드 3개 확정됐으므로 바로 구현 가능
- [ ] 수신 시 `configId`를 `agentId`로 매핑해 `openOverlay('AI_CHAT', { boothId, agentId })` 호출하는 흐름 연결

---

## 별건 — 문서 서명 공간·브랜치 운영 (2026-08-20, 당일 2차 갱신)

`spec.md` 최신본은 이제 **`develop`에 있다** (`front`→`develop` sync, [PR #12](https://github.com/kanghyunsoon/ssafesta/pull/12) 병합 완료). `back`·`main`은 여전히 구버전.

경위: 08-18에 `front`→`develop` 전체 통합을 시도(`f8c47fc`, PR #3)했다가 커밋 타입·브랜치명·PR 제목이 `docs/17` 컨벤션을 벗어나 되돌림(`95cd845`). 그때는 "`front`를 최종 작업 브랜치로 간주" 합의였으나, **당일 오후 2차 협의로 "서명만 담은 작은 PR을 develop에 직접"으로 대체.**

- **현재 규칙**: 서명 대상 문서는 `develop`에 최소 diff로 먼저 올린다(전체 merge 아님 — `git checkout <owner-branch> -- <path>`로 파일 단위 sync). 서명 자체는 `develop` 기준 새 브랜치를 따 **PR로** 진행한다 — 직접 push 금지(`docs/17` §2, T-7에서 이 실수를 자체 발견·정정)
- PR #12로 실증된 안전한 sync 절차: `develop`에서 브랜치 생성 → 파일 단위 checkout → `festa-unity/` 0줄 확인 → `docs(sdd):` 타입 커밋 → PR → 리스크 낮으면 리뷰어 스킵 가능(팀 협의로 결정, 문서화된 근거 남길 것)
- ⚠️ PR #3이 남긴 미해결 구조 문제는 여전히 미결 — `front`/`back`/`ai`가 `festa-unity/` 삭제 이력을 갖고 있어 **전체 merge**는 여전히 위험(파일 단위 sync는 이 문제를 안 겪음, 근본 해결 아님). 제안 2건 팀 결정 대기: ① `festa-unity/`를 별도 저장소로 분리 ② 일반 Merge 버튼 금지 규칙화 (`docs/26` ①표 16번)

---

---

## 별건 — 문서 정합 (병합 시점 처리)

- `docs/26` front·game 분기 — front가 최신본. 병합 때 front 채택
- spec 013 vs 헌법 25조 — 25조 문언은 "텍스트 입력·외부 콘텐츠" 한정이라 아바타 편집은 해당 없음. 충돌 아님으로 판정, 조치 없음
- 단 front `specs/013/spec.md`는 여전히 "FE는 창 이관만"·FR-020 웹 이관·C-07 React 이관 방식 기술. `FE.md`만 갱신된 상태 — 013 진행 시 정리 필요
