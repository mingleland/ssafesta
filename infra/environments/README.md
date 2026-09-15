# Runtime Environments

이 디렉터리는 단일 EC2에서 실행하는 `festa-dev`, `festa-demo`, 공유 데이터 프로젝트의 환경 계약과 배포 어댑터를 소유한다.

## 소유 경계

- `infra/environments/`: Compose overlay, Nginx, PostgreSQL·Redis 설정, R2·MinIO 어댑터, 환경 검증과 운영 절차
- `infra/deploy/`, `infra/jenkins/`: infra-001이 소유하는 릴리스 생성·검증·승격·롤백 경로. 여기서 복제하거나 수정하지 않는다.
- `infra/unity-server/`: infra-003이 소유하는 Unity Dedicated Server 외부 WSS와 부하 실측
- Backend·FastAPI·Frontend·Unity 애플리케이션 계약: 각 파트가 소유하며 이 디렉터리는 환경값만 주입한다.

## 공급자 경계

- 현재 실행 경계는 Ubuntu 단일 EC2와 Docker Compose다.
- PostgreSQL·Redis는 애플리케이션과 분리된 공유 데이터 프로젝트에서 실행한다.
- Cloudflare DNS/Proxy와 R2는 공급자 어댑터 뒤에 두며 실제 계정값은 런타임 Secret으로만 주입한다.
- MinIO는 R2 장애 시 운영자가 검증 후 활성화하는 임시 저장소이며 백업이나 고가용성 수단이 아니다.

## 명령 규약

- 모든 셸 스크립트는 `set -euo pipefail`을 사용하고 저장소 루트와 무관하게 자기 위치에서 경로를 계산한다.
- 환경 전체 `docker compose down`을 실행하지 않는다. 컴포넌트 갱신은 허용 목록과 `up -d --no-deps`를 사용한다.
- 실제 Secret, presigned URL, 쿠키, 개인 키를 명령 인자·로그·검증 근거에 기록하지 않는다.
- 로컬 계약 검사는 `bash infra/environments/tests/contract/run.sh`, 전체 검사는 `bash infra/environments/tests/run.sh`로 실행한다.
- 실제 EC2·DNS·TLS·R2 단계는 `scripts/preflight.sh`가 필수 입력을 확인한 뒤에만 실행한다.

## 릴리스 계약 재사용

환경 매니페스트는 infra-001의 release manifest와 verification result를 참조한다. `current`와 `known-good`, target lock, freshness, rollback 상태는 infra-001의 소유이며 이 기능은 별도 복사본을 만들지 않는다.
