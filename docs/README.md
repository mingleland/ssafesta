# SSAFY FESTA

SSAFY FESTA는 SSAFY 구성원이 가상 공간에서 만나 프로젝트와 AI 서비스를 전시하고, 직접 부스를 제작·운영할 수 있는 사용자 제작형 소셜 메타버스 플랫폼입니다.

사용자는 Unity 기반 월드를 탐색하고, Booth Studio에서 영상·프로젝트·설문·AI 직원을 조합해 부스를 구성합니다. 게시된 부스는 Layout 데이터에 따라 Unity 월드에 반영되며, 다른 사용자는 같은 공간에서 이를 체험할 수 있습니다.

> 현재 저장소에는 기획·설계 문서와 Unity 파트 문서만 공개되어 있습니다. 애플리케이션 코드, Unity 에셋, 빌드 결과물과 외부 레퍼런스는 포함하지 않습니다.

## 핵심 가치

- 흩어진 SSAFY 프로젝트와 포트폴리오를 하나의 체험형 공간에서 발견
- 별도의 Unity 개발 없이 데이터 기반 부스를 제작하는 제한형 UGC
- Prompt와 RAG 문서를 활용한 사용자 설정형 AI 직원 운영
- AI 상담에서 실제 담당자 상담으로 이어지는 운영 흐름
- 영구 서비스 데이터와 실시간 월드 상태를 분리한 확장 가능한 구조

## 시스템 구성

```text
사용자 브라우저
├─ React Web
│  └─ 인증, Booth Studio, AI 설정, 설문, 운영 대시보드
└─ Unity Web
   └─ 월드 렌더링, 아바타, 멀티플레이, Booth Runtime

Spring Boot API ─ PostgreSQL / Redis / S3
├─ 사용자, 부스, 임대, 코인, 권한 등 영구 비즈니스 상태
└─ World Session 및 Channel 연결 정보

FastAPI AI
└─ 문서 처리, Embedding, RAG, LLM Streaming

Unity Dedicated Server
└─ 접속자, 이동, Presence와 실시간 월드 상태
```

정적 Booth 오브젝트는 네트워크 오브젝트로 동기화하지 않고 Published Layout을 기반으로 각 클라이언트에서 생성합니다. AI 채팅·상담·설문처럼 한글 입력이 필요한 UI는 React 오버레이가 담당하고, Unity는 상호작용 진입 이벤트만 전달합니다.

## 기술 구성

| 영역 | 주요 기술 및 방향 |
| --- | --- |
| Web | React 기반 관리·운영 UI와 Unity Web 연동 |
| Unity | Unity 6000.0.78f1, URP 17.0.4, Netcode for GameObjects 2.4.3 |
| 실시간 통신 | Unity Transport 2.5.1, WebSocket, Dedicated Server |
| Backend | Spring Boot, PostgreSQL, Redis, S3 |
| AI | FastAPI, Embedding, pgvector, RAG, SSE Streaming |
| Infra | Docker, AWS 배포 설계, LB TLS 종료, ECS 확장 고려 |

## 현재 진행 상태

Unity POC 단계에서 다음 항목을 로컬 검증했습니다.

- 브라우저 Web 빌드 다중 접속과 플레이어 Spawn·Despawn·이동 동기화
- Published Layout Mock을 이용한 Booth Runtime 오브젝트 생성
- Spring 및 AI 연동 경계를 인터페이스와 Mock으로 분리
- AI Mock의 토큰 단위 스트리밍
- Linux Dedicated Server 빌드와 Docker 컨테이너 연결

다음 핵심 관문은 HTTPS로 배포된 Web 클라이언트에서 AWS Load Balancer를 거쳐 `wss://`로 Unity Dedicated Server에 접속하는 검증입니다. 상세 현황은 [Unity POC 진행 현황](../festa-unity/Docs/poc-status.md)을 참고하세요.

## 문서 안내

### 기획과 사용자 경험

- [문서 인덱스](./00_문서_인덱스.md)
- [프로젝트 기획서](./01_프로젝트_기획서.md)
- [서비스 기능 명세서](./02_서비스_기능_명세서.md)
- [서비스 정책 및 비기능 요구사항](./03_서비스_정책_비기능_요구사항.md)
- [User Flow와 IA](./04_User_Flow_IA.md)
- [UI/UX 디자인 가이드](./05_UIUX_디자인_가이드.md)
- [Figma·Wireframe 명세](./06_Figma_Wireframe_명세서.md)

### 시스템과 파트별 설계

- [전체 시스템 아키텍처](./07_전체_시스템_아키텍처.md)
- [Backend API 명세](./08_Backend_API_명세서.md)
- [DB ERD 및 DB 설계](./09_DB_ERD_DB_설계서.md)
- [Frontend 설계](./10_Frontend_설계서.md)
- [Unity Client 설계](./11_Unity_Client_설계서.md)
- [Unity Game Server·Network 설계](./12_Unity_Game_Server_Network_설계서.md)
- [AI 시스템 설계](./13_AI_시스템_설계서.md)
- [AI Server API 명세](./14_AI_Server_API_명세서.md)
- [Infra·AWS 설계](./15_Infra_AWS_설계서.md)
- [Realtime 통신 명세](./16_Realtime_통신_명세서.md)

### 개발과 운영

- [Git·개발 Convention](./17_Git_개발_Convention.md)
- [Jira 운영 가이드](./18_Jira_운영_가이드.md)
- [Test·QA 계획](./19_Test_QA_계획서.md)
- [Demo·발표 시나리오](./20_Demo_발표_시나리오.md)
- [착수 전 기술 결정 ADR](./21_착수전_기술결정_ADR.md)
- [다음 할 일](./22_다음_할일.md)
- [기준선 동결 워크플로](./23_기준선_동결_워크플로.md)
- [작업일지](./24_작업일지.md)
- [트러블슈팅](./25_트러블슈팅.md)
- [팀 결정 필요사항](./26_팀_결정_필요사항.md)

### Unity 파트 문서

- [Unity 아키텍처](../festa-unity/Docs/architecture.md)
- [에디터 설정 체크리스트](../festa-unity/Docs/editor-setup-checklist.md)
- [POC 진행 현황](../festa-unity/Docs/poc-status.md)
- [Dedicated Server 배포 인수인계](../festa-unity/Docs/deployment-handoff.md)
- [레퍼런스 검토 기록](../festa-unity/Docs/reference-audit.md)

## 문서 상태

설계 문서에는 확정안과 Draft·제안안이 함께 존재합니다. 구현자가 미정 사항을 임의로 결정하지 않도록 [착수 전 기술 결정 ADR](./21_착수전_기술결정_ADR.md)과 [팀 결정 필요사항](./26_팀_결정_필요사항.md)에서 결정 시점과 권장안을 관리합니다.

