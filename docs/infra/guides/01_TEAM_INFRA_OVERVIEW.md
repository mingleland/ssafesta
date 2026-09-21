# SSAFY FESTA 인프라 개편 배경과 효과

> 이 문서는 팀 설명용입니다. 계약·운영값은 [`../canonical/`](../canonical/)이 우선합니다.

## 1. 개편 전 핵심 문제

인프라 문제는 단순히 "CI가 느리다" 수준이 아니었습니다.

### 역할 경계 문제
- GitLab CI, Jenkins, develop, main, Demo, Production의 책임이 섞여 있었습니다.
- `part branch + squash` 구조 때문에 단순 변경 파일 기반 판정이 자주 실제 배포 의도와 어긋났습니다.

### 단일 호스트 과부하
한 EC2에 다음이 공존했습니다.

```text
GitLab Runner
Jenkins controller / agents
Demo / Production runtime
Docker / Testcontainers
Unity 관련 작업
```

과거에는 restart loop, 높은 CPU steal, 수천 개 zombie process까지 관측됐습니다.

### Unity Release 문제
초기에는 Jenkins가 Unity WebGL/Linux Server를 직접 빌드하는 방향이었지만, Unity Personal entitlement와 machine-binding이 CI 환경에서 안정적인 운영 계약이 되지 못했습니다.

### 배포 추적성 문제
Production은 "같은 코드"가 아니라 **Demo에서 실제 검증한 동일 실행 artifact**를 승격해야 했습니다.

---

## 2. 채택한 원칙

### GitLab과 Jenkins 책임 분리

```text
GitLab CI = merge 전 validation
Jenkins   = develop 이후 build / execution / deployment
```

### Branch 의미 고정

```text
develop = integration approved
main    = Production promotion baseline
```

`develop → main`은 반드시 **NON-SQUASH**입니다.

### Production exact-artifact

Demo가 실제 사용한 artifact identity를 receipt/state로 보존하고 Production은 그것을 그대로 승격합니다.

### Unity Consumer-only Release

```text
Unity 담당자 licensed 환경에서 Release 생성
→ Registry
→ Jenkins Consumer
```

Jenkins Unity Editor는 Release build가 아니라 MR EditMode 검증에만 남았습니다.

---

## 3. 현재 운영 결과

GitLab Runner는 실제 mixed backlog soak를 통해 다음 값으로 확정됐습니다.

```text
concurrent = 4
limit = 4
request_concurrency = 4
```

4개 혼합 workload에서는 queue 0~2초, Memory PSI 0, Demo/Jenkins 영향 없음이 확인됐습니다. 반면 5개에서는 Memory PSI, IO PSI, swap과 build duration이 함께 악화되어 상한을 확인했습니다.

그 결과 별도 CI 호스트나 FAST/BUILD lane 분리는 현재 필요하지 않습니다.

---

## 4. 팀에게 직접 생긴 변화

### 개발자
- MR 검증이 빨라졌습니다.
- develop merge 후 Demo 배포를 위해 서버를 직접 만질 필요가 없습니다.
- docs-only/CI-only 변경은 불필요한 앱 배포를 만들지 않습니다.

### Unity 담당자
- CI 서버에 Unity 계정이나 라이선스를 맞추는 일이 Release 조건에서 빠졌습니다.
- 평소 사용하는 licensed 개발환경에서 Release Bundle만 만들면 됩니다.
- Bundle 이후 artifact 검증/Registry canonical publish/Demo 배포는 Consumer가 처리합니다.

### 인프라 담당자
- Demo/Production 경계와 World 포트가 분리됐습니다.
- Production은 exact-artifact promotion으로 고정됐습니다.
- rollback/current/known-good state를 추적할 수 있습니다.
- Docker/Registry retention이 state reference를 보호하도록 정리됐습니다.

---

## 5. 현재 완료 상태

```text
Batch 1                  COMPLETE
Batch 2 Consumer E2E     COMPLETE
Batch 3                  COMPLETE
Runner Capacity          COMPLETE
Issue #220               CLOSED
Infra Handoff            READY
```

현재 남아 있는 항목은 재설계 미완료가 아니라 다음 실제 운영 이벤트에 종속된 carry-forward입니다.

1. GitLab provenance가 있는 실제 Unity Bundle canonical gate 1회 확인
2. 다음 Production 승격에서 AI/Back stale env 정리
3. 다음 Production 승격에서 Gate G 실측
4. Raffle 운영값 확정 후 응모형 경품 provisioning
