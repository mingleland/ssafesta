# Jenkins agent 분리·이전 절차

현재 단일 EC2의 controller/agent 분리는 논리적 격리일 뿐 완전한 보안 경계가 아니다. Docker socket 접근은 사실상 호스트 제어 권한이므로 서버 수령 직후 rootless Docker를 우선 검증한다.

## 현재 확인 항목

- controller built-in executor 0, Docker socket 미마운트, Unity/배포 도구 미설치
- `linux-docker`, `deploy`, `unity-6000.0.78f1` 노드별 OS 사용자·workspace·executor 1
- agent 사용자의 `JENKINS_HOME` read/write와 무제한 sudo 금지
- rootless Docker의 Compose/build/healthcheck/local image store 동작

rootless가 불가능해 docker group/socket을 임시 사용하면 위험 수용자·만료일·대체 계획을 `docs/26_팀_결정_필요사항.md`에 기록한다.

## 별도 EC2 이전

1. 새 agent EC2의 egress, disk, Docker/Compose 버전과 label을 준비한다.
2. controller credential에는 새 agent 연결 정보의 참조만 등록한다.
3. 같은 Jenkinsfile로 dry-run build/test를 수행한다.
4. local image store를 쓰는 동안 build와 deploy agent가 동일 Docker daemon을 보지 못하면 동작하지 않는다. 이 시점에 registry adapter 또는 image 전송 계약을 먼저 마련한다.
5. 파트 하나를 canary로 새 agent에 고정하고 package→deploy→verify→provenance를 확인한다.
6. 나머지 label을 순차 이전한 뒤 기존 node를 offline으로 전환한다.
7. queue·workspace·credential 누출 검사 후 기존 agent 사용자와 disk를 폐기한다.
