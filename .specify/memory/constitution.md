# SSAFY FESTA Constitution

**버전**: v1.1 | **비준일**: 2026-08-12 | **최종 개정**: 2026-08-12

> 모든 spec / plan / 구현이 위반할 수 없는 프로젝트 최상위 원칙.
> v1.0의 미확정 항목을 2026-08-12 팀 결정으로 채웠다. 잔여 미확정은 `(AWS 실측 후)` 표기.
> AI 세션(Claude / Codex) 공용 — 루트 `CLAUDE.md` / `AGENTS.md`가 이 문서를 참조한다.

---

## Article I — 아키텍처 경계

1. **Source of Truth 분리**: 영구 비즈니스 상태(User/Booth/Lease/Coin/Layout/Survey/Staff/Avatar)는 Spring이 유일한 기준이다. Unity·React는 이를 복제 구현하지 않는다.
2. **실시간/영구 분리**: Unity Dedicated Server는 실시간 월드 상태(위치/스폰/존/외형 문자열)만 권위를 갖는다. Coin·Lease를 게임 서버가 변경하지 않는다.
3. **AI 장애 격리**: FastAPI 장애가 월드 접속·비AI 기능을 중단시키지 않는다. Spring이 AI 응답을 동기 중계하는 구조를 만들지 않는다.
4. **Booth는 데이터로 생성한다**: 부스 콘텐츠 추가에 Unity 재빌드가 필요한 구조를 금지한다. 정적 Booth Object는 NetworkObject로 만들지 않는다.
5. **아바타도 데이터로 생성한다**: 외형은 인코딩 문자열 하나만 동기화하고 각 클라이언트가 로컬 생성한다. 외형 오브젝트에 NetworkObject를 붙이지 않는다.

## Article II — 네트워크 / 배포

6. **Transport는 WebSocket**: 브라우저는 UDP 불가. 배포 환경 연결은 항상 `wss://`(TLS는 LB에서 종료)이며 `ws://`를 프로덕션에 노출하지 않는다. *LB 종류(ALB/NLB)는 AWS 실측 후 확정.*
7. **모든 서버 컴포넌트는 Docker 이미지로 빌드한다** (Spring/FastAPI/Unity Server). EC2로 시작하되 Unity Server는 Week 6 전 ECS 이관을 검토한다. **AWS 자원(EC2 등)은 제공받는다.**
8. **endpoint 하드코딩 금지**: Unity는 서버 주소를 world-sessions API 응답(`scheme/host/port + connectionToken`)으로만 받는다.
9. **월드 세션은 구역(층/채널) 단위다**: world-sessions 요청은 **1차 MVP부터 목적 층 파라미터를 포함**한다. 층 이동은 세션 전환으로 처리한다 (spec 018).
10. **브랜치/CI-CD**: `ai`/`back`/`front`/`game` 파트 브랜치는 각각 CI/CD를 가지며 개발환경에 **개별 배포**된다. `develop`은 **실사용 환경 기준**으로 CI/CD하며 완료된 상태만 병합한다. Merge는 Squash.

## Article III — 보안 / 인증

11. **로그인은 소셜 전용**: 자체 이메일·비밀번호 회원가입을 만들지 않는다. **Google / Kakao** 소셜 로그인과 **게스트 로그인**만 제공한다.
12. **게스트는 영속화하지 않는다**: 게스트 세션은 둘러보기 전용이며 종료 시 데이터를 삭제한다. 게스트에게 부스 임대·결제·영구 자산을 허용하지 않는다.
13. **Refresh Token은 Unity·게임 서버에 절대 전달하지 않는다.** 토큰은 4계층(Refresh / Access / Connection / WS)으로 분리하고, Unity 접속은 TTL이 짧은 1회용 connection token만 사용한다.
14. **접속 토큰은 서명으로 검증한다**: 게임 서버는 접속 승인 시 토큰 서명을 자체 검증하고, 사용된 토큰 식별자를 기록해 재사용을 차단한다. *게임 서버가 매 접속마다 Spring에 조회하는 구조를 만들지 않는다 — Spring 장애가 월드 입장을 막으면 안 된다 (3조 정신).*
15. **Secret은 저장소에 커밋하지 않는다** (`.env.example`만 허용). LLM/Embedding API Key는 **GMS 지급 키**를 Secret 저장소에서 주입한다. 지급 전에는 어댑터 뒤 Mock으로 개발한다.
16. **클라이언트 주장 불신**: 서버는 클라이언트가 보낸 userId/닉네임/점수/아바타 값을 검증 없이 신뢰하지 않는다.

## Article IV — AI / 데이터

17. **Vector 격리는 무조건**: 모든 RAG 검색은 boothId+agentId 필터를 강제한다. Booth A의 Agent가 Booth B의 chunk를 1건이라도 반환하면 릴리스 불가 (Critical Test).
18. **Embedding 차원은 고정**: `vector(1536)` (관리형 API). 변경은 전량 재임베딩을 수반하므로 chunk에 `embedding_model_id`를 기록한다.
19. **AI Streaming은 SSE**: 이벤트 스키마는 `start / token / source / done / error`로 정규화한다. Provider raw 응답을 프론트로 직접 흘리지 않는다. 1차 모델은 어댑터 뒤 저가 모델, timeout은 **TTFT 15s / 전체 60s**.
20. **Coin·Lease·Reward 변경은 REST/DB 트랜잭션으로만** 한다. Realtime 이벤트로 영구 경제 상태를 바꾸지 않는다. 모든 지급/차감은 Ledger에 기록하고 idempotency를 보장한다.

## Article V — 계약 (파트 간 고정 규약)

21. **Layout JSON 좌표 규칙** (3파트 확정, 2026-08-12):
    - 값의 단위는 **미터(m)**. React 편집기가 저장 직전 픽셀·그리드를 미터로 환산해 보낸다.
    - 원점은 **부스 바닥의 중앙**, `y = 0`이 바닥면.
    - **+Z가 부스 정면**(방문자가 부스를 바라볼 때 안쪽), +X는 그 기준 오른쪽. 2D 편집기의 아래 방향은 `-Z`.
    - `rotationY`는 도(degree). `0`이면 오브젝트가 `+Z`를 바라보며, 위에서 볼 때 시계 방향이 +.
    - 확정 후 **오브젝트 1개짜리 왕복 검증 1회**를 반드시 수행한다.
22. **부스당 오브젝트 상한은 12개**. 성능 실측 결과 부족하면 하향한다.
23. **아바타 인코딩 문자열은 최대 2000자**이며, 저장 컬럼은 `TEXT`(또는 2000자 이상 가변 문자열)로 만든다. *과거 계약서의 "29~32자"는 무효다.*
24. **API 계약 변경 절차**: Consumer 통보 없는 Breaking Change 금지. Layout JSON·Bridge Event·SSE 스키마·아바타 인코딩 변경은 관련 파트 합의로만 한다.

## Article VI — UI

25. **텍스트 입력·외부 콘텐츠 표시는 React 레이어가 처리한다** (AI 채팅·상담·설문·부스 노트북 홈페이지). Unity WebGL의 한글 IME를 핵심 UX 경로에 두지 않으며, Unity는 상호작용 트리거만 발생시킨다. 외부 페이지는 iframe 차단(X-Frame-Options/CSP) 시 **새 탭 fallback을 반드시 제공**한다.
26. **음성·마이크는 브라우저가 처리한다**: Unity Web은 마이크를 사용할 수 없다. 음성은 WebRTC/SFU 경로로 다루며 **게임 서버를 경유하지 않는다**. Unity는 위치 정보만 제공한다 (spec 017).

## Article VII — 프로세스

27. **기준선 동결 준수**: 동결된 기준선 코드를 재구현/리팩터링하지 않는다. spec 없이 만들어진 코드가 살아남으면 소급 spec을 작성한다 (002 / 006 / 013 해당).
28. **범위 통제**: P0/P1/P2와 Cut Line을 따른다. Week 6 이후 핵심 아키텍처 변경 금지. 미니게임은 **1종만** 만든다.
29. **개인 기록 의무**: 팀원 각자는 ① 작업이 끝날 때마다 **본인 작업일지를 날짜별로** 갱신하고 ② 트러블 발생 시 해결 여부와 무관하게 **본인 트러블슈팅 문서에 날짜와 함께** 등록하며 ③ **매일 작업 종료 시 Jira를 반드시 1회** 갱신한다. AI 세션에 시킨 작업도 동일하게 기록한다.
30. **미정 항목은 구현자가 임의 확정하지 않는다** — `docs/26_팀_결정_필요사항.md`에 등록하고 해당 spec의 `/speckit-clarify` 또는 팀 결정으로 푼다.

---

## Governance

- 이 헌법은 다른 모든 관행에 우선한다. 개정은 팀 합의 + 문서화 + 영향 spec 갱신을 수반한다.
- 모든 PR/리뷰는 헌법 준수를 확인한다. 복잡도를 늘리는 선택은 근거를 남긴다.
- 개발 지침은 `docs/sdd/README.md`와 파트별 브리프(`docs/sdd/parts/`)를 따른다.

## 잔여 미확정 (AWS 실측 후)

| 조항 | 항목 |
|---|---|
| 6 | ALB vs NLB, idle timeout 최종값 |
| 7 | ECS 이관 시점 |
| — | pgvector 공유/분리, SQS 도입 시점 |

**Version**: 1.1.0 | **Ratified**: 2026-08-12 | **Last Amended**: 2026-08-12
