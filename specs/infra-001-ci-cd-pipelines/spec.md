# Feature Specification: 파트별 CI/CD 파이프라인

**Feature Branch**: `infra-001-ci-cd-pipelines` *(spec 식별자이며 Git 브랜치명과는 독립)*

**Created**: 2026-08-17

**Status**: Confirmed — **Infra 리뷰 완료 (2026-08-18)**

**Input**: `docs/sdd/parts/INFRA.md`의 infra-001 초안을 기반으로, GitLab CI/Runner의 `feature/* → develop` MR build·test merge gate와 Jenkins의 `develop` 선택적 dev 배포, 승인된 release의 demo 통합 배포를 명세한다. 후속 요구로 수집 Agent 기반 로그·서버 지표 관측과 규칙 기반 Mattermost 알림을 P1에 포함한다.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 파트 변경을 독립적으로 검증하고 demo 환경에 자동 배포한다 (Priority: P0)

파트 개발자는 `feature/*` MR에 변경을 올리면 GitLab CI의 필수 build·test 결과를 확인하고, 통과한 변경이 `develop`에 병합된 뒤 Jenkins의 해당 컴포넌트 demo 통합 환경(`demo.ssafesta.world`) 자동 배포 결과를 확인한다.

**Why this priority**: 네 파트가 서로의 배포를 기다리거나 다른 컴포넌트를 재시작하면 병렬 개발이 불가능해지므로, 프로젝트의 기본 개발 경로다.

**Independent Test**: 네 파트 중 하나의 브랜치에 검증 가능한 변경을 올리고, 해당 파트만 검증·배포되며 나머지 세 파트의 실행 상태가 유지되는지 확인한다.

**Acceptance Scenarios**:

1. **Given** `feature/*` MR에 유효한 Front·Back 또는 Game 변경이 제출되었을 때, **When** 해당 build·test gate가 통과하고 `develop`에 병합되면, **Then** Jenkins는 변경된 컴포넌트만 `demo.ssafesta.world`에 새 버전으로 갱신하고 다른 컴포넌트를 재시작하지 않는다.
2. **Given** `feature/*` MR의 필수 GitLab CI build 또는 test가 실패했을 때, **When** 파이프라인이 종료되면, **Then** 해당 변경은 `develop` 병합과 dev 배포가 차단되고 실패 단계와 원인을 개발자가 확인할 수 있다.
3. **Given** 같은 `feature/*` MR 브랜치에 새 변경이 연속으로 제출되었을 때, **When** 이전 실행보다 최신 실행이 먼저 배포 가능한 상태가 되면, **Then** 오래된 실행이 최신 demo 환경을 덮어쓰지 않는다.
4. **Given** Unity 담당자가 QA를 끝낸 최종 WebGL zip을 immutable release ID로 GitLab Generic Package Registry에 업로드했을 때, **When** 업로드 도우미가 산출물 SHA-256과 함께 Jenkins job을 호출하면, **Then** Jenkins는 패키지를 검증해 EC2 정적 release로 원자적으로 승격하고 Dedicated Server를 재시작하지 않는다.
5. **Given** `festa-unity/**` 또는 Game CI 경로를 바꾼 MR이 제출되었을 때, **When** Jenkins Unity agent가 해당 MR head SHA에서 Unity 스크립트 컴파일과 `ci/test` EditMode를 실행하면, **Then** 성공 결과만 GitLab MR의 필수 상태로 게시되고 어떤 이미지 빌드·패키지 업로드·dev/demo 배포·컨테이너 재시작도 발생하지 않는다.

---

### User Story 2 - demo에서 검증된 release를 production(main) 환경에 수동 승격한다 (Priority: P0)

릴리스 담당자는 `demo.ssafesta.world`에서 팀 검증이 완료된 `develop` release의 소스 commit SHA와 동일 아티팩트를 명시적으로 승인하여 `main` 브랜치 및 프로덕션 환경(`ssafesta.world`)으로 수동 승격(Production Promotion)한다.

**Why this priority**: 프로덕션(`ssafesta.world`)은 실제 사용자와 평가위원이 접근하는 최종 운영 환경이므로 개발 중인 `develop` 변경이 자동으로 배포되면 안 되며, demo에서 충분히 검증된 동일 아티팩트만 승인을 거쳐 무중단·불변으로 승격되어야 한다.

**Independent Test**: demo 검증을 마친 release를 선택해 `main` 승격 MR을 생성하고, Squash 없이 ancestry를 보존한 채 병합된 뒤 동일 아티팩트가 프로덕션으로 승격되어 웹 접속, 로그인, 월드 입장, AI 응답의 핵심 사용자 여정이 정상 동작하는지 확인한다.

**Acceptance Scenarios**:

1. **Given** `demo.ssafesta.world`에서 팀 검증을 통과한 `develop` release가 있을 때, **When** 담당자가 Production Promotion을 수행하면, **Then** 검증된 정확한 develop SHA가 Squash 없이(`squash=false`) `main` 브랜치에 계승되고, demo에서 검증된 동일 아티팩트가 재빌드 없이 프로덕션(`ssafesta.world`)에 배포된다.
2. **Given** 프로덕션 배포가 완료되었을 때, **When** 핵심 사용자 여정 검증(웹, 로그인, 월드, AI)이 실행되면, **Then** 모든 검증이 통과해야 성공 상태와 추적 가능한 버전 정보(release ID, source SHA)로 기록된다.
3. **Given** 프로덕션 배포 또는 검증 중 실패가 발생했을 때, **When** 복구가 실행되면, **Then** 마지막 정상 프로덕션 릴리스로 즉시 복구할 수 있는 상태가 보존된다.

---

### User Story 3 - 릴리스·배포·복구 이력을 추적한다 (Priority: P1)

개발자와 운영 담당자는 각 변경으로 만들어진 산출물이 어느 대상에 배포되었는지, 실패 후 어떤 복구 판단과 조치가 이루어졌는지, 현재 실행 중인 버전이 무엇인지 확인한다.

**Why this priority**: 단계별 원본 로그 열람은 P0 파이프라인의 기본 기능이지만, 변경부터 산출물·배포·복구·현재 버전까지 연결된 이력이 없으면 장애 후 상태를 재구성하고 복구 결과를 검증하기 어렵다.

**Independent Test**: 성공 배포와 복구가 발생한 실패 배포를 하나씩 선택하여 원본 변경, 산출물, 배포 대상, 실패 지점, 복구 전후 릴리스와 현재 실행 버전을 이력만으로 재구성할 수 있는지 확인한다.

**Acceptance Scenarios**:

1. **Given** 파이프라인 실행이 완료되었을 때, **When** 담당자가 배포 이력을 조회하면, **Then** 변경 식별자·산출물·대상 파트·배포 대상·배포된 릴리스와 최종 상태를 연결해 확인할 수 있다.
2. **Given** 통합 배포 후 자동 또는 수동 복구 판단이 이루어졌을 때, **When** 담당자가 복구 이력을 조회하면, **Then** 실패 단계와 컴포넌트, 복구 판단, 복구 전후 릴리스와 복구 검증 결과를 확인할 수 있다.
3. **Given** 개발환경에서 컴포넌트가 실행 중일 때, **When** 담당자가 현재 버전을 조회하면, **Then** 실행 중인 릴리스를 원본 변경과 산출물까지 역추적할 수 있다.

---

### User Story 4 - 비밀정보를 노출하지 않고 배포한다 (Priority: P0)

보안 담당자와 개발자는 저장소에 비밀정보를 넣지 않고도 각 환경에 필요한 값을 안전하게 주입하며, 로그와 산출물에도 값이 노출되지 않음을 확인한다.

**Why this priority**: 비밀정보 커밋 또는 로그 노출은 즉시 사고로 이어지며 헌법 15조를 위반한다.

**Independent Test**: 식별 가능한 테스트 비밀값으로 파이프라인을 실행한 뒤 저장소, 로그, 캐시와 배포 산출물에서 원문이 검색되지 않는지 확인한다.

**Acceptance Scenarios**:

1. **Given** 배포에 비밀정보가 필요할 때, **When** 파이프라인이 실행되면, **Then** 승인된 외부 저장소의 값만 실행 시점에 주입된다.
2. **Given** 파이프라인이 성공하거나 실패했을 때, **When** 저장소·로그·캐시·산출물을 검사하면, **Then** 비밀값 원문이 남아 있지 않는다.

---

### User Story 5 - 운영 로그의 특이사항과 서버 사용량을 관측한다 (Priority: P1)

Infra 담당자는 수집 Agent가 모은 로그와 서버 사용량을 조회하고, 직접 승인한 탐지 규칙에 해당하는 특이사항이 발생했을 때만 Mattermost 알림을 받는다.

**Why this priority**: 배포 자동화 이후의 장애와 자원 부족을 빠르게 발견하는 데 필요하지만, P0인 빌드·테스트·배포 경로를 완성하는 것보다 후순위이며 세부 탐지 규칙은 운영 경험을 바탕으로 확장할 수 있다.

**Independent Test**: 테스트 로그와 서버 지표를 수집한 뒤 승인된 탐지 규칙에 맞는 이벤트만 Mattermost에 전달되고, 일반 CI 상태와 규칙에 맞지 않는 로그는 알림을 만들지 않으며 서버 사용량을 조회할 수 있는지 확인한다.

**Acceptance Scenarios**:

1. **Given** Infra 담당자가 탐지 규칙을 활성화했을 때, **When** 수집 로그가 해당 조건을 충족하면, **Then** 규칙과 발생 대상을 식별할 수 있는 Mattermost 알림이 생성된다.
2. **Given** 일반 CI 성공·실패 또는 규칙에 맞지 않는 로그가 발생했을 때, **When** 수집과 평가가 이루어지면, **Then** 별도 탐지 규칙이 없는 한 Mattermost 알림이 생성되지 않는다.
3. **Given** 서버와 파트별 컨테이너가 실행 중일 때, **When** Infra 담당자가 운영 대시보드를 조회하면, **Then** 대상별 CPU·메모리·디스크·네트워크 사용 상태를 구분해 확인할 수 있다.
4. **Given** 로그 수집·저장·알림 구성요소가 실패했을 때, **When** 애플리케이션과 CI/CD가 실행되면, **Then** 해당 장애 때문에 빌드·배포·서비스가 중단되지 않는다.

### Edge Cases

- 같은 `feature/*` MR 브랜치에 여러 변경이 빠르게 제출되어 실행 순서가 뒤바뀌는 경우
- 한 파트의 검증은 성공했지만 배포 대상이 응답하지 않는 경우
- 단일 EC2에서 한 파트의 빌드 또는 실행 부하가 다른 파트와 Jenkins의 가용성을 저해하는 경우
- demo 통합 배포 중 일부 컴포넌트만 갱신되고 나머지가 실패하는 경우
- 통합 헬스체크에서 웹 접속은 성공하지만 로그인·월드 입장·AI 응답 중 일부만 실패하는 경우
- 캐시가 손상되었거나 변경 내용과 호환되지 않아 재사용할 수 없는 경우
- 배포 중 비밀값이 오류 메시지나 명령 출력에 포함될 가능성이 있는 경우
- 이전 실행의 오래된 산출물이 최신 릴리스를 덮어쓰려는 경우
- 외부 서비스 장애로 AI 응답 검증만 실패한 경우에도 월드와 비AI 기능의 상태를 별도로 판별해야 하는 경우
- 같은 특이사항이 짧은 시간에 반복되어 Mattermost 알림이 폭주하는 경우
- 잘못된 탐지 규칙이 정상 로그를 이상으로 판정하거나 실제 이상 로그를 놓치는 경우
- 수집 Agent·로그 저장소·대시보드 또는 Mattermost가 일시적으로 응답하지 않는 경우
- 수집 로그나 알림 본문에 Secret·토큰·개인정보가 포함될 가능성이 있는 경우
- Unity agent가 offline·대기열 초과·컴파일 또는 EditMode 실패한 경우에는 Game MR gate를 성공 또는 skip으로 표시하지 않고 merge를 차단하는 실패 상태로 남겨야 하는 경우

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: GitLab CI/Runner는 `develop` 대상 MR의 소스 브랜치 명명 제한 없이 `festa-frontend/**`, `backend/**`, 공용 계약·CI 경로를 `rules:changes`의 `compare_to: 'refs/heads/develop'` (Net Diff)로 매핑해 실제로 변경된 컴포넌트 build·test job만 선별 실행해야 한다 (Phase 1, Phase 2). Front·Back 공용 경로는 두 gate를 모두 실행한다. `jira-*` job 정의는 보존하되 실행하지 않는다.
- **FR-002**: GitLab CI의 Front gate는 `ci/test front` 및 `ci/build front`, Back gate는 `ci/test back` 및 `ci/build back`을 수행해야 한다. Back gate runner는 Testcontainers를 위해 Docker executor 또는 Docker socket 접근을 제공해야 한다.
- **FR-003**: GitLab의 필수 MR gate가 하나라도 실패하면 `develop` 병합을 차단해야 한다. Jenkins의 `develop` 검증이 실패하면 dev 배포를 차단해야 한다.
- **FR-003a**: GitLab CI/Runner의 초기 MR merge gate는 Front·Back을 `rules:changes`로 수행한다. Game MR gate는 Jenkins Unity agent가 수행하며, 병합 후 Jenkins `develop` CI·dev 배포 범위에는 네 컴포넌트를 모두 유지한다.
- **FR-003b**: `festa-unity/**`, Game CI adapter 또는 Unity project 설정 변경 MR은 Jenkins Unity agent에서 해당 MR head SHA의 `ci/test` EditMode를 실행해야 한다. `ci/test`가 수행하는 Unity 스크립트 컴파일과 모든 EditMode 정적 검사는 이 gate의 일부다. 실행은 GitLab MR의 필수 성공 상태를 게시해야 하며, 실패·timeout·agent 미가용은 merge를 차단해야 한다.
- **FR-003c**: MR용 Unity 검증 경로는 `ci/test`만 실행하고 이미지 build·package·Registry 업로드·Jenkins dev deploy·demo promotion·운영 컨테이너 재시작을 호출해서는 안 된다. merge 후 `develop`의 기존 build/package/deploy 흐름은 별도로 유지한다.
- **FR-004**: `develop`에 병합된 변경의 성공한 배포는 변경된 컴포넌트의 `demo.ssafesta.world` 환경만 갱신해야 하며 다른 컴포넌트를 재시작하거나 교체해서는 안 된다.
- **FR-005**: 시스템은 오래된 실행이 더 최신 변경의 배포 결과를 덮어쓰지 못하도록 실행 순서와 배포 권한을 통제해야 한다. 하나의 MR이 여러 컴포넌트를 변경하면 모든 변경 컴포넌트의 CI가 성공한 뒤에만 dev 배포를 시작해야 하며, 배포 또는 검증이 하나라도 실패하면 같은 MR에서 이미 갱신된 컴포넌트를 이전 정상 release로 자동 복구해야 한다.
- **FR-006**: 모든 실행은 변경 식별자, 대상 파트, 단계별 결과, 산출물 식별자, 배포 대상과 최종 상태를 추적 가능하게 기록해야 한다.
- **FR-007**: `demo.ssafesta.world`에서 팀 통합 검증을 통과한 `develop` release만 담당자 승인을 거쳐 `main` 브랜치 및 프로덕션(`ssafesta.world`) 환경으로 수동 승격(Production Promotion)해야 하며, demo에서 검증한 동일 아티팩트를 재빌드 없이 프로덕션에 재사용해야 한다.
- **FR-007a**: **Architecture Invariant: `develop → main` 승격 MR은 절대 Squash Merge를 적용해서는 안 되며 (`squash=false` 강제)**, develop의 커밋 계보(ancestry)를 main에 온전히 보존하여 후속 승격 시 전체 커밋이 다시 diff로 개입하는 문제를 원천 차단해야 한다.
- **FR-008**: demo 자동 배포 및 production 승격 배포는 웹 접속 → 로그인 → 월드 입장 → AI 응답의 핵심 사용자 여정 검증을 지원해야 한다.
- **FR-009**: 배포는 모든 필수 배포 단계와 핵심 사용자 여정이 통과한 경우에만 성공으로 기록해야 한다.
- **FR-010**: 통합 배포 실패 시 시스템은 실패 릴리스의 승격을 막고 마지막 정상 릴리스를 식별·복구할 수 있는 상태를 보존해야 한다.
- **FR-011**: 서버 컴포넌트의 배포 산출물은 헌법 7조에 따라 재현 가능한 컨테이너 이미지여야 한다.
- **FR-012**: Unity 자동 빌드는 유효한 캐시를 재사용하고, 캐시가 유효하지 않을 때 안전하게 폐기한 뒤 재생성할 수 있어야 한다.
- **FR-013**: 실제 비밀정보는 저장소에 저장하지 않고 승인된 비밀정보 저장소에서 실행 시점에만 주입해야 한다.
- **FR-014**: 파이프라인은 비밀정보가 로그, 캐시, 테스트 보고서와 배포 산출물에 원문으로 노출되지 않도록 차단해야 한다.
- **FR-015**: 파이프라인 실행·배포·복구 권한은 승인된 사용자 또는 서비스 계정으로 제한되어야 한다.
- **FR-016**: 실패 결과는 실패한 단계와 대상 컴포넌트를 구분하여 담당자가 추가 실행 없이 원인 범위를 좁힐 수 있게 해야 한다.
- **FR-017**: 기준선으로 동결된 코드와 Infra 합의 대상인 `festa-unity/Docker/`는 이 기능 구현을 이유로 임의 변경해서는 안 된다.
- **FR-018**: Unity 자동 빌드는 Unity Personal 라이선스가 Unity Hub로 1회 활성화된 영속 Unity Agent에서만 수행해야 하며, Unity 계정 인증정보를 Jenkins 자격 증명이나 파이프라인 변수로 저장해서는 안 된다. 라이선스는 Agent 폐기·교체 시 관리자가 반납해야 한다.
- **FR-019**: 초기 단일 EC2에서는 `ai`/`back`/`front`/`game` 개발환경을 파트별 컨테이너·네트워크·배포 단위로 논리적으로 분리하고, 한 파트의 배포가 다른 파트 컴포넌트를 재시작하지 않게 해야 한다.
- **FR-020**: 파트별 배포 정의는 EC2 자원이 추가될 경우 다른 파트의 파이프라인을 변경하지 않고 해당 파트만 별도 인스턴스로 이전할 수 있어야 한다.
- **FR-021**: 새 컨테이너 시작 또는 비AI 핵심 헬스체크가 실패하고 되돌릴 수 없는 데이터 변경이 없을 때, 시스템은 마지막 정상 릴리스로 자동 복구하고 복구 후 헬스체크를 다시 수행해야 한다.
- **FR-022**: DB 스키마 변경, Secret·환경 설정 오류 또는 되돌릴 수 없는 데이터 변경이 관련된 실패는 자동 복구하지 않고 현재 상태와 로그를 보존한 뒤 승인된 담당자의 수동 판단을 기다려야 한다.
- **FR-023**: 외부 AI 서비스 장애로 AI 응답 검증만 실패하면 통합 릴리스를 성공으로 기록하지 않되 정상인 월드와 비AI 컴포넌트를 자동 복구해서는 안 되며, AI 검증 재시도 또는 수동 승인을 기다려야 한다.
- **FR-023a**: Unity 담당자의 QA 완료 후 GitLab Generic Package Registry에 성공한 최종 WebGL package 업로드를 해당 정적 산출물의 배포 승인 신호로 사용해야 한다.
- **FR-023b**: 업로드 도우미는 package 업로드와 SHA-256 checksum 업로드가 모두 성공한 뒤에만 immutable release ID와 SHA-256을 Jenkins parameterized job에 전달해야 한다.
- **FR-023c**: Jenkins는 개인 PAT가 아니라 `read_package_registry` 최소 권한 GitLab Deploy Token을 Jenkins Credentials에서 실행 시점에만 주입해 package를 내려받아야 한다.
- **FR-023d**: Jenkins는 전달받은 SHA-256, ZIP 무결성, 절대·상위·드라이브 경로와 심볼릭 링크가 없는 안전한 entry, `index.html`·`manifest.json`·`Build/`·`TemplateData/`, manifest 4개 참조 파일을 승격 전에 검증해야 한다.
- **FR-023e**: WebGL 산출물은 `/srv/festa/webgl/releases/<release-id>/`에 불변으로 설치하고, 동일 release ID의 동일 SHA 재호출은 멱등 처리하며 다른 SHA 재사용은 거부해야 한다.
- **FR-023f**: Jenkins는 같은 파일시스템의 임시 심볼릭 링크와 원자적 rename으로만 `/srv/festa/webgl/current`를 전환하고 경로 내용을 직접 덮어쓰지 않아야 한다.
- **FR-023g**: 전환 후 공개 URL의 index·manifest·manifest 참조 파일에 대해 HTTP 상태, MIME, Brotli와 Cache-Control을 검증하고 실패 시 직전 current를 복원해야 한다.
- **FR-023h**: 성공·known-good 기록이 끝난 WebGL 배포에서만 `current`와 `previous`를 보존하고, `/srv/festa/webgl/current.legacy.<UTC 14자리 timestamp>` 중 최신 두 개만 남겨야 한다. 검증·출처 검사 실패 시 legacy 정리를 실행하지 않으며, WebGL 정적 배포는 Unity Dedicated Server 컨테이너를 재시작하지 않아야 한다.
- **FR-023i**: 배포 이력은 release ID, artifact SHA-256, Registry package URL, 공개 대상 URL, 결과와 시각을 기록하되 인증정보 원문을 포함하지 않아야 한다.
- **FR-023j**: Unity 담당자 PC는 Jenkins Agent로 상시 연결할 필요가 없으며 업로드 도우미 실행 시 GitLab과 Jenkins HTTPS API에 접근할 수 있으면 된다.

#### P1 — 로그 기반 이상 탐지·운영 관측

- **FR-024**: 시스템은 수집 Agent를 통해 Jenkins와 파트별 실행 환경의 로그 및 서버·컨테이너 사용량 지표를 수집해야 한다.
- **FR-025**: Infra 담당자는 탐지 규칙을 생성·변경·활성화·비활성화할 수 있어야 하며, 각 규칙은 대상 로그 또는 지표, 조건, 심각도와 알림 여부를 식별 가능하게 가져야 한다.
- **FR-026**: Mattermost 알림은 활성화된 탐지 규칙이 충족된 경우에만 생성해야 하며, 별도 규칙이 없는 일반 CI 성공·실패 상태를 자동 전송해서는 안 된다.
- **FR-027**: 동일 원인에서 반복되는 이벤트는 그룹화하거나 재알림을 억제할 수 있어야 한다.
- **FR-028**: 탐지 알림은 규칙 식별자, 심각도, 발생 대상·시각과 확인에 필요한 로그 문맥을 포함하되 Secret·토큰·개인정보 원문을 포함해서는 안 된다.
- **FR-029**: Infra 담당자는 Grafana 기반 대시보드에서 서버와 파트별 컨테이너의 CPU·메모리·디스크·네트워크 사용 상태를 구분해 조회할 수 있어야 한다.
- **FR-030**: 수집 Agent, 로그·지표 저장, 대시보드 또는 Mattermost 알림의 장애는 애플리케이션 서비스와 CI/CD 실행을 중단시키거나 성공 판정을 변경해서는 안 된다.
- **FR-031**: Mattermost Webhook 등 관측·알림용 인증정보는 승인된 Secret 저장소에서 주입하고 저장소·수집 로그·알림 본문에 노출하지 않아야 한다.

### Key Entities

- **Pipeline Definition**: 어떤 브랜치 변경이 어떤 검증·배포 흐름을 거치는지 정의하는 버전 관리 대상.
- **Pipeline Run**: 하나의 변경으로 시작된 실행. 변경 식별자, 단계별 결과, 시작·종료 시각과 최종 상태를 가진다.
- **Build Artifact**: 검증을 통과한 배포 후보. 원본 변경과 무결하게 연결되어야 한다.
- **Deployment Target**: 컴포넌트별 dev 환경 또는 승인된 release의 demo 통합환경처럼 릴리스가 적용되는 논리적 대상.
- **Release**: 함께 배포·검증되는 산출물들의 식별 가능한 묶음.
- **Health Check Result**: 핵심 사용자 여정의 단계별 성공·실패와 실패 지점을 나타내는 결과.
- **Secret Reference**: 실제 값을 포함하지 않고 승인된 비밀정보 저장소의 항목을 가리키는 참조.
- **Detection Rule**: Infra 담당자가 승인한 이상 조건. 평가 대상, 조건, 심각도, 활성 상태와 알림 여부를 가진다.
- **Operational Signal**: 수집 Agent가 전달한 로그 또는 서버·컨테이너 사용량 지표.
- **Alert Event**: 활성 탐지 규칙이 충족되어 생성된 사건. 원인 규칙과 발생 대상을 추적할 수 있어야 한다.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: `feature/* → develop` MR의 Front·Back 변경 100%가 수동 요청 없이 해당 GitLab CI gate를 시작하고, Game 변경 100%가 Jenkins Unity test-only gate를 시작한다. Front·Back 공용 경로 변경 100%는 두 gate를 모두 시작한다.
- **SC-001a**: Unity 컴파일·EditMode를 의도적으로 실패시킨 Game MR 표본의 100%는 GitLab 필수 상태가 실패하고, 이미지·package·dev/demo 컨테이너 변경 건수는 0건이다.
- **SC-002**: 빌드 또는 필수 테스트가 실패한 실행의 개발환경 배포 건수는 0건이다.
- **SC-003**: `develop` 배포로 인해 변경 대상이 아닌 다른 컴포넌트가 재시작되는 건수와 공용 파이프라인 파일 변경으로 dev 컴포넌트가 재시작되는 건수는 각각 0건이다.
- **SC-004**: 성공으로 기록된 demo 통합 릴리스의 100%가 웹 접속·로그인·월드 입장·AI 응답 검증 기록을 모두 가진다.
- **SC-005**: 성공·실패 실행 표본의 100%에서 변경부터 산출물, 배포 대상, 최종 상태까지 이력을 재구성할 수 있다.
- **SC-006**: 분기별 복구 리허설의 100%에서 마지막 정상 릴리스를 식별하고 문서화된 절차로 복구할 수 있다.
- **SC-007**: 저장소·로그·캐시·산출물에 대한 비밀값 원문 검사 결과가 0건이다.
- **SC-008**: 외부 서비스 장애를 제외한 파이프라인 실행의 95% 이상이 사람의 중간 개입 없이 최종 성공 또는 명확한 실패 상태로 종료된다.
- **SC-009**: 동일 조건의 Unity 빌드 표본에서 유효한 캐시 사용 시 중앙 빌드 시간이 콜드 빌드 중앙값보다 짧음이 확인된다.
- **SC-010**: 자동 복구 조건에 해당하는 배포 실패 표본의 100%에서 마지막 정상 릴리스가 복원되고 복구 후 비AI 핵심 헬스체크가 통과한다.
- **SC-011**: 수동 판단 조건에 해당하는 실패 표본의 100%에서 자동 복구가 실행되지 않고 실패 상태·로그·대상 릴리스가 보존된다.
- **SC-011a**: WebGL package 배포 fixture의 100%에서 정상·중복 trigger는 같은 SHA의 release로 수렴하고, bad SHA·unsafe ZIP·bad manifest·공개 검증 실패는 새 current를 남기지 않는다.
- **SC-011a**: QA 완료 WebGL package 배포 표본의 100%에서 전달 SHA-256과 설치 SHA-256이 일치하고, 공개 검증 성공 시에만 새 current가 유지되며 실패 표본은 직전 current로 복원된다.
- **SC-012**: Mattermost로 전송된 운영 알림의 100%가 알림 시점에 활성화된 탐지 규칙과 발생 대상으로 추적된다.
- **SC-013**: 일반 CI 상태와 탐지 규칙에 맞지 않는 테스트 로그를 사용한 검증에서 Mattermost 운영 알림 발생 건수는 0건이다.
- **SC-014**: 정의된 서버와 파트별 컨테이너 대상의 100%에서 CPU·메모리·디스크·네트워크 사용 상태를 대시보드로 구분해 확인할 수 있다.
- **SC-015**: 수집·저장·대시보드·알림 구성요소 장애 시험에서 애플리케이션 및 CI/CD 중단 건수는 0건이다.

## Assumptions

- 개발 흐름은 소스 브랜치 이름(`feature/*`, `fix/*`, `chore/*` 등) 제한 없이 `develop`으로 Merge Request를 만들고 Squash Merge한다. 대규모 테스트 슈트(예: 백엔드 Testcontainers) 또는 다수의 자잘한 커밋이 발생하는 파트의 경우 #206 배칭 규약에 따라 파트 브랜치(`back`, `front` 등)에서 작업을 취합한 뒤 `develop`으로 올리는 배치 MR을 병행 허용하며, Net Diff 판정(`compare_to: refs/heads/develop`)을 통해 타 파트 CI 오폭 없이 변경된 파트만 격리 실행한다.
  - GitLab CI/Runner는 merge 전 Front·Back build·test와 GitLab merge 차단을 담당한다. Jenkins Unity agent는 merge 전 Game MR의 test-only 컴파일·EditMode 상태를 게시하며, Jenkins의 merge 후 경로는 네 컴포넌트 selected CI, dev batch 배포·rollback, 수동 demo promotion을 담당한다.
  - **Session 2026-09-20 (Batch 1) — CI/CD 경계와 배포 결정 계약.** ① `develop → main` promotion MR의 authoritative pre-merge gate는 GitLab 네이티브 `mr-status` pipeline이다. Jenkins가 develop SHA에 게시하는 `jenkinsci/branch` external status는 develop 배포 결과 정보이며 merge를 결정하지 않는다. GitLab은 MR pipeline이 있으면 그것을 `head_pipeline`으로 잡고 없을 때만 external을 잡으므로(실측 !1220/!1229/!1232), merge 직전 `infra/deploy/scripts/check-promotion-mr-gate.sh <iid>`로 `head_pipeline.source == merge_request_event`를 확인하고 아니면 merge하지 않는다. `only_allow_merge_if_pipeline_succeeds`는 유지한다. ② `detect-changed-components.sh`는 네 축을 낸다 — `validationComponents`(shared 변경이면 네 컴포넌트), `buildComponents`(변경된 app + runtime-shared면 app 3종 + `gameBuildRequired`면 game), `deployComponents`(변경된 app + runtime-shared면 app 3종 + game 소스 변경이면 game), `sharedCiChanged`. `ci_only_shared`(Jenkinsfile·.gitlab-ci.yml·ci/·infra/jenkins/·infra/deploy/scripts/ 등)는 검증만 넓히고 빌드·배포·Unity를 늘리지 않는다. `runtime_shared`(demo base compose·demo manifest·deploy/verify-environment·preflight·versions.env·.dockerignore·package-local-image)는 app 3종을 재빌드·재배포한다. 검증 전용 컴포넌트는 app `ci/validate`+`ci/test`, game `ci/validate`(Unity 없음)만 실행한다. ③ Demo KNOWN_GOOD은 컴포넌트별 exact identity의 조합이며 `environment.json.batchId`가 유일한 canonical ID다. 컴포넌트 `releaseId`는 서로 달라도 되고 미변경 artifact는 재발급하지 않는다. Production receipt `demoReleaseId == batchId`. ④ `festa-production-promotion`은 `agent none`이며 human gate stage는 executor를 잡지 않는다. 실행 stage는 각자 `deploy` agent와 checkout을 확보하고, 상태는 `env.*`와 `/var/lib/festa-environments`로만 전달한다(같은 node/workspace 재사용을 가정하지 않는다). ⑤ World hostname: Dev `world-dev.<root>`→7777, Demo `world-demo.<root>`→17777, Production `world.<root>`→27777. readiness는 명시된 `WORLD_PUBLIC_HOST`만 검사하며 환경 host를 추론하지 않는다.
- `develop` 병합은 변경 컴포넌트만 dev에 자동 배포한다. demo 통합 환경은 dev 검증 후 Jenkins에서 승인한 release만 배포한다.
- 각 파트는 파이프라인이 호출할 수 있는 빌드 명령과 필수 테스트 범위를 소유하고 유지한다.
- CI/CD 서비스는 Jenkins를 사용한다. 소스 저장소는 초기 GitHub에서 추후 GitLab으로 이전하되 Jenkins 파이프라인은 유지하고 연동 Webhook만 전환한다.
- 초기에는 제공받은 단일 EC2 안에서 Jenkins Controller와 빌드 Agent를 논리적으로 분리하며, Controller에서 빌드를 직접 실행하지 않는다. 자원이 추가되면 Agent를 별도 EC2로 이전할 수 있어야 한다.
- Unity 자동 빌드는 영속 Unity Agent에서 Unity Personal 라이선스를 Unity Hub로 1회 활성화해 수행한다. Unity 계정 인증정보는 Jenkins에 저장하지 않으며, Agent 폐기·교체 시 관리자가 라이선스를 반납한다.
- 초기 제공 서버는 EC2 한 대이며 Jenkins와 파트별 개발환경이 함께 사용한다. 파트별 실행 환경은 컨테이너·네트워크·배포 단위로 논리적으로 격리하고, 추후 자원 추가 시 개별 이전할 수 있게 구성한다.
- 통합 배포 복구는 실패 유형에 따라 조건부 자동화한다. 되돌릴 수 있는 컨테이너 시작·비AI 핵심 헬스체크 실패는 자동 복구하고, DB·Secret·환경 설정·되돌릴 수 없는 데이터 변경 및 AI 외부 장애는 상태를 보존한 뒤 수동 판단한다.
- AWS 서버 자원은 제공되며, 환경·도메인·인증서의 상세 구성은 `infra-002-environments`에서 다룬다.
- Unity Dedicated Server의 외부 `wss://` 노출과 로드밸런서 실측은 `infra-003-unity-server-deploy`에서 다룬다.
- AI 장애는 월드 접속과 비AI 기능의 가용성을 중단시키지 않아야 하지만, demo 통합 릴리스의 전체 성공 판정에는 AI 응답 검증 결과를 포함한다.
- 로그 기반 이상 탐지와 서버 사용량 관측은 P1이다. 구체적인 탐지 패턴·임계치·지속시간·재알림 간격, 로그·지표 보존 기간과 대시보드 패널 구성은 운영 경험과 EC2 사양을 확인한 뒤 후속 설계에서 정한다.

## Clarifications

아래 항목은 요구 결과가 아니라 구현 선택이며, Infra 담당자가 `$speckit-clarify` 또는 계획 단계 전에 확정한다. 미확정 상태로 구현을 시작하지 않는다.

### Session 2026-09-17

- Q: `dev.ssafesta.world` 와 `demo.ssafesta.world` 의 환경 역할 분리와 실제 통합 검증 대상을 어떻게 정합화할 것인가? → A: 별도의 `dev.ssafesta.world` 환경은 운영하지 않으며, `demo.ssafesta.world` 가 `develop` 브랜치의 최신 통합 결과를 지속적으로 검증하는 통합 환경(Staging) 역할을 전담한다. `develop` 에 머지된 변경은 Jenkins develop 파이프라인을 통해 컴포넌트별 필수 검증 후 `demo.ssafesta.world` 에 자동 배포된다.
- Q: Demo 릴리스 상태 모델(3단계)과 롤백 기준은 어떻게 정의하는가? → A: Demo 릴리스 상태를 candidate(배포 및 자동 검증 진행 중) → current(자동 readiness 성공, 실제 demo 서비스 중) → known-good(사람의 실제 서비스 검증 통과) 3단계로 엄격히 분리한다. Jenkins CI/readiness 성공만으로 known-good 을 자동 갱신해서는 안 되며(current ≠ known-good), 자동 readiness 실패는 candidate 자동 롤백, 이후 실제 서비스 장애 발견 시 수동 롤백은 미검증 current 가 아닌 마지막 정상 확인된 known-good 스냅샷을 재빌드 없이 복원한다.
- Q: 수동 승격(Production Promotion)의 개념과 최종 운영 환경 대상은 무엇인가? → A: 수동 승격은 가상의 dev→demo가 아니라 **`develop → main` 승격 정책**으로 전환하며, `main` 브랜치는 `https://ssafesta.world` 에서 서비스되는 실제 사용자 프로덕션(Production) 운영 환경을 나타낸다. 팀이 `demo.ssafesta.world` 에서 특정 release 를 실제 사용자 검증까지 완료하여 known-good 으로 승인한 릴리스만 수동 Production Promotion 대상이 된다 (미검증 current 승격 금지).
- Q: `develop → main` 승격 시 소스 반영 및 아티팩트 승격 원칙은 무엇인가? → A: 
  1. **Source Promotion & Architecture Invariant**: demo 에서 검증 완료된 정확한 develop commit SHA 를 main 에 반영하며, **develop → main 승격 MR 은 절대 Squash Merge 를 금지한다 (`squash=false` 강제).** develop 의 커밋 계보(ancestry)를 그대로 보존하여 차기 승격 시 전체 커밋이 다시 diff 로 잡히는 문제를 원천 차단한다.
  2. **Artifact Promotion**: Production 에서는 demo 에서 검증한 동일 artifact 를 그대로 승격하여 재사용하며(`Demo Artifact == Production Artifact`), main 반영 시 새로운 빌드 아티팩트를 재생성하지 않는다.
- Q: Game Dedicated Server 및 WebGL 의 배포 흐름과 정합성은 어떻게 규정하는가? → A: Game Dedicated Server 는 Jenkins develop 파이프라인의 demo 자동 배포 흐름에 포함하되, 스모크 러너 의존성은 제거하고 1~3단계(`processRunning`, `internalListener`, `externalWebSocket`) 통과로 known-good 승격한다. WebGL 클라이언트 배포는 독립적인 릴리스 패키지/수동 배포 경로를 유지하며, `deploy-game.sh` 의 프리팹 트리 대조 가드로 클라이언트-서버 정합성을 보장한다.
- Q: Redis 데이터 영속성 및 메모리 상한 기준을 어떻게 확정할 것인가? → A: 실측 결과 40명 동시 접속 시 Redis 메모리 피크가 1.27MiB 로 확인되었으므로, 컨테이너 OOM 방지를 위해 `mem_limit: 256m` 상한을 compose 에 강제한다. 또한 컨테이너 재시작 시 로그인 세션 키 일괄 소멸을 방지하기 위해 `appendonly yes` AOF 영속성을 적용한다.

### Session 2026-08-18

- Q: CI 서비스와 작업 실행용 컴퓨터를 어떤 구성으로 운영할까요? → A: Jenkins를 사용하고, 초기에는 단일 EC2 안에서 Controller와 Agent를 분리하며 추후 Agent를 별도 EC2로 이전할 수 있게 구성한다. 소스 저장소는 GitHub에서 시작해 GitLab으로 이전하며 Jenkins 연동 Webhook을 전환한다.
- Q: Jenkins의 Unity 자동 빌드에 사용할 라이선스 유형과 EC2 활성화 방식을 무엇으로 확정할까요? → A: Unity Personal 라이선스를 영속 Unity Agent에서 관리자가 Unity Hub로 1회 활성화하고, Agent 폐기·교체 시에만 반납한다. Unity 계정 인증정보는 Jenkins에 저장하지 않는다.
- Q: 파트별 개발환경을 제공받은 EC2 한 대에서 컨테이너로 분리할까요, 아니면 파트마다 별도 EC2로 분리할까요? → A: 제공 서버가 한 대이므로 단일 EC2 안에서 파트별 컨테이너·네트워크·배포 단위를 논리적으로 분리하고, 추후 개별 이전할 수 있게 구성한다.
- Q: demo 통합 배포가 실패했을 때 어디까지 자동으로 이전 정상 버전으로 복구하고, 어떤 경우에 사람의 승인을 기다릴까요? → A: 컨테이너 시작·비AI 핵심 헬스체크의 되돌릴 수 있는 실패는 자동 복구한다. DB·Secret·환경 설정·되돌릴 수 없는 데이터 변경 및 AI만의 외부 장애는 상태를 보존하고 수동 판단한다.

### Session 2026-09-08

- Q: `feature/*` MR 생성·갱신 시, 변경된 컴포넌트만 CI를 실행할까? → A: GitLab CI가 초기 Front·Back merge gate를 `rules:changes`로 실행하고, `develop` 병합 후 Jenkins가 같은 변경 컴포넌트를 dev에 배포한다.
- Q: GitLab CI와 Jenkins가 같은 MR build·test를 중복 실행할까? → A: 하지 않는다. GitLab CI는 merge 전 gate, Jenkins는 merge 후 selected CI·dev 배포와 demo promotion만 담당한다.
- Q: `infra/**`, `Jenkinsfile`, 공용 빌드 파일이 바뀌면 어떻게 처리할까? → A: 모든 컴포넌트 CI만 실행하고 dev 자동 배포는 하지 않는다.
- Q: 특정 컴포넌트의 배포 설정만 바뀌면, 그 컴포넌트 변경으로 보고 dev에 자동 배포할까? → A: 해당 컴포넌트만 CI를 실행하고 `develop` 병합 후 dev에 자동 배포한다.
- Q: 하나의 MR이 여러 컴포넌트를 바꾸면, 변경된 모든 컴포넌트 CI가 통과한 뒤에만 dev 배포할까? → A: 변경 컴포넌트 전체 CI가 성공한 뒤 해당 컴포넌트들을 dev에 배포한다.
- Q: 여러 컴포넌트 dev 배포 중 하나가 실패하면, 이미 배포된 같은 MR의 컴포넌트는 어떻게 할까? → A: 같은 MR에서 이미 갱신된 컴포넌트도 이전 정상 release로 자동 복구한다.
- Q: dev 검증 뒤 demo 통합 배포는 언제 실행할까? → A: dev 검증 완료 후 Jenkins에서 명시적으로 승인한 release만 demo에 통합 배포한다.

### Session 2026-09-09

- Q: Front·Back 경로가 아닌 Infra·문서 MR은 component gate가 없는데, protected `develop`의 성공 pipeline 요구를 어떻게 만족할까? → A: 모든 유효 MR은 build·test·deploy를 수행하지 않는 `mr-status` job을 하나 실행한다. Front·Back component gate는 기존 `rules:changes`로만 추가되며, Jenkins의 merge 후 선택 CI와 중복되지 않는다.

### Session 2026-09-17 (GitLab 이슈 #220 CI/CD 구조 개편)

- Q: 소스 브랜치 명명 규칙(`feature/*` 등)에 따라 파이프라인 생성이 누락되는 문제를 어떻게 해결할까? → A: `workflow.rules`의 브랜치 접두어 정규식 가드를 해제하여 소스 브랜치명과 무관하게 `develop` 대상 MR이면 정상 파이프라인을 생성한다 (Phase 1).
- Q: 장수 파트 브랜치 및 스쿼시 머지 구조에서 과거 커밋 이력으로 인해 불필요한 타 파트 CI가 딸려 도는 문제를 어떻게 방지할까? → A: `rules:changes`에 `compare_to: 'refs/heads/develop'`를 적용하여 `develop` 최신 트리와의 순수 Net Diff 기준으로만 컴포넌트 CI를 선별 실행한다 (Phase 2).
- Q: 백엔드 등 대규모 통합 테스트(1,400여 건)를 가진 파트의 러너 병목을 어떻게 완화할까? → A: `#206` 파트 브랜치 배칭 규약을 유지하여 가벼운 수정은 직통 MR(`feat/*`, `fix/*` → `develop`), 대규모/자잘한 커밋은 파트 브랜치 집약 후 배치 MR(`back` → `develop`)을 병행 허용한다. Net Diff 덕분에 배치 MR에서도 타 파트 CI 오폭 없이 안전하게 격리된다.
- Q: Unity MR 검증 시 러너 슬롯 45분 점유 병목을 어떻게 해소할까? → A: GitLab Runner가 Jenkins를 동기 폴링하지 않고, 비동기 디스패치 및 GitLab Commit Status 비동기 통보 구조로 전환한다 (Phase 3).
- Q: 데모 통합 배포 승격 주기는 어떻게 운영할까? → A: 매일 퇴근 전 1회 고정 승격(C안)을 기본선으로 하되, 사용자 시나리오 완결 및 E2E/Smoke Gate를 통과한 건에 한해 업무시간 중 예외 승격(B안)을 허용한다. 핫픽스는 즉시 승격한다.

| ID | 질문/결정 | 결정 주체 | 결정 시점 |
|---|---|---|---|
| C-01 | **확정**: Jenkins 사용. 초기 단일 EC2 내 Controller/Agent 분리, 추후 Agent 별도 EC2 이전 가능. GitHub에서 GitLab 이전 시 Jenkins Webhook 연동 전환. | Infra | 2026-08-18 |
| C-02 | **확정**: Unity Personal 라이선스를 영속 Unity Agent에서 Unity Hub로 1회 활성화. 계정 인증정보는 Jenkins에 저장하지 않고 Agent 폐기·교체 시 관리자 반납. | Infra + Unity | 2026-08-18 |
| C-03 | **확정**: 초기 단일 EC2에서 Jenkins와 파트별 개발환경을 함께 운영하되 컨테이너·네트워크·배포 단위를 논리적으로 분리. 추후 파트별 별도 EC2 이전 가능. | Infra | 2026-08-18 |
| C-04 | **확정**: 되돌릴 수 있는 컨테이너 시작·비AI 핵심 헬스체크 실패는 자동 복구. DB·Secret·환경 설정·비가역 데이터 변경 및 AI 외부 장애는 상태 보존 후 수동 판단. | Infra + 팀 | 2026-08-18 |
| C-05 | **확정**: develop MR 브랜치명 제한 해제 (Phase 1) 및 Net Diff (`compare_to: 'refs/heads/develop'`) 적용 (Phase 2). | Infra + 팀 | 2026-09-17 |
| C-06 | **확정**: #206 파트 브랜치 배칭 규약 병행 유지 (직통 MR + 파트 배치 MR 혼용). | Infra + 팀 | 2026-09-17 |
| C-07 | **확정**: Unity MR 검증 비동기화로 러너 독점 방지 (Phase 3). | Infra + Game | 2026-09-17 |
| C-08 | **확정**: 데모 승격 주기 확정 (퇴근 전 1회 C안 기본 + E2E 게이트 통과 시 B안 예외 승격 + 핫픽스 즉시). | Infra + 팀 | 2026-09-17 |

### Session 2026-09-18 (릴리스 상태 3분할 및 이력 보관 규칙)

- Q: candidate 에서 readiness 에 실패한 릴리스도 이력에 남길까? → A: 남기지 않는다. current 가 된 릴리스만 Release History 대상이다. 사람이 아직 검증하지 않았어도 current 였다면 남긴다 (규칙 1).
- Q: 롤백은 마지막 known-good 으로만 가능한가? → A: 실서비스 복구는 known-good 을 쓰고, 장애 구간 조사를 위한 과거 이력 릴리스 재배포는 별도 경로로 허용한다 (규칙 2).
- Q: Jenkins 를 팀원 6명이 어떻게 쓸까? → A: 공용 계정 공유 대신 팀원별 개별 계정을 둔다. known-good 승인·수동 롤백·프로덕션 승격의 수행자를 추적할 수 있어야 한다. 권한은 조회 전용과 배포·승격 승인 가능으로 나눈다 (규칙 3).
- Q: known-good 은 변경된 컴포넌트만 저장할까? → A: 그 시점 demo 전체 조합(ai·back·front·game)을 하나의 snapshot 으로 저장한다. back 만 바뀌었어도 known-good 은 [F10, B11, A10, G10] 형태의 조합이다 (규칙 4).
- Q: 프로덕션 승격은 어떤 릴리스에 허용할까? → A: known-good 으로 승인된 조합만 허용한다. readiness 통과로 current 가 된 것만으로는 승격할 수 없다 (규칙 5).
- Q: 롤백 후 상태는? → A: current 와 known-good 이 같은 릴리스를 가리킨다. 문제가 된 릴리스는 삭제하지 않고 이력에 남긴다 (규칙 6).
- Q: 이력에 무엇을 저장할까? → A: release_id, source_sha, 컴포넌트별 image ref, 배포 매니페스트, 배포 시각, readiness 결과, 검증 상태 (규칙 7).
- Q: 이력은 몇 개까지 보관할까? → A: 최근 10개. 단 known-good 이 가리키는 이력은 개수와 무관하게 보호한다 (규칙 8).
- Q: 야간에 연속 배포된 릴리스를 전부 사람이 확인해야 할까? → A: 아니다. 최신 통합 상태만 실측해 한 번에 known-good 으로 올린다. 문제가 있으면 known-good 으로 롤백하고 필요 시 이력의 중간 릴리스를 재배포해 구간을 좁힌다 (규칙 9).
- Q: 최종 상태 정의는? → A: candidate(검증 중) → current(readiness 통과, demo 실행 중) → known-good(사람이 실측 확인). Jenkins 성공은 current 까지만 보장하며 known-good 과 같지 않다 (규칙 10).

| ID | 질문/결정 | 결정 주체 | 결정 시점 |
|---|---|---|---|
| C-09 | **확정**: Release History 는 current 가 된 릴리스만, 최근 10개 보관하며 known-good 참조분은 보호한다. | Infra + 팀 | 2026-09-18 |
| C-10 | **확정**: known-good 은 컴포넌트 단위가 아니라 demo 전체 조합 snapshot 으로 관리한다. | Infra + 팀 | 2026-09-18 |
| C-11 | **확정**: 프로덕션 승격은 known-good 승인 조합만 허용한다. | Infra + 팀 | 2026-09-18 |
| C-12 | **확정**: 장애 구간 조사용으로 이력의 특정 과거 릴리스 재배포를 허용한다. 실서비스 복구 기준선은 known-good 으로 유지한다. | Infra + 팀 | 2026-09-18 |
| C-13 | **미완**: Jenkins 팀원 6인 개별 계정 및 조회/승격 권한 분리. | Infra | 2026-09-18 |

### Session 2026-09-18 (ssafesta.world 프로덕션 진입점 조기 오픈 — S15P21A604-928)

- Q: 정규 main 승격 파이프라인(US2) 가동 전, 외부 공유를 위해 프로덕션 루트 도메인(`https://ssafesta.world`)을 어떻게 조기 오픈할 것인가? → A: Nginx `demo.conf.template`의 `server_name`에 `${ROOT_DOMAIN}`(`ssafesta.world`)을 추가하여, 루트 도메인 요청을 현재 검증된 `demo` 웹/WebGL/AI 오리진(`:18080`, `/unity/`, `:18082`)으로 동일 서빙한다. Cloudflare DNS A 레코드(`@` → EC2 탄력적 IP, 프록시 활성화) 및 Origin Certificate(`*.ssafesta.world`, `ssafesta.world`)를 재사용하여 즉시 HTTPS 암호화 접속을 지원한다.

| ID | 질문/결정 | 결정 주체 | 결정 시점 |
|---|---|---|---|
| C-14 | **확정**: 프로덕션 승격 파이프라인 완성 전, Nginx demo vhost에 `${ROOT_DOMAIN}`을 결합하여 `https://ssafesta.world`를 조기 개방한다 (S15P21A604-928). | Infra + 팀 | 2026-09-18 |

### Session 2026-09-20 (Production Promotion final correction)

- C-14의 조기 alias는 종료한다. Demo는 `demo.ssafesta.world`, Production은 `ssafesta.world`만 소유한다.
- Production은 승인된 Demo App/WebGL/World artifact를 재빌드·repack하지 않고 canonical `festa-production` runtime에서 그대로 사용한다.
- Redis `prod_ai`는 `prod:ai:*`와 실제 ConversationRepository namespace인 `conversation:*`만 허용한다. `prod_back`은 `prod:*`만 유지하며 `conversation:*`을 거부한다.
- public 전환 전 maintenance fence를 적용하고 broken legacy `festa-prod-*`를 제거한 뒤 동일 host port `28080/28081/28082/27777`에 canonical runtime을 배치한다.
- CURRENT는 public activation, KNOWN-GOOD는 external 검증과 사람 승인 이후의 별도 상태다.
- 최초 canonical migration에는 `previous`가 없을 수 있다. 실패하면 maintenance를 유지하며 legacy를 복원하지 않는다. 첫 canonical KNOWN-GOOD 이후부터 직전 canonical CURRENT만 exact-artifact rollback 대상으로 사용한다.
- Production OAuth callback route는 Back `28081`, WebGL은 `/srv/festa/webgl/prod/current`, World는 `27777`을 사용한다.
- 실제 consumer가 없는 `festa_prod_readonly` role은 P0 범위에서 생성하지 않는다.

| ID | 질문/결정 | 결정 주체 | 결정 시점 |
|---|---|---|---|
| C-15 | **확정**: C-14 root alias를 종료하고 root domain은 Production만 소유한다. | Infra + 팀 | 2026-09-20 |
| C-16 | **확정**: 최초 migration 실패는 maintenance를 유지하며 legacy rollback을 금지한다. | Infra + 팀 | 2026-09-20 |
| C-17 | **확정**: canonical CURRENT와 human-approved KNOWN-GOOD를 분리하고 후속 canonical release만 previous rollback을 허용한다. | Infra + 팀 | 2026-09-20 |


## Out of Scope

- dev/demo의 네트워크·도메인·인증서 상세 설계 (`infra-002`)
- Unity Dedicated Server의 AWS 배치, ALB/NLB 선택과 `wss://` 실측 (`infra-003`)
- 애플리케이션 기능 코드와 테스트 자체의 구현
- 운영 환경 자동 확장, 다중 리전, 무중단 배포의 고급 전략
- 거리 기반 음성채팅의 SFU/TURN 호스팅 (`017-proximity-voice`)
- P1 관측 기능의 개별 탐지 규칙 값, 로그·지표 보존 기간과 세부 대시보드 레이아웃

## 리뷰 (Infra 담당이 채운다 — 3칸 모두 채워야 확정)

| 구분 | 검토 내용 |
|---|---|
| ① Clarification 답변 | ✅ 2026-08-18 확정: Jenkins+초기 단일 EC2 논리 분리, Unity Personal 영속 Agent 활성화, 파트별 컨테이너 격리, 실패 유형별 조건부 자동 복구. 상세 결정과 근거는 Clarifications 참조. |
| ② 틀린 요구사항 지적 | ✅ 기존 User Story 3이 단순 파이프라인 로그 열람을 독립 기능처럼 오해하게 만들 수 있었다. 단계별 원본 로그 확인은 P0 기본 기능으로 두고, Story 3을 변경→산출물→배포→복구→현재 버전의 연결 이력 추적으로 교정했다. 그 외 현재 확인된 잘못된 운영 전제는 없다. |
| ③ 빠진 요구사항 추가 | ✅ P1로 수집 Agent 기반 로그·서버 지표 관측, Infra 승인 규칙 기반 Mattermost 선택 알림, Grafana 서버 사용량 대시보드와 장애 격리를 추가했다. 세부 규칙·임계치·보존 기간·패널 구성은 후속 설계에서 확정한다. |

## 아직 MVP까지 해야 하는 점

현재 핵심 CI/CD 파이프라인(GitLab MR gate, Jenkins develop 선택적 배포, WebGL 릴리스 패키지 배포, demo 프로모션 수동 파이프라인, 시크릿 마스킹)의 코드 및 스크립트 구현은 대부분 완료되었다.
그러나 **MVP 기준 배포 안정성 확보와 릴리스 보증을 완료하기 위해 남아 있는 필수 작업(P0)** 및 후속 과제는 다음과 같다.

### 1. 실환경 격리 및 안정성 리허설 (P0)
- **컴포넌트 독립 배포 격리 실측 (T020)**: `dev-component-isolation.sh`를 통해 단일 컴포넌트(Front 또는 Back) 배포 시 나머지 서비스 컨테이너의 restart delta가 0임을 EC2 실환경에서 검증하고 리허설 증적 확보.
- **Unity MR Gate 실측 검증 (T050)**: Unity 변경 MR에 대해 Jenkins Unity Agent가 `ci/test` EditMode를 실행하여 성공 시 GitLab merge 허용, 실패 시 merge 차단 및 컨테이너 무영향 상태를 `gitlab-unity-mr-gate.md`에 실측 기록.

### 2. 인프라 환경 사전 실측 및 기준선 문서화 (P0)
- **서버 호스트 Preflight 실측 (T038)**: EC2 단일 인스턴스의 Docker rootless/rootful 권한 분리, UFW 방화벽 규칙, 루프백 전용 바인딩(18080, 18081, 18082 등), DNS/TLS 및 디스크 사용량 기준선을 `server-preflight.md`에 기록.
- **Unity 빌드 에이전트 Preflight 실측 (T039)**: Unity Personal 라이선스 1회 영속 활성화 상태 유지, agent 컨테이너/프로세스 재시작 시 라이선스 보존 여부 검증 및 `unity-agent-preflight.md` 기록.

### 3. 파이프라인 통합 리허설 및 운영 매뉴얼 최신화 (P0)
- **GitLab-Jenkins 통합 리허설 (T040)**: `feature/*` MR 생성부터 merge gate 통과, `develop` Squash Merge 후 변경 컴포넌트 선별 배포까지의 전 과정 실측 기록 (`gitlab-component-pipeline-rehearsal.md`).
- **Demo 승인 프로모션 및 롤백 리허설 (T041)**: dev 검증 릴리스 승인 후 demo 배포, E2E 헬스체크 성공 확인, 가역 실패 시 자동 롤백 동작 실측 (`demo-promotion-rehearsal.md`).
- **운영 퀵스타트 및 작업일지 동기화 (T022, T042, T043)**: `quickstart.md`에 최신 배포/롤백 절차를 반영하고, `docs/24_작업일지.md`, `docs/25_트러블슈팅.md`, `docs/26_팀_결정_필요사항.md`의 미해결 게이트 현황을 최종 동기화.

---

### 참고: MVP 이후 후속 과제 (P1)
- **배포 이력 및 추적성 관리 (US3 / T031~T034)**: `provenance.sh` 및 `show-release.sh` 구현으로 현재 실행 중인 릴리스와 직전 정상 버전의 산출물 SHA/커밋 역추적 CLI 지원.
- **운영 관측성 및 알림 연동 (US5 / T035~T037)**: Prometheus/Grafana 기반 자원(CPU, Memory, Disk) 대시보드 구축 및 Mattermost 이상 탐지 Webhook 알림 연동.
