# SSAFY FESTA AI

FastAPI 기반 문서 처리·RAG 서비스다. Secret은 저장소에 두지 않고 배포 환경에서 주입한다.

## 로컬 실행 (GitLab #150 / S15P21A604-548)

`app/core/config.py`의 `Settings`는 13개 필드가 전부 채워져야 기동한다(fail-fast, spec 007
plan.md §10). 이 값들은 실배포 infra secret이 아니라 로컬에서 self-serve로 채울 수 있다 —
아래는 검증된 최소 레시피다.

**절대 실제 R2 계정이 필요 없다.** `create_object_storage`(`app/providers/factory.py`)는
`storage_provider` 태그로만 R2/MinIO를 가르고 endpoint 문자열은 구분하지 않는다. 로컬 MinIO
컨테이너 하나로 `R2_*`·`MINIO_*` 여덟 필드를 전부 채운다(기존 `tests/integration/
test_s3_compatible_storage_minio.py`가 쓰는 것과 같은 패턴).

```bash
# 1) Redis — backend/compose.yaml이 이미 띄운 것을 그대로 쓴다(db index만 분리)
#    (없다면: docker compose -f ../backend/compose.yaml up -d redis)

# 2) 로컬 MinIO (R2/MinIO 필드 공용)
docker run -d --name ssafesta-local-minio -p 9000:9000 -p 9001:9001 \
  -e MINIO_ROOT_USER=minioadmin -e MINIO_ROOT_PASSWORD=minioadmin \
  minio/minio server /data --console-address ":9001"
docker exec ssafesta-local-minio mkdir -p /data/festa-ai-storage-local
docker restart ssafesta-local-minio   # 버킷으로 인식시키기 위한 재기동

# 3) festa-ai/.env (커밋 금지 — .gitignore가 이미 막는다)
cp .env.example .env
```

`.env`에 채울 값:

| 변수 | 로컬 값 | 비고 |
|---|---|---|
| `JWT_SECRET` | **backend/.env의 `JWT_SECRET`과 완전히 동일한 값** | `app/core/auth.py` — Spring이 HS512로 서명한 Access Token을 festa-ai가 같은 키로 검증한다. 재사용이 스펙 확정 사항이다(임의 결정 아님). 새로 만들지 말 것 |
| `REDIS_URL` | `redis://localhost:6379/1` | backend도 같은 Redis 인스턴스를 쓰므로(`spring.data.redis`) db index로 분리 |
| `SPRING_INTERNAL_BASE_URL` | `http://localhost:8080` | 로컬 Spring이 떠 있어야 한다 |
| `INTERNAL_AI_TO_SPRING_TOKENS` | 아무 랜덤 값(`openssl rand -hex 24`) | **backend/.env의 같은 이름 변수와 정확히 일치해야 한다** — `conversations.py`가 실제로 이 방향을 호출한다(booth 임대 검증). 바꾸면 backend 재시작 필요 |
| `INTERNAL_SPRING_TO_AI_TOKENS` | 아무 랜덤 값, 위와 다른 값 | 역방향(S15P21A604-175)은 backend에 아직 없다 — festa-ai `Settings` 검증만 통과하면 되는 placeholder |
| `R2_ENDPOINT` / `MINIO_ENDPOINT` | `http://localhost:9000` | 위에서 띄운 로컬 MinIO |
| `R2_BUCKET` / `MINIO_BUCKET` | `festa-ai-storage-local` | 위에서 만든 디렉터리 |
| `R2_ACCESS_KEY_ID` / `MINIO_ACCESS_KEY_ID` | `minioadmin` | |
| `R2_SECRET_ACCESS_KEY` / `MINIO_SECRET_ACCESS_KEY` | `minioadmin` | |
| `EMBEDDING_PROVIDER` / `LLM_PROVIDER` | `mock` (기본값 유지) | GMS credit 없이 부팅·SSE 배관만 검증할 때 |

```bash
python -m venv .venv && .venv/Scripts/pip install -e .   # 또는 source .venv/bin/activate (POSIX)
python -m uvicorn app.main:app --port 8010
curl http://localhost:8010/ai/v1/health/ready   # {"status":"UP"}
```

**여기서 끝나는 것과 안 끝나는 것.** 위 레시피는 기동(#150이 실측한 `ValidationError`)과
JWT·내부 토큰 배관까지 검증한다 — 인증 없이 호출하면 401, `INTERNAL_AI_TO_SPRING_TOKENS`가
backend와 안 맞으면(또는 backend가 아직 그 값을 모르면) `POST /ai/v1/conversations`가
503 `SPRING_UNAVAILABLE`로 fail-closed 된다. **부스 임대·Agent가 실제로 booth/agent
lease 검증을 통과해 SSE 스트림까지 왕복하려면 backend 쪽에 유효한 booth·agent·ACTIVE
lease 테스트 데이터가 별도로 필요하다** — 이건 이 레시피의 범위 밖이고, backend 로컬
fixture 문제로 남는다.

## GMS Provider 전환

실환경에서는 다음 설정을 Secret/환경변수로 주입한다. 전체 예시는 `.env.example`을 따른다.

```dotenv
EMBEDDING_PROVIDER=gms
LLM_PROVIDER=gms
GMS_API_KEY=<secret storage에서 주입>
```

Embedding 전용 키가 필요한 환경은 `EMBEDDING_API_KEY`로 공통 키를 덮어쓸 수 있다. 기본 모델과 endpoint는 Spike 확정값인 `text-embedding-3-large` 1536차원 및 `gpt-4.1-mini` OpenAI 호환 API다.

비용 추적 로그에는 원문·API Key를 남기지 않는다. Embedding은 모델·요청 수·입력 수·지연을, LLM은 모델·입력/출력/전체 token·지연을 기록한다.

## 검증

일반 테스트는 실제 GMS Credit을 쓰지 않는다.

```bash
python -m pytest -m "not live_provider"
```

실제 Provider 회귀는 키를 프로세스 환경에 주입한 뒤 명시적으로 활성화한다.

```bash
RUN_GMS_LIVE_TESTS=1 GMS_API_KEY=... python -m pytest tests/integration/test_gms_provider_live.py --log-cli-level=INFO
```
