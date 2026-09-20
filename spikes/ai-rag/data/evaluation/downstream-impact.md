# S15P21A604-370 후속 티켓 영향

## 확정 기본값

- Embedding: `text-embedding-3-large`, 1536차원
- Chunk: 900 tokens
- Overlap: 20% (180 tokens)
- Retrieval Top-K: 10
- 비용 기준선: `text-embedding-3-small`은 같은 검색 설정에서 유지

## S15P21A604-123 — PDF 청킹·Embedding 처리

- 토큰 청킹 기본값을 900 tokens, overlap 180 tokens로 외부 설정에서 주입한다.
- 각 청크에 `embedding_model_id`와 token count를 보존한다.
- 모델 교체 시 문서 전체 재임베딩이 필요하므로 기존 모델 벡터와 섞지 않는다.

## S15P21A604-128 — 범위 제한 Vector 검색

- `boothId + agentId + modelId` 필터 이후 cosine distance로 후보 10건을 조회한다.
- Top-K 10은 최종 LLM 전달 개수가 아니라 Reranker 입력 후보 수로 취급한다.
- 검색 결과에는 chunk token count를 포함해 Context 예산을 계산할 수 있어야 한다.

## S15P21A604-163 — RAG 답변·SSE 연결

- Top-K 10을 그대로 LLM에 전달하면 평균 약 5,023 tokens이므로 대화 이력과 시스템 프롬프트를 합친 8,000-token 입력 제한을 주의한다.
- S15P21A604-371에서 Reranker의 최종 전달 개수와 Context token budget을 별도로 결정한다.
- 근거 문서가 없는 `NO_ANSWER` 판정은 Recall/MRR에서 제외했으므로 유사도 임계값·답변 거절 정책을 별도 검증한다.
