# Implementation Plan: Unity Dedicated Server 외부 배포

**Branch**: `infra-003-unity-server-deploy` | **Date**: 2026-08-24 | **Spec**: [spec.md](./spec.md)

## Summary

x86_64 단일 EC2의 demo 환경에 `11F-01` Unity Dedicated Server 하나를 배포하고 `Cloudflare → Nginx:443 → demo-game:7777` 외부 WSS 경로를 검증한다. Backend는 120초 HS256 월드 입장 JWT를 전용 Secret으로 발급하고 Unity 서버는 이를 자체 검증한다. 사용된 토큰은 game 전용 영속 볼륨에 만료까지 기록해 컨테이너 교체 뒤에도 재사용을 차단한다. OCI Ampere A1 ARM64는 WebGL 정적 배포 검증에만 사용한다.

## Technical Context

**Language/Version**: Java 21, Spring Boot 4.1, C# / Unity 6000.0.78f1, YAML/Nginx/shell

**Primary Dependencies**: Spring Security Nimbus JWT, Netcode for GameObjects 2.4.3, Unity Transport 2.5.1, Docker Compose v2, Nginx, Cloudflare DNS/Proxy

**Storage**: game 전용 named volume의 append-only used-grant ledger; Redis는 정확성 저장소로 사용하지 않음

**Testing**: Maven/Spring integration tests, Unity EditMode tests, Compose/Nginx 정적 검사, 외부 WSS 브라우저·Unity runner 실측

**Target Platform**: Ubuntu x86_64 단일 EC2, Linux x86_64 Dedicated Server container, Unity WebGL browser client

**Project Type**: Spring API + Unity client/server + single-host infrastructure

**Performance Goals**: P0 외부 브라우저 2개 10분 무입력 유지, P1 단일 채널 목표 40명

**Constraints**: 공개 포트는 80/443만, game 7777 내부 전용, ALB/NLB/ACM/ECS 없음, game-only 배포, Secret/토큰 원문 기록 금지, Unity 6000.0.78f1 Linux Server는 x86_64 전용, ARM64 에뮬레이션·임시 엔진 업그레이드 금지

**Scale/Scope**: `11F` 단일 채널 `11F-01`, Dedicated Server 1개, 최대 40명

## Constitution Check

| 조항 | 판정 |
|---|---|
| 3·7조 단일 EC2 Docker 경계 | PASS — 기존 Docker 산출물을 재사용하고 game 서비스만 교체한다. |
| 6조 외부 WSS | PASS — Cloudflare/Nginx에서 TLS와 Upgrade를 유지하고 7777은 내부 전용이다. |
| 8·9조 endpoint/층 | PASS — `world-sessions` 구조화 응답으로 `11F/11F-01`을 전달한다. |
| 13·14·16조 토큰·클라이언트 불신 | PASS — 전용 서명 키, 서버 자체 검증, 영속 replay ledger, claim 기반 신원을 사용한다. |
| 27조 기준선 동결 | PASS — POC 전송·이동 코드는 재설계하지 않고 승인 경계만 증분한다. |
| 30조 미정 금지 | PASS — TTL 120초, 전용 키, game volume, 외부 Unity runner를 확정했다. |

Phase 1 설계 후에도 위 판정은 변하지 않는다.

## Implementation Changes

### Backend world-session

- `POST /api/v1/world-sessions`가 인증된 회원·게스트의 검증된 신원을 사용해 구조화 endpoint와 120초 connection JWT를 발급한다.
- JWT는 `CONNECTION_TOKEN_SECRET` 전용 HS256 키와 고정 issuer/audience를 사용한다.
- claim은 `jti/sub/role/playerId/nickname/avatarCode/sessionId/worldId/channelId/iat/exp`를 포함한다.

### Unity approval and replay protection

- Connection Approval은 JWT 서명·만료·issuer/audience·대상을 로컬 검증하고 token claim으로 세션 신원을 만든다.
- 사용된 `jti` 해시와 만료 시각을 원자적으로 append/flush한 뒤 승인한다. ledger 오류는 fail-closed다.
- 연결 종료 시 player/session을 제거하며 자동 재접속은 추가하지 않는다.

### Runtime and ingress

- game 서비스는 비관리자 container, 내부 `7777`, `maxPlayers=40`, 전용 replay volume과 Secret Reference를 가진다.
- 배포 전 host와 game image가 모두 x86_64인지 확인하고 아키텍처 불일치나 에뮬레이션 경로는 실패 처리한다.
- Nginx `world` host는 WebSocket Upgrade, cache/buffering off와 초기 read/send timeout 180초를 사용한다.
- 배포는 `--no-deps`로 game만 갱신하고 외부 승인 접속 실패 시 known-good으로 복구한다.

## Project Structure

```text
backend/src/main/java/com/example/ssafesta/world/
backend/src/test/java/com/example/ssafesta/world/
festa-unity/Assets/_Project/Scripts/Network/Connection/
festa-unity/Assets/_Project/Scripts/Network/Security/
infra/unity-server/
├── compose.yaml
├── nginx/world.conf
├── scripts/
└── evidence/
```

**Structure Decision**: 아직 생성되지 않은 infra-002 공통 디렉터리를 재구현하지 않고, 독립 실행·검증 가능한 `infra/unity-server` 경계를 제공한다. 추후 infra-002 Compose에서는 같은 환경 변수와 volume 계약으로 overlay를 소비한다.

## Delivery Order

1. 토큰/API 계약과 Backend 발급 구현
2. Unity 검증기와 영속 replay ledger
3. game-only Compose/Nginx 및 검증 스크립트
4. 로컬 자동 테스트
5. 실제 도메인·x86_64 EC2에서 P0 10분 WSS 및 P1 40명 실측

## Complexity Tracking

헌법 위반 없음. 영속 파일 ledger는 Redis 유실 허용과 Backend 무의존을 동시에 만족하기 위한 단일 서버 한정 설계다.
