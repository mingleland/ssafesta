# Demo world ingress 적용

Demo World 의 public host 는 `world-demo.${ROOT_DOMAIN}` 이다 (Batch 1). `world.${ROOT_DOMAIN}` 은 Production World(27777),
`world-dev.${ROOT_DOMAIN}` 은 dev(7777) 의 vhost 이며 서로 host 를 공유하지 않는다. 2026-09-20 에 Demo 가 `world.<root>` 를
Production 과 같이 선언해 nginx 가 Demo vhost 를 `conflicting server name … ignored` 로 버렸고, Demo 토큰이 Production World
에서 `INVALID_TOKEN` 으로 거절됐다. demo host 에 dev allowlist 나 dev-game upstream 을 넣지 않는다.

## 사전 조건

- `infra/.env`에 immutable `GAME_IMAGE_REF`, `CONNECTION_TOKEN_SECRET_FILE`, `ROOT_DOMAIN`, origin TLS 파일 경로가 있다.
- `DEMO_GAME_HOST_PORT=17777`을 `infra/.env`에 설정한다. 이 포트는 host loopback 전용이며 public 공개 금지다.
- `DEMO_WORLD_HOST=world-demo.${ROOT_DOMAIN}` 을 `infra/.env`에 설정한다 (deploy agent 도 같은 값을 읽는다).
- DNS: `world-demo.${ROOT_DOMAIN}` 레코드가 `world-dev` 와 같은 origin 으로 Cloudflare proxied 로 존재한다 (MANUAL OPERATOR ACTION).
- TLS: origin 인증서 SAN 이 `*.${ROOT_DOMAIN}` 을 포함하는지 `openssl x509 -in <origin.pem> -noout -ext subjectAltName` 으로 확인한다. 포함되면 발급하지 않는다.
- Demo Back 은 `infra/environments/compose/demo/back.yaml` 의 `WORLD_HOST: world-demo.${ROOT_DOMAIN}` 으로 토큰을 발급한다 (env-file 값보다 우선).

## 적용

```bash
cd ~/festa/S15P21A604
set -a
source infra/.env
set +a

bash infra/unity-server/scripts/preflight.sh
docker compose --env-file infra/.env -f infra/unity-server/compose.yaml up -d --wait demo-game
```

다음 렌더는 Nginx 변수(`$host`, `$request_uri`, `$http_upgrade`)를 보존하도록 환경변수 목록을 제한한다.

```bash
envsubst '${ROOT_DOMAIN} ${NGINX_ORIGIN_CERTIFICATE_FILE} ${NGINX_ORIGIN_PRIVATE_KEY_FILE}' \
  < infra/environments/nginx/sites/world-dev.conf.template \
  > /tmp/world-dev.conf

envsubst '${DEMO_WORLD_HOST} ${NGINX_ORIGIN_CERTIFICATE_FILE} ${NGINX_ORIGIN_PRIVATE_KEY_FILE} ${DEMO_GAME_HOST_PORT}' \
  < infra/unity-server/nginx/world.conf.template \
  > /tmp/world-demo.conf

sudo install -m 0644 /tmp/world-dev.conf /etc/nginx/sites-available/world-dev.conf
sudo install -m 0644 /tmp/world-demo.conf /etc/nginx/sites-available/world-demo.conf
sudo ln -sfn /etc/nginx/sites-available/world-demo.conf /etc/nginx/sites-enabled/world-demo.conf
# 예전 공유 host vhost 는 비활성화한다 (Production 의 world-prod.conf 만 world.<root> 를 가진다).
sudo rm -f /etc/nginx/sites-enabled/world.conf
sudo nginx -t
sudo systemctl reload nginx
```

`sudo nginx -T | grep -n 'server_name world'` 에 `world.${ROOT_DOMAIN}` 이 `world-prod.conf` 한 곳에만 있어야 하고 `conflicting server name` 경고가 없어야 한다.

### Rollback

`sudo rm -f /etc/nginx/sites-enabled/world-demo.conf` 후 `nginx -t`·reload. 이전 `world.conf` 를 되살리는 것은 Demo 가 다시 Production host 를 공유하는 상태로 돌아가는 것이므로 정상 rollback 이 아니다 — Production `world-prod.conf` 는 어떤 경우에도 건드리지 않는다.

## 확인

```bash
sudo docker ps --format 'table {{.Names}}\t{{.Ports}}' | grep festa-demo-world-demo-game
curl --http1.1 -i --max-time 8 \
  -H 'Connection: Upgrade' \
  -H 'Upgrade: websocket' \
  -H 'Sec-WebSocket-Version: 13' \
  -H 'Sec-WebSocket-Key: SGVsbG8sIHdvcmxkIQ==' \
  "https://${DEMO_WORLD_HOST}/"
```

HTTP `101 Switching Protocols`가 보여야 한다. `curl`은 Upgrade 뒤 연결을 유지하다 timeout으로 끝날 수 있으나, 그 전에 101이 있으면 성공이다.

readiness 검사는 host 를 명시해야 한다 (추론 없음, 미설정이면 exit 64):

```bash
ROOT_DOMAIN=... WORLD_PUBLIC_HOST="world-demo.${ROOT_DOMAIN}" bash infra/unity-server/scripts/verify-public-wss.sh --output /tmp/demo-wss.txt   # Demo
ROOT_DOMAIN=... WORLD_PUBLIC_HOST="world-dev.${ROOT_DOMAIN}"  bash infra/unity-server/scripts/verify-public-wss.sh --output /tmp/dev-wss.txt    # Dev
```

Production 은 `infra/deploy/scripts/verify-production-public.sh`(`PRODUCTION_WORLD_HOST=world.${ROOT_DOMAIN}`) 가 검사한다.
