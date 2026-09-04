# Backend dev CD 작업 가이드

> 관련 Jira: `S15P21A604-228`
>
> 기준일: 2026-09-04
>
> 대상 환경: 단일 EC2의 `dev` Backend

이 문서는 SSAFY FESTA Backend를 EC2에 처음 배포하면서 실제로 수행한 작업을 처음 보는 사람도 따라갈 수 있도록 정리한 기록이다. 명령은 별도 표시가 없으면 **MobaXterm으로 접속한 EC2 터미널**에서 실행한다.

실제 비밀번호, R2 Access Key, R2 Secret Key, 내부 통신 토큰, 공인 IP는 이 문서와 Git에 기록하지 않는다.

---

## 1. 지금 무엇을 하는가

한 문장으로 말하면 다음 작업이다.

> Jenkins CI가 검사하고 만든 Backend 이미지를 EC2의 PostgreSQL·Redis·R2와 연결하여 실제 Backend 컨테이너로 실행한다.

전체 흐름은 아래와 같다.

```text
GitLab develop 코드
  ↓
Jenkins CI
  ├─ Backend 테스트
  ├─ Backend JAR 생성
  └─ festa-back:<commit SHA> 이미지 생성
       ↓
EC2 배포용 Docker로 이미지 전달
       ↓
Backend 컨테이너 실행
  ├─ PostgreSQL 연결 및 Flyway 실행
  ├─ Redis ACL 인증
  ├─ Cloudflare R2 연결
  └─ /actuator/health 확인
```

### CI와 CD의 차이

| 구분 | 쉬운 설명 | 현재 상태 |
|---|---|---|
| CI | 코드가 제대로 만들어졌는지 시험하고 포장한다 | AI·Backend·Frontend 통과, Game은 Unity Agent 준비 필요 |
| 수동 첫 배포 | 사람이 명령을 실행해 포장된 Backend를 EC2에 처음 띄운다 | PostgreSQL·Redis와 이미지 준비 완료, Backend 기동 전 |
| 자동 CD | Jenkins가 사람이 하던 배포·검증·롤백을 자동 실행한다 | Deploy Agent·Pipeline 검증 전 |

따라서 현재 작업을 넓게 보면 Backend CD 작업이 맞다. 다만 지금은 **자동 CD를 만들기 전에 수동 첫 배포 경로를 검증하는 단계**다.

### 처음 나오는 단어

| 단어 | 이 문서에서의 뜻 |
|---|---|
| 이미지 | Backend 실행 파일과 실행에 필요한 도구를 한 상자에 포장한 것 |
| 컨테이너 | 이미지를 실제로 실행한 프로그램 |
| Compose | 여러 컨테이너의 실행 방법을 YAML 파일에 적고 함께 관리하는 도구 |
| Overlay | 공통 Compose 설정 위에 Backend 설정만 덧붙이는 작은 YAML 파일 |
| healthcheck | 컨테이너가 켜진 것뿐 아니라 요청에 답할 준비까지 됐는지 확인하는 검사 |
| DB Role | PostgreSQL에 로그인하고 정해진 DB만 사용하는 전용 계정 |
| Flyway | Backend 시작 시 필요한 테이블 변경을 순서대로 적용하는 도구 |
| Redis ACL | Redis 계정마다 사용할 수 있는 키와 명령을 제한하는 권한표 |
| R2 | 사진·PDF 같은 파일을 저장하는 Cloudflare의 파일 창고 |

---

## 2. 왜 먼저 수동으로 한 번 띄우는가

자동화는 사람이 성공시킨 명령을 Jenkins가 대신 실행하는 것이다. PostgreSQL, Redis, R2, 이미지, 네트워크가 실제로 연결되는지 모르는 상태에서 자동화부터 만들면 실패 원인을 찾기 어렵다.

그래서 순서를 다음처럼 잡는다.

1. PostgreSQL·Redis 데이터 저장소를 직접 실행한다.
2. Backend 이미지를 직접 준비한다.
3. Backend를 직접 실행하고 검증한다.
4. 성공한 명령을 Deploy Agent와 Pipeline에 연결한다.
5. 실패 시 이전 이미지로 돌아가는 롤백을 검증한다.

---

## 3. 구성 요소를 상자에 비유하기

```text
┌──────────────── EC2 ────────────────┐
│                                     │
│  rootless Docker                    │
│  └─ Jenkins CI가 만든 이미지 창고   │
│                                     │
│  rootful Docker                     │
│  ├─ PostgreSQL                      │
│  ├─ Redis                           │
│  └─ Backend                         │
│                                     │
│  /opt/festa/secrets                 │
│  └─ 실제 비밀번호와 환경변수        │
└─────────────────────────────────────┘
                 │
                 └─ Cloudflare R2 비공개 문서 버킷
```

### rootless와 rootful Docker

- `rootless Docker`는 일반 사용자 권한으로 실행한다. Jenkins `linux-docker` Agent가 CI 이미지 생성에 사용한다.
- `rootful Docker`는 `sudo docker`로 실행한다. 현재 PostgreSQL·Redis·Backend 배포에 사용한다.
- 두 Docker는 같은 EC2 안에 있어도 **이미지 창고와 네트워크가 서로 다르다.**
- Jenkins에서 `festa-back` 이미지를 만들었더라도 `sudo docker image ls`에 바로 나타나지 않는 이유가 이것이다.

현재는 CI 이미지를 `docker save | docker load`로 전달한다. 자동 CD에서는 Deploy Agent가 이 전달과 배포를 담당해야 한다.

---

## 4. 저장소의 핵심 파일

### 4-1. Backend dev Overlay

경로: `infra/environments/compose/dev/back.yaml`

아래 코드는 이해에 필요한 핵심 부분만 옮긴 것이다. 실제 실행에는 저장소의 원본 파일을 사용한다.

```yaml
services:
  back:
    image: ${COMPONENT_IMAGE_REF:?COMPONENT_IMAGE_REF is required}
    restart: unless-stopped
    profiles: [back]
    ports:
      - "127.0.0.1:${BACK_HOST_PORT:-8081}:8080"
    env_file:
      - ${COMPONENT_ENV_FILE:?COMPONENT_ENV_FILE is required}
    environment:
      FESTA_ENVIRONMENT: dev
      SPRING_PROFILES_ACTIVE: infra
      ROOT_DOMAIN: ${ROOT_DOMAIN:?ROOT_DOMAIN is required}
      POSTGRES_HOST: postgres
      POSTGRES_PORT: 5432
      POSTGRES_DB: festa_dev_business
      POSTGRES_USER: festa_dev_back_app
      REDIS_HOST: redis
      REDIS_PORT: 6379
      REDIS_USERNAME: dev_back
      INTERNAL_AI_TO_SPRING_TOKENS: ${INTERNAL_AI_TO_SPRING_TOKENS:?INTERNAL_AI_TO_SPRING_TOKENS is required}
    networks:
      dev-private:
        aliases: [back]
      data-private: {}
```

중요한 뜻은 다음과 같다.

- Backend 이미지는 커밋 SHA가 붙은 `COMPONENT_IMAGE_REF`로 받는다.
- 실제 Secret은 Git 파일이 아니라 `COMPONENT_ENV_FILE`에서 받는다.
- Backend 포트는 `127.0.0.1:8081`에만 열린다. 인터넷에 8080·8081을 직접 공개하지 않는다.
- PostgreSQL·Redis는 Docker 내부 이름인 `postgres`, `redis`로 찾는다.
- 로컬 기본 DB인 `ssafesta`를 재사용하지 않고 dev 전용 DB·Role을 사용한다.

### 4-2. PostgreSQL·Redis Data Compose

경로: `infra/environments/compose/data/compose.yaml`

아래 코드는 PostgreSQL·Redis·내부 네트워크에 관한 핵심 부분만 옮긴 것이다.

```yaml
services:
  postgres:
    image: pgvector/pgvector:0.8.1-pg17
    volumes:
      - postgres-data:/var/lib/postgresql/data
      - ../../postgres/init/00-databases-and-roles.sql:/docker-entrypoint-initdb.d/00-databases-and-roles.sql:ro
    networks:
      - data-private

  redis:
    image: redis:7.2.10-alpine
    volumes:
      - redis-data:/data
      - ../../redis/redis.conf:/usr/local/etc/redis/redis.conf:ro
      - ${REDIS_ACL_FILE:?REDIS_ACL_FILE is required}:/usr/local/etc/redis/users.acl:ro
    networks:
      - data-private

networks:
  data-private:
    name: festa-data-private
    internal: true
```

`internal: true`는 PostgreSQL과 Redis를 인터넷에 직접 공개하지 않는 잠금장치다. Backend처럼 같은 Docker 네트워크에 들어온 서비스만 접근할 수 있다.

### 4-3. PostgreSQL 초기화 SQL

경로: `infra/environments/postgres/init/00-databases-and-roles.sql`

Backend가 사용할 dev DB와 Role은 다음과 같다.

```sql
CREATE DATABASE festa_dev_business OWNER festa_dev_back_app;

\connect festa_dev_business
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO festa_dev_back_app;
CREATE EXTENSION IF NOT EXISTS vector;
```

Backend는 시작할 때 Flyway `V1`부터 현재 버전까지 직접 실행한다. 따라서 `festa_dev_back_app`에는 대상 스키마 DDL 권한이 필요하고, 관리자 초기화 단계에서 `vector` 확장이 먼저 만들어져야 한다.

### 4-4. Redis ACL

경로: `infra/environments/redis/users.acl.example`

```text
user default off
user health on >CHANGE_ME_HEALTH ~* +ping
user dev_back on >CHANGE_ME_DEV_BACK ~dev:* +@read +@write -@dangerous
user dev_ai on >CHANGE_ME_DEV_AI ~dev:ai:* +@read +@write -@dangerous
user demo_back on >CHANGE_ME_DEMO_BACK ~demo:* +@read +@write -@dangerous
user demo_ai on >CHANGE_ME_DEMO_AI ~demo:ai:* +@read +@write -@dangerous
```

`dev_back`은 `dev:`로 시작하는 키만 읽고 쓸 수 있다. Spring이 현재 사용하는 `dev:auth:*`, `dev:wallet:*`를 포함하며, demo의 `demo:*`에는 접근할 수 없다.

---

## 5. Secret은 어디에 저장하는가

실제 Secret은 아래 두 종류로 나눈다.

```text
infra/.env
  ├─ Compose가 해석할 공통 설정
  ├─ Secret 파일 경로
  ├─ ROOT_DOMAIN
  └─ INTERNAL_AI_TO_SPRING_TOKENS

/opt/festa/secrets/
  ├─ dev-back.env
  ├─ postgres-admin-password
  ├─ postgres-dev-back-password
  ├─ postgres-dev-ai-password
  ├─ postgres-demo-back-password
  ├─ postgres-demo-ai-password
  ├─ redis-health-password
  └─ redis-users.acl
```

### `/opt/festa/secrets/dev-back.env` 예시

아래는 변수 이름만 보여주는 예시다. `...`를 Git에 커밋하거나 실제값을 채운 파일을 공유하지 않는다.

```dotenv
POSTGRES_PASSWORD=...
REDIS_PASSWORD=...
JWT_SECRET=...
CONNECTION_TOKEN_SECRET=...

FRONTEND_BASE_URL=...
AUTH_COOKIE_SECURE=false

GOOGLE_CLIENT_ID=...
GOOGLE_CLIENT_SECRET=...
GOOGLE_REDIRECT_URI=...
KAKAO_REST_API_KEY=...
KAKAO_CLIENT_SECRET=...
KAKAO_REDIRECT_URI=...

AI_STORAGE_UPLOAD_GATE=OPEN
AI_STORAGE_ACTIVE_WRITE_PROVIDER=R2
R2_ENDPOINT=https://ACCOUNT_ID.r2.cloudflarestorage.com
R2_BUCKET=festa-documents
R2_ACCESS_KEY_ID=...
R2_SECRET_ACCESS_KEY=...

MINIO_ENDPOINT=
MINIO_BUCKET=
MINIO_ACCESS_KEY_ID=
MINIO_SECRET_ACCESS_KEY=
```

dev에서 OAuth 로그인을 검증하지 않더라도 OAuth 변수 6개는 빈값으로 두면 안 된다. Backend 설정에 기본값이 없어서 기동 자체가 실패한다. 실제 Secret을 dev에 배포할 필요가 없다면 dummy 문자열을 넣고, OAuth 최종 검증은 demo HTTPS에서 수행한다.

### `infra/.env`에 연결하는 파일 경로

```dotenv
POSTGRES_ADMIN_PASSWORD_FILE=/opt/festa/secrets/postgres-admin-password
POSTGRES_DEV_BACK_PASSWORD_FILE=/opt/festa/secrets/postgres-dev-back-password
POSTGRES_DEV_AI_PASSWORD_FILE=/opt/festa/secrets/postgres-dev-ai-password
POSTGRES_DEMO_BACK_PASSWORD_FILE=/opt/festa/secrets/postgres-demo-back-password
POSTGRES_DEMO_AI_PASSWORD_FILE=/opt/festa/secrets/postgres-demo-ai-password
REDIS_ACL_FILE=/opt/festa/secrets/redis-users.acl
REDIS_HEALTH_PASSWORD_FILE=/opt/festa/secrets/redis-health-password
REDIS_HEALTH_USER=health
ROOT_DOMAIN=ssafesta.world
INTERNAL_AI_TO_SPRING_TOKENS=...
```

`INTERNAL_AI_TO_SPRING_TOKENS`는 Backend 개발자에게 전달할 필요가 없다. Infra가 Backend와 AI 컨테이너에 같은 값을 주입한다.

---

## 6. Cloudflare R2 설정

현재 Backend 기동에는 AI 원본 문서용 비공개 버킷이 필요하다.

1. Cloudflare 대시보드에서 `Storage & databases` → `R2 Object Storage`로 이동한다.
2. `festa-documents` 버킷을 생성한다.
3. Public access와 `r2.dev` 공개 주소는 활성화하지 않는다.
4. Account API Token을 생성한다.
5. 권한은 `Object Read & Write`, 대상은 `festa-documents` 버킷 하나로 제한한다.
6. 가능하면 Client IP Address Filtering의 Include에 EC2 공인 IPv4 `/32`만 등록한다.
7. S3 API Endpoint, Access Key ID, Secret Access Key를 `dev-back.env`에 저장한다.

`festa-postgres-backups`는 DB 백업용 별도 비공개 버킷이다. Backup writer·Restore reader 자격증명은 문서 버킷 자격증명과 분리한다.

CORS는 Frontend의 정확한 Origin이 확정된 뒤 설정한다. `*`를 쓰지 않고 승인된 Origin과 `PUT`만 허용한다. CORS가 없어도 Backend 기동은 가능하지만 브라우저의 R2 직접 업로드 검증은 아직 완료된 것이 아니다.

---

## 7. 실제 수행 순서

### 7-1. 최신 코드 받기

MR 병합 후 실행한다.

```bash
cd ~/festa/S15P21A604
git pull --ff-only origin develop
git log -1 --oneline
```

Overlay가 들어왔는지 확인한다.

```bash
test -f infra/environments/compose/dev/back.yaml \
  && echo "dev back overlay: OK" \
  || echo "dev back overlay: MISSING"
```

### 7-2. 비공개 디렉터리 만들기

```bash
sudo install -d -m 700 /opt/festa/secrets
sudo install -m 600 /dev/null /opt/festa/secrets/dev-back.env
sudo nano /opt/festa/secrets/dev-back.env
```

파일 저장 후 실제값을 출력하지 않고 누락 여부만 확인한다.

```bash
sudo bash -c '
for key in \
  POSTGRES_PASSWORD REDIS_PASSWORD JWT_SECRET CONNECTION_TOKEN_SECRET \
  FRONTEND_BASE_URL AUTH_COOKIE_SECURE \
  GOOGLE_CLIENT_ID GOOGLE_CLIENT_SECRET GOOGLE_REDIRECT_URI \
  KAKAO_REST_API_KEY KAKAO_CLIENT_SECRET KAKAO_REDIRECT_URI \
  AI_STORAGE_UPLOAD_GATE AI_STORAGE_ACTIVE_WRITE_PROVIDER \
  R2_ENDPOINT R2_BUCKET R2_ACCESS_KEY_ID R2_SECRET_ACCESS_KEY
do
  grep -qE "^${key}=.+" /opt/festa/secrets/dev-back.env \
    && echo "$key: SET" \
    || echo "$key: MISSING"
done
'
```

### 7-3. Data Secret 파일 권한 준비

PostgreSQL과 Redis 공식 이미지는 컨테이너 안에서 UID 999로 동작한다. 호스트 파일이 `root:root`, mode `600`이면 컨테이너 프로세스가 읽을 수 없다.

따라서 Data Compose를 처음 기동하기 전에 해당 파일만 UID 999가 읽도록 설정한다.

```bash
sudo chown 999:999 \
  /opt/festa/secrets/postgres-admin-password \
  /opt/festa/secrets/postgres-dev-back-password \
  /opt/festa/secrets/postgres-dev-ai-password \
  /opt/festa/secrets/postgres-demo-back-password \
  /opt/festa/secrets/postgres-demo-ai-password \
  /opt/festa/secrets/redis-users.acl

sudo chmod 600 \
  /opt/festa/secrets/postgres-admin-password \
  /opt/festa/secrets/postgres-dev-back-password \
  /opt/festa/secrets/postgres-dev-ai-password \
  /opt/festa/secrets/postgres-demo-back-password \
  /opt/festa/secrets/postgres-demo-ai-password \
  /opt/festa/secrets/redis-users.acl
```

`dev-back.env`는 계속 `root:root`, mode `600`으로 유지한다.

### 7-4. Data Compose 설정 검사와 기동

```bash
cd ~/festa/S15P21A604

sudo docker compose \
  --env-file infra/versions.env \
  --env-file infra/.env \
  -f infra/environments/compose/data/compose.yaml \
  config --quiet

sudo docker compose \
  --env-file infra/versions.env \
  --env-file infra/.env \
  -f infra/environments/compose/data/compose.yaml \
  up -d
```

상태 확인:

```bash
sudo docker compose \
  --env-file infra/versions.env \
  --env-file infra/.env \
  -f infra/environments/compose/data/compose.yaml \
  ps
```

기대 결과:

```text
festa-data-postgres-1   Up ... (healthy)
festa-data-redis-1      Up ... (healthy)
```

### 7-5. PostgreSQL 검증

Business DB의 `vector` 확장을 확인한다.

```bash
sudo docker exec festa-data-postgres-1 \
  sh -c 'PGPASSWORD="$(cat /run/secrets/postgres_admin_password)" \
  psql -U festa_admin -d festa_dev_business -c "\dx vector"'
```

Backend Role의 DDL 권한을 실제 데이터가 남지 않는 트랜잭션으로 확인한다.

```bash
sudo docker exec festa-data-postgres-1 \
  sh -c 'PGPASSWORD="$(cat /run/secrets/postgres_dev_back_password)" \
  psql -U festa_dev_back_app -d festa_dev_business \
  -c "BEGIN; CREATE TABLE infra_permission_probe (id integer); ROLLBACK;"'
```

기대 결과:

```text
BEGIN
CREATE TABLE
ROLLBACK
```

### 7-6. Redis ACL 검증

```bash
sudo docker exec festa-data-redis-1 sh -c 'password=$(sed -n "s/^user dev_back on >\([^ ]*\).*/\1/p" /usr/local/etc/redis/users.acl); redis-cli --user dev_back --pass "$password" SET dev:infra:probe ok EX 60; redis-cli --user dev_back --pass "$password" DEL dev:infra:probe'
```

기대 결과:

```text
OK
1
```

`Using a password ... may not be safe`는 `redis-cli` 인자 사용에 대한 경고다. 실제 실패 여부는 `OK`, `1`로 판단한다. 위 명령은 60초 TTL 임시 키를 만들고 바로 삭제한다.

### 7-7. Jenkins CI 이미지를 배포용 Docker로 전달

Jenkins Agent의 rootless Docker에서 Backend 이미지 태그를 찾는다.

```bash
sudo docker exec festa-jenkins-agent-linux-docker \
  docker image ls --filter 'reference=festa-back:*' \
  --format '{{.Repository}}:{{.Tag}}  {{.CreatedAt}}'
```

현재 배포하려는 `develop` 커밋과 일치하는 40자리 SHA 태그를 선택한다. 출력에서 전체 이미지 이름을 복사해 `BACKEND_IMAGE`에 한 번 저장한다.

```bash
BACKEND_IMAGE='festa-back:여기에_40자리_SHA_입력'
echo "$BACKEND_IMAGE"
```

`여기에_40자리_SHA_입력`을 실제 값으로 바꾸지 않으면 다음 명령이 이미지를 찾지 못하고 안전하게 실패한다.

```bash
sudo docker exec festa-jenkins-agent-linux-docker \
  docker save "$BACKEND_IMAGE" \
  | sudo docker load
```

배포용 rootful Docker에 들어왔는지 확인한다.

```bash
sudo docker image inspect \
  "$BACKEND_IMAGE" \
  --format '{{.Id}}'
```

`sha256:...`가 나오면 전달 성공이다.

---

## 8. 다음 단계: Backend 컨테이너 기동

> 아래 단계는 2026-09-04 현재 아직 실환경 검증 전이다. 실행 후 결과를 이 문서에 갱신한다.

먼저 Compose가 만들어 낼 최종 설정을 검사한다.

```bash
cd ~/festa/S15P21A604

sudo env \
  COMPONENT_IMAGE_REF="$BACKEND_IMAGE" \
  COMPONENT_ENV_FILE=/opt/festa/secrets/dev-back.env \
  docker compose \
  --env-file infra/versions.env \
  --env-file infra/.env \
  -f infra/environments/compose/dev/base.yaml \
  -f infra/environments/compose/dev/back.yaml \
  --profile back \
  config --quiet
```

검사가 성공하면 Backend만 기동한다.

```bash
sudo env \
  COMPONENT_IMAGE_REF="$BACKEND_IMAGE" \
  COMPONENT_ENV_FILE=/opt/festa/secrets/dev-back.env \
  docker compose \
  --env-file infra/versions.env \
  --env-file infra/.env \
  -f infra/environments/compose/dev/base.yaml \
  -f infra/environments/compose/dev/back.yaml \
  --profile back \
  up -d back
```

상태와 로그를 확인한다.

```bash
sudo docker ps --filter name=festa-dev-back
sudo docker logs --tail 120 festa-dev-back-1
curl -fsS http://127.0.0.1:8081/actuator/health
```

완료 기준:

- Backend 컨테이너가 `healthy`다.
- Flyway가 현재 migration까지 성공한다.
- `curl` 응답의 상태가 `UP`이다.
- PostgreSQL 인증 오류가 없다.
- Redis ACL 인증 오류가 없다.
- R2 설정 검증 오류가 없다.

---

## 9. 실제 발생한 문제와 해결

### 9-1. Redis가 계속 재시작함

증상:

```text
Aborting Redis startup because of ACL errors:
Error loading ACLs, opening file '/usr/local/etc/redis/users.acl': Permission denied
```

원인:

- ACL 파일은 `root:root`, mode `600`이었다.
- Redis 프로세스는 UID 999로 실행됐다.
- 파일 내용이 틀린 것이 아니라 파일을 읽을 권한이 없었다.

해결:

```bash
sudo chown 999:999 /opt/festa/secrets/redis-users.acl
sudo chmod 600 /opt/festa/secrets/redis-users.acl
sudo docker restart festa-data-redis-1
```

### 9-2. PostgreSQL은 healthy인데 Business DB가 없음

증상:

```text
FATAL: database "festa_dev_business" does not exist
```

초기화 로그:

```text
could not open file "/run/secrets/postgres_dev_back_password" for reading: Permission denied
PostgreSQL Database directory appears to contain a database; Skipping initialization
```

원인:

1. PostgreSQL 초기화 SQL이 UID 999 권한으로 Secret 파일을 읽지 못했다.
2. Role 네 개만 생성된 시점에 초기화가 중단됐다.
3. PostgreSQL은 기본 DB만 있어도 healthy가 됐다.
4. 실패한 볼륨이 남아 재시작 때 초기화 SQL을 건너뛰었다.

이 사례에서는 Business DB 생성 전 처음 설치가 실패했고 실제 데이터가 없음을 확인한 뒤, 정확히 `festa-data-postgres` 볼륨만 제거해 재초기화했다. 운영 데이터가 있는 볼륨에는 이 절차를 사용하면 안 된다.

### 9-3. `festa-back image: MISSING`

원인:

- Jenkins CI 이미지는 rootless Docker에 있었다.
- `sudo docker image ls`는 rootful Docker만 조회했다.

해결:

```bash
sudo docker exec festa-jenkins-agent-linux-docker \
  docker save "$BACKEND_IMAGE" \
  | sudo docker load
```

새로 다시 빌드하지 않고 CI가 검증한 동일 이미지를 전달했다.

---

## 10. 현재 완료 상태

2026-09-04 기준:

| 항목 | 상태 |
|---|---|
| Backend dev Overlay | 완료·develop 병합 |
| PostgreSQL 17 + pgvector | healthy |
| `festa_dev_business`·`festa_dev_back_app` | 생성·권한 검증 완료 |
| Business DB `vector` 확장 | 0.8.1 확인 완료 |
| Redis 7.2 ACL | healthy·`dev_back` 쓰기/삭제 검증 완료 |
| Cloudflare R2 문서 버킷·dev 토큰 | 생성·EC2 주입 완료 |
| CI Backend 이미지 | rootful Docker 전달 완료 |
| Backend 컨테이너 | 기동 전 |
| Backend health/Flyway/R2 실연결 | 검증 전 |
| Nginx dev API 경로 | 실제 적용·외부 검증 전 |
| Jenkins Deploy Agent | Online·권한 검증 전 |
| Jenkins 자동 배포·롤백 | 검증 전 |

즉, **Backend dev 수동 첫 배포 준비는 약 70%**이며, 실제 Backend 기동과 health 검증이 다음 작업이다. 수동 배포가 성공해도 Jenkins 자동 CD가 완료된 것은 아니다.

---

## 11. 자동 CD까지 남은 일

1. Backend 컨테이너를 수동 기동하고 health를 확인한다.
2. Flyway·Redis·R2 연결을 실제 로그와 기능으로 확인한다.
3. Nginx의 승인 IP 전용 dev API 경로를 적용한다.
4. Deploy Agent Secret과 필요한 Credential을 등록한다.
5. Deploy Agent가 rootful 배포 경로를 안전하게 사용할 방법을 확정한다.
6. Jenkins Pipeline에서 CI 이미지 전달·Compose 배포·health 검증을 자동화한다.
7. 실패한 배포가 이전 정상 이미지로 돌아가는지 롤백을 검증한다.
8. 성공·실패 결과를 Jira와 작업일지에 남긴다.

자동화의 핵심은 새로운 명령을 만들어 내는 것이 아니라, 이 문서에서 수동으로 성공한 명령을 Jenkins Credential과 승인된 Deploy Agent 경계 안에서 그대로 실행하는 것이다.
