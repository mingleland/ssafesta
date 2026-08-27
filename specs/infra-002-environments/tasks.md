# 작업 목록: dev/demo 실행 환경

**입력**: `specs/infra-002-environments/`의 설계 문서

**선행 문서**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**테스트**: spec의 독립 테스트·인수 시나리오·SC-001~SC-018이 검증을 명시하므로 각 사용자 스토리에 계약·통합·장애 테스트 작업을 포함한다. 각 테스트 작업은 해당 구현보다 먼저 작성하고 실패를 확인한다.

**구성**: 1·2단계는 모든 스토리가 공유하는 환경 골격과 차단 조건이다. 3~7단계는 spec의 US1~US5 순서이며 각 단계는 독립 검증 완료 지점을 가진다. 기존 infra-001의 Jenkins·릴리스·롤백 스키마는 참조만 하고 재구현하지 않는다.

## 형식: `[ID] [P?] [Story] 설명`

- **[P]**: 다른 파일을 수정하며 아직 완료되지 않은 작업에 의존하지 않아 병렬 실행 가능
- **[Story]**: spec의 사용자 스토리 `US1`~`US5`
- 모든 구현·검증 작업은 정확한 대상 경로를 포함

---

## 1단계: 초기 구성(공통 인프라)

**목적**: `infra/environments/` 작업 경계와 공통 테스트·검증 근거 구조를 만든다.

- [ ] T001 계획된 환경 디렉터리의 소유권, 공급자 경계, infra-001 재사용 규칙과 명령 규약을 `infra/environments/README.md`에 작성한다
- [ ] T002 [P] 런타임 전용 Secret 주입과 `.env.example` 규칙을 `infra/environments/config/README.md`에 문서화한다
- [ ] T003 [P] 재사용 가능한 엄격 셸 검증문, 정리 트랩과 민감정보 제거 명령 도우미를 `infra/environments/tests/lib/assert.sh`에 작성한다
- [ ] T004 [P] 계약 전용 실행기와 계약·통합·보안·장애·자원 전체 실행기를 `infra/environments/tests/contract/run.sh`와 `infra/environments/tests/run.sh`에 작성한다
- [ ] T005 [P] 민감정보가 제거된 검증 근거 디렉터리 이름 규칙과 필수 메타데이터 필드를 `infra/environments/tests/evidence/README.md`에 정의한다

**완료 확인**: 신규 구현은 `infra/environments/` 안에 위치하며 동결된 `festa-unity/Docker/`와 infra-001 파이프라인을 수정하지 않는다.

---

## 2단계: 공통 기반(전체 스토리의 선행 조건)

**목적**: 모든 사용자 스토리가 공통으로 사용하는 매니페스트, 사전 점검, 데이터 계층, Secret·검증 근거 경계를 구현한다.

**⚠️ 중요**: 이 단계가 끝나기 전에는 사용자 스토리 구현을 시작하지 않는다.

- [ ] T006 [P] 환경 매니페스트 스키마 검증기와 유효하지 않은 픽스처 사례를 `infra/environments/tests/contract/environment-manifest.sh`에 작성한다
- [ ] T007 [P] R2 Usage Admission의 79%/80%/90%/61분·active provider 혼입 사례와 Storage Failover Control의 상태별 uploadEnabled/activeWriteProvider 불일치 사례를 각각 `infra/environments/tests/contract/usage-guard.sh`와 `infra/environments/tests/contract/storage-failover-state.sh`에서 분리 검증한다
- [ ] T008 도구·버전 검사, C-01/C-02 지연 확정 입력, SG 준비 상태, Secret Reference 존재 여부와 단계별 조기 실패 동작을 `infra/environments/scripts/preflight.sh`에 구현한다
- [ ] T009 [P] dev용 변수 이름과 안전한 로컬 자리표시자만 `infra/environments/config/environments/dev.env.example`에 추가한다
- [ ] T010 [P] demo/R2/TLS 자격증명은 변수 이름만 두고 배포 가능한 기본값은 넣지 않도록 `infra/environments/config/environments/demo.env.example`에 작성한다
- [ ] T011 추정 수치 없이 EC2 용량 참조, 서비스별 demo 제한, 빌드 에이전트 제한과 `heavyBuildMaxConcurrency: 1`을 `infra/environments/config/resource-limits.example.yaml`에 정의한다
- [ ] T012 공통 레이블, 상태 확인 앵커, 내부 네트워크 규약과 공개 포트 없음 기본값을 `infra/environments/compose/common.yaml`에 작성한다
- [ ] T013 고정 이미지, 영속 볼륨과 호스트 포트 비공개 설정을 갖춘 별도 관리 PostgreSQL/Redis 데이터 프로젝트를 `infra/environments/compose/data/compose.yaml`에 작성한다
- [ ] T014 [P] `festa_dev_business`, `festa_dev_ai`, `festa_demo_business`, `festa_demo_ai`, 전용 역할, PUBLIC CONNECT 회수와 AI 전용 pgvector를 구성하는 멱등 부트스트랩 SQL을 `infra/environments/postgres/init/00-databases-and-roles.sql`에 작성한다
- [ ] T015 [P] Redis 기본 사용자를 비활성화하고 `noeviction`을 설정하며 외부 ACL 파일을 불러오고 환경·서비스 ACL 예시를 `infra/environments/redis/redis.conf`와 `infra/environments/redis/users.acl.example`에 정의한다
- [ ] T016 [P] infra-001의 스키마를 복제하지 않고 릴리스 매니페스트와 검증 대상 참조를 `infra/environments/scripts/validate-infra001-contracts.sh`에서 검증한다
- [ ] T017 자격증명, 쿠키, 서명 URL, 개인 키와 원본 환경 덤프를 거부하는 민감정보 제거 검증 근거 기록을 `infra/environments/scripts/write-evidence.sh`에 구현한다

**완료 확인**: 유효하지 않은 픽스처에서 스키마 테스트가 실패하고, 실제 배포 입력이 누락되면 사전 점검이 차단하며, 데이터 서비스는 내부 전용이고 검증 근거에는 Secret 원문을 포함할 수 없다.

---

## 3단계: 사용자 스토리 1 - 파트별 dev 환경을 독립적으로 운영한다 (우선순위: P0) 🎯 첫 번째 증분

**목표**: `ai`/`back`/`front`/`game` 중 한 컴포넌트만 배포하고 나머지 dev 컴포넌트, demo, 공용 데이터 서비스를 재시작하지 않는다.

**독립 테스트**: 한 컴포넌트의 새 전체 SHA를 dev에 배포해 대상 이미지·릴리스만 변경되고 다른 dev 컴포넌트와 모든 demo 컨테이너의 재시작 횟수가 0이며 영속 데이터가 유지되는지 확인한다.

### 사용자 스토리 1 테스트

- [ ] T018 [P] [US1] 네 가지 대상 ID, 고유한 서비스·네트워크·볼륨 이름, 불변 릴리스 참조와 승인된 Mock 표시를 검증하는 실패 우선 dev 매니페스트 테스트를 `infra/environments/tests/contract/dev-manifest.sh`에 작성한다
- [ ] T019 [P] [US1] 각 파트 배포 전후의 이미지 ID와 재시작 횟수를 스냅샷으로 비교하는 실패 우선 컴포넌트 격리 테스트를 `infra/environments/tests/integration/dev-component-isolation.sh`에 작성한다
- [ ] T020 [P] [US1] 잘못된 dev 릴리스가 다른 dev 서비스, demo와 데이터 프로젝트를 변경하지 않음을 입증하는 실패 우선 배포 오류 테스트를 `infra/environments/tests/failure/dev-deploy-failure.sh`에 작성한다

### 사용자 스토리 1 구현

- [ ] T021 [US1] `dev-ai`, `dev-back`, `dev-front`, `dev-game` 대상을 포함한 `festa-dev` 환경 매니페스트를 `infra/environments/config/manifests/dev.json`에 작성한다
- [ ] T022 [US1] 공용 dev 프로젝트, 내부 네트워크 별칭, Mock 선택 입력과 컴포넌트 프로필을 `infra/environments/compose/dev/base.yaml`에 작성하고 승인된 IP 기반 `__dev/{front|api|ai|world}` 라우팅을 `infra/environments/nginx/sites/dev.conf`에 정의한다
- [ ] T023 [P] [US1] 환경에서 주입되는 엔드포인트와 자원 참조를 사용하는 FastAPI dev 서비스 오버레이를 `infra/environments/compose/dev/ai.yaml`에 추가한다
- [ ] T024 [P] [US1] 환경 범위 PostgreSQL/Redis 연결을 사용하는 Spring dev 서비스 오버레이를 `infra/environments/compose/dev/back.yaml`에 추가한다
- [ ] T025 [P] [US1] 환경 호스트 이름을 내장하지 않은 React·정적 dev 서비스 오버레이를 `infra/environments/compose/dev/front.yaml`에 추가한다
- [ ] T026 [P] [US1] 내부 전용 WebSocket 엔드포인트를 사용하고 7777 포트를 공개하지 않는 Unity Dedicated Server dev 오버레이를 `infra/environments/compose/dev/game.yaml`에 추가한다
- [ ] T027 [US1] 컴포넌트 전용 `up -d --no-deps`, 대상 허용 목록, infra-001 최신성 검증과 환경 전체 `down` 명시적 금지를 `infra/environments/scripts/deploy-environment.sh`에 구현한다
- [ ] T028 [US1] 대상 이미지·릴리스 변경, 비대상 재시작 횟수 0, Mock 사용 공개와 공용 데이터 연속성을 확인하는 dev 검증을 `infra/environments/scripts/verify-environment.sh`에 구현한다

**완료 확인**: 네 컴포넌트 대상 모두에서 T018~T020이 통과하고, 도메인·Cloudflare·US2~US5 없이도 US1을 시연할 수 있다.

---

## 4단계: 사용자 스토리 2 - develop을 통합 demo 환경으로 제공한다 (우선순위: P0)

**목표**: infra-001의 `develop` 릴리스 매니페스트를 하나의 demo에 배포하고 웹·로그인·월드·AI·문서 검증을 동일 릴리스 추적 정보에 연결하면서 빌드 경합 시 demo를 보호한다.

**독립 테스트**: 통합 릴리스 배포 후 공개 웹, 로그인 API, AI 상태와 문서 경로의 검증 결과가 같은 릴리스 ID를 가리키며 AI 전용 장애가 정상 비AI 서비스를 중단시키지 않는지 확인한다.

### 사용자 스토리 2 테스트

- [ ] T029 [P] [US2] `integration` 대상, 모든 필수 컴포넌트, infra-001 릴리스 참조, 공개 진입점 참조와 demo 자원 우선순위를 검증하는 실패 우선 demo 매니페스트 테스트를 `infra/environments/tests/contract/demo-manifest.sh`에 작성한다
- [ ] T030 [P] [US2] 웹, 로그인, 월드, AI 상태, 문서 경로와 릴리스·검증 추적 정보를 확인하는 실패 우선 종단 간 검증기를 `infra/environments/tests/integration/demo-journey.sh`에 작성하고 AI·R2 장애가 정상 비AI 경로와 CI/CD를 중단시키지 않는지 `infra/environments/tests/failure/isolation.sh`에서 검증한다
- [ ] T031 [P] [US2] 동시 실행 수 ≤1, 두 번째 빌드 대기, 정상 demo 여정과 demo 재시작 횟수 0을 검증하는 실패 우선 2요청 고부하 빌드 테스트를 `infra/environments/tests/resource/demo-with-heavy-build.sh`에 작성한다
- [ ] T032 [P] [US2] 호스트 라우팅, TLS 준비 상태, SSE·WebSocket 핸드셰이크와 내부 포트 비공개를 검증하되 infra-003의 시간 초과 값을 확정하지 않는 실패 우선 검사를 `infra/environments/tests/integration/public-entry.sh`에 작성한다

### 사용자 스토리 2 구현

- [ ] T033 [P] [US2] infra-001 `integration` 대상과 모든 공개·데이터 연결에 연계된 통합 demo 환경 매니페스트를 `infra/environments/config/manifests/demo.json`에 작성한다
- [ ] T034 [P] [US2] 모든 릴리스 매니페스트 이미지 참조, 상태 확인, 격리 네트워크·볼륨과 demo 자원 참조를 갖춘 `festa-demo` Compose 프로젝트를 `infra/environments/compose/demo/compose.yaml`에 작성한다
- [ ] T035 [US2] Nginx 진입 프로세스, 신뢰 프록시 처리, 접근 로그 민감정보 제거와 include 구조를 `infra/environments/nginx/nginx.conf`에 작성한다
- [ ] T036 [US2] `demo`/`api`/`ai`/`world.${ROOT_DOMAIN}`을 demo 서비스로 라우팅하고, SSE 버퍼링을 비활성화하며, WebSocket Upgrade를 전달하고 동적 응답의 기본값을 no-store로 설정하도록 `infra/environments/nginx/sites/demo.conf`에 작성한다
- [ ] T037 [P] [US2] 전역 고부하 빌드 세마포어, 승인 전 demo 상태 게이트와 대기열 검증 근거 출력을 `infra/environments/scripts/admit-heavy-build.sh`에 구현한다
- [ ] T038 [US2] 검증된 infra-001 통합 매니페스트만 사용하고 infra-001의 current/known-good 소유권을 유지하도록 `infra/environments/scripts/deploy-environment.sh`의 demo 배포를 확장한다
- [ ] T039 [US2] infra-001 호환 검사 이름, AI 전용 성능 저하와 릴리스 검증 근거 참조를 출력하도록 `infra/environments/scripts/verify-environment.sh`의 demo 검증을 확장한다

**완료 확인**: T029~T032가 통과한다. US3가 연결되기 전까지 US2는 승인된 Mock 문서·AI 어댑터를 사용할 수 있지만, 완전한 운영 준비 상태로 보고하지 않고 검증 결과에 Mock임을 표시해야 한다.

---

## 5단계: 사용자 스토리 3 - 영구 데이터와 AI 문서를 안전하게 보존한다 (우선순위: P0)

**목표**: 애플리케이션 재배포 뒤 PostgreSQL/R2 원본을 유지하고, 브라우저 직접 업로드를 검증하며, PostgreSQL 백업·Redis 손실·R2 장애에서 명시된 복구 경로를 제공한다.

**독립 테스트**: 테스트 비즈니스·벡터 메타데이터와 비공개 문서를 저장한 후 앱 컨테이너와 Redis를 재생성하고, PostgreSQL/R2 원본 손실 0, 재생성 결과 일치, R2 백업 복원과 수동 MinIO 상태 전이를 검증한다.

### 사용자 스토리 3 테스트

- [ ] T040 [P] [US3] 4개 역할 × 4개 데이터베이스 CONNECT 행렬, AI 전용 pgvector와 런타임 역할 권한을 검증하는 실패 우선 테스트를 `infra/environments/tests/integration/postgres-isolation.sh`에 작성한다
- [ ] T041 [P] [US3] Redis 익명·기본 사용자, 환경 간 키, 금지 명령, TTL과 공개 포트 없음을 검증하는 실패 우선 테스트를 `infra/environments/tests/integration/redis-acl.sh`에 작성한다
- [ ] T042 [P] [US3] 정상, 잘못된 Content-Type, 만료, 다른 객체, 크기 불일치, 위조 MIME, SHA 불일치와 중복 완료 업로드 사례를 검증하는 실패 우선 테스트를 `infra/environments/tests/integration/object-upload.sh`에 작성한다
- [ ] T043 [P] [US3] 현재·예상 저장량, Class A와 Class B에 대한 79%/80%/90% 및 61분 경과 사례를 검증하는 실패 우선 테스트를 `infra/environments/tests/failure/r2-usage-guard.sh`에 작성한다
- [ ] T044 [P] [US3] 정확한 수동 상태 머신, 운영자 승인, MinIO 포트 차단, 체크섬 불일치와 R2 복귀를 검증하는 실패 우선 R2 장애 테스트를 `infra/environments/tests/failure/storage-fallback.sh`에 작성한다
- [ ] T045 [P] [US3] 스키마, 행, 벡터, 릴리스, 체크섬, 보존 기간과 문서 목록 검증 근거를 포함하는 실패 우선 R2 전용 PostgreSQL 덤프·복원 테스트를 `infra/environments/tests/failure/postgres-restore.sh`에 작성한다
- [ ] T046 [P] [US3] 재인증, 영구 데이터 손실 0, RAG 범위·리비전 재구축과 설문 집계 일치를 검증하는 실패 우선 Redis 전체 손실 테스트를 `infra/environments/tests/failure/redis-total-loss.sh`에 작성한다

### 사용자 스토리 3 구현

- [ ] T047 [P] [US3] 비공개 R2 Standard 버킷 2개, 분리된 문서·백업 자격증명 참조, 공개 접근 비활성화와 백업 버킷 CORS 없음을 `infra/environments/storage/r2/buckets.example.yaml`에 정의한다
- [ ] T048 [P] [US3] 정확한 허용 출처, PUT 전용 메서드, 필수 Content-Type·체크섬 헤더, 노출 ETag와 와일드카드 금지를 `infra/environments/storage/r2/documents-cors.json`에 정의한다
- [ ] T049 [US3] URL을 영속 저장하지 않는 S3 호환 사전 서명·PUT·HEAD·본문 매직 바이트·SHA-256·멱등 완료 검사를 `infra/environments/storage/r2/presign-probe.sh`에 구현한다
- [ ] T050 [P] [US3] `collectedAt`과 `dataFreshThrough`를 포함한 계정 전체 R2 작업·저장량 15분 주기 수집을 `infra/environments/storage/usage-guard/collect-cloudflare.sh`에 구현한다
- [ ] T051 [US3] 보수적인 현재·예상 GB-month 비율, Class A/B 비율, 80% 경고, 90% 차단과 오래된 데이터의 안전 차단 평가를 `infra/environments/storage/usage-guard/evaluate.sh`에 구현한다
- [ ] T052 [P] [US3] 영속 로컬 볼륨을 사용하고 9000/9001 호스트 포트를 공개하지 않는 내부 전용 단일 노드 MinIO 긴급 Compose 프로필을 `infra/environments/compose/emergency/minio.yaml`에 작성한다
- [ ] T053 [US3] 운영자 전용 `R2_ACTIVE → UPLOAD_BLOCKED → FALLBACK_VALIDATING → LOCAL_ACTIVE → R2_RECONCILING → R2_ACTIVE` 전이와 검증 근거 요구사항을 `infra/environments/storage/fallback/transition.sh`에 구현한다
- [ ] T054 [US3] MinIO 대기 객체 목록과 크기·형식·SHA-256 검증 후 R2 복사를 구현하고 불일치 시 메타데이터를 전환하지 않도록 `infra/environments/storage/fallback/reconcile.sh`에 작성한다
- [ ] T055 [P] [US3] 사용자 지정 형식 데이터베이스 덤프, 릴리스·스키마·버전 매니페스트, SHA-256과 비공개 R2 업로드를 `infra/environments/postgres/backup/dump.sh`에 구현한다
- [ ] T056 [US3] 체크섬을 검증한 다운로드와 명시적으로 폐기 가능한 대상 데이터베이스로의 복원을 `infra/environments/postgres/backup/restore.sh`에 구현한다
- [ ] T057 [US3] 복원된 스키마·행·벡터·문서 목록 검증과 민감정보 제거 검증 근거 출력을 `infra/environments/postgres/backup/verify.sh`에 구현한다
- [ ] T058 [P] [US3] 일간 7개, 주간 4개, 마이그레이션 전 보존과 모의 삭제 보고를 `infra/environments/postgres/backup/retention.sh`에 구현한다
- [ ] T059 [US3] 환경 ID, Redis 사용자 이름·비밀번호 참조, 자격증명 누락 시 조기 실패와 키 공간 연결을 갖춘 `infra` Spring 프로필을 `backend/src/main/resources/application-infra.yml`과 `backend/src/main/java/com/example/ssafesta/config/RedisKeyspaceProperties.java`에 추가한다
- [ ] T060 [US3] 기존 TTL·대체 동작을 유지하면서 인증, OAuth 전달과 지갑 캐시 키 앞에 주입된 환경 네임스페이스를 붙이도록 `backend/src/main/java/com/example/ssafesta/auth/MemberSessionService.java`, `backend/src/main/java/com/example/ssafesta/auth/OAuthHandoffService.java`, `backend/src/main/java/com/example/ssafesta/wallet/DailyCoinGrantService.java`를 수정한다
- [ ] T061 [US3] 합성 RAG `boothId+agentId+sourceRevision` 및 설문 `surveyId+sourceRevision` 캐시 검사, 원본 대체 경로 비교와 과대 항목 거부를 `infra/environments/storage/usage-guard/cache-recovery-probe.sh`에 구현한다

**완료 확인**: T040~T046이 통과한다. 객체 본문은 HEAD 메타데이터만으로 유효하다고 취급하지 않고, PostgreSQL을 R2에서 복원할 수 있으며, Redis는 원본 데이터 저장소가 아니고, MinIO는 동일 호스트의 임시 가용성으로만 보고한다.

---

## 6단계: 사용자 스토리 4 - Secret과 내부 서비스를 외부에 노출하지 않는다 (우선순위: P0)

**목표**: 외부에는 승인된 22/80/443만 보이고 데이터·CI·관측·게임·MinIO 포트와 다른 환경의 네트워크·DB·Redis·Secret에는 접근할 수 없게 한다.

**독립 테스트**: 외부 포트 검사, 환경 간 접근, 렌더링된 설정·로그·릴리스 검증 근거의 Secret 검사에서 비인가 접근과 Secret 원문 검출이 모두 0인지 확인한다.

### 사용자 스토리 4 테스트

- [ ] T062 [P] [US4] 22/80/443과 5432/6379/7777/8080/9000/9001을 비교하고 계층별 SG/UFW 진단을 수행하는 실패 우선 외부 검사를 `infra/environments/tests/security/port-exposure.sh`에 작성한다
- [ ] T063 [P] [US4] dev→demo 및 demo→dev Docker DNS·네트워크, PostgreSQL, Redis와 Secret Reference 접근 사례를 검증하는 실패 우선 테스트를 `infra/environments/tests/security/cross-environment.sh`에 작성한다
- [ ] T064 [P] [US4] 저장소, 렌더링된 Compose, 앱·Nginx·CI 로그, 릴리스 검증 근거, 서명 URL, 쿠키와 TLS 키를 검사하는 실패 우선 스캐너를 `infra/environments/tests/security/secret-scan.sh`에 작성한다

### 사용자 스토리 4 구현

- [ ] T065 [P] [US4] 보안 헤더, 요청 제한, 서버 토큰 숨김, 관리 경로 차단과 접근 로그 민감정보 제거를 `infra/environments/nginx/snippets/security.conf`에 추가한다
- [ ] T066 [P] [US4] Full (strict) 원본 서버 인증서·키 참조, 최신 TLS 설정과 인증서 누락 시 조기 실패 동작을 `infra/environments/nginx/snippets/tls.conf`에 추가한다
- [ ] T067 [P] [US4] 80/443 허용과 내부 포트 차단 전에 현재 SSH 접속 출처를 보존하는 멱등 UFW 정책 스크립트를 `infra/environments/scripts/apply-ufw.sh`에 구현한다
- [ ] T068 [US4] 데이터 서비스를 노출하지 않으면서 환경별 내부 네트워크와 명시적인 ingress 전용 연결을 `infra/environments/compose/security.override.yaml`에 추가한다
- [ ] T069 [P] [US4] 필수 Secret Reference 목록, 교체 담당자 필드와 금지된 리터럴 패턴을 `infra/environments/config/secrets.required`에 정의한다
- [ ] T070 [US4] 안전한 합성 카나리를 사용하고 일치한 Secret 값을 출력하지 않는 저장소·설정·로그·검증 근거 Secret 검사를 `infra/environments/scripts/scan-secrets.sh`에 구현한다
- [ ] T071 [US4] SG·UFW·Docker 리스너 계층 비교 진단 출력과 민감정보 제거 검증 근거 수집을 `infra/environments/scripts/verify-network-boundary.sh`에 구현한다
- [ ] T072 [US4] 안전한 SSH 보존, UFW 적용·롤백, 외부 검사, TLS 키 처리와 사고 롤백을 위한 운영자 절차를 `infra/environments/runbooks/network-security.md`에 작성한다

**완료 확인**: 외부 호스트와 양쪽 환경 네트워크에서 T062~T064가 통과하며, 허용되지 않은 내부·데이터·관리 포트 노출과 Secret 원문 검출은 0건이다.

---

## 7단계: 사용자 스토리 5 - 정적 콘텐츠 전송 부하를 외부로 분산한다 (우선순위: P1)

**목표**: 버전이 지정된 React/Unity Web 정적 자산의 반복 요청을 Cloudflare 캐시로 분산하고 HTML·동적 경로는 안전하게 우회하며 CDN 장애 시 검증 가능한 원본 서버 경로를 제공한다.

**독립 테스트**: 같은 정적 릴리스를 두 번 요청해 캐시 가능한 자산의 원본 서버 도달이 감소하고 HTML은 갱신되며 API·SSE·서명 업로드·WebSocket은 캐시되지 않고 `curl --resolve` 원본 서버 검증이 성공하는지 확인한다.

### 사용자 스토리 5 테스트

- [ ] T073 [P] [US5] HTML 재검증, 콘텐츠 해시 불변 자산, 압축 헤더, 동적 경로 우회와 릴리스 혼합 방지를 확인하는 실패 우선 최초·반복 요청 테스트를 `infra/environments/tests/integration/static-cache.sh`에 작성한다
- [ ] T074 [P] [US5] `-k`를 거부하고 공개 우회 호스트 이름을 만들지 않는 인증서 검증 `curl --resolve` 원본 서버 테스트를 실패 우선으로 `infra/environments/tests/failure/origin-fallback.sh`에 작성한다

### 사용자 스토리 5 구현

- [ ] T075 [P] [US5] React와 Unity Web 산출물의 HTML 재검증·콘텐츠 해시 불변 cache-control을 `infra/environments/nginx/snippets/cache.conf`에 구현하고 API·인증·SSE·업로드·WebSocket 우선 우회를 `infra/environments/nginx/snippets/dynamic-bypass.conf`에 정의한다
- [ ] T076 [P] [US5] 버전이 지정된 정적 자산만 캐시하고 API·인증·SSE·업로드·WebSocket을 우회하는 Cloudflare 호스트·프록시·캐시 규칙을 `infra/environments/storage/r2/cloudflare-cache-rules.example.yaml`에 정의한다
- [ ] T077 [P] [US5] 롤백 이력을 소유하지 않으면서 infra-001 릴리스 ID에 연결되는 정적 `current`/`known-good` 원자적 전환을 `infra/environments/scripts/switch-static-release.sh`에 구현한다
- [ ] T078 [P] [US5] 인증서를 검증하는 원본 서버 접근, DNS/CDN 장애 검증 근거와 운영자 전용이라는 사실을 `infra/environments/runbooks/origin-access.md`에 문서화한다
- [ ] T079 [US5] 동적 경로 동작을 바꾸지 않고 캐시·보안 스니펫과 버전이 지정된 정적 릴리스 루트를 `infra/environments/nginx/sites/demo.conf`에 포함한다
- [ ] T080 [US5] Cloudflare 캐시 상태, 원본 서버 도달, 압축, 오래된 HTML과 동적 경로 우회 검증 출력을 `infra/environments/scripts/verify-static-delivery.sh`에 구현한다

**완료 확인**: T073~T074가 통과하고, 정적 반복 요청이 원본 서버 트래픽을 줄이며, 동적 응답은 비공개로 유지되고, 문서화된 원본 서버 경로가 인증서 검증을 유지한다.

---

## 8단계: 마무리 및 공통 정리

**목적**: 전체 빠른 시작 절차, 운영 문서, 기준선 동결과 최종 검증 근거의 정합성을 맞춘다.

- [ ] T081 `specs/infra-002-environments/quickstart.md`의 실행 가능한 모든 시나리오를 수행하는 단일 엄격 실행기를 `infra/environments/tests/quickstart.sh`에 구현한다
- [ ] T082 [P] 오래된 pgvector 스키마 전용, 단일 버킷 접두사, R2 2차 백업 미정과 demo 동결 설명을 기준 spec·plan에 맞게 `docs/15_Infra_AWS_설계서.md`에서 정리한다
- [ ] T083 [P] ALB/NLB를 다시 도입하지 않고 현재 단일 EC2 소유권, C-01/C-02 입력과 확정된 C-07 정책을 `docs/26_팀_결정_필요사항.md`에 반영한다
- [ ] T084 [P] R2 차단·MinIO 승인 전환·rollback·reconcile을 `infra/environments/runbooks/r2-fallback.md`, PostgreSQL 복원을 `infra/environments/runbooks/postgres-restore.md`, Redis 전체 손실 복구를 `infra/environments/runbooks/redis-recovery.md`에 문서화하고 네트워크 보안·원본 서버 절차까지 연결하는 운영 색인을 `infra/environments/runbooks/README.md`에 작성한다
- [ ] T085 [P] 구현 작업이 동결된 기준선 경로를 수정하지 않았는지 확인하고 검사 경로와 결과를 `infra/environments/tests/evidence/baseline-freeze.md`에 기록한다
- [ ] T086 모든 계약·통합·보안·장애·자원 테스트 모음을 실행하고 SC-001~SC-014 매핑과 민감정보가 제거된 검증 근거를 `infra/environments/tests/evidence/final-verification.md`에 기록한다
- [ ] T087 완료한 INFRA 구현, 검증 결과와 관련 INFRA-T 참조를 실행 날짜 아래 `docs/JSW/24_작업일지.md`에 기록한다

---

## 의존성과 실행 순서

### 단계별 의존성

- **1단계 초기 구성**: 의존성이 없다.
- **2단계 공통 기반**: 1단계에 의존하며 모든 사용자 스토리의 시작을 차단한다.
- **US1 / 3단계**: 2단계 이후 시작하며 다른 스토리에는 의존하지 않는다.
- **US2 / 4단계**: 2단계 이후 시작한다. US3 어댑터가 없을 때 승인된 Mock 표시를 사용하므로 독립적으로 테스트할 수 있다.
- **US3 / 5단계**: 2단계 이후 시작한다. 영구 데이터, R2, Redis 복구와 백업 작업은 US1/US2 런타임 라우팅과 독립적으로 진행할 수 있다.
- **US4 / 6단계**: 2단계 이후 시작한다. 최종 외부 검사에는 최소 실행 ingress·데이터 스택이 필요하지만 테스트 픽스처는 독립적으로 실행할 수 있다.
- **US5 / 7단계**: US2 Nginx demo 사이트와 정적 릴리스 라우팅에 의존하며 US3에는 의존하지 않는다.
- **8단계 마무리**: 릴리스 대상으로 선택한 모든 스토리에 의존한다. T086의 전체 SC-001~SC-018 검증 근거에는 US1~US5가 필요하다.

### 사용자 스토리 의존성 그래프

```text
초기 구성 → 공통 기반 ─┬→ US1 dev 격리 ───────────────┐
                      ├→ US2 demo 통합 ─→ US5 CDN ───┤
                      ├→ US3 데이터·객체·복구 ───────┤
                      └→ US4 보안 경계 ───────────────┤
                                                        └→ 마무리·전체 검증
```

### 각 사용자 스토리 내부 순서

- 스토리 테스트 작업을 먼저 작성하며, 구현 전에 의도한 미구현 동작으로 실패해야 한다.
- 설정·모델 파일을 이를 사용하는 오케스트레이션 스크립트보다 먼저 작성한다.
- 오케스트레이션을 종단 간 검증과 검증 근거보다 먼저 구현한다.
- 스토리 완료 확인을 통과해야 해당 단계를 완료로 표시한다.
- 완료한 INFRA 작업마다 즉시 `docs/JSW/24_작업일지.md`를 갱신하고, 발생한 문제마다 `docs/JSW/25_트러블슈팅.md`에 다음 `INFRA-T-번호`를 부여한다.

## 병렬 실행 가능 항목

### 사용자 스토리 1

```text
T018 dev 매니페스트 테스트 || T019 컴포넌트 격리 테스트 || T020 장애 격리 테스트
T023 AI 오버레이 || T024 백엔드 오버레이 || T025 프론트엔드 오버레이 || T026 게임 오버레이
```

### 사용자 스토리 2

```text
T029 demo 매니페스트 테스트 || T030 demo 여정 테스트 || T031 자원 경합 테스트
T033 demo 매니페스트 || T034 demo Compose || T037 빌드 승인
```

### 사용자 스토리 3

```text
T040 PostgreSQL 테스트 || T041 Redis ACL 테스트 || T042 업로드 테스트 || T043 사용량 테스트
T044 대체 경로 테스트 || T045 복원 테스트 || T046 Redis 손실 테스트
T047 버킷 설정 || T048 CORS 설정 || T050 지표 수집기 || T052 MinIO Compose || T055 덤프 || T058 보존
```

### 사용자 스토리 4

```text
T062 포트 테스트 || T063 환경 간 테스트 || T064 Secret 테스트
T065 Nginx 보안 || T066 TLS || T067 UFW || T069 Secret 목록
```

### 사용자 스토리 5

```text
T073 캐시 테스트 || T074 원본 서버 테스트
T075 캐시 스니펫 || T076 Cloudflare 규칙 || T077 정적 전환 || T078 원본 서버 운영 절차
```

## 구현 전략

### 첫 번째 독립 제공 가능 증분

1. 1단계 초기 구성을 완료한다.
2. 2단계 공통 기반을 완료한다.
3. 3단계 US1을 완료한다.
4. 작업을 멈추고 네 dev 대상 모두에 T018~T020을 실행한다.

US1만으로 첫 번째 독립 시연 가능 증분이 된다. 그러나 spec의 릴리스 준비 P0 기준선은 영속성·보안을 빼면 성립하지 않으므로 실제 demo 공개 전에는 US1~US4를 모두 완료해야 한다.

### 증분 제공 순서

1. **US1**: 컴포넌트별 독립 dev 배포.
2. **US2**: 통합 demo와 추적 정보. 필요하면 처음에는 명시적으로 표시된 Mock 어댑터를 사용한다.
3. **US3**: 영속 PostgreSQL/R2 경계, Redis 복구, 백업과 수동 저장소 대체 경로.
4. **US4**: 공개 포트·Secret·환경 간 보안 게이트. 여기서 P0 릴리스 게이트가 닫힌다.
5. **US5**: P1 개선 사항인 Cloudflare 정적 부하 분산과 원본 서버 대체 경로.
6. **마무리**: 전체 빠른 시작 절차를 실행하고 운영 문서의 정합성을 맞춘다.

### 팀 병렬 실행 전략

- 1·2단계를 함께 완료한다.
- 이후 US1 런타임, US2 demo·ingress, US3 데이터·저장소를 서로 다른 담당자에게 배정한다.
- US4 보안 설정은 병렬로 시작하되 최종 외부 검사는 통합 스택을 대상으로 수행한다.
- US2가 `nginx/sites/demo.conf`를 작성한 후 US5를 시작한다.

## 참고

- `[P]`는 파일 수준 병렬 처리만 의미한다. 공용 EC2/R2 변경은 여전히 운영자가 순차 처리해야 한다.
- C-01/C-02 값이 사전 점검을 통과할 때까지 실제 DNS/TLS/자원 제한 활성화는 차단된다.
- R2 원본 문서에는 2차 백업이 없으며 MinIO도 이 사실을 바꾸지 않는다.
- WSS heartbeat·유휴 시간 초과 수치 조정은 infra-003 범위이므로 여기서 도입하지 않는다.
- Secret 값, Secret이 포함된 렌더링 Compose 출력, 서명 URL이나 개인 키를 커밋하지 않는다.
