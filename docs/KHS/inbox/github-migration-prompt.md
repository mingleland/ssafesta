# Codex 프롬프트 — GitLab → GitHub 포트폴리오 이관 + 포트폴리오 문서 작성

> 아래 `---` 사이 블록을 그대로 Codex 에 붙여 넣는다. 조사 결과(2026-09-06 기준)를 이미 반영했으므로 Codex 가 다시 세지 않아도 된다.

---

## 역할

SSAFY 2학기 공통 프로젝트 **SSAFY FESTA** 를 포트폴리오용 GitHub 저장소로 이관하고, 포트폴리오 문서를 작성한다.
나(강형순)는 이 팀에서 **게임 파트 단독 담당**이다. Unity 6 WebGL 클라이언트와 Unity 전용 서버(Dedicated Server), 그리고 그 둘이 React 프런트·Spring 백엔드·FastAPI AI 와 주고받는 계약을 맡았다.

## 원본과 목적지

| | |
|---|---|
| 원본 | GitLab `https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604` (project id `1443023`) |
| 원본 규모 | 이슈 135건 · MR 341건 · 원격 브랜치 148개 · 태그 `v0.0.1-poc` · `.git` 760 MB |
| 목적지 | **새 GitHub 저장소**를 만든다. 기존 `github.com/kanghyunsoon/ssafesta` 는 GitLab 이전의 아카이브라 이력이 섞이므로 쓰지 않는다 |
| 토큰 | GitLab PAT 는 저장소 루트 `.gltok`(gitignore 됨). GitHub 토큰은 환경변수로 받는다. **둘 다 커밋하지 않는다** |

## 반드시 지킬 것

1. **GitLab 원본을 건드리지 않는다.** 읽기만 한다. push·삭제·이슈 수정 금지.
2. **시크릿을 옮기지 않는다.** 이관 전에 전체 이력을 스캔하고, 발견되면 멈추고 보고한다. 확인된 현황: 추적 중인 `.env` 는 `*.env.example` 뿐이고 문서에는 변수명만 있다(값 노출 없음). `.gltok`, `backend/.env`, `festa-ai/.env`, `*.jwt` 는 이미 gitignore 대상이다.
3. **라이선스 에셋을 공개 저장소에 올리지 않는다.** 아래 목록은 유료·제3자 에셋이라 재배포하면 안 된다. 이력에서도 제거한다.
4. 이관 결과를 push 하기 전에 **검증 결과를 먼저 보고**한다. 내가 확인한 뒤 push 한다.

## 1단계 — 이력을 포함한 저장소 이관

`git clone --mirror` 로 GitLab 을 받고, `git filter-repo` 로 아래 경로를 **이력 전체에서** 제거한 뒤 새 GitHub 저장소로 push 한다. 커밋 이력·브랜치·태그는 유지한다(SHA 는 재작성되며, 그건 새 저장소이므로 문제없다).

**이력에서 제거할 경로**

```
# 제3자 에셋 (재배포 불가) — 추적 중인 파일 수
festa-unity/Assets/ithappy/                      854
festa-unity/Assets/Palmov Island/                243
festa-unity/Assets/Hyper casual cartoon castles/ 133
festa-unity/Assets/danthaigames/                  60
festa-unity/Assets/300Mind/                       25

# 대용량 아트·모델 (합계 600 MB 이상)
festa-unity/Assets/_Project/Art/
festa-unity/Assets/_Project/Models/
festa-unity/Assets/_Project/Resources/
festa-unity/Assets/_Project/Animations/
festa-unity/Assets/_Project/Audio/
festa-unity/Assets/Screenshots/

# 빌드 산출물·작업 부산물
festa-unity/Builds/
work/
spikes/
reference/
```

**남길 것** — 코드와 기록이 포트폴리오의 내용이다.

```
festa-unity/Assets/_Project/Scripts/     # 142개 파일 · 22,554줄 (내 파트 코드 전부)
festa-unity/Assets/_Project/Prefabs/
festa-unity/Assets/_Project/Scenes/
festa-unity/Assets/_Project/ScriptableObjects/
festa-unity/Assets/_Project/Settings/
festa-unity/Assets/Plugins/WebGL/        # jslib 브리지
festa-unity/Docker/  festa-unity/Tools/  festa-unity/ProjectSettings/
backend/  festa-frontend/  festa-ai/  infra/  ci/
docs/  specs/
```

에셋을 뺀 뒤 씬·프리팹의 참조가 깨지는 것은 **의도된 상태**다. 루트 README 에 "실행에는 별도 라이선스 에셋이 필요하다"고 한 줄 적고, 어떤 에셋인지 이름과 출처만 표로 남긴다.

**검증**: 이관 후 `.git` 크기, 커밋 수(원본과 동일해야 한다), 브랜치·태그 존재, 제거 경로가 `git log --all -- <경로>` 에서 0건인지 확인해 보고한다.

## 2단계 — 이슈와 MR 이관

GitLab API 로 읽어 GitHub API 로 만든다. 라벨·상태·작성 시각·작성자를 보존한다.

- **이슈 135건** → GitHub Issue. 본문 맨 위에 `원본: <GitLab URL> · 작성자: <username> · <작성일>` 한 줄을 넣고, 코멘트는 순서대로 이어 붙이되 각 코멘트에 작성자와 시각을 남긴다. 닫힌 이슈는 만든 뒤 닫는다.
- **MR 341건** → GitHub Issue 로 만들고 `merge-request` 라벨을 붙인다. GitHub 은 원본 브랜치 없이 PR 을 만들 수 없고, MR 설명에 작업 목적·변경 사항·테스트 방법·영향 범위가 들어 있어 그 자체가 기록이다.
- **이메일 주소는 옮기지 않는다.** username 만 남긴다.
- API 사용량 제한에 걸리면 재시도하고, 진행 상황을 파일에 기록해 중단돼도 이어서 돌 수 있게 한다.

## 3단계 — 포트폴리오 문서 작성

루트 `README.md` 와 `docs/portfolio/` 를 쓴다.

**서술 규칙 — 이건 채용 담당자가 읽는다.**

- **두괄식.** 각 항목은 결론을 먼저 쓰고 근거를 뒤에 붙인다.
- **형용사를 쓰지 않는다.** "빠른", "안정적인", "효율적인", "완성도 높은" 같은 말을 넣지 않는다. 대신 수치를 쓴다. 예: "빠른 진입" (X) → "월드 진입 3.2초" (O).
- **1인칭으로 내가 쓴 것처럼.** "~했다", "~로 판단했다", "~를 확인했다". 제3자 소개문("그는", "본 프로젝트는 ~을 자랑한다") 금지.
- **내가 맡은 파트만 중점.** 프런트·백엔드·AI 는 계약 상대로만 언급한다. 팀 전체 소개는 개요 한 문단으로 끝낸다.
- **형식은 일반적인 포트폴리오 저장소 관례를 따른다.** 목차 → 개요 → 기술 스택 → 아키텍처 → 담당 기능 → 문제 해결 → 실행 방법. 배지·이모지 남발 금지.

**README 구조**

1. 한 줄 정의 — 무엇을 만들었고 내가 무엇을 맡았는지
2. 데모 화면 3~4장 (`docs/portfolio/` 이미지)
3. 기술 스택 — Unity 6 / Netcode for GameObjects / URP / WebGL, Unity Dedicated Server(Docker), 연동 대상(React·Spring·FastAPI)
4. 아키텍처 다이어그램 한 장 — 브라우저(React 호스트 + Unity WebGL) ↔ Spring ↔ Unity 전용 서버(WebSocket 7777)
5. 내가 맡은 기능 — 아래 표를 근거와 함께
6. 문제 해결 3~5건 — `docs/25_트러블슈팅.md` 의 T-1~T-127 에서 고른다
7. 실행 방법 — 라이선스 에셋이 빠져 있다는 전제와 로컬 스택 기동 순서

**담당 기능(근거 있는 것만 쓴다)**

| 영역 | 내용 | 근거 |
|---|---|---|
| 월드 접속 | `POST /world-sessions` grant 를 Unity 전용 서버가 자체 검증(서명·iss·aud·exp·worldId·jti 1회용)해 승인 | `Network/Auth/`, `ConnectionManager.ApprovalCheck` |
| 멀티플레이 | Netcode for GameObjects, 40명 정원, 스폰 격자, 같은 신원 재접속 시 이전 접속 정리 | `Network/Connection/` |
| 재접속 | 브라우저 탭 백그라운드에서 rAF 가 멈춰 끊기는 것을 감지해 2·5·10·20·30초 5회 재시도, 상태를 호스트에 통지 | `WorldReconnector`, docs/25 T-115·T-120 |
| 부스 런타임 | 백엔드 게시본(JSON)을 읽어 6×6 m 셸 안에 오브젝트를 Local Spawn, 12실 | `Booth/Runtime/`, `BoothObjectFactory` |
| 상호작용 계약 | F 키 → `AI_AGENT_INTERACT`·`BOOTH_PROJECT_INTERACT`·`BOOTH_SURVEY_INTERACT`·`BOOTH_LAPTOP_INTERACT`·`WORLD_MANAGEMENT_INTERACT` 를 jslib 브리지로 호스트에 전달 | `Integration/Bridge/`, `Plugins/WebGL/` |
| 미니게임 | 서버 우선 판정. 백엔드 API 부재 시 목업으로 내려가되 그 사실을 화면에 표시 | `Minigame/`, `ServerFirstSlotMachineClient` |
| 아바타 | 모듈형 아바타 조립·색 변경, 서버 동기화, 프로필 저장 | `World/Avatar/` |
| 빌드·배포 | Linux 서버 + WebGL + Docker 이미지를 한 흐름으로 뽑는 에디터 빌더, 산출물 자체 검증 | `Editor/FestaReleaseBuilder.cs` |

**수치(측정값 그대로 쓴다)**

- 월드 진입 3.2초 (main_loaded 1.2s → 세션 0.9s → 접속 0.2s → 게이트 개방 0.9s), 프런트 임베드 경로 5.8초
- 1280×720 릴리스 빌드 80 fps
- WebGL 산출물 143 MB (Brotli), Linux 서버 이미지 430 MB
- 게임 파트 커밋 323건 / 저장소 전체 723건, C# 142파일 22,554줄
- 트러블슈팅 기록 127건

**문제 해결에 쓸 후보** — 원인을 좁힌 과정이 남아 있는 것들

- T-125 회원만 월드 접속 실패. `OverflowException 1172/1114 bytes` → 접속 payload 가 Netcode 비분할 메시지 한도 초과 → grant 1,094자 중 avatarCode 클레임 411자가 원인. MTU 를 4096 으로 올려 해결하고 백엔드에 클레임 제거 요청.
- T-115·T-120 브라우저 탭을 뒤로 보내면 30초 안에 끊김. Chrome 이 숨은 탭의 rAF 를 멈춰 하트비트가 서는 것이 원인. 자동 재접속으로 대응.
- T-119 스태프 NPC 2기가 매 프레임 경고를 뿜고 입력을 가로챔. 벤더 데모 컨트롤러 잔존.
- T-126 빌드 중 소스를 고쳐 30분짜리 빌드가 통째로 실패. 빌드 시스템이 그래프를 6회 재시도 후 포기.
- S15P21A604-316 렌더 파이프라인 에셋이 빌드에서 빠져 월드가 조명 없이 렌더링. 활성 빌드 타깃이 품질 레벨 필터를 결정하는 것이 원인. 빌더에 산출물 검증을 넣어 재발 차단.

**출처**: `docs/24_작업일지.md`(작업일지) · `docs/25_트러블슈팅.md`(T-1~T-127) · `docs/KHS/inbox/user-test-qa-checklist.md` · `specs/` 20종. 내용을 지어내지 말고 이 문서들에서 가져온다. 수치가 없는 주장은 쓰지 않는다.

## 산출물

1. 새 GitHub 저장소 URL, 이관 검증 결과(커밋 수·브랜치·태그·제거 경로 확인·`.git` 크기)
2. 이슈/MR 이관 건수와 실패 목록
3. `README.md`, `docs/portfolio/`
4. 시크릿 스캔 결과

---
