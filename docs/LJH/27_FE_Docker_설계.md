# FE Docker 설계 (구현 전 확정안)

> **상태**: 설계 확정 · **구현 미착수**. Jira 이슈가 없어 규칙 1에 걸린다(§0).
> **소유 경계**: `docs/17_Git_개발_Convention.md` §2-1 규칙 6 · [#107](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/issues/107)
> **작성** 2026-08-26 · 실측 기준 `origin/develop` `20ecd3b`

## 0. 왜 지금 이 문서인가

2026-08-26 Infra intake가 compose 체계를 develop에 반입했다.

```text
infra/deploy/compose/dev/{ai,back,front,game}.compose.yaml
infra/deploy/compose/integration/compose.yaml
infra/jenkins/{controller,agents}/compose.yaml
.dockerignore                        ← 저장소 루트
```

`integration/compose.yaml` 의 `front` 서비스가 이렇게 되어 있다.

```yaml
front:
  image: ${FRONT_IMAGE_REF:?FRONT_IMAGE_REF is required}
  environment:
    PUBLIC_API_BASE_URL: ${PUBLIC_API_BASE_URL:?PUBLIC_API_BASE_URL is required}
  ports: ["127.0.0.1:${FRONT_LOOPBACK_PORT:-18080}:8080"]
```

**그 이미지를 만들 Dockerfile이 없다.** `festa-frontend/` 에 Docker 관련 파일이 하나도 없고, 규칙 6이 그 자리를 FE 소관으로 확정했다 — *"`festa-frontend/Dockerfile.dev` 등 파트 디렉터리 내부의 Docker 파일은 그 파트 소관이다."*

즉 Infra가 만든 조립 구조가 FE 이미지를 기다리는 상태다.

### 선결 — Jira 이슈가 없다

JAM 전수 조회 결과(`meta.complete=true`) Docker 관련 이슈 4건(`-230`·`-80`·`-198`·`-154`)이 **전부 Infra 담당**이다. `-154`(Jenkins BE·FE 검증·이미지 빌드 파이프라인) 본문을 끝까지 읽었으나 작업 내용은 *"front 변경 경로별 검증·빌드·이미지 publish"* 로 **파이프라인이 Dockerfile을 호출하는 쪽**이고, Dockerfile 작성은 포함되지 않는다. 이 작업은 `-154` 의 선행이다.

AGENTS.md 규칙 1(모든 개발 작업은 Jira 이슈 선행)에 따라 **이슈 생성 전에는 구현 브랜치를 만들지 않는다.**

## 1. 결정 F1 — 멀티스테이지 Dockerfile 하나

`festa-frontend/Dockerfile` 단일 파일에 타깃을 나눈다.

```text
base            node:24-alpine + npm ci
├─ dev          vite dev server --host 0.0.0.0 (HMR)
└─ build        npm run build (tsc -b && vite build) → /app/dist
      ↓
   prod         nginx:alpine + dist + entrypoint + SPA fallback
```

`Dockerfile.dev` 를 따로 두지 않는다 — 규칙 6이 그 이름을 예시로 들었을 뿐 파일 분리를 요구하지 않고, `npm ci` 계층을 두 파일이 중복으로 갖는 것이 더 나쁘다.

**FE는 이미지 생성까지만 책임진다.** orchestration은 Infra 소관이다(F3).

### nginx

- SPA fallback `try_files $uri /index.html` 이 핵심 — 이것이 없으면 `/app/booths` 같은 deep link가 404다
- **80 포트로 서빙한다** — `infra/deploy/compose/dev/front.compose.yaml` 의 healthcheck 가 `wget -qO- http://127.0.0.1/` 이다

## 2. 결정 F2 — `runtime-config.js` 생성 방식

```text
PUBLIC_API_BASE_URL
   ↓ container entrypoint (컨테이너 기동 시)
/runtime-config.js
   ↓
window.__FESTA_CONFIG__.apiBaseUrl
   ↓
shared 단일 accessor
   ↓ 값이 없으면
import.meta.env.VITE_API_BASE_URL
```

### 왜 index.html 직접 치환이 아닌가

빌드 산출물 `index.html` 을 entrypoint 가 치환하는 방식은 **치환 실패가 조용하다**. placeholder 문자열이 그대로 남아도 페이지는 뜨고, API 호출 단계에서야 이상하게 실패한다. 재실행마다 원본이 오염되는 문제도 있다(치환된 파일을 다시 치환).

별도 파일 생성은 실패가 드러난다 — 파일이 없으면 `window.__FESTA_CONFIG__` 가 `undefined` 이고 fallback 이 명시적으로 동작한다.

### 구현 범위 — 2곳뿐이다

`VITE_API_BASE_URL` 을 읽는 곳을 전수 조사했다.

| 위치 | 용도 |
|---|---|
| `src/shared/api/client.ts:52` | 모든 API 요청의 base |
| `src/pages/login/LoginPage.tsx:13` | OAuth 시작 시 전체 페이지 이동 URL |

**두 곳이 각자 해석하지 않고 `shared/` 의 단일 accessor 를 쓴다.** `client.ts` 가 `pages/` 를 import 할 수 없으므로 `shared/` 가 유일하게 가능한 자리다.

`index.html` 은 `/runtime-config.js` 를 **동기 로드**한다(모듈 스크립트보다 먼저). `npm run dev` 에서는 그 파일이 없어 404가 나고 `window.__FESTA_CONFIG__` 가 undefined 로 남아 자연스럽게 `import.meta.env.VITE_API_BASE_URL` 로 떨어진다 — 이것이 로컬 개발의 정상 경로다.

### 건드리지 않는 것

나머지 `import.meta.env` 사용처는 그대로 둔다.

| 변수 | 사용처 |
|---|---|
| `VITE_USE_MOCK` | 6곳 (`entities/*/api.select.ts` 5 + game-studio) |
| `VITE_GAME_STUDIO_API_ENABLED` | 2곳 |
| `VITE_UNITY_BUILD_BASE` | 1곳 (`unity/host/resolver.ts`) |

기능 토글이라 이미지에 고정돼도 무방하고, 지금 함께 바꾸면 범위가 번진다. 런타임화가 필요해지면 같은 accessor 를 확장한다.

### 타입 선언

`window.__FESTA_CONFIG__` 는 기존 선례를 따라 `declare global { interface Window { ... } }` 로 선언한다 — `entities/auth/api.mock.ts` 가 `__festaTriggerOtherBrowserLogin` 을 그렇게 선언하고 있다.

### 변수명

entrypoint 가 받는 이름은 **`PUBLIC_API_BASE_URL`** 이다. Infra integration compose 가 주입하는 이름을 그대로 받는다 — 여기서 이름을 새로 만들면 Infra 파일을 고쳐야 하고 그건 F3 위반이다.

## 3. 결정 F3 — Infra 경계 불가침

| 대상 | 소유 | 이 작업에서 |
|---|---|---|
| `infra/deploy/compose/**` | Infra | **수정 금지** |
| 루트 compose | Infra (규칙 6) | **수정 금지** |
| `backend/compose.yaml` | BE | **수정 금지** |
| `festa-frontend/**` | FE | 이 작업 범위 |

변경이 필요하면 #107 경계대로 **develop행 MR + Infra 리뷰**로 제안한다. 작성 자체는 막히지 않는다.

> 규칙 6이 말하는 *"루트 compose(Frontend+Backend+PostgreSQL+Redis)"* 는 **아직 존재하지 않는다.** develop 루트에는 `.dockerignore` 만 있고, `integration/compose.yaml` 에는 postgres·redis 가 없다(ai·back·front·game 4개). 앞으로 Infra 가 만들 물건이며, 만들어지면 §5의 로컬 실행 절차가 그것으로 대체될 수 있다.

## 4. `festa-frontend/.dockerignore` 신설

빌드 컨텍스트가 `festa-frontend/` 이면(즉 `cd festa-frontend && docker build .`) **저장소 루트의 `.dockerignore` 는 적용되지 않는다.** Docker 는 컨텍스트 루트의 것만 읽는다.

```text
node_modules
dist
.env*
coverage
```

`.env*` 가 특히 중요하다 — `.env.local` 이 이미지에 들어가면 개발용 설정이 배포 이미지에 박힌다.

## 5. dev 타깃 주의 — node_modules 가려짐

호스트 소스를 `/app` 에 bind mount 하면 **이미지 안에서 `npm ci` 로 만든 `/app/node_modules` 가 호스트 것으로 덮인다**. 호스트에 없거나 플랫폼이 다르면(Windows ↔ Linux 네이티브 바이너리) 그대로 깨진다.

`/app/node_modules` 를 **container-owned volume 으로 분리**해 덮이지 않게 한다. 목표는 소스 수정 시 이미지 rebuild 없이 HMR 이 도는 것이다.

Windows/WSL2 에서 bind mount 파일 감시는 느리다. **로컬 개발의 기본 경로는 호스트 실행을 유지하고**, dev 타깃은 "컨테이너로도 된다"는 선택지로 둔다.

## 6. 로컬 통합 실행 경로 (현재)

```text
postgres·redis   docker compose -f backend/compose.yaml up -d     BE 소관 파일, 그대로 사용
Spring           호스트 ./mvnw spring-boot:run                     :8080
FE               호스트 npm run dev                                :5173
```

**새 compose 파일을 만들지 않는다.** `backend/compose.yaml` 에 Spring 서비스가 없어 어차피 완전 조립이 안 되고, FE 서비스 하나를 위해 compose 를 신설하면 규칙 6의 소유 경계와 헷갈린다.

## 7. 구현 착수 조건

1. **Jira 이슈 생성** — `[FE] Docker 이미지·로컬 실행 구조` 성격 (원격 재개 후)
2. **최신 `develop` 에서 새 작업 브랜치** — `feat/S15P21A604-<키>-fe-docker` (규칙 3)
3. **baseline 재측정** — 그 브랜치에서 `vitest / tsc / build` 를 먼저 돌려 기준값을 잡는다.
   **직전 front 기준값(50 files / 273 tests)을 그대로 쓰지 않는다** — develop 에 전 파트 코드가
   들어와 값이 달라졌을 수 있다
4. 구현

## 8. 검증 기준

```bash
cd festa-frontend
docker build --target prod -t festa-front:local .
docker run --rm -e PUBLIC_API_BASE_URL=http://localhost:8080 -p 3001:80 festa-front:local
```

| # | 항목 | 통과 조건 |
|---|---|---|
| 1 | 호스트 `npm run dev` + mock | 기존 경로가 그대로 동작 — fallback 이 깨지지 않는 것이 조건 |
| 2 | `docker build --target prod` | 성공 |
| 3 | nginx `/` | 200 |
| 4 | SPA deep link `/app/booths` | 200 (fallback) |
| 5 | `PUBLIC_API_BASE_URL` 주입 | `/runtime-config.js` 에 값이 반영 |
| 6 | **동일 이미지 · 다른 URL 재실행** | 값이 바뀐다 — runtime 주입의 결정적 증거. 빌드타임이면 안 바뀐다 |
| 7 | dev 타깃 HMR | 소스 수정 시 rebuild 없이 반영 |

6번이 이 설계 전체의 판정 항목이다. 나머지가 다 통과해도 6번이 실패하면 빌드타임 주입과 다를 바 없다.

## 9. 미결

| 항목 | 상태 |
|---|---|
| FE Docker용 Jira 이슈 | 원격 재개 후 생성. 없으면 구현 불가 |
| Infra 의 "루트 compose" 신설 시점 | Infra 소관. 생기면 §6 절차가 대체될 수 있다 |
| `VITE_UNITY_BUILD_BASE` 런타임화 | 범위 밖. 필요해지면 같은 accessor 확장 |
