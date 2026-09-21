# 팀 공통 인프라 요약 가이드

> 대상: 전체 팀  
> 목적: **기존에 무엇이 문제였고, 무엇을 어떻게 바꿨으며, 지금 개발·배포를 어떻게 하면 되는지** 한 문서에서 이해하기.  
> 충돌 시 [`../canonical/`](../canonical/) 정본이 우선합니다.

---

## 1. 왜 인프라를 다시 설계했나

기존 구조는 CI, 배포, Unity, Demo/Production이 한 호스트와 여러 경로에 섞여 있었습니다.

대표적으로 다음 문제가 실제로 발생했습니다.

- GitLab Runner 1개 슬롯 때문에 MR queue가 길게 밀림
- Jenkins / Runner / Runtime / Unity 작업이 동일 4-vCPU 호스트에서 충돌
- 과거에는 CPU steal 58~61%, 수천 개 zombie process, restart loop까지 발생
- `develop` 통합 검증과 `main` Production 승격 역할이 명확하지 않았음
- Demo와 Production World 경계가 불완전해 readiness false-positive 가능
- Unity Release를 Jenkins에서 직접 빌드하려다 Unity Personal license / entitlement 문제가 CI blocker가 됨
- MR secret scan이 전체 저장소를 훑어 CI-only MR도 지나치게 느림
- Testcontainers, Docker image, Registry package가 누적됨

---

## 2. 지금은 어떻게 바뀌었나

### 일반 애플리케이션

```text
MR
↓
GitLab CI — merge 전 검증
↓
develop — integration approved
↓
Jenkins develop — build / execution / Demo deploy
↓
Demo
```

### Unity

Unity는 **MR 검증**과 **실제 Release**가 완전히 분리됩니다.

```text
[MR 검증]
Unity 변경 MR
→ GitLab CI dispatch
→ Jenkins Unity Agent
→ Unity Editor EditMode Test
→ commit status

[실제 Release]
Unity 담당자 licensed PC
→ WebGL + Game Release Bundle 4파일 생성
→ GitLab Generic Package Registry
→ Jenkins Consumer
→ artifact 검증
→ Demo candidate / readiness / 승격
```

중요:

> Jenkins는 **Unity Release artifact를 빌드하지 않습니다.**  
> Unity Editor는 **MR EditMode validation에만 제한적으로 사용**합니다.

### Production

```text
Demo 검증/승인
↓
Approved Demo Receipt
↓
develop → main NON-SQUASH
↓
Jenkins `festa-production-promotion` 수동 시작
↓
동일 artifact candidate
↓
자동 검증
↓
CURRENT
↓
Human Verification
↓
KNOWN-GOOD
```

Production에서 Front/Back/AI/Unity/WebGL/World를 다시 빌드하거나 repack하지 않습니다.

---

## 3. 개선 효과

실측 결과 기준입니다.

| 항목 | 이전 | 현재 |
|---|---|---|
| GitLab Runner | `concurrent=1` 응급 안정화 상태 | **`concurrent=4` 최종 운영값** |
| FAST/MR 상태 job | 전체 scan 중심, 수십 초 이상 | **약 10~14초** |
| CI-only MR | 과거 약 12~15분 | **약 10~14초급 경량 검증** |
| Runner queue | 과거 최대 약 18분 사례 | mixed soak에서 **0~2초** |
| Testcontainers | 누수 존재 | **누수 0** |
| Docker disk | 222GB / 72% | **196GB / 64%, 약 26GB 회수** |
| Production | build/deploy 경계 불명확 | **Demo-approved exact artifact만 승격** |

Capacity는 더 많이 여는 것이 항상 좋은 것이 아니었습니다.

```text
c=4 = FINAL / OPTIMAL
c=5 = Memory / IO / Swap pressure 발생 → CEILING EXCEEDED
c=6 = c=5 stop condition으로 미실행
```

---

## 4. 개발자가 평소 해야 할 일

### FE / BE / AI

```text
1. 작업 브랜치에서 코드 작성
2. MR 생성
3. GitLab CI 확인
4. Auto-merge를 사용하는 경우 한 번 등록
5. develop에 들어간 뒤 Demo 반영은 Jenkins가 처리
```

Demo를 반영하기 위해 서버에 SSH해서 `docker compose`를 직접 실행할 필요가 없습니다.

### Unity

Unity는 별도입니다.

```text
MR 검증:
코드 MR → EditMode validation

Release:
develop에 도달한 clean commit
→ 담당자 licensed Unity 환경에서 Release Bundle 생성
→ Registry 게시
→ Jenkins Consumer가 Demo 배포
```

자세한 절차: [`03_UNITY_RELEASE_DEPLOY_GUIDE.md`](./03_UNITY_RELEASE_DEPLOY_GUIDE.md)

---

## 5. 꼭 기억할 규칙

```text
develop = integration approved
main    = Production promotion baseline

develop → main = NON-SQUASH
```

또한:

- Production에서 rebuild / repack 금지
- CURRENT와 KNOWN-GOOD를 동시에 갱신하지 않음
- `558d6624` fixture는 Production canonical artifact가 아님
- Demo World는 17777, Production World는 27777
- CI/Jenkins 상태를 기다리기 위해 busy-polling하지 않음
- Unity Release build를 Jenkins/EC2로 되돌리지 않음

---

## 6. 지금 자동화된 범위

```text
일반 앱:
MR → develop → Demo
             ✅ 자동

Unity:
Release Bundle Registry 게시 → Consumer → Demo
                              ✅ 자동화 경로

Production:
Demo 승인 → Production Promotion
            ⚠️ 의도적으로 수동 시작 + Human Gate
```

Production이 완전자동이 아닌 것은 미완성이 아니라 현재 운영 계약입니다.

---

## 7. 문제가 생겼다면

- MR이 안 넘어감 → GitLab CI / `mr-status`
- develop은 들어갔는데 Demo가 안 바뀜 → Jenkins `festa-gitlab-develop`
- Unity MR만 실패 → `unity-mr-validation`
- Unity Release가 Demo에 안 올라감 → Release Bundle / Consumer validation
- World 연결 실패 → Demo 17777 / WSS 101
- Production 문제 → 직접 수정하지 말고 CURRENT / KNOWN-GOOD / previous 상태부터 확인

빠른 가이드: [`05_INFRA_TROUBLESHOOTING_QUICK_GUIDE.md`](./05_INFRA_TROUBLESHOOTING_QUICK_GUIDE.md)

---

## 8. 더 정확한 내용이 필요하면

- 현재 아키텍처: [`../canonical/02_CURRENT_CICD_RUNTIME_ARCHITECTURE.md`](../canonical/02_CURRENT_CICD_RUNTIME_ARCHITECTURE.md)
- 완료 근거: [`../canonical/03_BATCH_1_2_3_COMPLETION_EVIDENCE.md`](../canonical/03_BATCH_1_2_3_COMPLETION_EVIDENCE.md)
- Runner 수치: [`../canonical/04_RUNNER_CAPACITY_ARCHITECTURE_CURRENT.md`](../canonical/04_RUNNER_CAPACITY_ARCHITECTURE_CURRENT.md)
- 운영 불변조건: [`../canonical/05_OPERATIONS_RUNBOOK_AND_INVARIANTS.md`](../canonical/05_OPERATIONS_RUNBOOK_AND_INVARIANTS.md)
