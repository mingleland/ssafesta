# Phase 0 Research: 파트별 CI/CD 파이프라인

**Date**: 2026-08-18  
**Scope**: Jenkins/SCM migration, single-EC2 execution, artifact provenance, recovery, Unity Personal build agent, P1 observability

## R-01. Jenkins controller와 agent 분리

**Decision**: controller built-in executor를 0으로 두고 일반 Docker agent, deploy agent, 영속 Unity agent를 별도 사용자·label·workspace로 운영한다. 초기에는 같은 EC2의 논리적 분리이며 완전한 보안 경계로 간주하지 않는다.

**Rationale**: controller 공격면과 빌드 의존성을 줄이고, 추후 agent만 별도 EC2로 옮겨도 pipeline 계약을 유지할 수 있다.

**Alternatives considered**:
- controller에서 직접 빌드: 가장 단순하지만 controller compromise/자원 고갈 위험 때문에 기각.
- 처음부터 다중 EC2: 현재 서버 1대 제약 때문에 보류.
- Docker socket 공유: 필요 시 임시 수용할 수 있으나 사실상 host 권한이므로 rootless 우선, 별도 agent 이전 게이트를 둔다.

**Sources**: [Jenkins controller isolation](https://www.jenkins.io/doc/book/security/controller-isolation/), [Using Jenkins agents](https://www.jenkins.io/doc/book/using/using-agents/), [Docker rootless mode](https://docs.docker.com/engine/security/rootless/)

## R-02. GitHub에서 GitLab로 이전 가능한 SCM 경계

**Decision**: Multibranch Pipeline과 `checkout scm`을 사용하고 provider 설정을 pipeline 로직 밖에 둔다. 이전 시 Branch Source/plugin, repository/API URL, API·checkout credential, webhook을 교체한다. Jenkinsfile과 stage/adapter 계약은 유지한다.

**Rationale**: “webhook만 변경”으로는 branch discovery와 checkout 인증이 바뀌지 않아 migration이 완결되지 않는다. provider-specific 요소를 job 설정으로 격리해야 한다.

**Alternatives considered**:
- provider별 Jenkinsfile 두 벌: drift와 이중 유지보수 때문에 기각.
- generic webhook만 사용: branch discovery/PR·MR 상태 연동이 약해 기각.

**Sources**: [Jenkins Multibranch Pipeline](https://www.jenkins.io/doc/book/pipeline/multibranch/), [Pipeline as Code](https://www.jenkins.io/doc/book/pipeline/pipeline-as-code/), [GitHub webhooks](https://docs.github.com/en/webhooks/using-webhooks/creating-webhooks), [GitLab webhooks](https://docs.gitlab.com/user/project/integrations/webhook_events/)

## R-03. 오래된 실행과 동시 배포 제어

**Decision**: build/test는 병렬화할 수 있지만 배포 직전에 `milestone`과 환경/파트별 `lock`을 적용하고 lock 획득 뒤 최신 SHA 또는 단조 증가 release sequence를 다시 확인한다. 오래된 실행은 `SUPERSEDED`로 종료한다.

**Rationale**: 전체 job을 강제 중단하면 배포 중간 상태가 남을 수 있고, 모든 실행을 직렬화하면 CI 처리량이 불필요하게 감소한다.

**Alternatives considered**:
- `abortPrevious: true`: 배포 중 강제 중단 위험 때문에 단독 사용하지 않음.
- 전체 pipeline 직렬화: 안전하지만 빌드/테스트 처리량 손실 때문에 기각.

**Sources**: [Pipeline milestone step](https://www.jenkins.io/doc/pipeline/steps/pipeline-milestone-step/), [Lockable Resources](https://www.jenkins.io/doc/pipeline/steps/lockable-resources/), [Pipeline syntax](https://www.jenkins.io/doc/book/pipeline/syntax/)

## R-04. Local Docker artifact와 release provenance

**Decision**: 초기에는 별도 registry 없이 단일 EC2 Docker daemon의 local image store를 사용한다. 이미지는 `<component>:<full-commit-sha>`처럼 commit별 고유 tag로 만들고 tag 재사용을 금지한다. `docker image inspect`로 얻은 sha256 image ID를 release manifest에 기록하고 배포 직전에 tag가 같은 image ID를 가리키는지 검증한다. current/known-good image는 garbage collection에서 보호한다. manifest와 deployment/verification record는 Jenkins가 archive/fingerprint하고 환경의 current/known-good pointer를 원자적으로 갱신한다.

**Rationale**: 현재 제공된 것은 EC2 SSH 접근이며 AWS 계정/IAM/ECR 권한은 확인되지 않았다. local image ID를 함께 기록하면 외부 registry 없이도 한 서버 안에서 build once/deploy same artifact와 rollback 대상을 식별할 수 있다.

**Alternatives considered**:
- `latest`/branch tag만 배포: tag 덮어쓰기로 재현성과 rollback 대상이 불명확해 기각.
- 환경마다 재빌드: 같은 소스라도 artifact가 달라질 수 있어 기각.
- ECR 즉시 사용: IAM 권한이 제공되지 않아 초기 선택에서 제외. 추후 권한 제공 시 registry adapter로 전환한다.

**Operational limit**: EC2가 유실되면 local image와 rollback 대상도 함께 유실된다. disk 정리 시 current/known-good와 진행 중 candidate를 삭제하면 안 된다.

**Future migration**: AWS 관리자가 IAM role과 ECR repository를 제공하면 `storageMode=registry`, `imageRef=repository@sha256:...`로 전환한다. ECR 인증에는 IAM 권한이 필요하다. [ECR repository permissions](https://docs.aws.amazon.com/AmazonECR/latest/userguide/repository-policies.html)

**Source**: [Jenkins fingerprints](https://www.jenkins.io/doc/book/using/fingerprints/)

## R-05. 단일 EC2 배포 격리

**Decision**: 환경/파트별 Compose project, network, container prefix, volume, lock을 분리하고 service-scoped deploy script만 허용한다. healthcheck/readiness는 각 파트가 소유한다.

**Rationale**: 서버가 하나여도 배포 단위를 분리하면 한 파트의 갱신이 다른 파트를 recreate/restart하는 일을 막을 수 있다.

**Alternatives considered**:
- 하나의 거대한 Compose project: 파트 독립 배포 요건과 충돌.
- 처음부터 ECS: 문서상 미래 목표이나 제공 전 EC2 MVP 범위를 넘음.

**Sources**: [Docker Compose up](https://docs.docker.com/reference/cli/docker/compose/up/), [Docker HEALTHCHECK](https://docs.docker.com/reference/dockerfile/#healthcheck), [Jenkins Docker Pipeline](https://www.jenkins.io/doc/book/pipeline/docker/)

## R-06. 조건부 rollback

**Decision**: 사전 검증 실패는 배포하지 않고, container start/비AI verify 실패 중 `rollbackSafety=SAFE`인 경우에만 직전 known-good release로 한 번 자동 복구한다. DB/Secret/config/비가역/unknown은 수동 대응, AI-only는 비AI를 유지하고 재시도/승인을 기다린다.

**Rationale**: 모든 실패에 rollback을 적용하면 DB나 설정의 되돌릴 수 없는 상태를 더 손상시킬 수 있다. 반대로 순수 런타임 실패는 알려진 정상 digest로 빠르게 복구할 수 있다.

**Alternatives considered**:
- 모든 deploy failure 자동 rollback: 데이터 안전성 때문에 기각.
- 전부 수동 rollback: 가역적 장애의 복구시간이 불필요하게 길어 기각.

## R-07. Unity Personal build agent와 cache

**Decision**: Unity `6000.0.78f1 (ec8a99a872be)`와 필요한 모듈을 설치한 영속 전용 agent를 executor 1개로 둔다. 운영자가 Hub에서 1회 대화형 Personal 활성화하고 계정/세션/license 파일은 자동화 경로에 넣지 않는다. WebGL과 Linux Dedicated Server는 별도 프로세스·workspace·Library에서 순차 빌드한다.

**Rationale**: Personal의 Hub 활성화 모델, 한 project를 동시에 열 수 없는 Unity 제한, 캐시 재사용을 함께 만족한다. agent 폐기/교체 시에만 Hub에서 license를 반환한다.

**Operational gate**: 별도 빌드 머신 활성화가 Authorized User/조직 사용과 Personal 재정 기준에 적합한지 운영 전 담당자가 확인·기록한다. 구현자가 라이선스 적격성을 추정하지 않는다.

**Alternatives considered**:
- ID/password로 매 build 자동 활성화·반환: Secret 노출과 외부 인증 의존 때문에 기각.
- ephemeral Unity agent: 1회 활성화와 persistent Library 요구에 맞지 않아 기각.
- Unity Build Server/floating license: 확장 대안이지만 현재 선택한 Personal 운영과 범위가 다름.

**Sources**: [Unity Hub license management](https://docs.unity.com/en-us/hub/manage-license), [Unity 6 command-line arguments](https://docs.unity3d.com/6000.0/Documentation/Manual/EditorCommandLineArguments.html), [Dedicated Server build](https://docs.unity3d.com/6000.0/Documentation/Manual/dedicated-server-build.html), [Unity build cache](https://docs.unity3d.com/6000.0/Documentation/Manual/build-cache-location-reference.html), [Unity license compliance](https://unity.com/pages/license-compliance), [Unity Editor terms](https://unity.com/legal/editor-terms-of-service/software)

## R-08. Secret 주입과 누출 차단

**Decision**: SCM에는 credential ID와 변수명만 두고 값은 초기 Jenkins Credentials를 통해 최소 범위로 주입한다. AWS IAM/Secret 저장소가 제공되면 adapter로 교체할 수 있다. shell tracing을 끄고 console/artifact/cache/release manifest를 canary 값으로 누출 검사한다. Unity credential은 저장 대상 자체에서 제외한다.

**Rationale**: 마스킹은 보조 통제일 뿐이며 Secret을 파일·인자·광범위 환경으로 노출하지 않는 것이 우선이다.

**Alternatives considered**:
- `.env` 커밋: 헌법 15조 위반.
- Jenkinsfile에 암호화 문자열 저장: 복호화 권한과 rotation이 코드에 결합되어 기각.

**Source**: [Using Jenkins credentials](https://www.jenkins.io/doc/book/using/using-credentials/)

## R-09. P1 수집·저장·조회 구조

**Decision**: Alloy가 `prometheus.exporter.unix`와 Docker discovery로 지표/로그를 수집하고 Prometheus remote-write receiver와 Loki monolithic instance에 보낸다. Grafana는 두 datasource를 조회한다. 관측 stack은 앱/CI와 별도 Compose project로 운영한다.

**Rationale**: 수집 Agent 경로를 통일하면서 PromQL/LogQL과 파일 provisioning을 사용할 수 있다. 단일 EC2 P1에 Mimir는 과도하다.

**Alternatives considered**:
- node_exporter/cAdvisor를 Prometheus가 직접 scrape: 더 단순하지만 Agent 중심 통합 수집 경계가 약함.
- Promtail/Grafana Agent: Alloy가 통합 후속 제품이므로 기각.
- Mimir: 현재 규모에 과도.

**Sources**: [Alloy unix exporter](https://grafana.com/docs/alloy/latest/reference/components/prometheus/prometheus.exporter.unix/), [How Alloy works](https://grafana.com/docs/alloy/latest/introduction/how-alloy-works/), [Prometheus storage/remote write receiver](https://prometheus.io/docs/prometheus/latest/storage/)

## R-10. P1 승인 규칙과 Mattermost

**Decision**: Git으로 provision된 탐지 규칙 manifest를 source of truth로 하고 `managed_by=infra`, `approval_status=approved`, `notify=mattermost`가 모두 맞는 규칙만 route한다. 일반 CI status와 승인 전 규칙은 dashboard/query 전용이다. 우선 Grafana Slack contact point에 Mattermost의 Slack-compatible incoming webhook을 연결해 E2E 검증하고, 호환되지 않으면 `{\"text\": ...}` custom webhook 또는 얇은 adapter를 사용한다.

**Rationale**: 사용자가 원하는 것은 CI 알림이 아니라 직접 정의하고 승인한 로그/자원 이상 알림이다. Grafana generic webhook payload를 Mattermost가 그대로 받는다고 가정하면 안 된다.

**Alternatives considered**:
- 모든 CI failure 알림: 명시 요구와 충돌.
- Grafana UI에서 임의 rule 생성: 승인/리뷰와 재현성이 없어 운영 route에서는 제외.
- generic webhook 직결: payload 계약 불일치 가능성 때문에 사전 검증 없이 채택하지 않음.

**Sources**: [Grafana file provisioning](https://grafana.com/docs/grafana/latest/alerting/set-up/provision-alerting-resources/file-provisioning/), [Grafana Slack contact point](https://grafana.com/docs/grafana/latest/alerting/configure-notifications/manage-contact-points/integrations/configure-slack/), [Mattermost incoming webhooks](https://developers.mattermost.com/integrate/webhooks/incoming/), [Grafana webhook notifier](https://grafana.com/docs/grafana/latest/alerting/configure-notifications/manage-contact-points/integrations/webhook-notifier/)

## R-11. P1 label, redaction, retention과 장애 격리

**Decision**: Loki label은 environment/service/container/host 등 bounded 값만 사용한다. Alloy `loki.process`로 승인된 Secret/PII 패턴을 저장 전에 `[REDACTED]` 처리한다. Loki/Prometheus retention과 disk cap을 반드시 두되 기간/용량은 24–72시간 기준선 수집 후 확정한다. app/CI health는 관측 stack에 의존하지 않는다.

**Rationale**: 고카디널리티 label과 무제한 보존은 단일 EC2 disk를 소진한다. 같은 EC2가 완전히 죽으면 같은 호스트 관측 stack은 알릴 수 없으므로 host-down은 외부 실패영역이 필요하다.

**Deferred**: EC2 vCPU/RAM/EBS, 일일 ingest, active series, scrape/evaluation interval, retention 기간/GB, dashboard refresh, alert threshold/pending, WAL/queue와 container resource cap. 측정 없이 임의 숫자로 확정하지 않는다.

**Sources**: [Loki cardinality](https://grafana.com/docs/loki/latest/get-started/labels/cardinality/), [Loki retention](https://grafana.com/docs/loki/latest/operations/storage/retention/), [Alloy loki.process](https://grafana.com/docs/alloy/latest/reference/components/loki/loki.process/), [Grafana No Data/Error states](https://grafana.com/docs/grafana/latest/alerting/fundamentals/alert-rule-evaluation/nodata-and-error-states/)

## R-12. Jenkins 외부 노출과 UFW

**Decision**: 서버 수령 후 UFW에서 22/80/443 TCP만 기본 허용하고 Jenkins는 loopback `127.0.0.1:8080`에 둔다. Ubuntu에서 별도 제품 저장소 없이 운영 가능한 Nginx가 443 TLS를 종료하고 Jenkins UI와 GitHub/GitLab webhook을 reverse proxy한다. 8080/3000/50000은 인터넷에 직접 공개하지 않는다.

**Rationale**: UFW 변경 권한은 제공되지만 Jenkins 관리 포트를 그대로 공개할 필요가 없다. 동일 EC2 agent는 외부 inbound agent port 없이 연결할 수 있고 Mattermost는 outbound 요청이다.

**Operational gate**: UFW는 host firewall일 뿐이다. 서버 수령 후 SSAFY/AWS 상위 방화벽, DNS, TLS 인증서, 외부 webhook 도달성을 별도로 확인한다. Unity 7777은 실제 Transport protocol 확인 후 추가한다.

**Alternatives considered**:
- `8080/tcp`를 Anywhere로 허용: 관리 포트 직접 노출이 불필요해 기각.
- Jenkins inbound agent `50000/tcp` 공개: 초기 동일 EC2 구성에는 필요 없어 기각.
- Grafana `3000/tcp` 직접 공개: reverse proxy와 인증 경계를 우회하므로 기각.
- Caddy: 자동 TLS가 편리하지만 초기 Ubuntu 기본 패키지 경계와 팀의 명시적 설정 검증을 위해 Nginx를 우선 선택하며 추후 교체 가능하다.

## Resolved Unknowns

Phase 0에서 발견한 기술 선택은 모두 위 결정 또는 명시적 운영 게이트로 분류했다. 구현자가 임의로 정하면 안 되는 것은 다음뿐이다.

- 제공 EC2의 실제 사양과 분리 시점
- Unity Personal 조직/사용자 적격성 확인 결과
- P1 관측 retention·resource cap·threshold·panel 세부값
- single-host down을 감시할 외부 실패영역 도입 여부

이 항목들은 기능 구현을 막는 코드 미정이 아니라 제공 자원/운영 승인에 종속된 설정 게이트다.
