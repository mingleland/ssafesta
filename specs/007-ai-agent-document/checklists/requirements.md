# 명세 품질 체크리스트: AI 직원 / 문서 파이프라인

**목적**: 구현 계획 수립 전 명세의 완전성과 품질 검증
**작성일**: 2026-08-20
**재검토일**: 2026-09-06 (S15P21A604-449)
**대상 기능**: [spec.md](../spec.md)

## 내용 품질

- [x] 명세에 구현 코드나 Migration 구현 상세가 포함되지 않았다
- [x] 사용자 가치, 관찰 가능한 동작, 파트 간 소유권 제약에 초점을 맞췄다
- [x] 기획·Backend·Infra·AI 검토자가 동작을 평가할 수 있도록 작성했다
- [x] 모든 필수 섹션을 작성했다

## 요구사항 완전성

- [x] 합의되지 않은 항목을 C-16과 `docs/26_팀_결정_필요사항.md`에 명시하고 구현 범위에서 차단했다
- [x] GitLab Work Item #100의 reconcile 기록 구조·전달 경로와 quota 오류 HTTP 상태가 확정됐다
- [x] GitLab Work Item #100의 `R2_RECONCILING` 중 신규 업로드 허용 여부와 장애 자동 판정 수치가 P0 구현 범위에서 제외되고 후속 이슈로 명시적으로 분리됐다
- [x] 요구사항이 테스트 가능하고 모호하지 않다
- [x] 성공 기준을 측정할 수 있다
- [x] 성공 기준이 외부에서 검증 가능한 결과를 설명한다
- [x] 모든 인수 시나리오를 정의했다
- [x] Edge Case를 식별했다
- [x] 기능 범위의 경계가 명확하다
- [x] 의존성과 가정을 식별했다
- [x] C-11에 Spring Business DB의 Document/Job/Staging/Chunk/pgvector 단독 소유와 FastAPI DB credential 금지가 명시됐다
- [x] 처리 snapshot, batch 멱등, fencing, finalize 단일 트랜잭션과 cancel의 관찰 가능한 결과가 정의됐다
- [x] 검색 API의 scope+READY+searchable, topK, timeout, distance 의미가 정의됐다
- [ ] 결과 수신 API endpoint·DTO를 Jira S15P21A604-400 합의 후 OpenAPI로 확정한다
- [ ] Agent 설정 API endpoint·응답·호출·캐시를 Jira S15P21A604-399 합의 후 확정한다

## 기능 준비도

- [x] 모든 기능 요구사항에 명확한 인수 기준 또는 상태 전이가 있다
- [x] 사용자 시나리오가 문서 생성·처리·관리·업로드 만료·복구의 주요 흐름을 포함한다
- [x] 기능이 성공 기준에 정의된 측정 가능한 결과를 충족한다
- [x] 구현 상세를 `plan.md`, `research.md`, `data-model.md`, `contracts/`로 분리했다

## 참고

- `Cloudflare R2(S3-compatible)`, `Spring`, `FastAPI`, `pgvector`, 1536차원, 파트별 소유권은 이번 계획에서 새로 선택한 구현안이 아니라 기존 헌법·아키텍처 계약이므로 명세에 유지했다.
- Issue #11에서 마지막 C-04 서버 재시작 복구 Clarification을 확정했다.
- GitLab Work Item #84의 업로드 URL 15분, `EXPIRED` 1시간, R2 정리 유예 24시간 계약과 Spring `DeleteObject` 책임을 반영했다. 자격증명 분리 방식은 동작을 바꾸지 않는 Infra 배포 선택이다.
- GitLab Work Item #84의 SHA-256 후속 확인에 따라 FastAPI 원본 재검증 실패를 `SOURCE_HASH_MISMATCH` 처리 결과 계약에 반영했다.
- GitLab Work Item #102의 방향별 Service Token, 상수 시간 검증, 단계적 토큰 회전 및 mTLS P2 결정을 반영했다.
- GitLab Work Item #100의 수동 MinIO fallback·문서별 Provider·운영자 승인 reconcile·유한 Job 재시도·`DEAD`는 AI 내부 상태로만 유지·저장소 복구 후 자동 재처리 없음·reconcile 결과 저장 구조와 전달 경로(#102 방식 재사용, Infra 전용 credential·scope 분리)·quota 오류 HTTP 코드(`STORAGE_UNAVAILABLE=503`/`STORAGE_QUOTA_EXCEEDED=507`) 결정을 반영했다. `R2_RECONCILING` 중 신규 업로드 허용 여부와 장애 자동 판정 수치 두 항목만 후속 이슈로 남겨 체크리스트와 spec C-10에 명시했다.
- GitLab Work Item #106의 callback 계약은 #119의 Spring 단일 DB·`jobId+attemptNo` fencing 결정으로 폐기됐으며 stale 409·deleted/cancelled 410으로 대체했다.
- S15P21A604-262의 별도 Business/AI DB 결정은 GitLab #119와 V21(S15P21A604-397)의 Spring 단일 소유권 결정으로 대체됐다. FastAPI의 문서 DB 설정·ORM·Repository·Alembic과 두 DB callback/reconciliation 전제를 제거했다.
