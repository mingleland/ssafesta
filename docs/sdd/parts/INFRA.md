# Infra 파트 — SDD 권장 브리프

> **상태**: 권장안 (2026-08-12) — Infra 담당자가 검토·수정 후 사용한다.
> 확정 전제 (2026-08-12): AWS 서버 제공됨. 도메인은 팀 자체 구매. 모든 서버 컴포넌트 Docker (헌법 7조).
> 브랜치/CI-CD 전략은 팀 결정 사항 (헌법 9조) — 아래 §2가 그 상세안.

---

## 1. 담당 범위

Infra는 기능 spec보다 **플랫폼 spec** 성격이다. 권장 spec 구성:

| Spec | 이름 | 내용 |
|---|---|---|
| infra-001 | ci-cd-pipelines | 파트 브랜치 4개 + develop 파이프라인 |
| infra-002 | environments | dev(브랜치별) / demo(develop) 환경 구성 |
| infra-003 | unity-server-deploy | Unity Dedicated Server 배포 (wss 실측 포함) |

## 2. 브랜치/CI-CD 전략 (2026-08-12 팀 결정)

```text
ai ──────┐  각 파트 브랜치: push 시 자체 CI/CD → 개발환경에 "개별" 배포
back ────┤  (파트가 서로를 기다리지 않고 빠르게 작업)
front ───┤
game ────┘
   │ 완료된 상태만 병합 (Squash)
   ▼
develop ──→ 실사용 환경 기준 CI/CD (모든 컴포넌트 통합 배포 = demo 환경)
```

**infra-001 specify 입력 (초안)**

```text
파트 브랜치(ai/back/front/game) 각각에 CI/CD 파이프라인을 둔다.
- CI: 빌드 + 테스트 (파트별 스택: Gradle / npm / pytest / Unity 빌드)
- CD: 해당 파트의 개발환경 인스턴스에 자동 배포 (파트 간 독립 — front 배포가 back을 재시작하지 않는다)
develop 브랜치는 실사용 환경 기준으로 CI/CD한다: 전 컴포넌트를 함께 배포하고
통합 헬스체크(웹 접속 → 로그인 → 월드 입장 → AI 응답)를 통과해야 성공으로 본다.
Secret은 파이프라인 Secret 저장소에서 주입하며 저장소에 커밋하지 않는다 (헌법 11조).
Unity 빌드는 캐시(Library) 전략 필수 — 캐시 없으면 빌드 시간이 파이프라인을 지배한다.
```

**infra-001 clarify 확정 (2026-08-18)**: CI/CD는 Jenkins를 사용한다. 초기에는 단일 EC2 안에서 Jenkins Controller와 Agent를 논리적으로 분리하고 Controller는 Executor 0으로 빌드를 직접 실행하지 않는다. 소스 저장소가 GitHub에서 GitLab으로 이전되더라도 Jenkins 파이프라인은 유지하고 Webhook 연동만 전환한다.

## 3. infra-002 — environments

```text
dev: 파트 브랜치별 배포 대상. 서로 독립적으로 갱신된다. Mock 연동 허용.
demo(=develop): 실사용 기준 — HTTPS/WSS, 실제 도메인, 실제 GMS 키, 전 컴포넌트 통합.
dev는 EC2 공인 IP의 제한된 진입점을 유지하고, 도메인과 TLS는 최종 demo에만 적용한다.
Cloudflare DNS에서 demo./api./ai./world. 서브도메인을 같은 EC2에 연결하고 Proxy를 사용한다.
원본 TLS는 EC2 Nginx의 Let's Encrypt 인증서로 처리하며 Cloudflare는 Full (strict)로 연결한다.
```

**infra-002 clarify 반영 (2026-09-06 갱신)**: dev front·api·ai는 EC2 IP 제한 경로를 유지한다. UnityTransport는 URL path를 지원하지 않아 dev game만 `world-dev.${ROOT_DOMAIN}` WSS host를 사용한다. demo 서브도메인은 `demo`·`api`·`ai`·`world`로 분리한다.

## 4. infra-003 — unity-server-deploy

기존 산출물 재사용: `festa-unity/Docker/Dockerfile`(검증 완료)과 `festa-unity/Docs/deployment-handoff.md`의 WebSocket·endpoint 계약. 기존 handoff의 ECS/LB 전제는 현재 단일 EC2 구조에 맞춰 별도로 정합화한다.

```text
11층·단일 채널용 Unity Dedicated Server 컨테이너 1개를 단일 EC2에 배포한다.
브라우저는 wss://world.<도메인>:443으로 접속하고, Cloudflare DNS/Proxy를 거쳐
EC2 Nginx(Let's Encrypt)에서 원본 TLS를 종료한 뒤 Docker 내부의 ws://unity:7777로 전달한다.
7777은 외부에 공개하지 않는다. ECS·ALB·NLB·ACM과 층별 인스턴스·자동 채널링은 현재 범위에서 사용하지 않는다.
검증 완료 조건: 외부 브라우저 2개 동시 접속과 상호 이동, 무입력 연결 유지, 연결 종료 후 재접속이 모두 성공한다.
```

이것이 **docs/22의 "AWS wss 실측"** 항목이며, Nginx WebSocket Upgrade·timeout·heartbeat 설정까지 실제 외부 경로로 검증한다.

## 5. P1 대비 메모 (spec 017 proximity-voice)

거리 기반 음성채팅은 WebRTC SFU(LiveKit/mediasoup 계열) 서버가 추가로 필요하다 — NGO/게임 서버로 음성을 실어 나르지 않는다. 2차 MVP 시점에 SFU 호스팅(같은 EC2 vs 분리)과 TURN 필요 여부를 확인한다. 지금은 서버 사양 여유만 염두.
