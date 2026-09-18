# EC2 서버 호스트 환경 및 보안 Preflight 실측

Jira: `S15P21A604-581` / Task: `T038`
실측 일시: `2026-09-17` (KST)
대상 호스트: 단일 EC2 인스턴스 (`ip-172-26-5-50`)

---

## 1. OS & 하드웨어 자원 기준선 (Hardware Baseline)

- **OS**: Ubuntu 24.04.4 LTS (Linux 6.17.0-1019-aws x86_64)
- **vCPU**: 4 vCPU (Intel(R) Xeon(R) Platinum 8259CL CPU @ 2.50GHz)
- **Memory**:
  - Total: 15 GiB
  - Used: 5.1 GiB / Free: 768 MiB / Available: 10 GiB
  - Swap: 4.0 GiB (1.5 GiB used)
- **Disk**:
  - Filesystem: `/dev/root` (309 GiB)
  - Used: 175 GiB (57%)
  - Available: 135 GiB
  - 판정: 57% 사용 중이며 잔여 135 GiB로 배포 및 Docker 이미지 캐시 보존 한도(Disk Cap) 안전 범위 내 유지.

---

## 2. Docker 런타임 및 권한 분리 (Socket & Isolation)

- **Docker Version**:
  - Rootful Docker: `29.1.3` (경로: `/var/run/docker.sock`)
  - Rootless Docker: `29.1.3` (경로: `/run/user/1000/docker.sock`, uid=1000)
- **권한 및 프로세스 격리 검증**:
  - `festa-integration-front-1` 컨테이너는 Rootless Docker(`rootlesskit`, port 18080) 상에서 비특권 사용자로 실행.
  - 빌드 및 배포 에이전트(`festa-jenkins-agent-deploy`, `festa-jenkins-agent-linux-docker`, `festa-jenkins-agent-unity`)와 컨트롤러(`festa-jenkins-controller`)가 독립 컨테이너로 실행 중.
  - 데이터베이스(`festa-data-postgres-1`, `festa-data-redis-1`)는 호스트 포트 바인딩 없이 Docker 내부 네트워크 격리 유지.

---

## 3. 방화벽 및 네트워크 포트 보안 (UFW & Port Security)

### UFW 상태
- **Status**: `active` (Default: incoming `deny`, outgoing `allow`, routed `deny`)
- **외부 인바운드 허용 포트**:
  - `22/tcp` (SSH 관리 포트)
  - `80/tcp` (HTTP - Nginx / Cloudflare 경유)
  - `443/tcp` (HTTPS - Nginx TLS 종단)

### 호스트 리스닝 포트 격리 검증
- **공개 포트 (0.0.0.0 / [::])**: 80, 443 (Nginx), 22 (SSH)만 외부 개방.
- **내부 루프백 격리 포트 (127.0.0.1 전용 바인딩)**:
  - `127.0.0.1:18080`: demo/integration Front (rootlesskit)
  - `127.0.0.1:18081`: demo Backend
  - `127.0.0.1:18082`: demo AI
  - `127.0.0.1:8081`: dev Backend
  - `127.0.0.1:8000`: dev AI
  - `127.0.0.1:7777`: dev Game (Dedicated Server)
  - `127.0.0.1:17777`: demo Game (Dedicated Server)
  - `127.0.0.1:8080`: Jenkins Controller Web UI (Nginx 프록시 경유)
- **데이터베이스 직접 노출 차단**:
  - PostgreSQL (`5432`) 및 Redis (`6379`)는 호스트 `ss -tulpn`에 노출되지 않으며, 승인된 Docker 네트워크 내 컨테이너 간 통신만 허용됨.

---

## 4. 최종 결론

- **단일 EC2 환경 분리**: dev, demo, data, CI/CD 스택이 하나의 EC2 인스턴스 안에서 포트 충돌 없이 루프백 및 네트워크로 격리되어 정상 작동 중.
- **보안 원칙 준수**: 외부에는 Cloudflare/Nginx(80/443)와 SSH(22)만 노출되며, 모든 마이크로서비스 및 DB는 127.0.0.1 루프백 또는 Docker 전용 네트워크로 봉쇄되어 헌법 및 보안 요구사항(FR-019, FR-020, Article III)을 만족함.
