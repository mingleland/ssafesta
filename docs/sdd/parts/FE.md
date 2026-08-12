# FE(React) 파트 — SDD 권장 브리프

> **상태**: 권장안 (2026-08-12) — FE 담당자(이정헌/김가현)가 검토·수정 후 사용한다.
> 역할 경계는 `docs/SSAFY_FESTA_FE_파트_역할_분담_확정안.md`를 따른다 — 이정헌: Platform & Booth / 김가현: AI·Conversation Vertical (AI 관련 spec은 `parts/AI.md`와 함께 볼 것).
> 공통 전제: 헌법 17조 — 텍스트 입력·외부 콘텐츠 표시는 React가 담당, Unity는 트리거만.

---

## 담당 spec 목록

| Spec | 이름 | FE 담당 | 비고 |
|---|---|---|---|
| 001 | auth-user | 이정헌 | 로그인/가입 UI + Auth 기반 구조 |
| 005 | booth-studio-layout | 이정헌 | **Layout JSON Source spec** — Mock API로 004와 병렬 가능 |
| 016 | booth-laptop-homepage | 이정헌 | 신설 P0 |
| 013a | avatar-customization | 이정헌(창) + Unity | 신설 P0 승격 |
| 009 | project-exhibition | 이정헌 | |
| 008 | ai-conversation-rag | 김가현 | AI Chat Overlay·SSE Client (parts/AI.md 참조) |
| 010 | survey (P1) | 이정헌 | 결과 화면 사양은 업로드 설문 문서에 이미 확정 |
| 011 | staff-consultation (P1) | 김가현 | |

착수 전 공동 확정(역할 분담 문서 §8): Overlay Platform 구조, `openOverlay(type, payload)` 계약, Unity→React Event 구조, DTO Convention. **이것들은 spec 001/005보다 먼저 이정헌이 Architecture 문서로 확정한다.**

## spec 005 — booth-studio-layout (FE분)

**specify 입력 (초안)**

```text
2D Booth Studio. 사용자는 오브젝트 팔레트에서 항목을 골라 배치/이동/회전/삭제하고
Properties Panel에서 속성을 편집한다. Draft 저장과 Publish를 분리한다.
산출물은 Layout JSON (version 필드 필수) — Unity가 이 JSON을 3D로 해석한다.
검증: 배치 한도, 겹침/영역 규칙. Publish 시 유효성 실패 항목을 명시한다.
```

**예상 clarify**: 부스 최대 오브젝트 수, 그리드/자유 배치, Undo/Redo는 P1(범위에서 제외 명시).

## spec 016 — booth-laptop-homepage (FE분) ★ 신설 P0

**specify 입력 (초안)**

```text
부스 안의 노트북 오브젝트를 방문자가 클릭하면, 임대 사용자가 등록해 둔 홈페이지가
"노트북 화면이 켜지는" 연출과 함께 React 오버레이로 열리고 그 안에서 웹서핑할 수 있다.
흐름: Unity 노트북 클릭 → Bridge Event(BOOTH_LAPTOP_INTERACT { boothId, objectId, url })
→ Interaction Dispatcher → 오버레이에서 iframe으로 표시.
필수 fallback: 대상 사이트가 X-Frame-Options/CSP로 iframe을 차단하면 감지 후
"새 탭에서 열기" 버튼을 제공한다 (많은 사이트가 차단하므로 이 경로가 사실상 기본).
소유자 측: Booth Studio(또는 부스 설정)에서 홈페이지 URL 등록 UI.
```

**예상 clarify**: iframe 차단 감지 방식(onload 휴리스틱 vs 사전 HEAD 체크는 CORS 불가 → UX로 해결), 오버레이 크기(노트북 프레임 연출 여부), URL 1개 vs 여러 개, http 사이트 혼합콘텐츠 경고 처리.

## spec 013a — avatar-customization (FE분) ★ P0 승격

**specify 입력 (초안)**

```text
정식 캐릭터 커스터마이징 창(React 오버레이). Unity 임시 HUD가 이미 검증한 기능을 정식 UI로:
파츠 카테고리별(머리/헤어/상의/하의 등) 선택, 색상 팔레트, 랜덤, 프리셋.
선택 즉시 Unity에 반영(AvatarBridge.ApplyAppearance(encoded))되고 다른 접속자에게 실시간 전파된다(Unity 검증 완료).
저장: PUT /users/me/avatar. 계약 기준 문서: festa-unity/Docs/avatar-customization-contract.md.
인코딩 문자열 형식은 Unity가 Source — FE는 생성 규칙을 공유 모듈로 받거나 Unity가 인코딩해 돌려준다.
```

**예상 clarify**: 인코딩 생성 주체(권장: Unity가 인코딩·React는 의미 단위로 조작), 파츠 썸네일 제작·호스팅, 프리셋 종수.

## spec 010 — survey 결과 화면 (P1, 미리 메모)

결과 화면 컴포넌트 구조·데이터 처리 원칙은 `docs/SSAFY_FESTA_내부설문_관련_업데이트.md` §2.5에 이미 확정되어 있다 (서버 집계값만 표시, 주관식 페이지네이션, 익명 보호). spec 작성 시 해당 문서를 그대로 입력으로 사용.

## 공통 비기능 요구

- 모든 Feature는 Loading/Error/Empty/Forbidden 상태를 정의한다 (이정헌 공통 정책)
- Overlay는 Overlay Platform을 통해서만 열린다 — Feature가 직접 DOM을 띄우지 않는다
- Unity Loader: 로딩/실패/재시도 UX 포함 (WebGL 용량 커서 진행률 표시 권장)
