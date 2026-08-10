# SSAFY FESTA Constitution (초안)

> **용도**: spec-kit 설치 후 `.specify/memory/constitution.md`로 이동한다.
> 모든 spec/plan/구현이 위반할 수 없는 프로젝트 최상위 원칙.
> `[회의 후 확정]` 표시는 docs/26 ①번 회의 결과로 채운다.
> 상태: 초안 v0.1 (2026-08-08)

---

## Article I — 아키텍처 경계

1. **Source of Truth 분리**: 영구 비즈니스 상태(User/Booth/Lease/Coin/Layout/Survey/Staff)는 Spring이 유일한 기준이다. Unity·React는 이를 복제 구현하지 않는다.
2. **실시간/영구 분리**: Unity Dedicated Server는 실시간 월드 상태(위치/스폰/존)만 권위를 갖는다. Coin·Lease를 게임 서버가 변경하지 않는다.
3. **AI 장애 격리**: FastAPI 장애가 월드 접속·비AI 기능을 중단시키지 않는다. Spring이 AI 응답을 동기 중계하는 구조를 만들지 않는다.
4. **Booth는 데이터로 생성한다**: 부스 콘텐츠 추가에 Unity 재빌드가 필요한 구조를 금지한다. 정적 Booth Object는 NetworkObject로 만들지 않는다 (Layout JSON → Local Spawn).

## Article II — 네트워크 / 배포

5. **Transport는 WebSocket**: 브라우저는 UDP 불가. 배포 환경에서 연결은 항상 `wss://`(TLS는 LB에서 종료)이며 `ws://`를 프로덕션에 노출하지 않는다. *(로컬 검증 완료 — AWS 실측으로 최종 확정)* `[회의 후 확정: LB 종류 ALB/NLB]`
6. **모든 서버 컴포넌트는 Docker 이미지로 빌드한다** (Spring/FastAPI/Unity Server). 배치는 EC2로 시작하되 Unity Server는 Week 6 전 ECS 이관. `[회의 후 확정]`
7. **endpoint 하드코딩 금지**: Unity는 서버 주소를 world-sessions API 응답(`scheme/host/port + connectionToken`)으로만 받는다.

## Article III — 보안 / 인증

8. **Refresh Token은 Unity·게임 서버에 절대 전달하지 않는다.** Unity 접속은 TTL이 짧은 1회용 connection token만 사용한다. `[회의 후 확정: 토큰 4계층 상세]`
9. **Secret은 저장소에 커밋하지 않는다** (.env.example만 허용). API Key는 Secret 저장소에서 주입한다.
10. **클라이언트 주장 불신**: 서버는 클라이언트가 보낸 userId/닉네임/점수를 검증 없이 신뢰하지 않는다.

## Article IV — AI / 데이터

11. **Vector 격리는 무조건**: 모든 RAG 검색은 boothId+agentId 필터를 강제한다. Booth A의 Agent가 Booth B의 chunk를 1건이라도 반환하면 릴리스 불가 (Critical Test).
12. **Embedding 차원은 고정**: `vector([회의 후 확정])`. 변경은 전량 재임베딩을 수반하므로 chunk에 embedding_model_id를 기록한다.
13. **AI Streaming은 SSE** (start/token/source/done/error 이벤트 스키마로 정규화). Provider raw 응답을 프론트로 직접 흘리지 않는다. `[회의 후 확정: LLM Provider, timeout 수치]`
14. **Coin·Lease·Reward 변경은 REST/DB 트랜잭션으로만** 한다. Realtime 이벤트로 영구 경제 상태를 바꾸지 않는다. 모든 지급/차감은 Ledger에 기록하고 idempotency를 보장한다.

## Article V — UI

15. **텍스트 입력 UI는 React 레이어에서 처리한다** (AI 채팅·상담·설문). Unity WebGL의 한글 IME 입력을 핵심 UX 경로에 두지 않는다. Unity는 상호작용 트리거만 발생시킨다.

## Article VI — 프로세스

16. **기준선 동결 준수** (docs/23): 동결된 기준선 코드를 재구현/리팩터링하지 않는다. spec 없이 만들어진 코드가 살아남으면 소급 spec을 작성한다.
17. **범위 통제**: P0/P1/P2와 Cut Line(doc 01 §12)을 따른다. 현재 요구보다 확장 기능 구현을 우선하지 않는다. Week 6 이후 핵심 아키텍처 변경 금지.
18. **기록 의무**: 작업은 docs/24, 트러블은 docs/25에 기록한다 (CLAUDE.md/AGENTS.md 규칙).
19. **API 계약 변경 절차** (doc 17 §17): Consumer 통보 없는 Breaking Change 금지. Layout JSON 스키마는 React/Spring/Unity 3파트 합의로만 변경한다.
20. **미정 항목은 구현자가 임의 확정하지 않는다** — docs/26에 등록하고 해당 spec의 clarify 또는 팀 결정으로 푼다.

---

## 회의 후 채울 빈칸 요약

| 조항 | 빈칸 | 근거 자료 |
|---|---|---|
| 5 | LB 종류 (AWS 실측 후) | handoff §4 |
| 6 | EC2/ECS 최종 | docs/21 결정6 |
| 8 | 토큰 TTL 등 상세 | docs/21 결정5 |
| 12 | Embedding 모델/차원 | docs/21 결정2 + AI 스파이크 |
| 13 | LLM Provider, timeout | docs/21 결정3 |
