# Booth Studio 2.5D — Spike 현황 정본

> **이 파일이 2.5D 현황의 canonical status surface 다.**
> "지금 2.5D 가 어디까지 결정·검증됐는가" 는 여기 하나로 판정한다.
> 상태를 다른 문서에 복제하지 않는다 — 다른 문서는 여기를 가리킨다.
>
> 갱신: 2026-09-07 · Jira `S15P21A604-479`

---

## 1. 결정 상태

| 결정 | 상태 | 내용 |
|---|---|---|
| **D-04** | **ADOPTED** | Booth Studio 는 2.5D 다 |
| **D-05** | **PASS_CANDIDATE · NOT ADOPTED** | R3F 가 1순위 후보이고 실 Spike evidence 가 있으나 정식 채택 결정은 내려지지 않았다 |
| **D-06** | **ADOPTED** | 기존 Layout / Editor 계약 보존 |

정본은 [`00_context/implementation-decisions.md`](../../00_context/implementation-decisions.md) 다. 이 표는 그 요약이다.

### D-04 — ADOPTED

```text
고정 Orthographic isometric 시점
X / Z 이동
Y 고정
Y축 회전
기본 snap 지원 + 세밀 회전값 유지
grid / bounds
Left Palette / Center Canvas / Right Inspector 계약
```

### D-05 — PASS_CANDIDATE, ADOPTED 아님

R3F + three 가 실제로 돌아가고, Unity 실제 모델이 그 위에 올라가며, 중첩 prefab 조립까지 재현된다. 그것이 evidence 로 확인된 전부다.

**채택은 별개다.** 채택 조건은 §4 에 있다.

### D-06 — ADOPTED

`OBJECT_LOCAL_BOUNDS` 가 도메인 정본이다. 실제 모델 치수가 계약과 달라도 저장·이동·bounds·snap·rotation 계약은 흔들리지 않는다. `-473`·`-476` 이 이 규칙 아래에서 검증됐다.

---

## 2. 실행 evidence

| Jira | 대상 | 결과 |
|---|---|---|
| [`S15P21A604-470`](https://ssafy.atlassian.net/browse/S15P21A604-470) | R3F renderer Spike | 파라메트릭 geometry · 실광원 · 그림자 · 3축 gizmo · lazy chunk |
| [`S15P21A604-473`](https://ssafy.atlassian.net/browse/S15P21A604-473) | Unity FBX → GLB → manifest → R3F | 실제 mesh 2종 · 계약 AABB 와 1.5mm 이내 |
| [`S15P21A604-476`](https://ssafy.atlassian.net/browse/S15P21A604-476) | Nested Unity Prefab Assembly | SurveyKiosk 조립 · 계약 AABB 와 5mm 이내 |

상세 수치는 [`spike-evidence.md`](./spike-evidence.md).

### 별도 독립 품질 티켓 (2.5D 사슬이 아니다)

| Jira | 내용 |
|---|---|
| [`S15P21A604-469`](https://ssafy.atlassian.net/browse/S15P21A604-469) | develop `npm run build` 게이트 복구 |
| [`S15P21A604-475`](https://ssafy.atlassian.net/browse/S15P21A604-475) | Game Studio 테스트의 `VITE_USE_MOCK` 고정 |

두 건은 2.5D 작업 중 드러났을 뿐 2.5D 의 전제나 결론이 아니다. 같은 줄에 세우지 않는다.

---

## 3. D-05 canonical Gate

15항 대조표는 [`gate-matrix.md`](./gate-matrix.md) 다.

**canonical 15 Gate 와 `-470` 에서 쓴 8 Gate 는 다른 것이다.**

```text
canonical 15 Gate   기존 문서(booth-studio-r3f-asset-pipeline-plan.md §47)가 정의한 것
                    = Spike PASS 6항 + MVP PASS 9항
                    D-05 가 "완료조건 15항" 이라고 부르는 그것

-470 의 8 Gate      이번 회차에 사용자 지시로 세운 검증 항목
                    canonical 을 대체하지 않는다. evidence 로만 쓴다
```

---

## 4. 다음 단계

```text
NEXT
  SSAFESTA Runtime Asset Compiler v1
    설계   04_prototypes/booth-studio-runtime-asset-compiler.md   (PROPOSAL)
    계획   runtime-asset-compiler-v1-plan.md                      (이 디렉터리)
    도구   01_tooling/booth-runtime-asset-toolchain.md            (PROPOSED)
    비교   06_visual-review/booth-studio-runtime-asset-parity.md  (TEMPLATE)

ADOPTION
  D-05 canonical 15 Gate reconciliation 완료
  → 미검증 조건 해결 또는 명시적 수용
  → 별도 사용자·팀 결정
  → 그 뒤에만 ADOPTED
```

`PASS_CANDIDATE` 를 `ADOPTED` 로 바꾸는 것은 이 문서의 권한이 아니다.

---

## 5. 이 디렉터리의 파일

| 파일 | 역할 |
|---|---|
| `README.md` | 현황 정본 (이 파일) |
| [`gate-matrix.md`](./gate-matrix.md) | D-05 canonical 15 Gate ↔ 실제 결과 대조 |
| [`spike-evidence.md`](./spike-evidence.md) | `-470`·`-473`·`-476` 의 branch·commit·실측 |
| [`runtime-asset-compiler-v1-plan.md`](./runtime-asset-compiler-v1-plan.md) | 다음 구현 Spike 계획과 PASS 조건 |

상세 실행 설계·조사자료는 상위의 [`booth-studio-r3f-asset-pipeline-plan.md`](../booth-studio-r3f-asset-pipeline-plan.md)(2,367줄)에 남아 있다. 그 문서는 **설계·조사 자료**이고, **현황 판정은 이 디렉터리**가 한다.
