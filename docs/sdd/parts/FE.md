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
| 013a | avatar-customization | Unity(UI) + 이정헌(WebGL 호스트·인증 연동) | P0 — Unity 정식 UI 확정 |
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
흐름: Unity 노트북 클릭 → `window.FestaUnity.onBoothInteract(json)`
→ Bridge Event(`BOOTH_LAPTOP_INTERACT { boothId, objectId, url? }`)
→ Interaction Dispatcher → 오버레이에서 iframe으로 표시.
필수 fallback: 대상 사이트가 X-Frame-Options/CSP로 iframe을 차단하면 감지 후
"새 탭에서 열기" 버튼을 제공한다 (많은 사이트가 차단하므로 이 경로가 사실상 기본).
소유자 측: Booth Studio(또는 부스 설정)에서 홈페이지 URL 등록 UI.
```

> **2026-08-20 계약·구현·브라우저 왕복 검증 완료**: `boothId`·`objectId`는 필수, `url`은 선택이다. URL이 없으면 오류가 아닌 안내를 표시한다. Unity 경로는 `LaptopInteractable → BoothInteractionInput(레이캐스트) → BoothInteractBridge → FestaUnityBridge.jslib` 이고 최종적으로 `window.FestaUnity.onBoothInteract(json)`을 호출한다.
>
> **WebGL 빌드에서 실제 클릭으로 왕복을 검증했다** — 노트북 클릭 1회당 이벤트 1건, 빈 공간·다른 파츠 클릭은 0건(오탐 없음). `url` 없음·정상·따옴표·백슬래시·한글 5종 모두 보낸 값과 정확히 일치했다. FE는 `window.FestaUnity.onBoothInteract`를 설치하기만 하면 된다.
>
> 참고: 클릭 감지에 `OnMouseDown`을 쓰지 않는다. Unity 6 WebGL에서 레거시 마우스 메시지가 디스패치되지 않아 Input System 포인터 + `Physics.Raycast`로 교체했다 (game `de38269`, T-166).

**예상 clarify**: iframe 차단 감지 방식(onload 휴리스틱 vs 사전 HEAD 체크는 CORS 불가 → UX로 해결), 오버레이 크기(노트북 프레임 연출 여부), URL 1개 vs 여러 개, http 사이트 혼합콘텐츠 경고 처리.

## spec 013a — avatar-customization (FE 연동 경계) ★ P0

> **2026-08-16 결정**: 완성된 Unity `CharacterLobby`를 정식 커스터마이징 화면으로 사용한다. React 오버레이 교체 요구는 폐기하며 FE는 같은 화면을 중복 구현하지 않는다. Unity 기준 커밋은 `game 6be0cfd`다.

- 파츠·썸네일·색상·무작위·미리보기 상태와 외형 인코딩은 Unity가 소유한다.
- FE는 아바타 편집용 Overlay route, 상태 store, 파츠 카탈로그를 만들지 않고 `avatarCode`를 opaque payload로 취급한다.
- FE 담당 범위는 Unity WebGL 로더, 화면 진입·이탈 같은 호스트 통합, 인증된 Access Token 전달 계약이다. Refresh Token은 Unity에 전달하지 않는다.
- Spring 영구 저장은 Unity `IUserApiClient.UpdateMyAvatarAsync`가 호출한다. FE가 저장 API를 직접 호출하는 아바타 편집 화면을 만들지 않는다.
- `AvatarBridge`는 외부 호스트에서 화면을 열어야 할 때만 선택적으로 사용하며, React 커스터마이징 UI 계약으로 사용하지 않는다.
- AI 채팅·상담·설문처럼 장문 한글 입력이 필요한 기능은 기존 React 오버레이 원칙을 유지한다.

**남은 협의**: Access Token을 Unity API Client에 전달하는 방식과 저장 실패 UX의 Spring/Unity 공통 오류 코드.

## spec 010 — survey 결과 화면 (P1, 미리 메모)

결과 화면 컴포넌트 구조·데이터 처리 원칙은 `docs/SSAFY_FESTA_내부설문_관련_업데이트.md` §2.5에 이미 확정되어 있다 (서버 집계값만 표시, 주관식 페이지네이션, 익명 보호). spec 작성 시 해당 문서를 그대로 입력으로 사용.

## 공통 비기능 요구

- 모든 Feature는 Loading/Error/Empty/Forbidden 상태를 정의한다 (이정헌 공통 정책)
- Overlay는 Overlay Platform을 통해서만 열린다 — Feature가 직접 DOM을 띄우지 않는다
- Unity Loader: 로딩/실패/재시도 UX 포함 (WebGL 용량 커서 진행률 표시 권장)
