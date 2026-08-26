# Research: World Session 발급 API (002 BE분)

**Date**: 2026-08-25 | **Plan**: [plan.md](plan.md)

Phase 0 산출물. spec 002의 BE 증분(FR-005·FR-012 + 토큰 발급측)에 대해 남아 있던 미확정을 전부 닫는다.
NEEDS CLARIFICATION 잔여 **0건** — 단, R-05(게스트 닉네임 claim)는 임의 확정이 아니라 **통보 대상**으로 남긴다(quickstart §4).

---

## R-01. HTTP 메서드·경로 — `POST /api/v1/world-sessions`

- **Decision**: `POST /api/v1/world-sessions`, `Authorization: Bearer <Access Token>` (회원·게스트 공용).
- **Rationale**: 정본 3곳이 전부 POST다 — `docs/08` §16, `docs/21` ADR:210("MVP에서도 POST를 반드시 존재시키고 항상 `11F-01` 고정 응답"), infra-003 `world-session.openapi.yaml`. 세션 생성 + 1회용 토큰 발급이므로 의미상으로도 POST가 맞다(같은 호출을 두 번 하면 다른 토큰이 나온다 — 멱등이 아니다).
- **Alternatives**: `GET` — Jira `S15P21A604-84` 제목과 `docs/sdd/parts/BE.md`:68이 GET으로 적혀 있으나 둘 다 2026-08-21 이전 초안이다. **계약이 아니라 이슈 쪽을 정정한다**(quickstart §4 ①).

## R-02. 계약 정본 — infra-003 contracts (origin/develop)

- **Decision**: 응답 스키마·토큰 형식의 정본은 `specs/infra-003-unity-server-deploy/contracts/`의 `world-session.openapi.yaml` + `world-entry-token.md`(2026-08-24, Infra 리뷰 3칸 완료)로 한다.
- **Rationale**: `docs/08` §16과 `docs/16` §3은 스스로 "Response **후보**"라고 적은 초안이고(`serverEndpoint` 문자열), spec 002 부록의 `WorldSessionDto`(구조화 endpoint)와 어긋난다. infra-003 **T070**이 "docs/08·16의 `serverEndpoint` 초안을 구조화 endpoint 계약으로 정합화한다"를 자기 작업으로 갖고 있다 — 즉 어느 쪽이 정본인지 infra-003이 이미 판정했다. Unity 구현체(`WorldSessionDto`)도 구조화 endpoint를 쓴다.
- **Alternatives**: docs/08을 정본으로 — Unity 기구현과 어긋나서 탈락.

## R-03. 요청 본문 — `{ worldId?: "11F", preferredPartyId?: null }`

- **Decision**: 요청 본문은 optional. `worldId`가 오면 `"11F"`만 통과, 다른 값은 400 `VALIDATION_FAILED` + `FIELD_INVALID`/`field: "worldId"`. 없으면 `11F`로 발급. `preferredPartyId`는 받되 무시(단일 채널).
- **Rationale**: 헌법 9조·FR-012 "세션 발급 수단은 목적 구역(층)을 입력으로 받아야 한다 — 1차 MVP부터". #31에서 리드가 BE 앞으로 "**파라미터 자리는 남긴다** — 지우면 되돌릴 때 계약 변경, 값이 고정된 파라미터는 아무도 다치게 하지 않는다"로 재확인했고 FR-012 본문에도 박혀 있다. infra-003 OpenAPI에는 `worldId` 필드가 없으나 **optional 필드 추가는 가산적**(기존 소비자 무영향) — 파일 소유자인 Infra에 통보한다(quickstart §4 ③, 헌법 24조).
- **Alternatives**: ① 층 파라미터 생략(OpenAPI 그대로) — 헌법 9조·#31 결정 위반. ② 필수 필드로 — 기존 OpenAPI 소비 코드(FE·Unity adapter)에 Breaking. 탈락.

## R-04. Connection Token 형식 — 전용 HS256 키, TTL 120초

- **Decision**: `world-entry-token.md` 그대로 구현한다.
  - compact JWS, **HS256 only** (`none`·타 알고리즘 거부는 검증측=게임 서버 몫)
  - 서명 키: **전용 `CONNECTION_TOKEN_SECRET`** (Base64, 디코드 후 ≥32바이트) — 기동 시 검증, 미달이면 fail-fast
  - TTL **120초**, `iss: ssafesta-backend` / `aud: ssafesta-world`
  - claims: `jti·sub·role·playerId·nickname·avatarCode·sessionId·worldId·channelId·iat·exp`
  - 토큰 원문·Secret을 로그에 남기지 않는다
- **Rationale**: 계약 문서가 값까지 확정했다. **키를 분리하는 이유가 이 설계의 핵심**이다 — Access Token 키(`JWT_SECRET`, HS512)를 게임 서버에 주면 게임 서버가 AT를 위조할 수 있다. 게임 서버 컨테이너는 배포 대상이라 그 키가 새는 순간 인증 전체가 무너진다. 전용 키면 최악의 유출도 "월드 입장 토큰 위조"로 국한된다.
- **Alternatives**: ① `JwtEncoder`(HS512, `JWT_SECRET`) 재사용 — 위 이유로 탈락. ② 비대칭(ES256) — 더 안전하지만 계약이 HS256으로 확정됐고(Infra 리뷰 완료), 게임 서버·BE가 같은 팀 소유 컨테이너라 대칭키로 충분하다. 계약 재협상 없이 따른다.

## R-05. 게스트 claims — 서버가 파생한다 (통보 대상)

- **Decision**: 게스트도 발급 대상(회원과 동일 endpoint). claim 매핑:
  - `sub`·`playerId` = AT subject 그대로 (`guest:{uuid}`)
  - `role` = `GUEST`
  - `nickname` = `"게스트-" + uuid 앞 4자` — **서버가 파생**하며 클라이언트 입력을 받지 않는다 (헌법 16조)
  - `avatarCode` = null (게스트 비영속 — 헌법 12조, `users` 행이 없다)
- **Rationale**: infra-003이 "로그인 회원과 게스트 모두" 입장·10분 유지를 P0 검증으로 못 박았고(D-04), Jira -84 작업 내용에 "게스트/회원 권한 분기"가 있다. claim 목록은 계약이 정했지만 **게스트의 nickname 값 규칙은 어디에도 없다** — 헌법 30조에 따라 임의 확정 대신 파생 규칙을 명시하고 소비자(Unity)에 통보한다. 표시용 값이라 되돌리기 쉬운 결정이다.
- **Alternatives**: 게스트 발급 거부(403) — infra-003 P0 검증 시나리오와 정면 충돌. 탈락.

## R-06. 세션 영속화 — 하지 않는다 (BE에 저장소 0)

- **Decision**: `sessionId` = `"ws_" + UUID`, **발급은 무상태**. DB 테이블·Redis 키를 만들지 않는다. 재사용 차단 원장(`jti` 소비 기록)은 **게임 서버 소유** — `game-runtime.md`의 `/var/lib/festa-world/used-grants.log`.
- **Rationale**: FR-007·FR-013("접속마다 Backend 조회 금지")·FR-014(재시작 후에도 차단 유지)가 재사용 차단의 소유자를 게임 서버로 확정했다. BE에 "사용된 토큰" 저장소를 만들면 ① 아무도 읽지 않고 ② 읽게 만드는 순간 헌법 14조("Spring 장애가 월드 입장을 막으면 안 된다")를 위반한다. 만료 정리는 TTL 120초가 대신한다 — 정리할 상태 자체가 없다.
- **Alternatives**: Jira -170 원문("토큰 발급 저장·1회용 마킹") — 이슈가 2026-08-21 초안이라 08-24 계약(FR-013·14)보다 낡았다. **이슈 완료 조건을 `jti` 유일성 + TTL 120초로 정정 요청**한다(quickstart §4 ②).
- `DELETE /world-sessions/{sessionId}`(docs/08 초안)도 같은 이유로 범위 밖 — 지울 서버 상태가 없고 OpenAPI에도 없다. 비정상 종료 정리는 게임 서버 TTL/Heartbeat 몫.

## R-07. endpoint 값 — 프로필 설정으로만 (하드코딩 0)

- **Decision**: `app.world.*` `@ConfigurationProperties`(record `WorldProperties`)로 주입.
  - local: `ws / 127.0.0.1 / 7777` (기본값 — 로컬 Docker `festa-world-01`과 일치)
  - demo: `wss / world.${ROOT_DOMAIN} / 443` — 값 자체는 infra-003 T021(`application-infra.yml`·env 주입)이 소유, 이 plan은 property 계약과 local 값만 만든다
  - 기동 시 검증: scheme ∈ {ws, wss}, port 1~65535, `CONNECTION_TOKEN_SECRET` 디코드 ≥32바이트. 틀리면 **기동 실패** — 조용히 기본값으로 뭉개지 않는다(T-24 원칙, infra-003 T021 "잘못된 값에서 조기 실패").
- **Rationale**: 헌법 8조는 Unity의 하드코딩만 금지하는 게 아니라 "주소는 world-sessions 응답으로만"이 성립하려면 응답의 출처가 코드 상수여선 안 된다. FR-010(infra-003)도 배포 환경 값을 명시했다.
- **Alternatives**: 코드 상수 + 프로필 분기 — SC-003("주소 변경 시 재빌드 없이") 위반. 탈락.

## R-08. 403 경로 — 정지 계정만, 게스트는 조회 없이 발급

- **Decision**: 회원은 `users` 행을 조회해 claims(`playerId=id`, `nickname`, `avatarCode`)를 채우면서 `status != ACTIVE`면 403 `FORBIDDEN` + 전용 메시지. 게스트는 행이 없으므로 조회 없이 발급. 미인증은 기존 체인이 401 봉투로 거부(추가 코드 0).
- **실측 정정**: 이 status 검사는 **재사용이 아니라 신규**다. `AccountStatus`를 보는 코드는 `OAuthLoginSuccessHandler:41`(로그인 시점) **한 곳뿐**이다. `MyAccountController.activeMember()`는 이름과 달리 존재 여부만 보고 상태를 보지 않으며(`USER_NOT_FOUND`만 던진다), `SessionRevocationFilter`도 **세션 활성만** 확인한다. 즉 요청 처리 경로에 "정지 계정 거부" 판정이 없다. 여기서는 계약이 403을 요구하므로(OpenAPI "Account cannot enter the world") 두 줄로 넣되 신규 `ErrorCode`는 만들지 않는다.
- **정정 (2026-08-25 실측)**: 앞서 이 자리에 *"정지된 회원의 기존 Access Token은 만료까지 통과한다 — 별도 이슈로 올린다"* 고 적었는데, 이슈로 낼 건이 아니다. **정지 기능이 아직 없다.**
  - `AccountLifecycleService.suspend()`·`unsuspend()`를 **호출하는 코드가 0곳**이다. 관리자 컨트롤러도 `admin/` 패키지도 없다. spec 001 `tasks.md` **T013이 `[ ]` Deferred** — *"admin role/권한 모델 확정 뒤 수동 정지·정지 해제·상태 감사 endpoint를 `backend/.../admin/`에 구현한다"*.
  - 서비스 계층은 이미 spec 001 US5 AC-2(*"활성 인증 세션과 월드 접속이 종료된다"*)를 만족하는 형태다 — `change()`가 상태 전이마다 `sessions.revoke(userId)`를 부르고, 그러면 `SessionRevocationFilter`가 다음 요청을 401로 끊는다. **T013에서 endpoint만 붙이면 된다.**
  - 즉 지금은 뚫린 것도 막힌 것도 아니고 **진입점이 없는 상태**다. 이 spec에서 할 일은 없다.

- **Rationale**: OpenAPI가 403("Account cannot enter the world")을 응답에 뒀다. 회원 claims는 어차피 DB에서 읽어야 하므로(헌법 16조 — 클라이언트가 보낸 닉네임·아바타를 믿지 않는다) 상태 검사가 공짜다. `SecurityConfiguration` 변경도 0 — `anyRequest().authenticated()`가 이미 회원·게스트 AT를 모두 통과시킨다.
- **Alternatives**: 별도 권한 규칙 추가 — 필요 없는 코드. 탈락.

## R-09. 알려진 리스크 — avatarCode claim 크기 (BE 결정 아님, 기록만)

`avatarCode`는 최대 3800자(013a 계약)라 토큰이 이론상 ~5KB까지 커질 수 있다. NGO Connection Approval payload에는 크기 상한이 있어 **-85(Unity 검증 연동) 실측에서 확인해야 한다**. 실측 아바타 코드는 수백 자 수준이고 claim 목록은 계약 확정값이므로 BE가 임의로 빼지 않는다 — 상한에 걸리면 계약 변경 절차(헌법 24조)로 푼다. quickstart §4 ④에 통보 항목으로 기록.

## R-10. 정원(C-02)·재접속(C-03) — 이 기능 범위 밖

정원 40명 거부는 게임 서버 몫(`game-runtime.md` maximum clients 40), 자동 재접속은 P1(리드 확정 C-03). BE는 발급 시 정원을 세지 않는다 — 세려면 접속 상태를 조회해야 하는데 그 상태의 권위는 게임 서버다(헌법 2조).
