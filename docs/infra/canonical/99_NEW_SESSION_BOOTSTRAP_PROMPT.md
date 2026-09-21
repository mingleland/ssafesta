# New Session Bootstrap Prompt — Final Frozen

아래 내용을 새 GPT/Codex 세션 첫 메시지로 사용한다.

---

SSAFY FESTA 인프라 작업을 이어간다.

먼저 제공된 문서 패키지를 읽고 다음 precedence를 지켜라.

1. `00_SSAFESTA_INFRA_MASTER_INDEX.md`
2. `02_CURRENT_CICD_RUNTIME_ARCHITECTURE.md`
3. `03_BATCH_1_2_3_COMPLETION_EVIDENCE.md`
4. `04_RUNNER_CAPACITY_ARCHITECTURE_CURRENT.md`
5. `05_OPERATIONS_RUNBOOK_AND_INVARIANTS.md`
6. `06_CAPACITY_SOAK_MEASUREMENT_PLAN.md`
7. `07_INFRA_HANDOFF_PACKAGE.md`
8. `01_INFRA_EVOLUTION_TIMELINE.md`
9. `08_SOURCE_MAP_AND_PRECEDENCE.md`

현재 상태:

```text
BATCH 1                       = COMPLETE
BATCH 2 CONSUMER E2E          = COMPLETE
BATCH 3                       = COMPLETE
RUNNER CAPACITY               = COMPLETE (concurrent = 4)
#220 CI/CD RESTRUCTURING      = CLOSED
INFRA DOCUMENTATION           = FROZEN
INFRA HANDOFF                 = READY
```

이미 완료된 Batch 1~3 및 Runner Capacity를 재설계하거나 TODO로 되살리지 마라.

절대 유지:

- `develop = integration approved`
- `main = production promotion baseline`
- `develop → main = NON-SQUASH`
- GitLab = pre-merge validation
- Jenkins = deployment/execution
- Production = Demo-approved exact artifact, rebuild/repack 0
- CURRENT와 KNOWN-GOOD 분리
- Unity Release = Consumer-only, Jenkins Unity 릴리스 빌드 금지
- Unity MR Validation = GitLab dispatch → Jenkins EditMode 검증
- `558d6624` = E2E fixture only / canonical provenance pending
- AI_AGENT binding ON 유지
- Demo World 17777 / Production World 27777
- GitLab Runner: 단일 호스트, `concurrent = 4`, `limit = 4`, `request_concurrency = 4`

비동기 외부 작업 정책:
- GitLab MR은 Auto-merge 등록 후 polling 금지
- Jenkins/external job은 trigger/register 후 polling 금지
- 플랫폼이 기다리게 하고 agent는 독립 작업으로 이동하거나 STOP

