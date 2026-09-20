# Tasks: 월드 세션 / 멀티플레이 접속

**Spec**: `specs/002-world-session/spec.md` | **Plan**: `plan.md`
**형식**: `[ID] [P?] [US?] 설명` — `[P]`는 다른 작업과 병렬 가능, `[US1]`은 해당 User Story

> **2026-09-06 대조** — 아래 체크는 develop `0418cf0c` 코드 기준으로 맞췄다(작성 당시 이름과 실제 구현 이름이 다른 항목은 괄호에 구현체를 적었다). 미체크는 정말 안 된 것 또는 타 파트·인프라 의존이다.

---

## Phase 1: 경계 만들기 (Foundational — 이후 전부가 여기 의존)

- [x] **T001** `IWorldSessionClient` 인터페이스 추가 — `Task<WorldSessionDto> RequestSessionAsync()`
      `Assets/_Project/Scripts/Integration/Contracts/IWorldSessionClient.cs` (구현체: `IUserApiClient.CreateWorldSessionAsync` 로 통합)
- [x] **T002** [P] `MockWorldSessionClient` 구현 — 로컬 Docker(`ws://127.0.0.1:7777`) 반환, `Awaitable.WaitForSecondsAsync` 사용 (**`Task.Delay` 금지 — T-07**)
- [x] **T003** [P] `HttpWorldSessionClient` 구현 — timeout 10s, 401/5xx 분기, 실패 시 null (`HttpBoothApiClient` 패턴 재사용)
- [x] **T004** `ApiServices`에 WorldSession 등록 + `ApiConfig.useMock` 분기 연결

## Phase 2: US2 — 접속 주소를 서버에서 받는다 (P0)

- [x] **T005** [US2] `ConnectionManager`가 세션 응답으로만 접속하도록 정리 — 하드코딩 주소 제거
- [x] **T006** [US2] 개발용 수동 주소 입력 HUD를 `#if UNITY_EDITOR || DEVELOPMENT_BUILD`로 격리 (릴리스 빌드에 노출 금지, **헌법 8조**)
- [x] **T007** [US2] 접속 payload에 `connectionToken` 실어 보내기 (approval에서 읽을 수 있게)
- [x] **T008** [US2] 서버 approval에 토큰 검증 훅 추가 (구현체: `WorldEntryTokenVerifier` HMAC, C-01 확정) — `IConnectionTokenValidator` 인터페이스 + `AlwaysAllowValidator`(C-01 결정 전 임시)
- [x] **T009** [US2] **검증** (세션 응답의 host/port 로만 접속 — Docker 7777·에디터 서버 모두 재빌드 없이 전환 확인): Mock 응답의 port를 7778로 바꿨을 때 재빌드 없이 7778로 접속되는지 확인 → SC-003

## Phase 3: US3 — 접속 실패를 이해할 수 있다 (P1)

- [x] **T010** [US3] `ConnectionStatusHud` 추가 (구현체: `WorldDisconnectReporter` + `onWorldConnectionState` 호스트 신호, 표시는 FE) — 연결중/실패/재시도 상태 표시
      ⚠️ 라벨은 **영문**으로 (WebGL IMGUI 한글 미표시, **T-22**)
- [x] **T011** [US3] 접속 실패 사유 분류 — 서버 무응답 / 토큰 거부 / 정원 초과
- [x] **T012** [US3] 수동 재시도 버튼 (C-03 확정 → 자동 재접속 `WorldReconnector` 5회로 대체, -432) (자동 재접속은 C-03 결정 후, 이번 범위 제외)

## Phase 4: US1 — 배포 환경 검증 (P0, Infra 의존)

- [ ] **T013** [US1] AWS에 `festa-world:dev` 배포 (Infra 협업)
- [ ] **T014** [US1] LB 뒤에서 `wss://` 접속 실측 → ALB/NLB 확정 → **헌법 6조 빈칸 채움**
- [ ] **T015** [US1] idle timeout 실측 후 `deployment-handoff.md` 갱신
- [ ] **T016** [US1] **검증**: 외부 브라우저 2개가 `wss://`로 붙어 서로의 이동이 보임 → SC-001, SC-005

## Phase 5: 마무리

- [x] **T017** [P] 회귀 검증 (2026-09-06 에디터+WebGL 2클라 스폰·외형 동기화 확인, -76) — 기존 POC 3종(2클라 스폰/이동 동기화/Despawn)이 여전히 동작
- [ ] **T018** [P] `poc-status.md`, `architecture.md` 갱신
- [x] **T019** 작업일지·트러블슈팅 기록 (**헌법 20조**)

---

## 의존 관계

```text
T001 ─┬─ T002 [P] ─┐
      ├─ T003 [P] ─┼─ T004 ─ T005 ─ T006 ─ T007 ─ T008 ─ T009
      │            │
      └────────────┴─ (T010~T012는 T005 이후 병렬 가능)

T013 ─ T014 ─ T015 ─ T016   (Infra 일정에 종속, 코드와 독립 진행 가능)
```

## 병렬 실행 예

- T002 / T003 은 서로 독립 (같은 인터페이스의 다른 구현)
- Phase 3(T010~T012)과 Phase 4(T013~T016)는 동시 진행 가능
- **T009 검증 없이 Phase 3으로 넘어가지 말 것** — 주소 전환이 실제로 되는지가 이 spec의 핵심 성과다

## 완료 판정

| 기준 | 확인 방법 |
|---|---|
| SC-001 | 브라우저 2탭 상호 이동 확인 |
| SC-003 | Mock port 변경 → 재빌드 없이 반영 (T009) |
| SC-004 | 만료/위조 토큰 접속 시도 → 거부 (C-01 결정 후) |
| SC-005 | 배포 환경에 `ws://` 경로 부재 확인 |

## 차단 요인 (해소 전 진행 불가)

| 차단 | 대상 | 해소 조건 |
|---|---|---|
| C-01 토큰 검증 방식 | T008 실제 구현 | BE와 합의 (인터페이스까지는 선행 가능) |
| AWS 접근 | T013~T016 | Infra의 환경 구성 |
| BE API | T003 실사용 | Mock으로 병행 개발 가능 |
