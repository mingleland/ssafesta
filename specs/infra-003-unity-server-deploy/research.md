# Research: Unity Dedicated Server 외부 배포

## R-01. Connection Token

**Decision**: Access JWT와 분리된 32바이트 이상 Base64 Secret으로 HS256 JWT를 발급하며 TTL은 120초다.

**Rationale**: Unity가 .NET 기본 `HMACSHA256`만으로 검증할 수 있고 Refresh/Access/Connection 경계를 분리한다. Unity 로딩 완료 뒤 발급하므로 120초면 정상 외부 지연을 흡수하면서 노출 시간을 제한한다.

**Alternatives considered**: Access JWT 키 공유는 경계를 결합한다. 60초는 느린 외부 환경에서 불필요한 재발급을 늘린다.

## R-02. Used-token persistence

**Decision**: token 원문이 아닌 `SHA-256(jti) + exp`를 game 전용 영속 volume의 append-only ledger에 기록한다.

**Rationale**: Backend 조회 없이 동시 소비를 직렬화하고 container 교체 뒤에도 차단을 유지한다. Redis는 infra-002에서 전체 유실을 허용하므로 보안 정확성의 유일한 근거가 될 수 없다.

**Alternatives considered**: Redis AOF는 correctness 근거가 아니며 PostgreSQL 직접 조회는 접속 경로에 외부 의존성을 추가한다.

## R-03. Public WSS path

**Decision**: `Cloudflare Full (strict) → Nginx TLS termination → ws://demo-game:7777`을 사용하고 Nginx timeout 초기값은 180초다.

**Rationale**: IAM role 없이 현재 헌법과 infra-002 공개 진입점 계약을 그대로 만족한다.

**Alternatives considered**: ALB/NLB/ACM/ECS는 권한과 현재 topology에 맞지 않는다. Caddy 전환은 이번 범위가 아니다.

## R-04. Capacity runner

**Decision**: P0는 실제 WebGL 브라우저 2개, P1은 별도 외부 머신의 자동 Unity client runner를 `1→10→20→30→40`으로 늘린다.

**Rationale**: P0가 브라우저 호환성을 증명하고 P1 runner는 브라우저 렌더링 부하가 서버 측 수용량 측정을 왜곡하는 일을 줄인다.

**Alternatives considered**: 브라우저 40개는 최종 경로와 같지만 runner host GPU/renderer가 병목이 될 가능성이 높다.

## R-05. Dedicated Server CPU architecture

**Decision**: Unity 6000.0.78f1 Dedicated Server는 x86_64 EC2에서만 실행한다. OCI Ampere A1 ARM64에서의 Unity 검증은 WebGL 정적 배포를 포함해 취소한다.

**Rationale**: Unity 6000.0.78f1 모듈 카탈로그의 Linux 모듈과 설치된 `LinuxStandaloneSupport/Variations`는 x86_64 서버 경로만 제공한다. `com.unity.sdk.linux-arm64` sysroot는 이 버전에서 Embedded Linux 전용이라 데스크톱 Linux Dedicated Server 타깃으로 사용할 수 없다.

**Alternatives considered**: Unity 6000.2+ 업그레이드는 프로젝트 전체 호환성 검증이 필요한 범위 확장이며 임시 OCI 검증을 위해 수행하지 않는다. x86_64 에뮬레이션은 성능·운영 결과를 왜곡하므로 사용하지 않는다. WebGL 정적 배포만 수행하는 대안은 실제 서버 기동·WSS·브라우저 동기화라는 원래 목적을 달성하지 못하며 x86_64 EC2 확보 후 함께 검증해도 일정상 충분해 제외했다.
