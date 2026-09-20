# Sol 골드 라벨 기반 임베딩 모델 평가

## 평가 조건

- 모델: `text-embedding-3-small`, `text-embedding-3-large`, `gemini-embedding-2`
- 차원: 1536
- Chunk: 600 tokens
- Overlap: 15%
- Top-K: 1, 3, 5
- 검색: 메모리 cosine distance
- PinLog: 정답 있는 질문 9개, `NO_ANSWER` 6개 제외, 청크 3개
- SSAFY FESTA: 정답 있는 질문 12개, `NO_ANSWER` 3개 제외, 청크 10개
- 수달: 정답 있는 질문 14개, `NO_ANSWER` 1개 제외, 청크 26개

## PinLog

| 모델 | Recall@1 | MRR@1 | Recall@3 | MRR@3 | Recall@5 | MRR@5 |
|---|---:|---:|---:|---:|---:|---:|
| text-embedding-3-small | 0.3333 | 0.3333 | 1.0000 | 0.6296 | 1.0000 | 0.6296 |
| text-embedding-3-large | 0.4444 | 0.4444 | 1.0000 | 0.6852 | 1.0000 | 0.6852 |
| gemini-embedding-2 | **0.6667** | **0.6667** | 1.0000 | **0.8148** | 1.0000 | **0.8148** |

PinLog는 청크가 3개뿐이므로 Recall@3과 Recall@5가 모든 모델에서 1.0이다. 이 두 값은 모델을 구분하는 지표로 사용할 수 없다. Top-1과 MRR에서는 Gemini가 가장 높다.

## SSAFY FESTA

| 모델 | Recall@1 | MRR@1 | Recall@3 | MRR@3 | Recall@5 | MRR@5 |
|---|---:|---:|---:|---:|---:|---:|
| text-embedding-3-small | 0.1667 | 0.1667 | 0.4167 | 0.2500 | 0.8333 | 0.3458 |
| text-embedding-3-large | **0.5000** | **0.5000** | **0.5833** | **0.5278** | **0.9167** | **0.5986** |
| gemini-embedding-2 | 0.1667 | 0.1667 | 0.5000 | 0.3056 | 0.6667 | 0.3389 |

SSAFY FESTA에서는 모든 K에서 `text-embedding-3-large`가 Recall과 MRR 모두 가장 높다.

## 수달

| 모델 | Recall@1 | MRR@1 | Recall@3 | MRR@3 | Recall@5 | MRR@5 |
|---|---:|---:|---:|---:|---:|---:|
| text-embedding-3-small | 0.0714 | 0.0714 | 0.2143 | 0.1429 | 0.5000 | 0.2071 |
| text-embedding-3-large | **0.2143** | **0.2143** | **0.5714** | **0.3452** | **0.7143** | **0.3810** |
| gemini-embedding-2 | 0.1429 | 0.1429 | 0.2857 | 0.2143 | 0.4286 | 0.2464 |

| 모델 | 질의 임베딩 | 문서 임베딩 | 전체 처리 | HTTP 요청 | 추정 Credit |
|---|---:|---:|---:|---:|---:|
| text-embedding-3-small | 3.1433초 | 2.8621초 | 6.5878초 | 2 | 0.002 |
| text-embedding-3-large | **2.6704초** | **2.6748초** | **5.9377초** | 2 | 0.020 |
| gemini-embedding-2 | 7.0539초 | 12.3808초 | 20.0011초 | 40 | 8.000 |

수달은 26개 청크로 세 문서 중 검색 공간이 가장 크다. 모든 K에서 `text-embedding-3-large`가 가장 높지만 Recall@5는 0.7143이므로 현재 600-token 청킹과 Top-K 5만으로는 품질 기준 0.95를 충족하지 못한다.
세 모델 모두 1536차원으로 응답했으며 스코프 격리 위반은 0건이었다.

## 전체 35문항 가중 합산

| 모델 | Recall@1 | MRR@1 | Recall@3 | MRR@3 | Recall@5 | MRR@5 | 추정 Credit 합계 |
|---|---:|---:|---:|---:|---:|---:|---:|
| text-embedding-3-small | 0.1714 | 0.1714 | 0.4857 | 0.3048 | 0.7429 | 0.3633 | 0.006 |
| text-embedding-3-large | **0.3714** | **0.3714** | **0.6857** | **0.4952** | **0.8571** | **0.5338** | 0.060 |
| gemini-embedding-2 | 0.2857 | 0.2857 | 0.5429 | 0.4000 | 0.6571 | 0.4243 | 14.800 |

## 해석

- 현재 골드 후보와 600-token 청킹에서는 `text-embedding-3-large`가 전체 품질 1위다.
- 수달을 포함하면 Recall@5 95% 기준을 충족한 모델은 없다. 임베딩 최종 확정보다 청킹·Overlap·Top-K 튜닝을 먼저 수행해야 한다.
- small은 large보다 추정 Credit이 10배 저렴하지만 Recall@5가 약 11.43%p, MRR@5가 약 0.1705 낮다.
- Gemini는 PinLog Top-1에서는 가장 좋았지만 FESTA 성능과 요청 비용을 합치면 기본 선택으로 보기 어렵다.
- `NO_ANSWER`는 정답 청크가 없으므로 Recall/MRR에서 제외했다. 이는 별도 유사도 임계값 평가가 필요하다.
- 세 프로젝트 35문항 Gold Label은 사용자 승인을 반영했다. 다만 표본이 작으므로 최종 모델 확정 전에 청킹·Top-K와 Reranker 후속 평가가 필요하다.

`estimated_gms_credits`는 모델 카드 표시 Credit × 실제 HTTP 요청 수이며 GMS 콘솔 실사용량이 정본이다.
