# AI RAG Retrieval Spike (`S15P21A604-92`)

PDF를 페이지별로 파싱하고, Embedding 모델과 청킹·Top-K 조합을 같은 평가셋으로 비교한다.
결과에는 `Recall@K`, MRR, 검색 P50/P95, Context token 추정, 실패 질문 ID,
임베딩 처리시간, 요청 수와 예상 GMS 크레딧이 기록된다.

## 안전·계약 조건

- 모든 모델 출력은 `1536`차원이어야 한다. 다르면 즉시 실패한다.
- 실제 검증은 pgvector 임시 테이블을 사용하며 `booth_id + agent_id + model_id` 필터를 강제한다.
- 임시 테이블은 연결 종료 시 사라지며 운영 테이블을 변경하지 않는다.
- API Key와 DB URL은 환경변수로만 받고 결과 JSON에 기록하지 않는다.
- 결과 파일에는 PDF 원문과 평가 질문을 넣지 않는다.

## Conda 환경

```bash
cd spikes/ai-rag
conda env create -f environment.yml
conda activate ssafesta-rag-spike
```

환경이 이미 있다면 `conda env update -f environment.yml --prune`으로 갱신한다.

## 환경변수

PowerShell 예시이며 실제 Secret은 파일이나 저장소에 커밋하지 않는다.

```powershell
$env:GMS_API_KEY = "<Secret에서 주입>"
$env:GMS_OPENAI_EMBEDDINGS_URL = "https://gms.ssafy.io/gmsapi/api.openai.com/v1/embeddings"
$env:GMS_GEMINI_EMBEDDINGS_URL = "https://gms.ssafy.io/gmsapi/generativelanguage.googleapis.com/v1beta/models/gemini-embedding-2:embedContent"
$env:RAG_SPIKE_DATABASE_URL = "postgresql://..."
```

URL 환경변수는 생략해도 위 GMS 주소가 기본값으로 사용된다.

- OpenAI: `Authorization: Bearer`로 인증하고 배열 `input`을 전송하며 `data[].embedding`을 읽는다.
- Gemini: `x-goog-api-key`로 인증하고 입력마다 `content.parts[].text`를 전송하며
  `embedding.values`를 읽는다. `embedContent`가 단건 API이므로 `--batch-size`와 무관하게
  텍스트당 요청 1회로 측정한다.
- 두 provider 모두 pgvector 계약을 맞추기 위해 출력 차원을 1536으로 요청하고, 실제 응답이
  1536차원이 아니면 즉시 실패한다.

## 평가셋

`eval.example.jsonl`을 복사하여 질문별 정답 페이지 또는 정답 문자열을 작성한다. 페이지 번호는
PDF 뷰어의 1부터 시작하는 페이지 번호다. 최소 하나의 정답 기준이 필요하다.

```json
{"id":"q-001","query":"운영 시간은?","relevant_pages":[2],"relevant_contains":["운영 시간"]}
```

## 실행

먼저 세 모델을 동일 기준으로 선별한다.

```bash
rag-spike \
  --pdf data/sample.pdf \
  --eval data/eval.jsonl \
  --output results/screening.json \
  --chunk-sizes 600 \
  --overlap-ratios 0.15 \
  --top-k 5
```

상위 두 모델만 튜닝한다.

```bash
rag-spike \
  --pdf data/sample.pdf \
  --eval data/eval.jsonl \
  --output results/tuning.json \
  --models text-embedding-3-small,text-embedding-3-large \
  --chunk-sizes 300,600,900 \
  --overlap-ratios 0.10,0.15,0.20 \
  --top-k 3,5,8,10
```

세 문서의 튜닝 결과를 하나의 의사결정 문서로 집계한다.

```bash
rag-tuning-summary \
  --inputs results/pinlog.json results/ssafesta.json results/sudal.json \
  --json-output results/chunk-topk-summary.json \
  --markdown-output results/chunk-topk-summary.md
```

370번의 실험 그리드와 확정 기본값은 각각 `config/tuning-grid.json`,
`config/retrieval-defaults.json`에서 확인할 수 있다.

pgvector 없이 API·청킹을 빠르게 확인할 때만 `--store memory`를 쓸 수 있다. Jira 완료 근거는
반드시 기본값인 pgvector 실행 결과를 사용한다.

## 단위 테스트

```bash
python -m unittest discover -s tests -v
```

## 결과 해석

1. `leakage_count`가 1 이상이면 즉시 실패다.
2. `recall_at_k >= 0.95`, `p95_search_ms <= 1000`을 만족하는 조합만 후보로 둔다.
3. 후보 사이에서는 MRR이 높은 조합을 우선한다.
4. 품질 차이가 미미하면 예상 GMS 크레딧과 처리시간이 낮은 모델을 선택한다.

`estimated_gms_credits`는 화면에 표시된 모델별 크레딧에 HTTP 요청 수를 곱한 추정치다. GMS의
실제 과금 단위가 토큰·배치 크기에 따라 다르면 콘솔 실사용량을 정본으로 기록한다.
