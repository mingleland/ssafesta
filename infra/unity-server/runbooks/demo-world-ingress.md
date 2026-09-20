# Demo world ingress 적용

Demo World 의 public endpoint 는 **`wss://demo.${ROOT_DOMAIN}/`** 이다 (Batch 1, 2026-09-20). `world.${ROOT_DOMAIN}` 은
Production World(27777) 전용이고 `world-dev.${ROOT_DOMAIN}` 은 dev(7777) 전용이다 — Demo 는 어느 쪽도 선언하지 않는다.

배경: Demo 가 `world.<root>` 를 Production 과 같이 선언해 nginx 가 Demo vhost 를 `conflicting server name … ignored` 로
버렸고, Demo 토큰이 Production World 에서 `INVALID_TOKEN` 으로 거절됐다. 전용 hostname(`world-demo`) 은 Cloudflare zone
권한이 없어 만들 수 없으므로 기존 `demo.<root>` 를 재사용한다. Unity 클라이언트(UnityTransport WebSocket)는 world-sessions 의
`endpoint{scheme,host,port}` 만 받아 **항상 루트 경로** 로 Upgrade 하므로, demo 사이트의 `location /` 이 Upgrade 헤더로
분기한다: WebSocket Upgrade → 17777, 그 외 → Front 18080. (`/api`·`/ws` 는 `api.<root>` 에 있어 이 host 와 겹치지 않는다.)

## 사전 조건

- `infra/.env`에 immutable `GAME_IMAGE_REF`, `CONNECTION_TOKEN_SECRET_FILE`, `ROOT_DOMAIN`, origin TLS 파일 경로가 있다.
- `DEMO_GAME_HOST_PORT=17777` — host loopback 전용, public 공개 금지.
- `DEMO_WORLD_HOST=demo.${ROOT_DOMAIN}` — deploy agent 가 readiness 에 넘기는 값.
- Demo Back 은 `infra/environments/compose/demo/back.yaml` 의 `WORLD_HOST: demo.${ROOT_DOMAIN}` 으로 토큰을 발급한다 (env-file 값보다 우선).
- DNS/TLS 는 기존 `demo.<root>` 것을 그대로 쓴다. 신규 레코드·인증서 없음.

## 적용

```bash
cd ~/festa/S15P21A604
set -a
source infra/.env
set +a

bash infra/unity-server/scripts/preflight.sh
docker compose --env-file infra/.env -f infra/unity-server/compose.yaml up -d --wait demo-game

# Nginx 변수($host, $request_uri, $http_upgrade)를 보존하도록 치환 목록을 제한한다.
envsubst '${ROOT_DOMAIN} ${NGINX_ORIGIN_CERTIFICATE_FILE} ${NGINX_ORIGIN_PRIVATE_KEY_FILE}' \
  < infra/environments/nginx/sites/demo.conf.template \
  > /tmp/demo.conf

sudo cp /etc/nginx/sites-available/demo.conf "/etc/nginx/sites-available/demo.conf.bak-$(date -u +%Y%m%dT%H%M%SZ)"
sudo install -m 0644 /tmp/demo.conf /etc/nginx/sites-available/demo.conf
# 예전 공유 host vhost(world.conf → world.<root>) 는 비활성화한다. world.<root> 는 world-prod.conf 만 가진다.
sudo rm -f /etc/nginx/sites-enabled/world.conf
sudo nginx -t
sudo systemctl reload nginx
```

설치 전에 template 문법만 보고 싶다면 `bash infra/environments/scripts/validate-nginx-template.sh infra/environments/nginx/sites/demo.conf.template` 를 쓴다.
**`nginx -t -c <렌더한 파일>` 을 직접 돌리지 않는다** — 그 명령은 설정의 `user` 로 `/var/lib/nginx` 의 temp 디렉터리를
chown 해서 살아 있는 서버의 POST 를 500 으로 만든다 (T-174). 위 절차의 `sudo nginx -t` 는 `-c` 없이 실설정을 읽으므로 안전하다.

`sudo nginx -T | grep -n 'server_name world'` 에 `world.${ROOT_DOMAIN}` 이 `world-prod.conf` 한 곳에만 있어야 하고
`conflicting server name` 경고가 없어야 한다. 라이브 `demo.conf` 가 snippet(`festa-unityweb.conf`, `festa-ai-v1.conf`) 을
include 하도록 손질돼 있다면 그 include 는 유지하고 `map` 두 개와 `location /` 만 template 과 같게 맞춘다.

### Rollback

`sudo install -m 0644 /etc/nginx/sites-available/demo.conf.bak-<stamp> /etc/nginx/sites-available/demo.conf` 후 `nginx -t`·reload.
이전 `world.conf` 를 되살리는 것은 Demo 가 다시 Production host 를 공유하는 상태이므로 정상 rollback 이 아니다.
Production `world-prod.conf`·`prod.conf` 는 어떤 경우에도 건드리지 않는다.

## 확인

```bash
sudo docker ps --format 'table {{.Names}}\t{{.Ports}}' | grep festa-demo-world-demo-game
curl --http1.1 -i --max-time 8 \
  -H 'Connection: Upgrade' \
  -H 'Upgrade: websocket' \
  -H 'Sec-WebSocket-Version: 13' \
  -H 'Sec-WebSocket-Key: SGVsbG8sIHdvcmxkIQ==' \
  "https://demo.${ROOT_DOMAIN}/"
curl -sS -o /dev/null -w '%{http_code}\n' "https://demo.${ROOT_DOMAIN}/"   # 일반 HTTPS 는 Front 200
```

Upgrade 요청은 `101 Switching Protocols`, 일반 요청은 Front 의 `200` 이어야 한다. `curl` 은 Upgrade 뒤 연결을 유지하다
timeout 으로 끝날 수 있으나 그 전에 101 이 있으면 성공이다.

readiness 는 host 를 명시해야 한다 (추론 없음, 미설정이면 exit 64). 검사는 실제 WebSocket endpoint 의 101 까지 본다 —
Demo World 17777 을 멈추면 nginx 가 502 를 내고 Demo readiness 는 FAIL, Front 와 Production 은 그대로다.

```bash
WORLD_PUBLIC_HOST="demo.${ROOT_DOMAIN}"      bash infra/unity-server/scripts/verify-public-wss.sh --output /tmp/demo-wss.txt   # Demo
WORLD_PUBLIC_HOST="world-dev.${ROOT_DOMAIN}" bash infra/unity-server/scripts/verify-public-wss.sh --output /tmp/dev-wss.txt    # Dev
```

Production 은 `infra/deploy/scripts/verify-production-public.sh`(`PRODUCTION_WORLD_HOST=world.${ROOT_DOMAIN}`) 가 검사한다.

## 전용 hostname 을 얻는 경우

`infra/unity-server/nginx/world.conf.template` 은 `${DEMO_WORLD_HOST}` 로 렌더하는 전용 vhost 변형이다. Cloudflare zone 에
`world-demo.<root>` 레코드를 만들 수 있게 되면 그 템플릿으로 별도 vhost 를 세우고 `DEMO_WORLD_HOST`·Demo Back `WORLD_HOST` 를
그 host 로 바꾼다. 그 전까지 이 파일은 사용하지 않는다.
