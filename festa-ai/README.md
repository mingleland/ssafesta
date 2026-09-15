# SSAFY FESTA AI

FastAPI 기반 문서 처리·RAG 서비스다. Secret은 저장소에 두지 않고 배포 환경에서 주입한다.

## GMS Provider 전환

실환경에서는 다음 설정을 Secret/환경변수로 주입한다. 전체 예시는 `.env.example`을 따른다.

```dotenv
EMBEDDING_PROVIDER=gms
LLM_PROVIDER=gms
GMS_API_KEY=<secret storage에서 주입>
```

Embedding 전용 키가 필요한 환경은 `EMBEDDING_API_KEY`로 공통 키를 덮어쓸 수 있다. 기본 모델과 endpoint는 Spike 확정값인 `text-embedding-3-large` 1536차원 및 `gpt-4.1-mini` OpenAI 호환 API다.

비용 추적 로그에는 원문·API Key를 남기지 않는다. Embedding은 모델·요청 수·입력 수·지연을, LLM은 모델·입력/출력/전체 token·지연을 기록한다.

## 로컬 실행

`app/core/config.py`는 fail-fast라 13개 필수 값이 전부 채워지지 않으면 기동조차 안 된다
(`JWT_SECRET`·`REDIS_URL`·`SPRING_INTERNAL_BASE_URL`·`INTERNAL_SPRING_TO_AI_TOKENS`·
`INTERNAL_AI_TO_SPRING_TOKENS`·`R2_*` 4개·`MINIO_*` 4개). 전부 실배포 secret일 필요는 없고
로컬 self-serve로 채울 수 있다.

1. **인프라** — Postgres·Redis·MinIO를 로컬 docker로 띄운다(backend가 이미 쓰는 것과 같은
   컨테이너를 공유해도 된다). `docker ps`로 세 개 다 떠 있는지 확인한다.
2. **`R2_*` / `MINIO_*`** — 로컬은 R2를 실제로 쓰지 않지만 Settings 검증은 둘 다 요구한다.
   `MINIO_*`는 로컬 MinIO의 실제 endpoint·bucket·자격증명을 채우고, `R2_*`는 검증만 통과하면
   되는 placeholder 값(예: `unused-local-placeholder`)으로 채운다.
3. **`JWT_SECRET`** — backend와 **동일한 값**을 그대로 복사한다. Spring이 HS512로 서명한
   access token을 festa-ai가 같은 키로 검증하기 때문에 한 글자라도 다르면 모든 요청이 401이다.
4. **`INTERNAL_AI_TO_SPRING_TOKENS`** — festa-ai가 Spring 내부 API(booth-access 등)를 부를 때
   쓰는 실사용 값이다(`conversations.py`). 임의의 dev 토큰을 만들어 backend `.env`의
   `INTERNAL_AI_TO_SPRING_TOKENS`와 반드시 같은 값으로 맞춘다.
5. **`INTERNAL_SPRING_TO_AI_TOKENS`** — 역방향(S15P21A604-175, Spring→FastAPI 문서 처리 위임)
   토큰이다. festa-ai의 Settings 검증만 통과하면 되는 값이면 되고, 실제로 이 경로를 검증하려면
   backend `.env`의 같은 키와 값을 맞춘다.
6. **`SPRING_INTERNAL_BASE_URL`** — 로컬 Spring이 뜬 origin(예: `http://localhost:8090`).

값을 다 채우면 기동한다.

```bash
uv run uvicorn app.main:app --host 0.0.0.0 --port 8000
```

`http://localhost:8000/docs`가 200을 주면 정상 기동이다.

### SSE 왕복 검증

회원 access token으로 대화를 만들고 질문을 보내 스트리밍이 실제로 도는지 확인한다
(토큰은 backend와 같은 `JWT_SECRET`으로 서명한 것이어야 하고, `sub`·`role`·`sid` claim이
필요하다 — `sid`는 Spring `SessionRevocationFilter`가 Redis `<namespace>:auth:session:<userId>`
키와 대조하므로 그 키도 함께 맞춰 둔다).

```bash
# 1) 대화 생성
curl -s -X POST http://localhost:8000/ai/v1/conversations \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"boothId":1,"agentId":1}'

# 2) 질문 — 한글은 --data-binary @file.json 으로 보낸다(인라인 -d 는 쉘에 따라 인코딩이 깨질 수 있다)
curl -sN -X POST http://localhost:8000/ai/v1/conversations/<conversationId>/messages \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json; charset=utf-8" \
  --data-binary @question.json
```

`event: token` 델타가 순서대로 오고 `event: source`로 근거 문서가 인용되고 `event: done`으로
끝나면 SSE 라운드트립이 정상이다.

## 검증

일반 테스트는 실제 GMS Credit을 쓰지 않는다.

```bash
python -m pytest -m "not live_provider"
```

실제 Provider 회귀는 키를 프로세스 환경에 주입한 뒤 명시적으로 활성화한다.

```bash
RUN_GMS_LIVE_TESTS=1 GMS_API_KEY=... python -m pytest tests/integration/test_gms_provider_live.py --log-cli-level=INFO
```
