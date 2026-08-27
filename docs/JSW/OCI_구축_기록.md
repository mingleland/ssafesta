# Oracle Cloud 무료 티어 서버 구축 기록

> EC2 제공 전, 배포 파이프라인을 미리 검증하기 위한 임시 환경 구축 기록
>
> 작성일: 2026-08-26

---

## 1. 배경

### 왜 Oracle Cloud인가

프로젝트에 사용할 EC2를 아직 받지 못한 상태다. 서버를 받은 뒤에야 배포를 시작하면
인프라 문제와 애플리케이션 문제가 뒤섞여 원인 파악이 어려워진다.

그래서 **비용이 들지 않는 환경에서 배포 경로를 먼저 뚫어두고**, EC2를 받으면
검증된 구성을 그대로 옮기는 방식을 택했다.

### 최종 목표 구성

React + Unity WebGL / Spring Boot / FastAPI / Unity Dedicated Server 를
PostgreSQL(pgvector) · Redis 위에서 Nginx 리버스 프록시로 묶는 구조.

이번 단계에서는 그중 **공통 기반(DB, 캐시)과 Nginx HTTP 진입점까지** 세운다.

---

## 2. 알아야 했던 제약

### 2-1. 무료 사양이 최근 축소됨

인터넷 자료 대부분이 "4 OCPU / 24GB 무료"라고 안내하지만, 현재 Always Free 계정
기준은 **Ampere A1 총 2 OCPU / 12GB** 다. 개인 프로젝트에는 여전히 충분하다.

| 항목 | 무료 한도 |
|---|---|
| ARM 서버 (A1.Flex) | 2 OCPU / 12GB |
| AMD 마이크로 | 2대 (각 1/8코어, 1GB) — 실사용 불가 |
| 디스크 | 총 200GB |
| 아웃바운드 트래픽 | 월 10TB |

### 2-2. 홈 리전은 변경 불가

가입 시 고르는 홈 리전에서만 무료 리소스를 만들 수 있고, 이후 변경이 어렵다.
이번 계정은 **애쉬번(us-ashburn-1)** 으로 잡혔다. 한국에서 접속 시 지연이 있지만
개발 검증에는 지장이 없어 그대로 진행했다.

### 2-3. 유휴 인스턴스 회수

7일간 CPU·네트워크·메모리 사용률이 모두 20% 미만이면 인스턴스를 회수한다.
실제 서비스를 돌리면 문제되지 않지만 알아둘 것.

### 2-4. ARM(aarch64) 아키텍처

무료로 쓸 만한 A1.Flex는 ARM 칩이다. EC2에서 흔히 쓰는 t3 계열은 x86이라
**Docker 이미지 호환에 주의가 필요하다.** → 5장에서 상세히 다룬다.

---

## 3. 구축 순서

### 3-1. 인스턴스 생성

**Compute → Instances → Create instance**

| 항목 | 선택값 | 비고 |
|---|---|---|
| Image | Oracle Linux 9 | RHEL 계열. `dnf` 사용 |
| Shape | **VM.Standard.A1.Flex** | `Always Free-eligible` 뱃지 필수 |
| OCPU / Memory | **2 / 12GB** | 기본 1/6에서 반드시 변경 |
| Boot volume | 100GB | 기본 46.6GB는 Docker에 부족 |
| Security | Shielded / Confidential 모두 끔 | 개인 프로젝트에 불필요 |

> **A2.Flex, A4.Flex 주의**
> 같은 Ampere ARM이라 헷갈리지만 **전액 유료**다. `Always Free-eligible`
> 뱃지가 있는 A1.Flex만 무료.

### 3-2. 네트워크 (VCN)

인스턴스 생성 화면에서 서브넷을 바로 만들면 공인 IP 옵션이 비활성화되는 문제가
있어(→ 4-2), **VCN을 먼저 만들고 인스턴스를 붙이는 순서**로 진행했다.

`Create VCN` 으로 만들면 아래가 자동 생성된다.

```
VCN (10.0.0.0/16)
├── Internet Gateway          ← 자동 생성됨
├── NAT Gateway               ← private 서브넷용
├── public subnet  (10.0.0.0/24)
├── private subnet (10.0.1.0/24)
├── default route table       → 0.0.0.0/0 → Internet Gateway
├── route table for private   → 0.0.0.0/0 → NAT Gateway
└── Default Security List     → 22, ICMP
```

**확인해야 할 것**

1. `public subnet` 이 쓰는 route table에 `0.0.0.0/0 → Internet Gateway` 가 있는지
   - NAT Gateway로 되어 있으면 외부에서 접속 불가
   - 확인 경로: Subnets → public subnet → Route Table 링크
2. Security List에 필요한 포트가 열려 있는지

**추가한 Ingress Rule**

| Source | Protocol | Port | 용도 |
|---|---|---|---|
| 0.0.0.0/0 | TCP | 80 | HTTP |
| 0.0.0.0/0 | TCP | 443 | HTTPS |
| 0.0.0.0/0 | TCP | 8443 | SSH 우회 시도 (→ 4-6) |

### 3-3. 방화벽은 2겹이다

**이 부분이 OCI 입문자가 가장 많이 막히는 지점.**

```
인터넷
  ↓
① Security List (클라우드 방화벽)     ← 콘솔에서 설정
  ↓
② firewalld (서버 내부 방화벽)        ← 서버 안에서 설정
  ↓
애플리케이션
```

AWS는 Security Group만 열면 되지만, **OCI의 Oracle Linux/Ubuntu 이미지는
내부 방화벽 규칙이 기본으로 걸려 있다.** 둘 다 열어야 통한다.

```bash
# Oracle Linux (firewalld)
sudo firewall-cmd --permanent --add-port=80/tcp
sudo firewall-cmd --permanent --add-port=443/tcp
sudo firewall-cmd --reload
sudo firewall-cmd --list-ports
```

Ubuntu 이미지라면 iptables를 직접 다뤄야 한다.

```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo netfilter-persistent save
```

### 3-4. 디스크 확장

부트 볼륨을 100GB로 지정했지만 파티션이 자동으로 늘어나지 않았다(→ 4-8).

```bash
sudo /usr/libexec/oci-growfs -y
df -h /
```

### 3-5. Docker 설치

```bash
sudo dnf update -y

sudo dnf install -y dnf-utils
sudo dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin

sudo systemctl enable --now docker
sudo usermod -aG docker opc     # 이후 재로그인 필요
```

> Oracle Linux 9는 RHEL 계열이므로 CentOS용 저장소를 그대로 사용한다.
> `podman` 과 충돌하면 `sudo dnf remove -y podman runc containers-common` 후 재시도.

설치 결과: Docker 29.7.2 / Compose v5.5.0

---

## 4. 트러블슈팅

실제로 막혔던 순서대로 기록한다.

### 4-1. Out of host capacity

```
Out of capacity for shape VM.Standard.A1.Flex in availability domain AD-3.
```

**원인**: 설정 오류가 아니라 재고 부족. ARM 무료 인스턴스는 수요가 많다.

**해결**
- Placement에서 **다른 AD(1, 2, 3)로 변경** → 이번에는 이걸로 해결됨
- Fault domain은 지정하지 말 것 (자동으로 두면 성공률이 높다)
- 그래도 안 되면 스펙을 1코어/6GB로 낮춰 확보 후 나중에 증설
- 유료(PAYG) 전환 시 재고 우선순위가 올라간다 (Always Free 한도 내에서는 계속 무과금)

### 4-2. 공인 IP 토글이 비활성화

인스턴스 생성 화면에서 `Automatically assign public IPv4 address` 가 회색.

```
Warning: You must select a public subnet to assign a public IPv4 address.
```

**원인**: 서브넷을 "지금 새로 만드는 중"이라 시스템이 public 여부를 확인할 대상이
아직 없다. 설정 실수가 아니다.

**해결**: VCN과 서브넷을 **먼저 만들어두고**, 인스턴스 생성 시
`Select existing virtual cloud network` → `Select existing subnet` 으로 고른다.
그러면 토글이 활성화된다.

### 4-3. Limit for internet gateways per VCN of 1 has been already reached

**원인**: `Create VCN` 시 Internet Gateway가 이미 자동 생성되어 있었다.
VCN당 1개가 한도라 추가 생성이 거부된 것.

**교훈**: OCI 콘솔은 진입 경로에 따라 기본 리소스를 함께 만들어준다.
**만들기 전에 목록부터 확인**하는 습관이 낫다.

### 4-4. route table에 NAT Gateway가 걸려 있음

라우팅을 확인하러 들어갔더니 `0.0.0.0/0 → NAT Gateway` 로 되어 있었다.

**원인**: 잘못 본 것. 열어본 게 **private 서브넷용 route table** 이었다.
제목이 `route table for private private subnet-VCJ` 였다.

| | 방향 | 용도 |
|---|---|---|
| Internet Gateway | 양방향 | public 서브넷 |
| NAT Gateway | 나가기만 | private 서브넷 |

**확인 방법**: Subnets → `public subnet` → 상세의 **Route Table 링크**를 클릭하면
그 서브넷이 실제로 쓰는 테이블로 이동한다. 이름으로 추측하지 말 것.

### 4-5. SSH Connection timed out (포트 22)

```
ssh: connect to host 129.213.24.228 port 22: Connection timed out
```

**원인 판별**: 휴대폰 핫스팟으로 전환하니 정상 접속됨
→ 서버 문제가 아니라 **사내망(교육기관)이 아웃바운드 22번을 차단**하고 있었다.

**판별 명령**
```powershell
Test-NetConnection <IP> -Port 22
```
- `TcpTestSucceeded : True` → 서버 도달 가능, 키/계정 문제
- `False` → 경로 차단

### 4-6. 8443 우회도 차단

22가 막혀 SSH를 다른 포트로 옮겨봤다.

```bash
echo "Port 22"   | sudo tee -a /etc/ssh/sshd_config
echo "Port 8443" | sudo tee -a /etc/ssh/sshd_config

# Oracle Linux는 SELinux 허용이 필수. 빠뜨리면 sshd가 아예 안 뜬다
sudo dnf install -y policycoreutils-python-utils
sudo semanage port -a -t ssh_port_t -p tcp 8443

sudo firewall-cmd --permanent --add-port=8443/tcp
sudo firewall-cmd --reload
sudo systemctl restart sshd
```

**주의사항**
- 설정 파일에 `Port` 를 명시하는 순간 기본값 22가 사라진다. **`Port 22`도 같이 적을 것**
- `semanage` 를 빠뜨리고 `restart sshd` 하면 SSH가 죽어 서버에 못 들어간다.
  **재시작 전 기존 SSH 창을 절대 닫지 말 것**
- 되돌릴 수 있게 백업: `sudo cp /etc/ssh/sshd_config /etc/ssh/sshd_config.bak`

**결과**: 8443도 차단됨. 화이트리스트 방식 방화벽으로 추정.

**포트를 옮길 때 고려할 점**
443을 쓰면 나중에 Nginx HTTPS와 충돌한다. 그래서 8443을 먼저 시도했다.

### 4-7. Windows: UNPROTECTED PRIVATE KEY FILE

```
Permissions for '...ssh-key.key' are too open.
This private key will be ignored.
```

**원인**: SSH는 개인키를 본인만 읽을 수 있을 때만 사용한다.
Downloads 폴더는 다른 사용자 그룹에도 접근 권한이 있다.

**해결 (PowerShell)**
```powershell
$key = "C:\Users\<사용자>\Downloads\ssh-key.key"
icacls $key /inheritance:r
icacls $key /grant:r "$($env:USERNAME):(R)"
icacls $key            # 본인 계정 (R) 한 줄만 남으면 성공
```

Mac/Linux는 `chmod 400 <키파일>`.

### 4-8. 디스크가 100GB인데 30GB만 보임

```
sda           100G          ← 디스크는 100GB
└─ sda3      44.5G          ← 파티션이 여기서 멈춤
   └─ root   29.5G  /
```

**원인**: 부트 볼륨 크기는 100GB로 지정됐지만, 이미지의 기본 파티션(46.6GB) 기준으로
잡혀서 나머지 공간이 미할당 상태.

**해결**
```bash
sudo /usr/libexec/oci-growfs -y
```

결과: `/` 가 30G → **83G** 로 확장.

수동으로 하려면:
```bash
sudo growpart /dev/sda 3
sudo pvresize /dev/sda3
sudo lvextend -l +100%FREE /dev/ocivolume/root
sudo xfs_growfs /
```

### 4-9. docker.sock permission denied

```
permission denied while trying to connect to the docker API at unix:///var/run/docker.sock
```

**원인**: `usermod -aG docker opc` 는 사용자 정보를 바꾸지만
**이미 열려 있는 세션에는 소급 적용되지 않는다.**

**확인**
```bash
id -nG     # 목록에 docker 가 있어야 한다
```

**해결**: 재로그인 또는 재부팅. VS Code 터널은 터미널만 새로 열어서는
같은 세션을 물려받으므로 **재부팅이 확실**하다.

---

## 5. VS Code Remote Tunnel — 방화벽 우회 접속

22번도 8443도 막혀서 SSH 자체를 포기하고 다른 방식을 택했다.

### 원리

SSH는 **외부에서 서버로 들어오는** 연결이라 사내망 아웃바운드 정책에 막힌다.
VS Code Tunnel은 **서버가 먼저 밖으로 나가서** Microsoft 중계 서버에 붙고,
사용자는 브라우저(443)로 그 중계 서버에 접속한다.

```
[사내망 PC] --443--> [MS 중계] <--443-- [OCI 서버]
                  둘 다 아웃바운드라 차단되지 않음
```

### 설치

```bash
# ARM64용 CLI 다운로드
curl -Lk 'https://code.visualstudio.com/sha/download?build=stable&os=cli-alpine-arm64' \
     --output vscode_cli.tar.gz
tar -xf vscode_cli.tar.gz

# 최초 1회 — GitHub 계정 인증
./code tunnel

# 상시 실행 서비스로 등록
./code tunnel service install

# 로그아웃 후에도 서비스 유지
sudo loginctl enable-linger $USER
```

접속: `https://vscode.dev/tunnel/<터널이름>` 을 브라우저로 열면
파일 편집기와 터미널을 모두 쓸 수 있다.

### 주의: GitHub 권한 범위

인증 시 요구하는 권한이 넓다.

- Full control of private repositories
- 소속 Organization 접근

서버에 접근 가능한 사람이 저장소까지 손댈 수 있게 된다는 뜻이다.
부담스럽다면 **터널 전용 GitHub 계정을 따로 만드는 것**을 권한다.

### 확인해둘 것

배포한 사이트를 사내망에서 볼 수 있는지는 별개 문제다. 임시 서버로 확인:

```bash
sudo python3 -m http.server 80
```
```powershell
Test-NetConnection <IP> -Port 80
```

이번 환경은 **80번이 열려 있어** 배포 결과 확인에 문제가 없었다.

---

## 6. ARM vs x86 — EC2 이전 시 주의점

### 결론

명령어와 설정은 거의 그대로 옮겨간다. 단 **Docker 이미지의 CPU 아키텍처**만 주의.

ARM에서 빌드한 이미지를 x86 서버에 그대로 올리면 `exec format error` 가 난다.
Docker가 OS 차이는 흡수하지만 CPU 차이는 흡수하지 못한다.

### 지켜야 할 3가지

1. `docker-compose.yml` 에 **`platform: linux/amd64` 같은 값을 박지 말 것**
   (박으면 ARM에서 에뮬레이션으로 떨어져 매우 느려지거나 실패)
2. 이미지 태그에 아키텍처 지정(`-amd64` 등)을 붙이지 말 것
3. **빌드된 이미지를 복사하지 말고 소스에서 재빌드**할 것

```bash
# 서버 이전 시 하는 일은 이게 전부가 된다
git clone <레포>
docker compose up -d --build
```

주요 공식 이미지(`postgres`, `redis`, `nginx`, `eclipse-temurin`, `python`,
`node`)는 모두 ARM/x86 멀티아치라 태그를 바꿀 필요가 없다.

아래 규칙은 멀티아치 이미지로 실행 가능한 공통 컴포넌트 기준이다. 현재 프로젝트의
Unity Dedicated Server는 예외이며 OCI ARM64에서 빌드·실행하지 않는다.

### 컴포넌트별 영향

| 대상 | ARM 호환 | 비고 |
|---|---|---|
| Spring Boot | ✅ | JVM이라 아키텍처 무관 |
| React 빌드 | ✅ | 결과물은 정적 파일 |
| FastAPI | ✅ | arm64 wheel 확인 필요 |
| PostgreSQL / Redis | ✅ | 공식 멀티아치 |
| **Unity Dedicated Server** | ❌ | 현재 프로젝트의 Unity 6000.0.78f1에는 Linux ARM64 서버 빌드 경로가 없다. OCI Unity 검증 계획은 취소하고 서버는 x86_64 EC2에서 검증한다 |

### Unity 6000.0.78f1 확인 결과

Jira `S15P21A604-267`에서 Unity 담당자가 아래 근거를 확인했다.

- `6000.0.78f1/modules.json`의 Linux 계열 모듈은 `linux-il2cpp`, `linux-mono`,
  `linux-server`이며 모두 x86_64 전용이다.
- 설치된 `LinuxStandaloneSupport/Variations`에도 `linux64_server_*` variation만 있다.
- `com.unity.sdk.linux-arm64` sysroot는 이 버전에서 Embedded Linux 전용이므로
  데스크톱 Linux Dedicated Server 타깃으로 사용할 수 없다.
- Linux ARM64 Dedicated Server를 사용하려면 Unity 6000.2+ 업그레이드가 필요하지만,
  임시 OCI 검증을 위해 기준 엔진을 올리거나 x86_64 에뮬레이션을 사용하지 않는다.

처음에는 OCI 범위를 `/unity/` 하위 WebGL 정적 파일 로딩까지로 축소하는 방안을
검토했다. 하지만 원래 목적은 서버 기동, WSS 연결과 브라우저 2개의 스폰·상호 이동
검증이며 정적 파일 로딩만으로는 이를 달성하지 못한다. x86_64 EC2 확보 후 전체 경로를
함께 검증해도 일정상 충분하다고 판단해 **OCI Unity 검증 계획 전체를 취소했다.**

### OS 차이

| | Oracle Linux 9 | Ubuntu |
|---|---|---|
| 패키지 | `dnf install` | `apt install` |
| 방화벽 | `firewall-cmd` | `iptables` / `ufw` |
| 기본 계정 | `opc` | `ubuntu` |

`cd`, `ls`, `docker`, `systemctl`, `git` 등 실사용 명령의 95%는 동일하다.
EC2를 Amazon Linux 2023으로 쓴다면 같은 RHEL 계열이라 오히려 잘 맞는다.

---

## 7. 서버 기준선

컨테이너를 올리기 전 빈 서버 상태. 이후 자원 사용량 판단의 비교 기준이 된다.

```
CPU     : 2 코어 (Neoverse-N1, aarch64)
Memory  : 10.6GB total / 9.5GB available
Swap    : 4GB
Disk    : 83GB / 72GB available
Docker  : 29.7.2, Compose v5.5.0
Load    : 0.08
```

기록해두면 좋다.

```bash
mkdir -p ~/baseline
{
  date
  echo "--- cpu";  nproc
  echo "--- mem";  free -m
  echo "--- disk"; df -h /
} > ~/baseline/00-empty-server.txt
```

---

## 8. 디렉토리 구조와 네트워크

### 구조

```
/home/opc/festa/
├── postgres/     PostgreSQL
├── redis/        Redis
├── nginx/        리버스 프록시
├── back/         Spring Boot (예정)
├── ai/           FastAPI (예정)
├── front/        React 정적 (예정)
├── game/         Unity 서버 (예정)
└── secrets/      .env 파일 (권한 700)
```

각 폴더를 **독립된 Compose 프로젝트**로 두는 이유는, 한 컴포넌트를 재배포할 때
다른 컴포넌트가 재시작되지 않게 하기 위해서다.
`docker compose up -d` 를 `back/` 에서 실행해도 `ai/` 컨테이너는 영향받지 않는다.

### 공용 네트워크

Compose 프로젝트가 분리되면 네트워크도 따로 생겨서 컨테이너끼리 이름으로 찾지 못한다.
공용 네트워크를 미리 만들어두고 각자 붙는 방식으로 해결한다.

```bash
docker network create festa-net
```

```yaml
networks:
  festa-net:
    external: true      # compose가 만드는 게 아니라 기존 것을 사용
```

`external: true` 덕분에 한 컨테이너를 내려도 네트워크가 사라지지 않는다.
이 설정으로 `postgres:5432` 처럼 **컨테이너 이름으로 접속**할 수 있다.

---

## 9. 공통 기반 컨테이너

### PostgreSQL

`~/festa/secrets/postgres.env` (권한 600)

```
POSTGRES_USER=festa
POSTGRES_PASSWORD=<openssl rand -base64 24>
POSTGRES_DB=festa
```

`~/festa/postgres/docker-compose.yml`

```yaml
services:
  postgres:
    image: pgvector/pgvector:pg16
    container_name: postgres
    restart: unless-stopped
    env_file:
      - ../secrets/postgres.env
    environment:
      TZ: Asia/Seoul
    volumes:
      - pgdata:/var/lib/postgresql/data
    networks:
      - festa-net
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $${POSTGRES_USER}"]
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  pgdata:

networks:
  festa-net:
    external: true
```

**설계 의도**

- **`ports:` 없음** — 외부에서 5432 접속 불가. 같은 네트워크의 컨테이너만 접근
- **`pgdata` 볼륨** — 컨테이너를 지웠다 만들어도 데이터 유지
- **`pgvector` 이미지** — 나중에 확장을 설치하는 것보다 처음부터 포함된 게 낫다
- **`healthcheck`** — 다른 컨테이너가 `depends_on` 으로 기동 순서를 보장할 수 있다

**검증**

```bash
docker compose up -d
docker compose ps                                    # healthy 확인
docker exec -it postgres psql -U festa -d festa -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

로그에서 `PostgreSQL 16.15 ... on aarch64-unknown-linux-gnu` 확인 →
ARM 네이티브로 동작 중.

> 기동 로그 중간의 `received fast shutdown request` 는 정상이다.
> 최초 실행 시 초기화용으로 한 번 띄웠다 끄고 다시 켜기 때문.

### Redis

`~/festa/redis/docker-compose.yml`

```yaml
services:
  redis:
    image: redis:7-alpine
    container_name: redis
    restart: unless-stopped
    command: >
      redis-server
      --maxmemory 512mb
      --maxmemory-policy allkeys-lru
    networks:
      - festa-net
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 3s
      retries: 5

networks:
  festa-net:
    external: true
```

**볼륨을 두지 않은 것이 의도다.** 세션·캐시처럼 유실을 허용하는 데이터만 저장하고,
영구 데이터는 PostgreSQL에 둔다. `allkeys-lru` 는 512MB가 차면 오래된 키부터
자동 삭제한다는 뜻.

**컨테이너 간 통신 확인**

```bash
docker exec -it redis getent hosts postgres
```

IP가 나오면 `festa-net` 을 통해 이름 해석이 되고 있다는 뜻이다.

### Nginx

아래 파일로 Nginx Compose 프로젝트와 기본 라우팅 설정을 구성했다.

```text
~/festa/nginx/docker-compose.yml
~/festa/nginx/conf.d/default.conf
```

OCI Security List와 서버 내부 방화벽에서 80번 포트를 허용한 뒤
`http://129.213.24.228` 접속을 확인했다. Backend·Frontend가 아직 없으므로
현재는 Nginx 공개 진입점까지 검증한 상태다.

---

## 10. 실측 결과 — 추정하지 말 것

작업 중 얻은 가장 실질적인 교훈이다.

| 대상 | 사전 추정 | **실측** |
|---|---|---|
| PostgreSQL 메모리 | 2,048MB | **25MB** |

80배 차이가 났다. "이 정도 스펙으로는 안 될 것"이라는 판단을 실측 없이 내렸다가
폐기한 셈이다.

```bash
docker stats --no-stream
free -h
```

컴포넌트를 하나 추가할 때마다 이 두 명령으로 기록해두면,
용량 판단을 근거 있게 할 수 있다.

```bash
{ echo "=== postgres only"; date; docker stats --no-stream; free -m; } \
  > ~/baseline/01-postgres.txt
```

> 유휴 상태 기준이라는 점은 감안해야 한다. 커넥션 풀이 붙고 실제 쿼리가 돌면
> 늘어난다. 그래도 사전 추정치와는 자릿수가 다르다.

---

## 11. 남은 작업

### 다음 단계

- [x] Redis 기동 및 통신 확인
- [x] Nginx 리버스 프록시 기본 진입점 (HTTP 80)
- [ ] Spring Boot 컨테이너화 → `/actuator/health` 도달 확인
- [ ] FastAPI 컨테이너화
- [ ] React 정적 배포 (버전별 릴리스 + 심볼릭 링크 전환)
- ~~Unity WebGL 배포 (`.br`/`.gz` Content-Encoding 처리)~~ — 취소: Dedicated Server 없이 정적 파일만 배포해서는 원래 검증 목적을 달성하지 못함

### EC2 확보 후

- [ ] Jenkins CI/CD — **수동 배포를 먼저 성공시킨 뒤 자동화한다.**
      자동화할 대상이 없는 상태에서 세우면 실패 원인이 Jenkins인지
      Docker인지 애플리케이션인지 구분되지 않는다
- [ ] 도메인 + Let's Encrypt TLS
- [ ] Cloudflare DNS/CDN
- [ ] Unity Dedicated Server 부하 시험

### 이 서버에서 하기 어려운 것

- **Unity 빌드** — 권장 RAM 8GB 이상, Library 캐시만 수십 GB.
  로컬 PC에서 빌드하고 산출물만 업로드하는 방식이 현실적이다
- **Jenkins Unity Agent** — 위와 같은 이유

---

## 부록: 자주 쓴 명령어

```bash
# 상태 확인
docker compose ps
docker compose logs --tail 50 -f
docker stats --no-stream

# 네트워크
docker network ls
docker network inspect festa-net
docker exec -it <컨테이너> getent hosts <다른컨테이너>

# 방화벽
sudo firewall-cmd --list-ports
sudo firewall-cmd --permanent --add-port=<포트>/tcp && sudo firewall-cmd --reload

# 디스크 / 자원
df -h /
free -h
sudo /usr/libexec/oci-growfs -y

# 시간대
sudo timedatectl set-timezone Asia/Seoul
```
