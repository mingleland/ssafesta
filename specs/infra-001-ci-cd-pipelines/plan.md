# Implementation Plan: 파트별 CI/CD 파이프라인

**Branch**: `infra-001-ci-cd-pipelines` | **Date**: 2026-08-18 | **Spec**: [spec.md](./spec.md)

**Input**: Confirmed feature specification from `specs/infra-001-ci-cd-pipelines/spec.md`

## Summary

Jenkins를 단일 EC2에 먼저 구축하되 controller는 실행기 0개로 오케스트레이션만 담당하고, 일반 Docker 빌드·배포 agent와 영속 Unity agent를 논리적으로 분리한다. `ai`/`back`/`front`/`game` 브랜치는 각 파트의 테스트·빌드·컨테이너 패키징·개발환경 배포·검증을 독립 수행하고, `develop`은 commit별 고유 local image tag와 Docker image ID가 고정된 release manifest를 한 번 배포한 뒤 웹 접속→로그인→월드 입장→AI 응답 순으로 검증한다. 가역적인 런타임 실패만 직전 정상 release로 자동 복구하며 DB·Secret·설정·비가역 변경과 원인 불명 실패는 현장을 보존하고 수동 판단으로 넘긴다.

초기 SCM은 GitHub Branch Source를 사용하고 GitLab 이전 시 Branch Source, 저장소 URL, API/checkout credential, webhook만 provider별 설정으로 교체한다. Jenkinsfile과 파트별 실행 계약은 SCM 중립적으로 유지한다. P1 관측성은 별도 Compose project에서 Alloy가 로그와 호스트/컨테이너 지표를 수집하고 Loki·Prometheus에 저장하며 Grafana가 조회·승인된 탐지 규칙만 평가해 Mattermost로 알린다. 일반 CI 성공/실패는 알림 대상이 아니다.

## Technical Context

**Language/Version**: Jenkins Declarative Pipeline(Groovy), Bash, YAML/JSON Schema; Unity `6000.0.78f1 (ec8a99a872be)`  
**Primary Dependencies**: Jenkins LTS/JCasC, GitHub Branch Source(초기), GitLab Branch Source(이전 후), Docker Engine + Compose v2, Unity Hub/Editor; P1 Grafana Alloy·Loki·Prometheus·Grafana OSS, Mattermost incoming webhook. AWS IAM 제공 후 ECR은 선택적 후속 adapter  
**Storage**: 초기에는 단일 EC2 Docker daemon의 local image store(commit별 고유 tag + image ID), Jenkins artifact/fingerprint, EC2의 환경별 current/known-good release record 및 영속 Unity Library; P1 Loki/Prometheus/Grafana 전용 영속 volume  
**Testing**: 파트 소유 test adapter, JSON Schema 계약 검증, Jenkins Pipeline/JCasC 정적 검증, Docker health/readiness smoke, develop E2E 시나리오, rollback/freshness/secret-leak 통합 테스트, Unity EditMode/PlayMode 및 WebGL/Linux Server 산출물 smoke  
**Target Platform**: 초기 Ubuntu Linux 단일 EC2 + Docker; 브라우저 WebGL, Linux Dedicated Server; 향후 agent/파트를 별도 EC2로 이전 가능  
**Project Type**: Infrastructure as Code + CI/CD orchestration + deployment/observability configuration  
**Performance Goals**: 동일 파트의 최신 실행만 배포 권한 획득; 파트 배포가 다른 파트를 재시작하지 않음; Unity 캐시 재사용; 알림 중복 억제; 수치형 빌드시간/관측 용량 목표는 기준선 측정 후 확정  
**Constraints**: 서버 1대와 SSH 접근만 확정, 초기 AWS 계정/IAM/ECR 접근 없음, controller 빌드 금지, Unity executor 1개, Personal 자격증명 자동화 금지, Secret 원문 비저장, `festa-unity/Docker/` 변경 금지, Docker socket은 완전한 보안 경계가 아님, 관측 장애가 앱·CI를 중단시키지 않아야 함  
**Scale/Scope**: 4개 파트 브랜치 + `develop`, 개발/통합 배포 target, Unity WebGL·Linux Server 2개 target, P1 단일 EC2 관측 스택. EC2 사양·보존기간·임계치·동시 실행 수는 실측 전 미정

## Constitution Check

*GATE: Phase 0 시작 전 점검했으며 Phase 1 설계 후 다시 점검했다.*

| 헌법 게이트 | 설계 적용 | 결과 |
|---|---|:---:|
| 1·2조 상태 권위 분리 | 파이프라인은 이미지·배포 상태만 다루며 Coin/Lease 등 도메인 상태를 변경하지 않는다. DB 변경은 별도 명시 단계와 수동 안전성 판단 대상으로 둔다. | PASS |
| 3조 AI 장애 격리 | AI-only 검증 실패는 release 성공으로 승격하지 않지만 정상 비AI/월드 컴포넌트를 자동 rollback하지 않는다. | PASS |
| 6·8·9조 접속 경로·endpoint | endpoint는 환경 설정과 world-sessions 응답에서 주입하며 Jenkinsfile에 주소를 하드코딩하지 않는다. 도메인/wss와 층별 인스턴스 실제 구성은 infra-002/003 계약을 소비한다. | PASS |
| 7조 컨테이너 배포 | 모든 서버 컴포넌트는 commit별 고유 tag로 빌드하고 local Docker image ID를 기록·검증한 뒤 컨테이너로 배포한다. | PASS |
| 10조 파트 브랜치 CI/CD | `ai`/`back`/`front`/`game` 독립 파이프라인과 `develop` 통합 release를 별도 target lock으로 운영한다. | PASS |
| 15조 Secret | SCM에는 credential ID·환경변수 이름만 두며 값은 초기 Jenkins credential에서 실행 시 주입한다. AWS Secret 저장소는 권한 제공 후 선택한다. 로그·캐시·artifact를 누출 검사한다. | PASS |
| 23·24조 계약 변경 | 파트 adapter와 release/verification schema를 버전 관리한다. 파트 간 payload 변경은 영향 파트 합의 절차를 따른다. | PASS |
| 27조 기준선 동결 | 기존 `festa-unity/Docker/`와 동결 Network/Connection/Booth 코드를 수정하지 않고 검증된 Docker context를 재사용한다. 새 Unity CI entry point만 Editor 전용 경로에 추가한다. | PASS |
| 28조 P0/P1 | CI/CD와 안전한 배포·복구를 P0, provenance와 관측/알림을 P1로 분리해 P1 장애가 P0를 막지 않는다. | PASS |
| 29조 기록 | 계획 완료를 작업일지에 기록하고 setup-plan sandbox 문제를 T-30으로 남긴다. | PASS |
| 30조 미정 항목 | EC2 sizing, 관측 보존/임계치/패널, Unity Personal 조직 적격성은 구현자가 임의 확정하지 않고 운영 전 게이트로 남긴다. | PASS |

**Post-design re-check**: 계약과 quickstart까지 검토한 결과 새 위반은 없다. Docker 권한과 단일 장애영역은 숨기지 않고 이전 게이트 및 후속 결정으로 기록했다.

## Architecture and Delivery Design

### Jenkins execution topology

- Jenkins controller의 built-in executor는 `0`이며 Docker socket, Unity 설치, 배포 권한을 주지 않는다.
- 일반 검증/컨테이너 빌드는 `linux && docker` agent, 배포는 제한된 `deploy` agent, Unity는 `unity-6000.0.78f1` 영속 agent에서 실행한다.
- 초기에는 모두 같은 EC2의 별도 사용자·workspace·Compose project로 분리한다. agent는 `JENKINS_HOME` 접근과 무제한 sudo를 갖지 않는다.
- Docker agent는 rootless Docker를 우선 검증한다. 불가하여 docker group/socket을 쓰면 호스트 제어 권한 위험을 수용한 것으로 기록하고 별도 agent EC2 이전을 확장 게이트로 둔다.
- CPU/RAM/disk 경합을 줄이기 위해 executor 1개에서 시작하고 cgroup 제한과 disk alarm을 둔다. 수치는 EC2 사양 확인 후 정한다.

### SCM migration boundary

- Multibranch Pipeline이 provider별 branch discovery와 webhook을 담당하고 Jenkinsfile은 `checkout scm`으로 자신과 같은 commit을 가져온다.
- GitHub→GitLab 이전 시 바꾸는 범위는 Branch Source/plugin, repository/API URL, checkout/API credential ID, webhook 및 서명 Secret이다.
- 유지되는 범위는 Jenkinsfile, component adapter 계약, stage/failure code, artifact/release schema, deploy/verify/rollback script다.

### Network exposure and UFW

- 서버 수령 후 기존 SSH 세션을 2~3개 유지한 상태에서 UFW 현황을 먼저 확인하고, 이미 활성화돼 있으면 초기화·비활성화하지 않고 필요한 rule만 추가한다.
- 기본 inbound 허용은 `22/tcp`(SSH), `80/tcp`(HTTPS redirect/인증서 발급 필요 시), `443/tcp`(웹 및 GitHub/GitLab webhook)로 제한한다.
- Jenkins는 `127.0.0.1:8080`에 bind하고 Nginx가 `443` TLS를 종료해 reverse proxy한다. Jenkins `8080`, Grafana `3000`, Jenkins inbound agent `50000`은 인터넷에 직접 공개하지 않는다.
- 같은 EC2의 agent는 local/private 연결을 사용한다. Mattermost 알림은 outbound 요청이므로 별도 inbound rule을 추가하지 않는다.
- Unity server `7777`은 실제 Transport의 TCP/UDP 요구사항과 외부 네트워크 정책을 확인한 후 필요한 protocol만 추가한다.
- UFW 허용만으로 외부 도달성을 가정하지 않는다. SSAFY/AWS 상위 방화벽, DNS가 EC2를 가리키는지, TLS 인증서 발급 가능 여부를 서버 수령 후 외부 네트워크에서 검증한다.

### Pipeline and deployment flow

1. 파트 브랜치: checkout → 계약/Secret 검사 → test → build → commit별 local image tag 생성 → Docker image ID 확정 → 파트 target lock → freshness 재확인 → 해당 Compose service만 deploy → readiness verify → 배포 기록.
2. `develop`: 각 component의 local image ref와 image ID를 하나의 release manifest로 고정 → 통합 target lock/freshness 확인 → 전체 candidate deploy → 웹/로그인/월드/AI 순차 검증 → 정책 판정 → current release를 한 번에 승격.
3. build/test는 병렬 가능하지만 배포 경계는 `milestone`과 target별 `lock`으로 직렬화한다. lock 획득 후 branch 최신 SHA/release sequence를 다시 확인해 오래된 실행을 `SUPERSEDED` 처리한다.
4. 초기 이미지는 EC2 local Docker store에 `<component>:<full-commit-sha>` 고유 tag로 저장한다. 배포 전 `docker image inspect`의 image ID가 manifest와 같은지 확인하고 tag를 재사용·덮어쓰지 않는다. current/known-good image는 정리 대상에서 보호하며 disk 상한을 둔다. AWS IAM과 ECR이 제공되면 `storageMode` adapter만 `registry`로 바꾸고 pipeline/release 계약은 유지한다.
5. 파트/환경마다 Compose project name, network, container prefix, named volume, deploy target을 분리하며 스크립트는 명시된 service만 조작한다.

### Failure and recovery policy

| 분류 | 예시 | 자동 동작 |
|---|---|---|
| `PRE_DEPLOY_FAILURE` | test/build/schema 실패 | 배포하지 않음. rollback 없음 |
| `REVERSIBLE_RUNTIME` | container start 실패, 비AI verify 실패, `rollbackSafety=SAFE` | local store의 직전 known-good image ID가 보존된 release로 1회 rollback 후 비AI verify 재실행 |
| `DATA_OR_CONFIG_RISK` | DB schema, Secret/설정 오류, 비가역 data change | 상태·로그·manifest 보존, `MANUAL_ACTION_REQUIRED` |
| `AI_EXTERNAL_ONLY` | 외부 AI 장애로 AI 응답 verify만 실패 | release 성공 승격 금지, 정상 비AI/월드 유지, AI 재시도 또는 승인 대기 |
| `UNKNOWN` 또는 rollback 실패 | 분류 불가/복구 실패 | 자동 반복 금지, 현장 보존 후 수동 대응 |

자동 rollback은 catch-all post action으로 두지 않는다. 배포 전 manifest의 data/config 변경 메타데이터와 구조화된 failure code를 함께 평가해야 한다.

### Unity build boundary

- 전용 agent에 정확한 Unity Editor와 Web Build Support/Linux Dedicated Server Build Support를 설치하고 운영자가 Hub에서 Personal을 1회 대화형 활성화한다.
- Unity ID·비밀번호·세션·라이선스 파일을 Jenkins/SCM/artifact에 넣지 않는다. 정상 job마다 반환하지 않으며 agent 교체·폐기 때 node offline 확인 후 Hub 로그아웃/반환을 수행한다.
- 별도 빌드 머신 활성화의 Authorized User/조직 사용 및 Personal 재정 기준 적격성은 운영 전 담당자가 확인·기록해야 한다.
- WebGL과 Linux Dedicated Server는 별도 Unity 프로세스·workspace·Library로 순차 빌드한다. executor는 1개다.
- target별 Library는 재사용하되 출력 폴더는 매번 정리한다. 캐시 의심 시 `CleanBuildCache`, 이후에도 실패하면 Editor 종료 후 해당 target Library만 재생성한다.
- `Assets/_Project/Editor/CI/`에 새 batch entry point를 추가하되 동결 코드는 건드리지 않는다. Linux 출력과 Docker context는 기존 `Builds/linux-server` 계약을 그대로 따른다.

### P1 observability boundary

- 별도 `observability` Compose project에서 Alloy가 host metric과 Docker log를 수집해 Prometheus/Loki로 전달하고 Grafana가 조회·규칙 평가를 수행한다.
- Git으로 관리되는 탐지 규칙만 source of truth로 삼고 `managed_by=infra`, `approval_status=approved`, `notify=mattermost`를 모두 만족할 때만 Mattermost route를 탄다. `domain=ci`/일반 pipeline status는 제외한다.
- 수집 전에 승인된 RE2 마스킹 규칙으로 token/password/cookie/webhook/PII를 제거하며 고카디널리티 값은 Loki label로 쓰지 않는다.
- app/CI는 관측 stack에 `depends_on`하지 않으며 관측 장애를 build/deploy/readiness 실패 조건으로 쓰지 않는다. 저장소·WAL·queue에 상한을 둔다.
- 단일 EC2 자체 장애는 같은 EC2의 관측 stack으로 알릴 수 없다. 외부 heartbeat/CloudWatch 등 두 번째 실패영역은 후속 결정 사항이다.

## Project Structure

### Documentation (this feature)

```text
specs/infra-001-ci-cd-pipelines/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── component-pipeline-contract.md
│   ├── release-manifest.schema.json
│   ├── verification-result.schema.json
│   └── detection-rule.schema.json
└── tasks.md                         # $speckit-tasks에서 생성
```

### Source Code (repository root)

```text
Jenkinsfile                            # 얇은 SCM 중립 dispatcher
infra/
├── jenkins/
│   ├── controller/compose.yaml
│   ├── reverse-proxy/nginx.conf
│   ├── casc/jenkins.yaml
│   ├── plugins.txt
│   ├── pipelines/{component,develop}.groovy
│   ├── scripts/{freshness,provenance,secret-scan}.sh
│   └── tests/
├── deploy/
│   ├── compose/{dev,integration}/
│   ├── scripts/{deploy-component,deploy-release,verify-release,rollback-release}.sh
│   ├── state/README.md               # 실제 state는 커밋하지 않음
│   └── runbooks/{manual-recovery,agent-retirement}.md
├── observability/                    # P1, 별도 Compose project
│   ├── compose.yaml
│   ├── alloy/config.alloy
│   ├── loki/config.yaml
│   ├── prometheus/prometheus.yaml
│   ├── grafana/provisioning/{datasources,dashboards,alerting}/
│   ├── grafana/dashboards/
│   └── .env.example
└── tests/{contract,integration,security}/

festa-unity/
├── Assets/_Project/Editor/CI/         # 신규 Unity batch build entry point
├── Builds/{webgl,linux-server}/       # 생성물, 커밋하지 않음
└── Docker/                            # 기존 동결 파일 재사용, 수정 금지
```

각 파트 저장소가 실제로 분리되면 파트 소유자가 계약에 맞는 `ci/test`, `ci/build`, `ci/package`, `ci/verify` adapter를 자기 저장소에 둔다. 중앙 `infra/`는 adapter를 호출할 뿐 아직 존재하지 않는 app 디렉터리를 가정하지 않는다.

**Structure Decision**: 중앙 Jenkins·배포·관측 구성을 새 `infra/`에 모으고 root Jenkinsfile은 dispatcher로만 유지한다. Unity에 필요한 신규 코드는 Editor 전용 CI 경계에만 추가하며 기존 배포 Dockerfile과 동결 런타임은 재사용한다.

## Phase Outputs and Implementation Order

1. P0 기반: Jenkins controller/agent/JCasC, credential reference, component adapter 계약, schema validator.
2. P0 파트 경로: 4개 branch의 test/build/package/local image ID/deploy/verify와 target isolation/freshness.
3. P0 통합 경로: develop release manifest, 순차 E2E verify, 조건부 rollback/manual/AI-only 판정.
4. P0 Unity 경로: Personal 운영 게이트, batch entry point, target별 캐시와 WebGL/Linux Server artifact 검증.
5. P1 provenance: commit→local image tag/image ID→target→verification→recovery→current release 조회.
6. P1 관측: Alloy/Loki/Prometheus/Grafana, 승인 규칙, Mattermost E2E, retention/sizing 실측.

상세 작업 분할과 의존 순서는 다음 `$speckit-tasks` 단계에서 생성한다.

## Complexity Tracking

헌법 위반 없음. 단일 EC2와 Docker socket은 요구사항상 초기 제약이며 완전한 격리로 주장하지 않고 위험·이전 게이트를 명시했다.
