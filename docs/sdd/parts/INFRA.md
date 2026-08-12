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

**예상 clarify**: CI 러너(GitLab CI 예상 — 팀 GitLab 사용 시), Unity 라이선스 활성화 방식(개인 라이선스 CLI), 파트 브랜치 dev 인스턴스를 1대에 포트 분리 vs 분리 인스턴스(제공 서버 사양 확인 후), 배포 실패 롤백 방식.

## 3. infra-002 — environments

```text
dev: 파트 브랜치별 배포 대상. 서로 독립적으로 갱신된다. Mock 연동 허용.
demo(=develop): 실사용 기준 — wss, 실제 도메인, 실제 GMS 키, 전 컴포넌트 통합.
도메인: 팀 구매 도메인 기준 서브도메인 설계 권장 (예: app./api./ai./world.) — 특히 world는 wss 인증서 필요.
```

**예상 clarify**: 도메인 이름·서브도메인 구조, 인증서(ACM), dev에 도메인 붙일지(포트 직결 허용?).

## 4. infra-003 — unity-server-deploy

기존 산출물 재사용: `festa-unity/Docker/Dockerfile`(검증 완료), `festa-unity/Docs/deployment-handoff.md`(NLB/ALB 트레이드오프, idle timeout, endpoint 계약).

```text
Unity Dedicated Server 컨테이너를 EC2에 배포하고 LB 뒤에서 wss://로 노출한다.
TLS는 LB에서 종료 (Unity 컨테이너는 ws). Health check 방식에 따라 NLB(TCP 체크, 코드수정 0)
vs ALB(/healthz 추가 필요)를 실측으로 결정한다 — docs/26 ③.
검증 완료 조건: 외부 브라우저 2개가 wss://world.<도메인>으로 동시 접속해 서로의 이동이 보인다.
```

이것이 **docs/22의 "AWS wss 실측"** 항목 — SDD와 병행 가능하며 헌법 6조의 마지막 빈칸(ALB/NLB)을 채운다.

## 5. P1 대비 메모 (spec 017 proximity-voice)

거리 기반 음성채팅은 WebRTC SFU(LiveKit/mediasoup 계열) 서버가 추가로 필요하다 — NGO/게임 서버로 음성을 실어 나르지 않는다. 2차 MVP 시점에 SFU 호스팅(같은 EC2 vs 분리)과 TURN 필요 여부를 확인한다. 지금은 서버 사양 여유만 염두.
