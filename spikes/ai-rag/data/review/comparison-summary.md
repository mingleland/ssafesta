# GMS 임베딩 수동 검토 1차 결과

## 조건

- 모델: `text-embedding-3-small`, `text-embedding-3-large`, `gemini-embedding-2`
- 차원: 1536
- 청크 크기: 600 tokens
- overlap: 15%
- Top-K: 3
- 저장소: 메모리 코사인 검색
- PinLog: 질문 15개, 청크 3개
- SSAFY FESTA: 질문 15개, 청크 10개

## PinLog

| 모델 | 문서 임베딩 | 질문 임베딩 | 검색 | 요청 | 추정 Credit | 전체 |
|---|---:|---:|---:|---:|---:|---:|
| text-embedding-3-small | 0.661s | 1.022s | 0.018s | 2 | 0.002 | 1.702s |
| text-embedding-3-large | 0.730s | 0.613s | 0.019s | 2 | 0.020 | 1.362s |
| gemini-embedding-2 | 1.523s | 6.264s | 0.019s | 18 | 3.600 | 7.807s |

Top-1 청크 일치: 세 모델 모두 동일 4문항, 두 모델 동일 8문항, 모두 다름 3문항.

## SSAFY FESTA

| 모델 | 문서 임베딩 | 질문 임베딩 | 검색 | 요청 | 추정 Credit | 전체 |
|---|---:|---:|---:|---:|---:|---:|
| text-embedding-3-small | 2.076s | 0.284s | 0.043s | 2 | 0.002 | 2.403s |
| text-embedding-3-large | 1.820s | 0.331s | 0.043s | 2 | 0.020 | 2.194s |
| gemini-embedding-2 | 5.050s | 7.017s | 0.064s | 25 | 5.000 | 12.132s |

Top-1 청크 일치: 세 모델 모두 동일 3문항, 두 모델 동일 9문항, 모두 다름 3문항.

## 검토 방법

1. 각 `*-results-review.jsonl`에서 질문과 모델별 Top-1 후보를 확인한다.
2. 정답 근거인 문장만 `relevant_contains`에 남긴다.
3. 문서에 답이 없으면 배열을 비우고 `review_status`를 `NO_ANSWER`로 바꾼다.
4. 정답을 확정했으면 `review_status`를 `APPROVED`로 바꾼다.
5. 확정된 라벨로 Recall@K와 MRR을 다시 계산한다.

`estimated_gms_credits`는 모델 카드의 표시 Credit에 실제 HTTP 요청 수를 곱한 추정치다.
Gemini `embedContent`는 텍스트마다 요청해 OpenAI 배열 배치보다 요청 수가 많다.
