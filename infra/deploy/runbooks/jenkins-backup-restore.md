# Jenkins 백업·복구 절차

백업 대상은 코드로 재생성할 수 없는 최소 상태와 감사 기록이다. credential **값**, 복호화 키, 실제 `.env`, Unity 세션·라이선스 파일은 백업 묶음에 넣지 않는다.

## 백업

1. 새 빌드를 막고 queue와 실행 중 배포가 없는지 확인한다.
2. 저장소의 `infra/jenkins/casc/`, `infra/jenkins/jobs/`, `infra/jenkins/plugins.txt`, `Jenkinsfile` commit SHA를 기록한다.
3. Jenkins UI/API에서 job별 build record와 archived artifact/fingerprint 보존 여부를 확인한다.
4. controller를 정상 정지한 뒤 `JENKINS_HOME`의 jobs/build history, fingerprints, users/RBAC metadata만 암호화된 운영 백업 위치로 복사한다.
5. Jenkins master key·credential store는 일반 백업과 분리해 승인된 관리자만 접근하도록 한다. 이 저장소나 작업일지에는 값을 기록하지 않는다.
6. 백업 시간, Jenkins/plugin 버전, commit SHA, 파일 목록과 checksum만 감사 기록에 남긴다.

## 복구 검증

1. 격리된 복구 호스트에 동일 Jenkins LTS와 `plugins.txt`를 설치한다.
2. JCasC와 Job DSL을 먼저 적용하고 controller executor가 0인지 확인한다.
3. 승인된 보안 경로에서만 master key/credential store를 복원한다. 화면·로그에 값을 출력하지 않는다.
4. build record/fingerprint를 복원하고 임의 성공·rollback 실행 하나의 provenance 연결을 확인한다.
5. canary secret scan과 RBAC 테스트를 통과한 뒤 agent를 재연결한다.
6. 분기 indexing은 수동 실행하되 배포 credential은 복구 검증 완료 전 바인딩하지 않는다.

분기별 빌드 산출물과 local Docker image는 같은 EC2 유실 시 함께 사라질 수 있다. current/known-good release manifest 백업과 registry 전환 여부는 별도 복구 목표로 관리한다.
