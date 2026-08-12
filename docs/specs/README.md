# specs/ — spec 초안 배포본

> **작성 기준일**: 2026-08-12
> **작성자**: Unity 리드 (초안)
> **상태**: 검토 대기 — 각 파트가 리뷰 3칸을 채워야 확정

---

## 1. 이 폴더가 무엇인가

SDD(Spec-Driven Development)의 **spec.md 초안**들이다. 각 spec은 "무엇을 만들 것인가"를 정의하고,
"어떻게 만들 것인가"(plan.md)와 "무슨 작업을 할 것인가"(tasks.md)는 **각 파트가 직접 생성**한다.

```text
spec.md    ← 리드가 초안 작성, 파트가 검토·확정   (이 폴더)
   ↓
plan.md    ← 각 파트가 자기 repo에서 /speckit.plan 실행
   ↓
tasks.md   ← 각 파트가 /speckit.tasks 실행
```

**왜 이렇게 나눴는가**: spec은 파트 간 계약과 MVP 범위를 담고 있어 한 사람이 일관되게 써야 하고,
plan은 각 파트의 기술 사정을 아는 사람만 제대로 쓸 수 있다.

## 2. 여기 있는 것 / 없는 것

**리드가 초안을 쓴 것 (6개)** — 파트 경계를 넘거나 계약이 걸린 spec:

| Spec | 이름 | 검토 담당 | 성격 |
|---|---|---|---|
| 001 | auth-user | BE | 토큰 구조가 Unity까지 영향 |
| 002 | world-session | BE | Unity 소급 spec + BE 신규 API |
| 005 | booth-studio-layout | **FE + BE** | **Layout JSON 계약의 원본** |
| 006 | booth-runtime | Unity(자체) | Unity 소급 spec |
| 013 | avatar-customization | FE + BE | P0 승격, Unity 구현 완료 |
| 016 | booth-laptop-homepage | FE + BE | 신규, 기술 리스크 큼 |

**각 파트가 직접 쓸 것** — 한 파트 안에서 닫히는 spec:

| Spec | 이름 | 작성 담당 |
|---|---|---|
| 003 | wallet-coin | BE |
| 004 | booth-slot-lease | BE (임대부스 논의 D01~D08 결과 반영) |
| 007 | ai-agent-document | AI |
| 008 | ai-conversation-rag | AI (SSE 스키마는 FE와 공동) |
| 009 | project-exhibition | BE + FE |
| 010~015, 017, 018 | P1 이후 | 해당 파트 |

작성 가이드는 `docs/sdd/parts/{BE,FE,AI,INFRA}.md`에 spec별 입력 초안과 예상 clarify 항목으로 정리되어 있다.

## 3. 검토하는 법 (중요)

각 spec 문서 맨 아래에 **리뷰 3칸**이 있다. 세 칸을 다 채워야 검토 완료로 본다.

| 칸 | 해야 할 일 |
|---|---|
| ① Clarification 답변 | `[NEEDS CLARIFICATION]`과 C-xx 표에 답한다. **모른다고 비워두지 말고** "언제까지 누가 정함"이라도 쓴다 |
| ② 틀린 요구사항 지적 | 리드가 추측으로 쓴 것 중 사실과 다르거나 과한 것을 지운다. **"좋아요"만 남기면 검토가 아니다** |
| ③ 빠진 요구사항 추가 | 담당자만 아는 필수 요구사항을 넣는다 |

> **초안 작성자가 clarify를 대신 답하지 않은 이유**: 헌법 22조 — 미정 항목은 구현자가 임의 확정하지 않는다.
> 리드가 "임대 1일 = 24시간" 같은 걸 대신 정해버리면 나중에 뒤집힐 때 비용이 커진다.

## 4. 검토가 끝난 뒤

1. spec.md를 자기 repo의 `specs/<번호>-<이름>/spec.md`로 복사한다.
2. spec-kit을 설치한다 (`docs/sdd/README.md` §2 — Claude/Codex 둘 다 쓸 수 있게 두 번 실행).
3. `/speckit.plan` → `/speckit.tasks` → `/speckit.implement` 진행.
4. **계약 관련 변경이 생기면 이 폴더의 원본도 갱신**하고 관련 파트에 알린다 (헌법 21조).

## 5. 3파트 합동 확정이 필요한 것

아래는 한 파트가 단독으로 정할 수 없다. 관련자가 모여 한 번에 정하는 게 가장 싸다.

| 계약 | 관련 spec | 참여자 |
|---|---|---|
| **Layout JSON 스키마** (좌표 원점·단위·configId 확장) | 005, 006, 016 | FE + BE + Unity |
| Unity → React 상호작용 이벤트 payload | 006, 008, 016 | FE + Unity |
| 아바타 문자열 길이 상한·저장 타입 | 013 | BE + Unity |
| 접속 토큰 검증 방식 | 001, 002 | BE + Unity |
| SSE 이벤트 스키마 | 008 | AI + FE |

## 6. 주의 — 기존 계약서 중 무효가 된 것

`festa-unity/Docs/avatar-customization-contract.md`의 **"avatarCode 최대 29자/32자"는 무효**다.
프리셋 방식 기준으로 작성됐으나 파츠 조립 방식 채택으로 실제 600자 이상이 된다 (T-24 참조).
spec 013 C-01에서 재확정한다.
