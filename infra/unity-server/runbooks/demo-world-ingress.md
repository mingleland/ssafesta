# Demo world ingress 적용

`world.${ROOT_DOMAIN}`는 `world-dev.${ROOT_DOMAIN}`와 별도 vhost다. demo host에 dev allowlist나 dev-game upstream을 넣지 않는다.

## 사전 조건

- `infra/.env`에 immutable `GAME_IMAGE_REF`, `CONNECTION_TOKEN_SECRET_FILE`, `ROOT_DOMAIN`, origin TLS 파일 경로가 있다.
- `DEMO_GAME_HOST_PORT=17777`을 `infra/.env`에 설정한다. 이 포트는 host loopback 전용이며 public 공개 금지다.

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

envsubst '${ROOT_DOMAIN} ${NGINX_ORIGIN_CERTIFICATE_FILE} ${NGINX_ORIGIN_PRIVATE_KEY_FILE} ${DEMO_GAME_HOST_PORT}' \
  < infra/unity-server/nginx/world.conf.template \
  > /tmp/world.conf

sudo install -m 0644 /tmp/world-dev.conf /etc/nginx/sites-available/world-dev.conf
sudo install -m 0644 /tmp/world.conf /etc/nginx/sites-available/world.conf
sudo ln -sfn /etc/nginx/sites-available/world.conf /etc/nginx/sites-enabled/world.conf
sudo nginx -t
sudo systemctl reload nginx
```

## 확인

```bash
sudo docker ps --format 'table {{.Names}}\t{{.Ports}}' | grep festa-demo-world-demo-game
curl --http1.1 -i --max-time 8 \
  -H 'Connection: Upgrade' \
  -H 'Upgrade: websocket' \
  -H 'Sec-WebSocket-Version: 13' \
  -H 'Sec-WebSocket-Key: SGVsbG8sIHdvcmxkIQ==' \
  "https://world.${ROOT_DOMAIN}/"
```

HTTP `101 Switching Protocols`가 보여야 한다. `curl`은 Upgrade 뒤 연결을 유지하다 timeout으로 끝날 수 있으나, 그 전에 101이 있으면 성공이다.
