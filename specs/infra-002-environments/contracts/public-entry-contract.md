# Public Entry Contract v1

## External routes

| Entry | External address | Nginx upstream | Cache | Owner |
|---|---|---|---|---|
| demo web | `https://demo.${ROOT_DOMAIN}` | demo front/static release | HTML revalidate, content-hash asset immutable | infra-002 |
| backend | `https://api.${ROOT_DOMAIN}` | demo Spring | bypass/no-store | infra-002 |
| AI/SSE | `https://ai.${ROOT_DOMAIN}` | demo FastAPI | bypass/no-store, proxy buffering off | infra-002 + AI |
| game | `wss://world.${ROOT_DOMAIN}:443` | `ws://127.0.0.1:${DEMO_GAME_HOST_PORT:-17777}` | bypass, Upgrade forwarding | infra-002 ingress; infra-003 final validation |
| dev | `https://dev.${ROOT_DOMAIN}{/,api/,ai/v1/,unity/}` | selected dev Front/Spring/FastAPI and shared WebGL `current` | API/AI bypass/no-store; WebGL HTML revalidates and hashed Build assets are immutable | infra-002 |
| dev world | `wss://world-dev.${ROOT_DOMAIN}:443` | selected dev game service | always bypass/no-store | infra-002 |

`ROOT_DOMAIN`과 `EC2_PUBLIC_IP`는 runtime/preflight input이다. client build에 실제 값을 고정하지 않는다. Unity game endpoint는 world-sessions API 응답으로만 전달한다.

## Required network path

```text
Browser → Cloudflare DNS/Proxy → EC2 Nginx:443 → loopback-only demo-game listener
```

- Nginx만 public host 80/443에 bind한다. demo-game 7777은 `127.0.0.1:${DEMO_GAME_HOST_PORT:-17777}`로만 publish하고, 80은 최종 demo에서 443으로 redirect한다.
- SSH 22는 승인된 source 범위에만 허용한다.
- PostgreSQL 5432, Redis 6379, Unity 7777, Jenkins 8080과 관측 port는 public bind하지 않는다.
- Cloudflare → origin은 Full (strict) TLS다. 인증서 검증을 끄는 origin fallback을 금지한다.
- ALB·NLB·ACM은 이 계약의 구성요소가 아니다.

## Cache invariants

- version/content-hash asset: `public, max-age`와 `immutable` 허용.
- HTML entry: 새 release와 구 asset이 섞이지 않도록 no-cache 또는 짧은 revalidation.
- `/api`, auth/cookie response, SSE, presigned URL payload, document grant, WebSocket: cache bypass와 `no-store`.
- response에 `Set-Cookie`, `text/event-stream`, `Upgrade`가 있으면 static cache rule보다 bypass가 우선한다.
- signed R2 upload는 R2 S3 API domain으로 직접 전송하며 Cloudflare custom domain cache를 통과하지 않는다.

## Dev limitations

승인된 source의 HTTPS dev는 Front·API·AI·WebGL 정적 경로와 Secure Cookie·social OAuth callback 계약을 검증하는 제한 경로다. WebGL은 demo와 같은 `/srv/festa/webgl/current` release를 공유한다. demo promotion의 전체 사용자 여정과 최종 WSS 완료 증거는 별도로 남긴다. Nginx source allowlist는 계속 적용한다.

## Origin validation

Cloudflare 장애 시 운영자는 동일 demo hostname을 EC2 IP로 강제하는 `curl --resolve` 방식으로 origin을 검증한다. 이 경로는 certificate·Host routing을 유지하며 일반 사용자용 상시 우회 endpoint가 아니다.

## Evidence

- 외부/내부 port scan 결과
- DNS resolution과 TLS chain/expiry
- static first/repeat request의 Cloudflare cache status와 origin request count
- dynamic endpoint의 BYPASS/no-store
- WebSocket Upgrade handshake; heartbeat/idle timeout 최종 수치는 infra-003 증거에 연결

