# EC2 Jenkins Controller·Docker Agent 설정 가이드

> 관련 Jira: `S15P21A604-225`
>
> 기준일: 2026-09-01
>
> 기준 환경: Ubuntu 24.04 LTS, amd64, Docker 29.1.3, Jenkins 2.555.3

이 문서는 SSAFY FESTA의 단일 EC2에 Jenkins Controller와 `linux-docker` Agent를 구성한 실제 절차를 정리한다. 명령은 별도 표시가 없으면 **MobaXterm으로 접속한 EC2 터미널**에서 실행한다.

실제 비밀번호·토큰·Agent Secret·인증서 내용은 문서, Git, 채팅, 터미널 출력에 남기지 않는다.

---

## 1. 현재 구성과 진행 상태

```text
Windows 브라우저
  └─ SSH 터널
      └─ EC2 127.0.0.1:8080
          └─ Jenkins Controller (rootful Docker)
              └─ festa-jenkins-control 네트워크
                  └─ linux-docker Agent (rootful Docker 컨테이너)
                      └─ /run/user/1000/docker.sock
                          └─ ubuntu 사용자의 rootless Docker
```

```mermaid
flowchart LR
    Browser[Windows 브라우저] -->|SSH 터널| Controller[Jenkins Controller<br/>127.0.0.1:8080]
    Controller -->|Jenkins control network| Agent[linux-docker Agent<br/>UID 1000]
    Agent -->|Unix socket mount| Socket[/run/user/1000/docker.sock]
    Socket --> Rootless[rootless Docker Engine]
    Rootful[rootful Docker Engine] --> Controller
    Rootful --> Agent
```

| 항목 | 상태 |
|---|---|
| Jenkins Controller | `healthy` |
| Jenkins UI 로그인 | 완료 |
| rootless Docker | 자동 시작·socket 확인 완료 |
| `linux-docker` Agent | Online |
| Agent 내부 Docker Client → rootless Server | 확인 완료 |
| Agent 내부 Docker Compose | 확인 완료 |
| `deploy`, `unity` Agent | 의도적으로 Offline |
| GitLab API·checkout·webhook Credentials | JCasC persistence 수정 후 재등록 예정 |
| GitLab Webhook | 연결 전 |
| Pipeline·실제 CI 빌드 | 실행 전 |
| Nginx·공개 Jenkins HTTPS | 구성 전 |

---

## 2. 절대 지켜야 할 안전 원칙

- SSH 22번 포트를 차단하지 않는다.
- 등록된 SSH 공개키를 삭제하지 않는다.
- `/home`, `/etc`, `/var` 등에 재귀 `chmod`·`chown`을 실행하지 않는다.
- UFW를 변경하기 전에 현재 SSH 세션과 새 SSH 세션이 모두 접속되는지 확인한다.
- Jenkins 8080은 외부에 공개하지 않고 `127.0.0.1`에만 bind한다.
- Docker socket은 TCP로 공개하지 않는다.
- 애플리케이션 Secret은 Jenkins bootstrap용 `infra/.env`와 섞지 않는다.
- `.env`, 토큰, 비밀번호, 개인키, 인증서 원문을 Git에 커밋하지 않는다.
- 서버의 tracked hotfix는 바로 삭제하지 말고 stash로 보존한 뒤 정식 코드와 비교한다.

---

## 3. EC2 기본 상태 확인

```bash
cat /etc/os-release
uname -m
nproc
free -h
df -hT /
lsblk -f
timedatectl
```

확인된 사양:

- Ubuntu 24.04.4 LTS
- `x86_64`, 4 vCPU, RAM 15GiB
- ext4 약 309GB
- NTP 동기화 활성

열린 포트와 방화벽을 확인한다.

```bash
ss -lntup
sudo ufw status verbose
sudo sshd -t
sudo sshd -T | grep -E 'passwordauthentication|permitrootlogin|pubkeyauthentication'
```

확인 기준:

- UFW 기본 incoming: `deny`
- SSH: 공개키 인증 활성, 비밀번호 인증 비활성
- 외부 허용 포트: 22, 80, 443만
- Jenkins: `127.0.0.1:8080`

### Swap 4GiB

현재 서버에는 4GiB swap을 생성해 영구 등록했다. 이미 `/swapfile`이 존재하면 생성 명령을 반복하지 않는다.

```bash
sudo fallocate -l 4G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
grep -q '^/swapfile ' /etc/fstab || \
  echo '/swapfile none swap sw,nofail 0 0' | sudo tee -a /etc/fstab
free -h
sudo swapon --show
```

---

## 4. 저장소 준비와 최신화

```bash
cd ~/festa/S15P21A604
git switch develop
git status --short
git log -1 --oneline
```

tracked 파일에 서버 hotfix가 있으면 바로 pull하지 않는다. 먼저 원격과 비교한다.

```bash
git fetch origin
git log -1 --oneline origin/develop
git diff origin/develop -- infra/jenkins/casc/gitlab.yaml
```

서버 hotfix를 보존한 실제 절차:

```bash
git stash push -m "ec2-jenkins-gitlab-hotfix" -- \
  infra/jenkins/casc/gitlab.yaml
git pull --ff-only origin develop
git status --short
git log -1 --oneline
```

`infra/.env`는 Git 제외 파일이므로 pull 뒤에도 유지된다. 2026-09-01 기준 서버는 `origin/develop`과 같고 작업 트리는 clean이다.

---

## 5. Docker와 Jenkins 정적 검사

```bash
sudo systemctl enable --now docker
sudo docker version
sudo docker compose version
sudo docker run --rm hello-world
```

- Host Docker Engine: 29.1.3
- Host Docker Compose: 2.40.3

Controller와 Agent 컨테이너 자체는 rootful Docker가 관리한다. CI 작업이 사용하는 Docker는 별도의 rootless daemon으로 분리한다.

```bash
python3 -c 'import yaml; print("PyYAML: OK")'
sudo bash infra/jenkins/tests/test-foundation.sh
```

과거에는 `validate-contracts.sh` 실행 bit가 없어 `Permission denied`가 발생했다. 정식 코드에서 Jenkins·deploy Shell 진입점의 Git mode를 `100755`로 고쳤으므로 서버에서 임의 `chmod`로 숨기지 않는다.

```bash
git ls-files --stage infra/jenkins/scripts/validate-contracts.sh
```

mode가 `100755`인지 확인한다.

---

## 6. Jenkins Controller 설정

### 6.1 `infra/.env`

실제 파일은 서버에만 만들고 권한을 제한한다.

```bash
cd ~/festa/S15P21A604
nano infra/.env
chmod 600 infra/.env
```

현재 Controller와 `linux-docker` Agent에 필요한 최소 항목:

```ini
JENKINS_ADMIN_ID=festa-admin
JENKINS_ADMIN_PASSWORD=<서버에만 저장한 관리자 비밀번호>
JENKINS_PUBLIC_URL=http://127.0.0.1:8080/
JENKINS_AGENT_SECRET_LINUX_DOCKER=<Jenkins 화면에서 확인한 Agent Secret>
GITLAB_WEBHOOK_SECRET_CREDENTIALS_ID=gitlab-webhook-secret
DOCKER_SOCKET_PATH=/run/user/1000/docker.sock
```

주의:

- 실제 `.env`에는 Markdown 링크 문법이나 백슬래시를 넣지 않는다.
- `GITLAB_WEBHOOK_SECRET`은 폐기된 항목이다.
- localhost Public URL은 SSH 터널로 UI를 확인하기 위한 임시값이다.
- GitLab Webhook 전에는 실제 공개 HTTPS 주소로 바꿔야 한다.
- DB·Redis·JWT·OAuth 값은 이 파일에 넣지 않는다.

### 6.2 Controller 실행

```bash
sudo docker compose \
  --env-file infra/versions.env \
  --env-file infra/.env \
  -f infra/jenkins/controller/compose.yaml \
  up -d --build --wait
```

확인:

```bash
sudo docker compose \
  --env-file infra/versions.env \
  --env-file infra/.env \
  -f infra/jenkins/controller/compose.yaml \
  ps

sudo docker inspect --format '{{.State.Health.Status}}' \
  festa-jenkins-controller
sudo docker logs --tail 50 festa-jenkins-controller
curl -I http://127.0.0.1:8080/login
```

정상 기준:

- 상태: `healthy`
- 로그: `Jenkins is fully up and running`
- 8080 bind: `127.0.0.1:8080`

### 6.3 발생했던 JCasC 기동 실패

GitLab Branch Source plugin 743에서는 `secretToken`이 폐기됐다. 현재 정식 설정은 Secret이 아니라 Jenkins Credential ID를 참조한다.

```yaml
webhookSecretCredentialsId: ${GITLAB_WEBHOOK_SECRET_CREDENTIALS_ID}
```

Controller가 `ConfigurationAsCodeBootFailure`로 `unhealthy`가 되면 로그에서 폐기된 JCasC 필드를 먼저 확인한다. 서버 파일을 계속 hotfix하지 말고 저장소 정본을 수정한다.

---

## 7. Windows에서 Jenkins UI 접속

Jenkins 8080은 외부에 열지 않는다. MobaXterm의 **Tunneling** 메뉴에서 SSH 터널을 시작한다.

| 항목 | 값 |
|---|---|
| Forwarded port | 로컬에서 비어 있는 포트, 예: `8080` |
| SSH server / port | EC2 접속 주소 / `22` |
| SSH user | `ubuntu` |
| Remote server / port | `127.0.0.1` / `8080` |

Windows 브라우저:

```text
http://127.0.0.1:8080/login
```

로그인은 `festa-admin`과 서버 `infra/.env`에만 저장한 비밀번호를 사용한다. 대시보드의 `linux-docker`, `deploy`, `unity` 노드는 JCasC가 자동 생성한다. 실행하지 않은 노드가 Offline인 것은 정상이다.

---

## 8. rootless Docker 구성

Agent가 rootful `/var/run/docker.sock`을 사용하면 Jenkins 작업이 host root에 가까운 권한을 얻는다. 이를 피하려고 UID 1000 `ubuntu` 사용자의 rootless Docker socket을 Agent에 mount한다.

### 8.1 요구 패키지

```bash
sudo apt update
sudo apt install -y \
  uidmap \
  slirp4netns \
  rootlesskit \
  fuse-overlayfs \
  dbus-user-session

command -v newuidmap newgidmap slirp4netns rootlesskit fuse-overlayfs
grep '^ubuntu:' /etc/subuid /etc/subgid
sysctl kernel.unprivileged_userns_clone
```

### 8.2 Docker 29.1.3 rootless 스크립트

Ubuntu `docker.io` 패키지에는 setup script가 없었다. Docker 공식 static rootless extras에서 스크립트와 `vpnkit`만 가져온다. Ubuntu AppArmor profile을 적용받는 배포판 `/usr/bin/rootlesskit`을 우선 사용하므로 static `rootlesskit`은 설치하지 않는다.

```bash
mkdir -p /home/ubuntu/.local/bin

curl -fL --proto '=https' --tlsv1.2 \
  -o /tmp/docker-rootless-extras-29.1.3.tgz \
  https://download.docker.com/linux/static/stable/x86_64/docker-rootless-extras-29.1.3.tgz

tar -xzf /tmp/docker-rootless-extras-29.1.3.tgz \
  -C /home/ubuntu/.local/bin \
  --strip-components=1 \
  docker-rootless-extras/dockerd-rootless.sh \
  docker-rootless-extras/dockerd-rootless-setuptool.sh \
  docker-rootless-extras/vpnkit

chmod 755 \
  /home/ubuntu/.local/bin/dockerd-rootless.sh \
  /home/ubuntu/.local/bin/dockerd-rootless-setuptool.sh \
  /home/ubuntu/.local/bin/vpnkit
```

공식 tarball의 checksum은 다운로드 시점의 Docker 공식 배포 페이지 값과 대조한다.

### 8.3 설치와 자동 시작

```bash
sudo loginctl enable-linger ubuntu

XDG_RUNTIME_DIR=/run/user/1000 \
PATH=/usr/local/bin:/usr/bin:/bin:/home/ubuntu/.local/bin \
/home/ubuntu/.local/bin/dockerd-rootless-setuptool.sh install

systemctl --user enable --now docker
```

확인:

```bash
loginctl show-user ubuntu -p Linger
systemctl --user is-active docker
test -S /run/user/1000/docker.sock && echo "Rootless socket: OK"

DOCKER_HOST=unix:///run/user/1000/docker.sock \
  docker run --rm hello-world
```

### 8.4 AppArmor 오류 대응

`fork/exec /proc/self/exe: operation not permitted`는 `/home/ubuntu/.local/bin/rootlesskit` static 바이너리가 Ubuntu 패키지의 AppArmor profile을 적용받지 못해 발생했다.

```bash
command -v rootlesskit
/usr/bin/rootlesskit --version
```

`/usr/bin/rootlesskit`을 사용한다. AppArmor를 끄거나 unprivileged user namespace 제한을 전역 완화하지 않는다.

---

## 9. `linux-docker` Agent 연결

### 9.1 설정 위치와 Secret

- `infra/jenkins/casc/jenkins.yaml`: 노드 이름·라벨·작업 경로
- `infra/jenkins/agents/compose.yaml`: Agent 컨테이너·Controller URL·socket mount
- 서버 `infra/.env`: 실제 Agent Secret

Jenkins 대시보드에서 `linux-docker`를 클릭하고 inbound Agent 안내의 Secret을 확인한다. 값을 채팅이나 스크린샷으로 공유하지 않는다.

```bash
nano infra/.env
```

```ini
JENKINS_AGENT_SECRET_LINUX_DOCKER=<화면에서 복사한 Secret>
```

값 노출 없이 확인:

```bash
grep -qE '^JENKINS_AGENT_SECRET_LINUX_DOCKER=.+$' infra/.env \
  && echo "Agent Secret: SET" \
  || echo "Agent Secret: EMPTY"
```

### 9.2 Agent 이미지 빌드

현재 Compose는 비활성 profile의 필수 Secret도 먼저 보간한다. 아직 사용하지 않는 `deploy`, `unity` 값은 명령 프로세스에만 임시값을 전달한다. `.env`에는 가짜 Secret을 저장하지 않는다.

```bash
sudo env \
  JENKINS_AGENT_SECRET_DEPLOY=not-used \
  JENKINS_AGENT_SECRET_UNITY=not-used \
  docker compose \
  --env-file infra/versions.env \
  --env-file infra/.env \
  -f infra/jenkins/agents/compose.yaml \
  --profile linux-docker \
  build linux-docker-agent
```

`buildx`가 없다는 경고가 발생해도 일반 builder로 이미지가 끝까지 생성되면 현재 단계는 진행할 수 있다.

### 9.3 Agent workspace 볼륨

Docker named volume의 루트가 `0:0 755`로 만들어지면 UID 1000의 `jenkins` 사용자가 작업 경로에 쓸 수 없어 Agent가 재시작한다.

먼저 볼륨을 만들고 **해당 Docker volume의 루트만** 소유자를 바꾼다. 호스트 `/home`에는 영향이 없고 재귀 변경도 하지 않는다.

```bash
sudo docker volume create \
  festa-jenkins-agents_linux_docker_workspace

sudo docker run --rm \
  --user root \
  --entrypoint chown \
  -v festa-jenkins-agents_linux_docker_workspace:/workspace \
  festa/jenkins-agent:local \
  1000:1000 /workspace
```

### 9.4 Agent 실행과 검증

```bash
sudo env \
  JENKINS_AGENT_SECRET_DEPLOY=not-used \
  JENKINS_AGENT_SECRET_UNITY=not-used \
  docker compose \
  --env-file infra/versions.env \
  --env-file infra/.env \
  -f infra/jenkins/agents/compose.yaml \
  --profile linux-docker \
  up -d --no-build linux-docker-agent
```

```bash
sudo docker ps -a \
  --filter name=festa-jenkins-agent-linux-docker
sudo docker logs --tail 50 \
  festa-jenkins-agent-linux-docker
```

Jenkins UI에서 `linux-docker`가 Online인지 확인한다. `deploy`, `unity`가 Offline인 것은 정상이다.

```bash
sudo docker exec \
  festa-jenkins-agent-linux-docker \
  docker version

sudo docker exec \
  festa-jenkins-agent-linux-docker \
  docker compose version
```

실제 확인 결과:

- Agent Docker Client: 29.1.3
- rootless Docker Server: 29.1.3
- Agent Docker Compose: v5.0.0
- Server 출력에 `rootlesskit`, `slirp4netns` 표시

---

## 10. GitLab 연동 예정 순서

### 10.1 Jenkins 전용 GitLab Token

GitLab 프로필의 **Access Tokens**에서 Jenkins 전용 토큰을 만든다.

- 이름 예시: `jenkins-api`
- Scope: `api`
- 만료일: 프로젝트 운영 기간을 넘는 최소 기간
- 실제 토큰 값은 생성 직후 Jenkins Credentials에만 등록

| ID | 종류 | 목적 |
|---|---|---|
| `gitlab-api` | GitLab API token | 연결 검사·Webhook 관리 |
| `gitlab-checkout` | Username with password | 저장소 checkout, password 자리에 전용 read token |
| `gitlab-webhook-secret` | Secret text | GitLab Webhook 요청 검증 |

API와 checkout Token은 역할을 분리한다. checkout Token에는 가능한 경우 `read_repository`만 준다.

세 Credential은 모두 **System → Global credentials**에 등록한다. JCasC는 Credential 저장소를 선언하지 않는다. 실제 값은 Jenkins가 `JENKINS_HOME`에 암호화해 보관하며 Controller 재시작 뒤에도 유지되어야 한다.

### 10.2 공개 Jenkins 주소 선행 조건

Webhook을 연결하기 전에 다음이 필요하다.

1. `ci.ssafesta.world` DNS가 EC2를 가리킴
2. Nginx가 `ci.ssafesta.world`를 `127.0.0.1:8080`으로 reverse proxy
3. HTTPS 인증서 적용
4. `JENKINS_PUBLIC_URL=https://ci.ssafesta.world/`
5. Controller 재기동 후 Jenkins Location과 GitLab hooks root URL 확인

Cloudflare Origin Certificate는 Cloudflare proxy를 통할 때 사용하는 인증서다. 인증서 파일 경로가 실제로 존재하는지 확인하지 않고 `.env`에 경로를 추정해 넣지 않는다.

### 10.3 애플리케이션 Secret과 분리

다음 값은 Jenkins Controller bootstrap용 `infra/.env`에 직접 넣지 않는다.

- PostgreSQL·Redis 계정과 비밀번호
- JWT·Connection Token Secret
- Google·Kakao OAuth Secret
- FastAPI → Spring 내부 토큰
- Backend·AI 애플리케이션 `.env`

이 값들은 별도의 Jenkins Secret file·Secret text Credential로 등록하고 Pipeline이 배포 시점에만 주입한다.

---

## 11. 장애 요약

| 증상 | 원인 | 해결 |
|---|---|---|
| Controller `unhealthy` | 폐기된 GitLab JCasC `secretToken` | Credential ID 방식으로 정본 수정 |
| foundation `Permission denied` | Shell 진입점 Git mode `100644` | 정본을 `100755`로 수정 |
| rootless Docker `operation not permitted` | static rootlesskit과 Ubuntu AppArmor 불일치 | 배포판 `/usr/bin/rootlesskit` 사용 |
| linux Agent만 실행하는데 다른 Secret 요구 | Compose가 비활성 profile도 보간 | 명령 프로세스에만 `not-used` 전달 |
| Agent 재시작, workDir RWX 오류 | named volume 루트가 `root:root 755` | 해당 volume 루트만 `1000:1000`으로 변경 |
| Controller 재시작 후 UI Credential 전체 삭제 | JCasC의 빈 `credentials: []`가 저장소를 덮어씀 | JCasC에서 Credential 저장소 선언 제거 |
| Jenkins UI는 열리지만 Webhook 불가 | localhost URL은 GitLab에서 접근 불가 | DNS·Nginx·HTTPS 뒤 공개 URL 적용 |

비활성 profile 보간과 named volume 초기 소유권은 정본 개선이 필요한 항목이다. 현재 workaround는 실행 검증됐지만 volume을 삭제·재생성하면 소유권 처리가 다시 필요하다.

---

## 12. 재부팅 후 점검

```bash
systemctl --user is-active docker
test -S /run/user/1000/docker.sock && echo "Rootless socket: OK"

sudo docker ps \
  --filter name=festa-jenkins-controller \
  --filter name=festa-jenkins-agent-linux-docker

sudo docker inspect --format '{{.State.Health.Status}}' \
  festa-jenkins-controller
```

Jenkins UI에서 Controller와 `linux-docker` Agent 상태를 확인한다. `restart: unless-stopped`와 user linger가 적용되어 있으므로 정상이라면 둘 다 자동 복구된다.

---

## 13. 다음 작업 체크리스트

- [x] Jenkins Controller 기동·로그인
- [x] rootless Docker 구성·자동 시작
- [x] `linux-docker` Agent Secret 저장
- [x] `linux-docker` Agent Online
- [x] Agent Docker·Compose 검증
- [ ] Credential persistence 수정 배포·재시작 검증
- [ ] GitLab API Token 생성
- [ ] GitLab Credentials 등록
- [ ] `ci.ssafesta.world` DNS·Nginx·HTTPS
- [ ] Jenkins Public URL 변경
- [ ] GitLab Webhook 연결
- [ ] Pipeline 생성
- [ ] 실제 CI 빌드
- [ ] demo CD 배포·Health Check
- [ ] 실패·롤백·알림 검증
