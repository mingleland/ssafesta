# FE 백로그 — spec 005

경위·판단 근거는 `24_작업일지.md`, 확정값은 `docs/26` 결정 로그가 원본. **여기는 상태·다음 액션만 둔다.**
완료 항목은 결과 1줄. 경위·근거·검증 내용은 쓰지 않는다.

---

## 지금 할 수 있는 것

- [ ] **`/speckit-tasks`** — plan 산출물 4종 완료. 단, [#43](https://github.com/kanghyunsoon/ssafesta/issues/43)(산출물 경로 구조) 확정 후가 안전
- [ ] **`client.ts`의 `ApiError` 갱신** — 봉투 `{code, message, requestId, errors, warnings}`, 원소는 **`{rule, objectId, message}`**(`contracts/layout-api.md`). 확답 나왔으므로 **착수 가능**
- [ ] **SSE 타입 + Mock Stream Fixture** — [#32](https://github.com/kanghyunsoon/ssafesta/issues/32) 합의 완료. discriminated union(`data.type` 판별), `timeoutPhase`(`FIRST_TOKEN`/`TOTAL_RESPONSE`), `retryable` 매핑 17종 반영

## 막힌 것 — 대기 중

| 항목 | 막는 것 | 상대 |
|---|---|---|
| **#43** spec 산출물 경로 구조 | FE 산출물 develop PR 전체 | 리드·BE |
| **C-04** 미연결 오브젝트 공개 차단 | spec 005 전체 확정 (구현은 PROVISIONAL로 진행 가능) | 기획 |
| **#30** refresh 토큰 쿠키 도메인 | 인증 경로 | @Alexjung0115 TLS 결정 |
| **#34** `GAME_PORTAL`이 `schemaVersion` 올리는가 | ObjectType union 확장 시점 | BE (#36에서 질문함) |
| **#21** GameProject 계약 | `features/game-entry/`(`configId → gameId`) | BE |
| **#20** `specs/019` contracts 편집 주체 | Game Studio 소켓 4종 | 리드 |
| `assetCode` 목록 | 자산 분기 종단 검증 | Unity 카탈로그 확장 |
| **#33** 공개 중단·삭제·랭킹 정책 4건 | Game Studio 범위 | **내 회신 미착수** |

## 회신 완료 — 상대 답변 대기

| 이슈 | 내가 답한 것 | 대기 |
|---|---|---|
| [#36](https://github.com/kanghyunsoon/ssafesta/issues/36) | 6건 수용 + 계약서 모순 1건 지적(409에 Draft 동봉 서술) | BE 정정 |
| [#31](https://github.com/kanghyunsoon/ssafesta/issues/31) | 로딩 경계 동의, `onWorldGateReady` B안 제안 | Unity |
| [#19](https://github.com/kanghyunsoon/ssafesta/issues/19) | footprint | Unity (셸 실측 2.72m 불일치) |
| [#17](https://github.com/kanghyunsoon/ssafesta/issues/17) | hex 6자리·팔레트 12색 | 팔레트 서버 검증 방식 |
| [#43](https://github.com/kanghyunsoon/ssafesta/issues/43) | 구조 제안 + 도구 검증 | 리드 채택 여부 |

## 내 것 아님

`#35` @busypark / `#22` AI+@busypark / `#24` back·game 잔여 (FE 몫 완료)

---

## 완료

| | 결과 |
|---|---|
| **블록 0** 08-18 | Architecture 4종 — `overlay.ts`·`events.ts`·`client.ts` + `app/{router,providers}` |
| **블록 1** 08-20 | 좌표 왕복 검증 통과 — C-02 실측 확정, SC-004 근거. 수치: `verify/block1-roundtrip.md` |
| **블록 2** 08-20 | 타 파트 결정 5건 소진 |
| **블록 3** 08-20 | 리뷰 4칸+BE 검토칸 서명, SC-002 해소 |
| **블록 4** 08-21 | spec 005 plan 산출물 4종 (`plan`·`research` R-01~R-11·`data-model`·`quickstart`) |
| `events.ts` 08-20 | `AI_AGENT_INTERACT` 타입 + `toAiChatPayload` (`d1bbb4a`) |
| `BOOTH_LAPTOP_INTERACT` | 브라우저 왕복 검증 완료 (PR #25) |
| 문서 develop 통합 | `docs/26`·`FE.md` 완료 |
| **CLOSED** | #1 #2 #5 #6 #14 #18 #20 #21 #22 #32 |

**해소된 미결** — C-02 좌표 / C-03 고정 크기 / C-05 `expectedRevision`(#36) / C-07 보관O·되돌리기 MVP제외 / **부스 6×6×6m 확정** / 오류 봉투 원소 타입 / SSE payload 전체(#32)

---

## 참고

- **develop 통합 절차**: `develop`에서 브랜치 → `git checkout <자기브랜치> -- <path>` 파일 단위 → `git diff --stat origin/develop -- festa-unity/` 0줄 확인 → PR. **직접 push·전체 merge 금지**(`festa-unity/` 1,462개 삭제)
- **착수 전 `origin/develop` 대조 필수** — 내 브랜치의 공유 문서 사본은 낡았다고 가정
- **013 문서 정합**: front `specs/013/spec.md`가 "FE는 창 이관만"·FR-020·C-07을 기술하는데 `FE.md`만 갱신됨 — 013 진행 시 정리
