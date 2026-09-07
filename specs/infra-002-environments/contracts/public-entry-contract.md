# Public Entry Contract v1

## External routes

| Entry | External address | Nginx upstream | Cache | Owner |
|---|---|---|---|---|
| demo web | `https://demo.${ROOT_DOMAIN}` | demo front/static release | HTML revalidate, content-hash asset immutable | infra-002 |
| backend | `https://api.${ROOT_DOMAIN}` | demo Spring | bypass/no-store | infra-002 |
| AI/SSE | `https://ai.${ROOT_DOMAIN}` | demo FastAPI | bypass/no-store, proxy buffering off | infra-002 + AI |
| game | `wss://world.${ROOT_DOMAIN}:443` | `ws://demo-game:7777` | bypass, Upgrade forwarding | infra-002 ingress; infra-003 final validation |
| dev | `http://${EC2_PUBLIC_IP}/__dev/{front|api|ai}` | selected dev HTTP service | bypass except explicit static test | infra-002 |
| dev world | `wss://world-dev.${ROOT_DOMAIN}:443` | selected dev game service | always bypass/no-store | infra-002 |

`ROOT_DOMAIN`과 `EC2_PUBLIC_IP`는 runtime/preflight input이다. client build에 실제 값을 고정하지 않는다. Unity game endpoint는 world-sessions API 응답으로만 전달한다.

## Required network path

```text
Browser → Cloudflare DNS/Proxy → EC2 Nginx:443 → Docker internal service
```

- Nginx만 host 80/443에 bind한다. 80은 최종 demo에서 443으로 redirect한다.
- SSH 22는 승인된 source 범위에만 허용한다.
- PostgreSQL 5432, Redis 6379, Unity 7777, Jenkins 8080, MinIO 9000/9001과 관측 port는 public bind하지 않는다.
- Cloudflare → origin은 Full (strict) TLS다. 인증서 검증을 끄는 origin fallback을 금지한다.
- ALB·NLB·ACM은 이 계약의 구성요소가 아니다.

## Cache invariants

- version/content-hash asset: `public, max-age`와 `immutable` 허용.
- HTML entry: 새 release와 구 asset이 섞이지 않도록 no-cache 또는 짧은 revalidation.
- `/api`, auth/cookie response, SSE, presigned URL payload, document grant, WebSocket: cache bypass와 `no-store`.
- response에 `Set-Cookie`, `text/event-stream`, `Upgrade`가 있으면 static cache rule보다 bypass가 우선한다.
- signed R2 upload는 R2 S3 API domain으로 직접 전송하며 Cloudflare custom domain cache를 통과하지 않는다.

## Dev limitations

IP 기반 dev는 파트 기능과 Nginx routing을 제한적으로 검증하는 경로다. HTTPS, Secure Cookie, social OAuth callback, Cloudflare cache, 최종 WSS의 완료 증거로 사용할 수 없다. 필요하면 Nginx source allowlist 또는 별도 access control을 적용한다.

## Origin validation

Cloudflare 장애 시 운영자는 동일 demo hostname을 EC2 IP로 강제하는 `curl --resolve` 방식으로 origin을 검증한다. 이 경로는 certificate·Host routing을 유지하며 일반 사용자용 상시 우회 endpoint가 아니다.

## Evidence

- 외부/내부 port scan 결과
- DNS resolution과 TLS chain/expiry
- static first/repeat request의 Cloudflare cache status와 origin request count
- dynamic endpoint의 BYPASS/no-store
- WebSocket Upgrade handshake; heartbeat/idle timeout 최종 수치는 infra-003 증거에 연결

