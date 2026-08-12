# SSAFY FESTA Constitution

> **용도**: spec-kit `specify init` 후 `.specify/memory/constitution.md`로 복사한다 (또는 `/speckit.constitution` 실행 시 이 문서를 입력으로 제공).
> 모든 spec/plan/구현이 위반할 수 없는 프로젝트 최상위 원칙.
> **상태: v1.0 (2026-08-12)** — docs/28 초안의 `[회의 후 확정]` 빈칸을 2026-08-12 팀 결정값으로 채움. 잔여 미확정은 `(AWS 실측 후)` 표기.
> AI 세션(Claude/Codex) 공용 — 루트 `CLAUDE.md`/`AGENTS.md`에서 본 문서를 참조한다.

---

## Article I — 아키텍처 경계

1. **Source of Truth 분리**: 영구 비즈니스 상태(User/Booth/Lease/Coin/Layout/Survey/Staff/Avatar 저장값)는 Spring이 유일한 기준이다. Unity·React는 이를 복제 구현하지 않는다.
2. **실시간/영구 분리**: Unity Dedicated Server는 실시간 월드 상태(위치/스폰/존/외형 동기화 문자열)만 권위를 갖는다. Coin·Lease를 게임 서버가 변경하지 않는다.
3. **AI 장애 격리**: FastAPI 장애가 월드 접속·비AI 기능을 중단시키지 않는다. Spring이 AI 응답을 동기 중계하는 구조를 만들지 않는다.
4. **Booth는 데이터로 생성한다**: 부스 콘텐츠 추가에 Unity 재빌드가 필요한 구조를 금지한다. 정적 Booth Object는 NetworkObject로 만들지 않는다 (Layout JSON → Local Spawn).
5. **아바타도 데이터로 생성한다**: 아바타 외형은 인코딩 문자열 하나만 동기화하고 각 클라이언트가 로컬 생성한다 (POC 검증 완료 구조). 외형 오브젝트에 NetworkObject를 붙이지 않는다.

## Article II — 네트워크 / 배포

6. **Transport는 WebSocket**: 브라우저는 UDP 불가. 배포 환경에서 연결은 항상 `wss://`(TLS는 LB에서 종료)이며 `ws://`를 프로덕션에 노출하지 않는다. LB 종류(ALB/NLB)는 AWS 실측 후 확정. *(AWS 서버는 제공됨 — 2026-08-12 결정)*
7. **모든 서버 컴포넌트는 Docker 이미지로 빌드한다** (Spring/FastAPI/Unity Server). 배치는 EC2로 시작하되 Unity Server는 Week 6 전 ECS 이관을 검토한다.
8. **endpoint 하드코딩 금지**: Unity는 서버 주소를 world-sessions API 응답(`scheme/host/port + connectionToken`)으로만 받는다.
9. **브랜치/CI-CD 전략 (2026-08-12 결정)**: `ai` / `back` / `front` / `game` 파트 브랜치는 각각 CI/CD를 가지며 **개발환경에 개별 배포**되어 파트 작업 속도를 보장한다. `develop` 브랜치는 **실사용 환경 기준**으로 CI/CD한다. 완료된 상태만 develop으로 병합하며, develop을 파트 작업장으로 쓰지 않는다. Merge는 Squash를 기본으로 한다.

## Article III — 보안 / 인증

10. **Refresh Token은 Unity·게임 서버에 절대 전달하지 않는다.** 토큰은 4계층(Refresh / Access / Connection / WS)으로 분리하고, Unity 접속은 TTL이 짧은 1회용 connection token만 사용한다 (docs/21 결정5 채택).
11. **Secret은 저장소에 커밋하지 않는다** (.env.example만 허용). LLM/Embedding API Key는 **GMS 지급 키**를 사용하며 Secret 저장소에서 주입한다. 지급 전에는 Mock/어댑터로 개발한다.
12. **클라이언트 주장 불신**: 서버는 클라이언트가 보낸 userId/닉네임/점수/아바타 코드를 검증 없이 신뢰하지 않는다.

## Article IV — AI / 데이터

13. **Vector 격리는 무조건**: 모든 RAG 검색은 boothId+agentId 필터를 강제한다. Booth A의 Agent가 Booth B의 chunk를 1건이라도 반환하면 릴리스 불가 (Critical Test).
14. **Embedding 차원은 고정**: `vector(1536)` (관리형 API, 2026-08-12 확정). 변경은 전량 재임베딩을 수반하므로 chunk에 `embedding_model_id`를 기록한다.
15. **AI Streaming은 SSE** (start/token/source/done/error 이벤트 스키마로 정규화). Provider raw 응답을 프론트로 직접 흘리지 않는다. 1차 모델은 어댑터 뒤 저가 모델, timeout은 TTFT 15s / 전체 60s (2026-08-12 확정).
16. **Coin·Lease·Reward 변경은 REST/DB 트랜잭션으로만** 한다. Realtime 이벤트로 영구 경제 상태를 바꾸지 않는다. 모든 지급/차감은 Ledger에 기록하고 idempotency를 보장한다.

## Article V — UI

17. **텍스트 입력·외부 콘텐츠 표시는 React 레이어에서 처리한다** (AI 채팅·상담·설문·**부스 노트북 홈페이지 열람**). Unity WebGL의 한글 IME를 핵심 UX 경로에 두지 않으며, Unity는 상호작용 트리거만 발생시킨다. 외부 페이지는 iframe 차단(X-Frame-Options/CSP) 시 새 탭 fallback을 반드시 제공한다.

## Article VI — 프로세스

18. **기준선 동결 준수** (docs/23): 동결된 기준선 코드를 재구현/리팩터링하지 않는다. spec 없이 만들어진 코드가 살아남으면 소급 spec을 작성한다 (002/006/013a 해당).
19. **범위 통제**: P0/P1/P2와 Cut Line(doc 01 §12 + 2026-08-12 개정: 캐릭터 커스텀 P0, 노트북 홈페이지 P0, 음성채팅·층 구조 P1, 마피아 P2)을 따른다. 현재 요구보다 확장 기능 구현을 우선하지 않는다. Week 6 이후 핵심 아키텍처 변경 금지.
20. **개인 기록 의무 (2026-08-12 강화)**: 팀원 각자는 ① 작업이 끝날 때마다 **본인 작업일지를 날짜별로** 업데이트하고 ② 트러블 발생 시 해결 여부와 무관하게 **본인 트러블슈팅 문서에 날짜와 함께** 등록하며 ③ **매일 작업 종료 시 Jira를 반드시 1회 업데이트**한다. AI 세션에 시킨 작업도 동일하게 기록한다 (형식: docs/24, docs/25 참조).
21. **API 계약 변경 절차** (doc 17 §17): Consumer 통보 없는 Breaking Change 금지. Layout JSON 스키마는 React/Spring/Unity 3파트 합의로만 변경한다. React↔Unity Bridge Event·SSE 스키마·아바타 인코딩도 동일 절차를 따른다.
22. **미정 항목은 구현자가 임의 확정하지 않는다** — docs/26에 등록하고 해당 spec의 `/speckit.clarify` 또는 팀 결정으로 푼다.

---

## 잔여 미확정 (AWS 실측 후)

| 조항 | 항목 | 판단 근거 |
|---|---|---|
| 6 | ALB vs NLB, idle timeout | festa-unity/Docs/deployment-handoff.md §4 |
| 7 | ECS 이관 시점 최종 | 부하 실측 |
| — | pgvector 공유/분리, SQS 도입 시점 | docs/26 ③ |
