# SDD 실행 가이드 (spec-kit, Claude + Codex 공용)

> **상태**: v1.0 (2026-08-12) — 팀 결정 완료로 SDD 착수 가능.
> 이 폴더가 SDD의 진입점이다: `constitution.md`(최상위 원칙) + `parts/`(파트별 권장 브리프).
> 파트별 브리프는 **권장안**이다 — 각 파트가 검토 후 수정해서 사용한다.

---

## 1. 폴더 구성

```text
docs/sdd/
├── README.md            ← 이 문서 (실행 순서)
├── 파트별_할일.md        ← ★ 팀원이 제일 먼저 볼 것 (자기 이름 칸만 보면 됨)
├── constitution.md      ← 프로젝트 헌법 v1.0 (빈칸 채움 완료)
└── parts/
    ├── BE.md            ← Spring 담당 spec 권장 브리프 (001/003/004/005/009 등)
    ├── FE.md            ← React 담당 spec 권장 브리프 (001/005/013a/016 등)
    ├── AI.md            ← FastAPI 담당 spec 권장 브리프 (007/008)
    └── INFRA.md         ← 브랜치별 CI/CD·배포 spec 권장 브리프

docs/specs/              ← spec.md 초안 6종 (리드 작성, 파트 검토 대기)
├── README.md            ← 검토하는 법
├── 001-auth-user/spec.md
├── 002-world-session/spec.md
├── 005-booth-studio-layout/spec.md
├── 006-booth-runtime/spec.md
├── 013-avatar-customization/spec.md
└── 016-booth-laptop-homepage/spec.md
```

Unity(game) 파트는 별도 브리프 없이 기존 방식대로 진행한다 — 소급 spec(002/006/013a Unity분)은 Unity 리드가 직접 작성.

## 2. spec-kit 설치 (Claude와 Codex 둘 다 쓸 수 있게)

레포마다 1회 (파트별 repo 자율이므로 각 파트 repo에서 실행):

```bash
# uv 필요 (https://docs.astral.sh/uv/)
uvx --from git+https://github.com/github/spec-kit.git specify init --here --ai claude
uvx --from git+https://github.com/github/spec-kit.git specify init --here --ai codex --force
```

- `--ai claude` → `.claude/commands/speckit.*.md` 생성 (Claude Code용 슬래시 커맨드)
- `--ai codex` → `.codex/prompts/speckit.*.md` 생성 (Codex용 프롬프트)
- 두 번 실행해도 공용 산출물(`.specify/` 스크립트·템플릿)은 동일 — 어느 도구로 spec을 만들든 `specs/`, `.specify/memory/`는 같은 파일을 본다.
- 설치 후 `docs/sdd/constitution.md` 내용을 `.specify/memory/constitution.md`에 복사한다 (또는 `/speckit.constitution`에 이 문서를 붙여넣어 정식 생성).

> **주의**: 루트 `CLAUDE.md`/`AGENTS.md`의 기록 규칙(작업일지·트러블슈팅·지라)은 spec-kit과 무관하게 계속 유효하다.

## 3. spec 1개의 표준 사이클

```text
/speckit.specify  ← parts/ 브리프의 "specify 입력" 블록을 붙여넣기
      ↓
/speckit.clarify  ← 브리프의 "예상 clarify" 항목을 여기서 확정 (임의 확정 금지 — 헌법 22조)
      ↓
/speckit.plan     ← 기술 스택·아키텍처 제약 (헌법 + docs/21 ADR 참조)
      ↓
/speckit.tasks → /speckit.analyze → /speckit.implement
```

- spec 이름은 `docs/27_SDD_기능분할안.md`의 번호를 따른다 (`specs/001-auth-user/` …).
- **소급 spec**(002/006/013a): 현재 구현이 만족하는 요구사항을 역으로 명세하고 부족분만 requirement로 추가한다. 구현을 spec에 맞춰 재작성하지 않는다.

## 4. 착수 순서 (docs/27 의존 그래프 기준)

| 주차 | BE | FE | AI | Unity |
|---|---|---|---|---|
| 착수 | 001 auth | 001 로그인 UI, 005 Studio(Mock) | 007 스파이크 → spec | 002 소급 spec + wss 실측 |
| 다음 | 003 wallet → 004 lease | 016 노트북 홈페이지, 013a 커스텀 창 | 008 conversation | 006 소급 spec, 013a 소급 spec |
| 이후 | 005/009 | 009 | 격리 Critical Test | 016 트리거, (2차) 017/018 |

계약 합의 지점 (헌법 21조 — 단독 확정 금지):

- **Layout JSON**: React ↔ Spring ↔ Unity (spec 005가 Source)
- **Bridge Event / 오버레이 페이로드**: FE 이정헌 + Unity (016·AI_AGENT_INTERACT 등)
- **아바타 인코딩·저장 API**: Unity + Spring (013a, `festa-unity/Docs/avatar-customization-contract.md`; FE는 WebGL 인증/호스트 연동만)
- **SSE 스키마**: AI 김가현 + FE (start/token/source/done/error)

## 5. 오늘(2026-08-12) 결정으로 달라진 것

- 캐릭터 커스터마이징 **P0 승격** → spec 013a. 2026-08-16 Unity `CharacterLobby`를 정식 UI로 확정해 React 교체 요구를 폐기했고, Spring 저장·재접속 복원이 남음
- **016 booth-laptop-homepage 신설 (P0)** — 노트북 클릭 → 임대자 등록 홈페이지 열람
- 017 proximity-voice, 018 world-floors 신설 (P1/2차) — 1차 월드는 11층 단일
- 마피아 게임은 P2 후보로 기록만 (014 비고)
- LLM/Embedding 키는 **GMS 지급 예정** — 나오기 전까지 Mock/어댑터로 개발 (막히지 않게)
- 브랜치별(ai/back/front/game) CI/CD + develop 실사용 기준 CI/CD — 상세는 `parts/INFRA.md`
