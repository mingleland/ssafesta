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

## 검증

일반 테스트는 실제 GMS Credit을 쓰지 않는다.

```bash
python -m pytest -m "not live_provider"
```

실제 Provider 회귀는 키를 프로세스 환경에 주입한 뒤 명시적으로 활성화한다.

```bash
RUN_GMS_LIVE_TESTS=1 GMS_API_KEY=... python -m pytest tests/integration/test_gms_provider_live.py --log-cli-level=INFO
```
