# SSAFY FESTA Infrastructure

이 디렉터리는 Jenkins 기반 CI/CD, 단일 EC2 배포, TLS 진입점과 관측성 설정의 단일 소스다. 현재 단계에서는 서버 없이 검토 가능한 설정과 계약만 관리하며, 실제 credential·인증서·런타임 상태는 커밋하지 않는다.

## 구조와 소유권

| 경로 | 역할 | 주 소유자 |
|---|---|---|
| `jenkins/controller/` | Jenkins controller 이미지와 Compose | Infra |
| `jenkins/casc/` | controller·agent·credential ID JCasC | Infra |
| `jenkins/agents/` | 빌드/배포/Unity 실행기 정의 | Infra, Unity |
| `jenkins/reverse-proxy/` | 외부 80/443을 loopback Jenkins로 전달 | Infra |
| `jenkins/scripts/` | 계약 및 파이프라인 공용 스크립트 | Infra |
| `jenkins/tests/` | 기반 보안·설정 검증 | Infra |
| `deploy/runbooks/` | 서버 수령과 운영 절차 | Infra |
| `deploy/state/` | 배포 포인터 계약(실제 값은 비커밋) | Infra |
| `tests/contract/fixtures/` | JSON Schema 정상/오류 예제 | Infra |

애플리케이션 파트는 저장소 루트의 공통 Jenkinsfile 계약에 맞춰 `validate/test/build/package/verify` 어댑터를 제공한다. Infra는 파트 내부 빌드 명령을 재구현하지 않는다. `festa-unity/Docker/`와 동결 기준선은 수정하지 않는다.

## 보안 경계

- controller의 built-in executor는 0이며 controller에 Docker socket을 연결하지 않는다.
- `linux-docker`, `deploy`, `unity` agent는 별도 node/workspace로 분리한다. 단일 서버이므로 이는 논리적 격리이며 완전한 보안 경계가 아니다.
- 실제 Secret은 Jenkins Credentials 또는 서버의 비커밋 `infra/.env`에서 주입한다. 저장소에는 credential ID와 변수 이름만 둔다.
- 공개 포트는 SSH 22, HTTP 80, HTTPS 443만 허용한다. Jenkins 8080, Grafana 3000, Jenkins agent 50000은 공개하지 않는다.
- 초기 이미지는 서버의 로컬 Docker에 보관한다. 레지스트리 도입 시에도 release manifest 계약은 유지한다.

## 적용 순서

1. `versions.env`와 `.env.example`을 검토하고 서버에서만 `infra/.env`를 만든다.
2. `jenkins/tests/test-foundation.sh`를 실행한다.
3. 서버 수령 후 `deploy/runbooks/server-bootstrap.md` 순서로 UFW·DNS·TLS를 적용한다.
4. controller를 먼저 시작하고 JCasC 로딩을 확인한 뒤 agent secret을 발급하여 agent를 연결한다.
5. Unity agent는 적격성 확인 후 운영자가 Unity Hub에서 한 번 대화형 활성화한다. Unity 자격증명이나 라이선스 파일을 Jenkins에 저장하지 않는다.

서버 크기, 도메인, 인증서 경로, agent secret과 관측성 자원 상한은 서버 수령 전 확정하지 않는다.
