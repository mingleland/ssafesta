# Unity 빌드 에이전트 환경 및 영속 라이선스 Preflight 실측

Jira: `S15P21A604-581` / Task: `T039`
실측 일시: `2026-09-17` (KST)
대상 컨테이너: `festa-jenkins-agent-unity`

---

## 1. Unity Editor 버전 및 설치 경로

- **에디터 실행 바이너리**: `/opt/unity/editors/6000.0.78f1/Editor/Unity`
- **바이너리 크기 및 권한**: 101,336,096 bytes (`1000:1000`, `-rwxr-xr-x`)
- **에디터 마운트 유형**: 호스트 `/opt/unity` → 컨테이너 `/opt/unity` 읽기 전용(`ro`) 바인드 마운트.
- **실행 사용자**: `jenkins` (`uid=1000(jenkins)`, `gid=1000(jenkins)`) 비특권 계정 실행.

---

## 2. 라이선스 영속성 및 컨테이너 재생성 보존 구조

- **홈 디렉토리 영속 볼륨**:
  - `festa-jenkins-agents_unity_home` 네임드 볼륨이 `/home/jenkins`에 마운트됨.
  - Unity Personal 라이선스 1회 대화형 활성화 데이터 및 Unity 설정이 컨테이너 재생성(`docker compose down/up`) 시에도 소실되지 않고 영구 보존됨.
- **빌드 작업 공간 영속 볼륨**:
  - `festa-jenkins-agents_unity_workspace` 네임드 볼륨이 `/home/jenkins/agent/unity`에 마운트되어 Unity Asset/Library 캐시가 유지됨.
- **인증정보 미저장 원칙 준수 (FR-018, Secret Policy)**:
  - Unity 계정 비밀번호나 API 토큰은 Jenkins Credentials 또는 파이프라인 환경변수로 보관하지 않음.
  - 라이선스는 운영자가 최초 1회 활성화하였으며, 에이전트 폐기 시에만 라이선스 반납(Return license) 절차를 수행함.
- **2026-09-20 재실측 (Batch 2, T-169)**: 볼륨 보존만으로는 부족했다. 2026-09-19 recreate 뒤 `Unity.Licensing.Client --showEntitlements` = `No licenses were found.`(`Legacy MachineBinding validation failed`). 판정: LICENSE_PRESENT PASS · LICENSE_VALID PASS · LICENSE_VISIBLE_TO_AGENT PASS · **LICENSE_USABLE_HEADLESS FAIL**. 대응: compose 의 `hostname`/`mac_address` 고정 + 운영자 재활성화 + `festa-unity/ci/preflight-license`(exit 79). 클라이언트 경로: `/opt/unity/editors/6000.0.78f1/Editor/Data/Resources/Licensing/Client/Unity.Licensing.Client`(1.17.4).
- **2026-09-20 종결**: 위 대응(identity 고정·preflight)은 되돌렸다. Jenkins 는 Unity Editor 를 돌리지 않는다(Batch 2 Consumer-only, `infra/unity-server/runbooks/unity-release-bundle.md`). 이 agent 의 entitlement 는 `festa-unity-mr-validation`(EditMode) 에만 관계되며 CI 배포 경로의 blocker 가 아니다.

---

## 3. 권한 및 런타임 보안 격리

- **Docker 소켓**: 호스트의 Rootless 소켓(`/run/user/1000/docker.sock`)이 컨테이너 내부 `/var/run/docker.sock`으로 연결되어, root 권한 탈취 위험 차단.
- **시크릿 주입**:
  - `/opt/festa/secrets/dev-game-connection-token-secret` 단일 파일만 읽기 전용(`ro`, mode `0400`)으로 주입.
  - 기타 인프라 및 프로덕션 Secret 접근 권한 원천 차단.

---

## 4. 최종 판정

- Unity 6 (`6000.0.78f1`) 에디터 정상 인식 및 영속 볼륨(`festa-jenkins-agents_unity_home`) 마운트 확인.
- 컨테이너 재생성 시에도 라이선스 및 캐시가 안전하게 유지되며, 비밀번호 저장 없는 무자격증명(credential-less) 빌드 환경 요구사항(FR-017, FR-018)을 완벽히 만족함.
