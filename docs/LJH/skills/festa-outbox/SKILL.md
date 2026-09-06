---
name: "festa-outbox"
description: "내가 밖에 물어 놓고 답을 못 받은 것을 모은다. `festa-inbox` 의 반대 방향이다 — 그쪽은 나를 기다리는 것을, 이쪽은 내가 기다리는 것을 본다. 상태는 ASC 가 증거에서 파생한 값을 그대로 쓰고 여기서 다시 계산하지 않는다."
argument-hint: "선택: 파트 이름 (front|back|ai|game|infra)"
metadata:
  author: "이정헌"
  jira: "S15P21A604-416"
  consumes: "asc coordination"
user-invocable: true
disable-model-invocation: false
---

# festa-outbox

**방향이 다른 두 skill 이다.** `festa-inbox` 는 GitLab 을 훑어 **나를 기다리는 것**(호명·리뷰 요청·배정)을 가린다. 이 skill 은 그 반대다 — **내가 밖에 물어 놓고 답을 못 받은 것**을 본다. 원본을 다시 훑지 않고 ASC 가 증거에서 파생한 값을 그대로 쓴다.

이 프로젝트에서 같은 일이 반복됐다. 의존이 **문서에 적혀 있다**는 것과, 그 질문이 상대
파트에 **닿았다**는 것과, 상대가 **답했다**는 것이 한 덩어리로 다뤄졌다. 그래서 아무도
답한 적 없는 항목 위에 "계약 확정"이 올라갔고, G-8 입력 잠금은 결정 대기 상태로
`decision-queue.md` 에만 8월 31일부터 놓여 있어 Unity 파트가 닷새 동안 몰랐다.

셋을 갈라 계산하는 일은 도구가 한다. 이 skill 이 하는 일은 **그 결과를 이 팀의 어휘로
옮기고, 어느 질문이 어느 파트로 가야 하는지 정하는 것**뿐이다.

## 무엇을 하지 않는가

- **상태를 다시 계산하지 않는다.** `UNPUBLISHED` / `WAITING_EXTERNAL` / `RESPONSE_RECEIVED`
  는 게시·응답 증거에서 파생된 값이다. 여기서 뒤집거나 보정하지 않는다.
- **답의 의미를 판정하지 않는다.** 코멘트가 달렸다는 것은 "왔다"까지다. 합의됐는지는
  사람이 읽고 정하고, 그 판단은 Jira·계약 문서에 남는다.
- **GitLab 이나 Jira 를 직접 읽어 상태를 추론하지 않는다.** 원본을 다시 훑어 판정하면
  볼 때마다 답이 달라진다.

## 명령

```bash
asc coordination            # 지금 무엇이 안 나갔고 무엇이 답을 기다리는가
asc coordination observe    # 게시한 곳에 답이 왔는지 다시 본다
```

내보낼 때:

```bash
asc coordination publish --query <X-ID> --title "<제목>" --body-file <경로> \
  --known '<group/project#iid>' --work '<JIRA-KEY>' --audience <파트>
```

`--known` 에 이미 있는 이슈를 주면 **거기에 잇는다.** 같은 주제로 새 이슈를 만들지
않는다 — 상대가 두 곳을 보게 되고, 그것이 실제로 났던 사고다.

## 이 팀의 어휘로

| 도구가 말하는 상태 | 이 팀에서는 | 다음에 할 일 |
|---|---|---|
| `UNPUBLISHED` | 우리끼리만 적어 뒀다 | 상대 파트에 실제로 물어야 한다 |
| `WAITING_EXTERNAL` | 물었고 답을 기다린다 | 오래됐으면 사람이 민다 |
| `RESPONSE_RECEIVED` | 답이 왔다 | 사람이 읽고 계약 문서·Jira 를 갱신한다 |
| `PUBLISHED` | 통보였고 답이 필요 없다 | 없음 |

## 누구에게 가는가

질문의 성격이 대상 파트를 정한다. **이 표는 이 프로젝트의 정책이고, 도구는 이 표를 모른다.**

| 질문의 성격 | 대상 | 표면 |
|---|---|---|
| REST 요청·응답 모양, 오류 코드, 대기열·상담 payload | Backend | 해당 주제의 GitLab Issue |
| Unity ↔ React 이벤트 이름·payload, 입력 잠금, 씬 전환 | Game | World 상호작용 계약 Issue |
| RAG 응답·SSE envelope·대화 계약 | AI | AI 계약 Issue |
| DB 경계·배포·환경 변수·자산 base URL | Infra | Infra Issue |
| 화면 동작·UX 결정 | Frontend | 해당 spec 의 Clarifications |

두 파트 이상이 걸리면 **양쪽 모두**를 대상으로 적는다. 제목도 `[back,front] 주제` 처럼
관련 파트를 앞에 단다 — 이 팀의 기존 관행이다.

## 언제 durable 한 표면이 필요한가

다음 중 하나라도 해당하면 내 문서나 Jira 코멘트로 끝내지 않고 GitLab Issue 에 올린다.

- 상대 파트가 **읽고 답해야** 진행되는 것
- 두 파트 이상의 코드가 같은 계약을 참조하는 것
- 결정이 미뤄진 채로 이틀 이상 지난 것

Jira 코멘트에만 두면 그 파트는 볼 자리가 없고, 내 문서에만 두면 더 나쁘다.

## 출력

나가지 않은 것을 맨 위에 둔다 — 그것이 지금 아무도 모르는 것이다.

```text
아직 안 나감
  X-20260906-03  게스트 오류 코드 목록        → back

답 기다리는 중
  X-20260906-01  handoffSummary 필드·STOMP 4항 → back   #133

답 옴 — 사람이 읽어야 함
  X-20260906-02  G-8 입력 잠금 계약           → game   #132
```
