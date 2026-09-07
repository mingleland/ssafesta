# Visual DNA — Foundation Draft

- 문서 종류: **PROPOSAL** (2026-09-01 Draft v2 — reference 3종 실독 반영. 최종 token 값 Freeze 아님. Freeze 는 사용자 Visual Direction 선택 후)
- 시각 입력(D-01 확정 원천): `00_context/sources/landing.png` · `login.png` · `booth-2_5d-reference.png` (3종 확보 완료) + 최종 UI 구조 문서 §20~22.
- Game Studio gss/grp CSS 는 원천이 아니다.

## 0. Reference 실독에서 확정되는 사실

| Reference | 관찰 |
|---|---|
| landing.png | **야간 축제 광장** — deep indigo/royal blue 별하늘+보름달, 관람차·열기구·회전목마, 웜 골드 festoon 조명 밀도가 "밝음"을 만든다. 메인 로고 = `ssafesta-logo.png` 원라인 "SSAFESTA" 워드마크(흰/블루 버블 레터+별 A, 상단 디오라마: 관람차·서커스 텐트·달·도시 실루엣, 골드→퍼플 스우시 링) — **이미지 자산이 정본, 폰트로 재현 금지**. 마스코트, 보라빛 석재 광장, 시안 분수. 하단 중앙 "화면을 클릭해 시작하기" |
| login.png | 같은 세계 유지. 중앙에 반투명 라이트 패널 — 게임 메뉴처럼 월드 위에 뜸(전체 화면 안 덮음). provider 버튼 4종: SSAFY(블루)·Google(화이트)·Kakao(옐로)·게스트(뉴트럴 아웃라인). 하단 유틸 링크 절제 |
| booth-2_5d-reference.png | **다크 에디터 크롬**(near-black/deep navy) + 좌 에셋 팔레트(구조/가구/전자기기/장식, 3D 썸네일 카드) + 중앙 고정 아이소메트릭 부스 캔버스(트러스 프레임·화이트 월·브랜드 컬러 바닥) + 우 Inspector(위치/회전/크기 mm·재질 hex·토글) + 상단 툴바·그리드 토글 + 하단 상태바(부스 크기·에셋 18/100·zoom) + 뷰큐브. 블루 프라이머리 버튼으로 본편과 연결 |

**핵심 결론 — "Bright Festival"의 실체는 주광이 아니라 야간 축제의 빛 밀도다.** 세계는 어둡지만(딥 인디고) 화면 인상은 밝다(웜 라이트·캔디 컬러). 따라서 "Bright World + Readable Game UI Surface"는 이분법이 아니라 **같은 밤하늘 아래 광장(Experience)과 작업실(Creator)** 관계로 자연 통합된다 — reference 가 이미 그 답을 담고 있다.

## 1. Color / Surface direction (방향만 — 수치 Freeze 아님)

```text
World Base    딥 인디고~로열 블루 계열 (밤하늘·광장 석재)
Light         웜 골드 (festoon 전구·간판 조명) — "축제 밝음"의 주역
Candy Accent  핑크 / 시안 / 퍼플 / 옐로 (로고·풍선·마스코트 계열) — 포인트 한정
Primary       로고 블루 계열 (SSAFY 'S' 블루 — 버튼·활성 상태)
Surface 계층   Experience: 월드가 배경, UI 는 반투명 라이트 패널
              Overlay: 라이트 패널 + 월드 dim
              Creator: 딥 네이비 다크 크롬 (reference 그대로) + 동일 Primary
              Operations: Creator 크롬의 절제 변형
Semantic      success 그린 / warning 골드 / danger 레드 (Kakao 옐로·경고 골드 혼동 주의)
```

## 2. Typography direction

- 한글 우선. 본문/UI: **Pretendard self-host** (현 코드 선언만 있고 로딩 없음 — Foundation 에서 실로딩).
- 메인 SSAFESTA 워드마크는 **이미지 자산**(ssafesta-logo.png) — 어떤 폰트로도 재현하지 않는다. Display 페이스는 로고 외 한글 헤더용으로만 탐색(라운드 계열 후보, fidelity 실측에서 system-ui 로도 성립 확인 — Freeze 때 재판정).
- 계층: Display / Heading / Body / Dense UI(에디터·Operations 용 소형) 4단. 수치는 Freeze 단계에서.

## 3. Geometry / Elevation / Layering

- Radius: 크게 둥근 카드·필 버튼 방향(로고·간판의 라운드 DNA). Creator 는 절제된 중간 radius.
- Spacing: 8px 기반 리듬 제안(4/8/12/16/24/32/48) — Freeze 시 확정.
- Elevation: 웜 글로우(빛 번짐)를 shadow 대신 쓰는 Experience 층 vs 표준 soft shadow 의 Creator 층.
- z-index 계층(토큰화 예정): world < screen-ui < overlay-dim < overlay < toast < system.

## 4. Semantic states

Loading(축제 모티프 스피너/불빛), Empty(초대형 안내 — "부스를 꾸며보세요" 류 행동 유도), Error(원인+행동, 사과·모호 금지), Disabled, Success(짧은 celebration 여지), Realtime/Streaming(AI·상담). 전 화면 공통 ScreenNotice·Toast 로 수렴.

## 5. Motion principles

- 핵심 1회성 연출에 집중: Landing "클릭해 시작하기" opacity pulse, overlay open/close(dim+scale), celebration(발행·완료).
- 산발 효과 금지, prefers-reduced-motion 존중. Creator 는 즉답성 우선(모션 최소).

## 6. Viewport 정책 (D-02/D-03)

Desktop / Laptop / Small Laptop + DesktopRequiredGate("PC 브라우저에서 이용해주세요"). breakpoint 수치는 Playwright matrix 실측 후 결정. 모바일 전체 디자인은 범위 밖(Post-MVP Landscape).

## 7. Component primitive direction

Button(필 형태, primary=로고 블루/provider 별 브랜드 색) · Modal(native dialog 정본) · Field · ScreenNotice · Toast · Badge · IconButton · OverlayFrame(§13 공통 골격: Title/Close/Content/Status/Action + dim) · PageShell. Creator 전용: WorkspaceShell(3열)·ToolPanel·Inspector·Toolbar — booth reference 의 구조를 정본 삼음.

## 8. 안티패턴 (유지)

Enterprise SaaS / Generic Dashboard / Cyberpunk cliché / 과도 Neon / 과도 Glassmorphism / Kids UI / Heavy Fantasy RPG.
주의: 야간+네온은 한 끗 차이 — **광원은 축제 전구·간판(웜)**, 시안·마젠타 네온 튜브가 아니다. 반투명 패널은 가독성 우선(과도 blur 금지).

## Freeze 하지 않은 것

hex 값 전부 · 폰트 최종 선정(디스플레이 페이스) · type scale 수치 · spacing/radius/shadow 수치 · breakpoint 수치 · motion duration/easing. → Visual Direction 선택 후 tokens-spec.md 에서 확정.
