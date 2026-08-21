# FE 백로그 — spec 005

경위·판단 근거는 `24_작업일지.md`, 확정값은 `docs/26` 결정 로그가 원본. **여기는 상태·다음 액션만 둔다.**
완료 항목은 결과 1줄. 경위·근거·검증 내용은 쓰지 않는다.

---

## 지금 할 수 있는 것

- [ ] **`client.ts`의 `ApiError` 갱신** — 봉투 `{code, message, requestId, errors, warnings}`, 원소는 **`{rule, objectId, message}`**(`contracts/layout-api.md`). 확답 나왔으므로 **착수 가능**
- [ ] **SSE 타입 + Mock Stream Fixture** — [#32](https://github.com/kanghyunsoon/ssafesta/issues/32)(CLOSED) 합의 완료. discriminated union(`data.type` 판별), `timeoutPhase`(`FIRST_TOKEN`/`TOTAL_RESPONSE`), `retryable` 매핑 17종 반영
- [ ] **`/speckit-tasks`** — [#43](https://github.com/kanghyunsoon/ssafesta/issues/43) PR #44 머지되면 `FE/`로 이관 후 착수

## 막힌 것 — 대기 중

| 항목 | 막는 것 | 상대 |
|---|---|---|
| **#43** spec 산출물 경로 구조 | FE 산출물 develop PR 전체 | 리드 — 채택됨, **PR #44 머지 대기** |
| **C-04·C-06** 미연결 오브젝트 공개·템플릿 종수 | spec 005 전체 확정 (구현은 PROVISIONAL로 진행 가능) | 기획 — [#45](https://github.com/kanghyunsoon/ssafesta/issues/45) 게시, assignee 지정 |
| **#30** refresh 토큰 쿠키 도메인 | 인증 경로 | @Alexjung0115 TLS 결정 |
| **#34** `GAME_PORTAL`이 `schemaVersion` 올리는가 | ObjectType union 확장 시점 | BE (#36에서 질문함) |
| **#35** Game Studio renderer·sandbox | — | @busypark 몫, FE 관여 없음 |

## 회신 완료 — 상대 답변 대기

| 이슈 | 내가 답한 것 | 대기 |
|---|---|---|
| [#36](https://github.com/kanghyunsoon/ssafesta/issues/36) | 6건 수용 + 계약서 모순 1건 지적(409에 Draft 동봉 서술) | BE 정정 |
| [#33](https://github.com/kanghyunsoon/ssafesta/issues/33) | Game Studio 정책 4건 전부 동의, 신규 진입 차단 판정 시점 질문 | 리드 |
| [#45](https://github.com/kanghyunsoon/ssafesta/issues/45) | C-04·C-06 기획 승인 요청 | 리드·BE |

## 내 것 아님

`#24` back·game 잔여 (FE 몫 완료)

---

## 완료

| | 결과 |
|---|---|
| **블록 0** 08-18 | Architecture 4종 — `overlay.ts`·`events.ts`·`client.ts` + `app/{router,providers}` |
| **블록 1** 08-20 | 좌표 왕복 검증 통과 — C-02 실측 확정, SC-004 근거. 수치: `verify/block1-roundtrip.md` |
| **블록 2** 08-20 | 타 파트 결정 5건 소진 |
| **블록 3** 08-20 | 리뷰 4칸+BE 검토칸 서명, SC-002 해소 |
| **블록 4** 08-21 | spec 005 plan 산출물 4종 (`plan`·`research` R-01~R-11·`data-model`·`quickstart`) — 단, 산출물 경로 구조 확정 전까지 develop PR 보류 |
| `events.ts` 08-20 | `AI_AGENT_INTERACT` 타입 + `toAiChatPayload` (`d1bbb4a`) |
| `BOOTH_LAPTOP_INTERACT` | 브라우저 왕복 검증 완료 (PR #25) |
| 문서 develop 통합 | `docs/26`·`FE.md` 완료 |
| **CLOSED** | #1 #2 #5 #6 #14 #17 #18 #19 #20 #21 #22 #31 #32 |

**해소된 미결** — C-02 좌표 / C-03 고정 크기 / C-05 `expectedRevision`(#36) / C-07 보관O·되돌리기 MVP제외 / **부스 6×6×6m 확정** / 오류 봉투 원소 타입 / SSE payload 전체(#32) / 산출물 경로 구조(#43, 채택·PR #44 대기)

---

## 참고

- **develop 통합 절차**: `develop`에서 브랜치 → `git checkout <자기브랜치> -- <path>` 파일 단위 → `git diff --stat origin/develop -- festa-unity/` 0줄 확인 → PR. **직접 push·전체 merge 금지**(`festa-unity/` 1,462개 삭제)
- **착수 전 `origin/develop` 대조 필수** — 내 브랜치의 공유 문서 사본은 낡았다고 가정
- **013 문서 정합**: front `specs/013/spec.md`가 "FE는 창 이관만"·FR-020·C-07을 기술하는데 `FE.md`만 갱신됨 — 013 진행 시 정리
