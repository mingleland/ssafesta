# SSAFY FESTA 외부 API 연동 명세서

> **기준**: `develop` `caa1c60d` (2026-09-23). 설정값은 `backend/src/main/resources/application.yml`, `festa-ai/app/core/config.py`, `infra/` 에서 확인했다.
> **범위**: 우리 서비스가 호출하는 외부 서비스. 우리 서버끼리 주고받는 내부 API(Spring ↔ FastAPI, `/internal/**`)는 [14_AI_Server_API_명세서](./14_AI_Server_API_명세서.md)와 `specs/008-ai-conversation-rag/contracts/` 가 기준이다.
> **Secret**: 아래 환경변수 값은 저장소에 없다. 배포 시 Secret 저장소에서 주입한다 (헌법 15조).

## 1. 한눈에 보기

| # | 외부 서비스 | 용도 | 호출하는 쪽 | 방식 |
|---|---|---|---|---|
| 1 | Google OAuth 2.0 / OIDC | 소셜 로그인 | Spring | Authorization Code |
| 2 | Kakao 로그인 | 소셜 로그인 | Spring | Authorization Code |
| 3 | SSAFY 통합 로그인 | 소셜 로그인 | Spring | Authorization Code |
| 4 | SSAFY GMS (OpenAI 호환) | AI 직원 답변 생성, 문서 임베딩 | FastAPI | HTTPS REST, 답변은 스트리밍 |
| 5 | Cloudflare R2 (S3 호환) | AI 원본 문서·게임 이미지·프로젝트 로고 저장, DB 백업 | Spring(서명), 브라우저(업로드) | S3 API, presigned URL |
| 6 | GitLab Generic Package Registry | Unity 릴리스 번들 보관·배포 | Unity 담당 로컬 빌드, Jenkins | GitLab REST API v4 |
| 7 | Mattermost Incoming Webhook | 운영 이상 알림 | Grafana Alerting | Webhook (Slack 호환 형식) |

아직 구현하지 않은 연동이 하나 있다. 거리 기반 음성채팅(spec 017)은 WebRTC SFU(LiveKit·mediasoup 등)로 브라우저끼리 연결하는 것으로 방향만 정했고, SFU 선택(C-01)이 결정 대기 중이다.

## 2. 소셜 로그인 (Google · Kakao · SSAFY)

자체 회원가입은 없다. 세 제공자 모두 Spring Security OAuth2 Client 가 처리하고, 브라우저에는 우리 Access Token 만 돌려준다 (헌법 11조).

### 2-1. 흐름

```text
브라우저 GET /api/v1/auth/oauth/{google|kakao|ssafy}
  → 제공자 인증 화면
  → 제공자가 Spring 콜백으로 리다이렉트 (/login/oauth2/code/{provider})
  → Spring 이 토큰 교환·사용자 정보 조회 후 계정 생성 또는 연결 (oauth_identities)
  → Set-Cookie: oauth_handoff (HttpOnly, 5분) + 302 {FRONTEND_BASE_URL}/auth/callback
  → 브라우저 POST /api/v1/auth/oauth/complete
  → Access Token 응답 + refresh_token HttpOnly 쿠키
```

최초 가입이면 `NICKNAME_REQUIRED` 를 받고 닉네임을 넣어 같은 endpoint 를 다시 부른다. 상세 계약은 [oauth-completion.md](../specs/001-auth-user/contracts/oauth-completion.md) 에 있다.

### 2-2. 제공자별 설정

| 항목 | Google | Kakao | SSAFY |
|---|---|---|---|
| 인가 | Spring 기본값 | `https://kauth.kakao.com/oauth/authorize` | `https://project.ssafy.com/oauth/sso-check` |
| 토큰 | Spring 기본값 | `https://kauth.kakao.com/oauth/token` | `https://project.ssafy.com/ssafy/oauth2/token` |
| 사용자 정보 | Spring 기본값 (OIDC) | `https://kapi.kakao.com/v2/user/me` | `https://project.ssafy.com/ssafy/resources/userInfo` |
| scope | `openid, profile, email` | `profile_nickname, account_email` | 없음 (보내면 안 된다) |
| 식별자 | `sub` | `id` | `userId` |
| 클라이언트 인증 | 기본 | `client_secret_post` | `client_secret_post` |
| 환경변수 | `GOOGLE_CLIENT_ID` `GOOGLE_CLIENT_SECRET` `GOOGLE_REDIRECT_URI` | `KAKAO_REST_API_KEY` `KAKAO_CLIENT_SECRET` `KAKAO_REDIRECT_URI` | `SSAFY_CLIENT_ID` `SSAFY_CLIENT_SECRET` `SSAFY_REDIRECT_URI` |

운영 콜백 주소는 `https://api.ssafesta.world/login/oauth2/code/{google|kakao|ssafy}` 이다. 콘솔 등록값과 한 글자라도 다르면 `redirect_uri_mismatch` 로 실패한다.

### 2-3. 실패 처리

제공자 오류나 취소는 로그인 실패로 끝나고 계정 상태를 바꾸지 않는다. 게스트 로그인(`POST /api/v1/auth/guest`)은 외부 제공자를 거치지 않으므로 제공자 장애와 무관하게 동작한다.

## 3. SSAFY GMS — LLM · 임베딩

SSAFY 가 제공하는 OpenAI 호환 게이트웨이다. FastAPI 만 호출하고, Spring·React·Unity 는 LLM 을 직접 부르지 않는다.

| 항목 | 값 |
|---|---|
| Base URL | `https://gms.ssafy.io/gmsapi/api.openai.com` |
| 답변 모델 | `gpt-4.1-mini` (`LLM_MODEL_ID`) |
| 임베딩 모델 | `text-embedding-3-large`, 1536 차원 (`EMBEDDING_MODEL_ID`, `EMBEDDING_DIMENSION`) |
| 인증 | `GMS_API_KEY` (임베딩 전용 키를 따로 쓰면 `EMBEDDING_API_KEY`) |
| 환경변수 | `LLM_API_BASE_URL` `EMBEDDING_API_BASE_URL` |

### 3-1. 쓰는 곳

- **AI 직원 대화** (spec 008): 부스 문서에서 찾은 청크를 근거로 답변을 스트리밍으로 생성해 SSE 로 브라우저에 전달한다. 검색은 `boothId` + `agentId` 필터를 강제한다 (헌법 17조).
- **문서 처리** (spec 007): 업로드된 문서를 청크로 나눠 임베딩하고 Spring 에 결과를 넘긴다. 벡터는 PostgreSQL pgvector(`ai_document_chunks`)에 저장한다.
- **상담 인계 요약** (spec 011): AI 대화를 사람 상담으로 넘길 때 요약 한 번을 생성한다.

### 3-2. 시간 제한과 실패 처리

| 구간 | 제한 |
|---|---:|
| 연결 | 10초 |
| 읽기 | 15초 |
| 첫 토큰까지 | 15초 |
| 답변 전체 | 60초 |
| 상담 인계 요약 | 20초 |

시간을 넘기거나 GMS 가 오류를 내면 그 대화 요청만 실패로 끝난다. AI 장애가 월드 접속·이동을 막지 않는다 (헌법 3조). 문서 처리 중간에 끊기면 Spring 의 작업 임대가 만료된 뒤 다시 처리한다. 장애 주입 검증 결과는 [failure-injection-results.md](../festa-ai/tests/validation/failure-injection-results.md) 에 있다 (6/6 복구).

## 4. Cloudflare R2 — 오브젝트 저장소

S3 호환 API 라서 Spring 은 AWS SDK(`S3Client`, `S3Presigner`)에 R2 endpoint 를 넣어 쓴다. 파일 바이트는 서버를 거치지 않고 브라우저가 presigned URL 로 R2 에 바로 올린다.

| 항목 | 값 |
|---|---|
| 저장 대상 | AI 직원 원본 문서, Game Studio 이미지, 프로젝트 로고 |
| 공개 여부 | 비공개 버킷. 올릴 때만 presigned PUT 을 쓰고, 내려받기는 서버가 권한을 확인한 뒤 바이트를 중계한다 |
| presigned URL 유효시간 | 15분 |
| 환경변수 | `R2_ENDPOINT` `R2_BUCKET` `R2_ACCESS_KEY_ID` `R2_SECRET_ACCESS_KEY` |
| 쓰기 대상 전환 | `AI_STORAGE_ACTIVE_WRITE_PROVIDER` (`R2` / `MINIO_LOCAL`) |
| 업로드 차단 스위치 | `AI_STORAGE_UPLOAD_GATE` — 무료 사용량 초과가 우려되면 운영자가 신규 업로드만 막는다 |

별도 백업 버킷은 DB 백업 전용이고 CORS 를 끈다. 버킷 정책 참고본은 `infra/environments/storage/r2/buckets.example.yaml` 이다.

R2 에 장애가 나면 신규 업로드가 `503` 으로 거부되고, 이미 올라간 문서로 만든 AI 답변은 DB 의 청크로 계속 동작한다. 쓰기 대상을 MinIO 로 바꿔도 기존 파일은 원래 저장소에서 읽는다 (문서 행의 `storage_provider` 기준). DB 와 저장소가 어긋난 객체는 `storage_reconciliation_log` 에 기록하고 정리한다.

## 5. GitLab Generic Package Registry — Unity 릴리스 번들

Unity 릴리스 빌드는 라이선스가 있는 담당자 PC 에서만 굽는다. Jenkins 는 릴리스 빌드를 하지 않고, 올라온 번들을 받아 검증한 뒤 배포만 한다.

```text
PUT/GET https://lab.ssafy.com/api/v4/projects/1443023/packages/generic/{package}/{version}/{file}
```

| 항목 | 값 |
|---|---|
| 번들 구성 | `festa-webgl-release-<sha8>.zip`, `festa-game-<sha8>.tar`, `webgl-manifest.json`, `image-metadata.json` |
| 무결성 | 업로드 후 다시 내려받아 바이트·해시를 대조한다 |
| 인증 | 배포용 GitLab 토큰. Jenkins 에는 credential ID 만 둔다 |
| 운영 문서 | [Unity 릴리스 배포 가이드](./infra/guides/03_UNITY_RELEASE_DEPLOY_GUIDE.md), `infra/unity-server/runbooks/unity-release-bundle.md` |

## 6. Mattermost Incoming Webhook — 운영 알림

Grafana Alerting 의 contact point `mattermost-approved-anomalies` 가 Slack 호환 형식으로 `#festa-operations` 채널에 알린다. Webhook URL 전체가 Secret 이라 `MATTERMOST_WEBHOOK_URL` 로만 주입한다. 승인된 탐지 규칙에 걸린 이상만 보내고, 일반 CI 성공·실패는 보내지 않는다. 설정은 `infra/observability/grafana/provisioning/alerting/` 에 있다.

## 7. 인프라 플랫폼 (참고)

코드가 API 로 호출하지는 않지만 서비스가 기대는 외부 플랫폼이다.

| 서비스 | 역할 |
|---|---|
| Cloudflare DNS · Proxy · CDN | `ssafesta.world` 도메인, TLS(Full strict), 정적 파일 캐시 |
| AWS EC2 | Nginx, Spring, FastAPI, Unity 전용 서버, Jenkins 실행 호스트 |

상세는 [15_Infra_AWS_설계서](./15_Infra_AWS_설계서.md) 에 있다.
