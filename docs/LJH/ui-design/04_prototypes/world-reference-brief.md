# World Reference Brief — LLM 이미지 생성 입력 정본

- 문서 종류: **PROPOSAL / VISUAL REFERENCE 사양** (2026-09-01, baseline origin/develop=18542ee 실측 기반)
- 목적: 실제 Unity 미연동 상태에서 최종 World + React UI 화면 구조를 확인할 기준 이미지 2장(`world.png`·`world-project-overlay.png`)의 생성 사양. **production background asset 이 아니다.**
- 교체 계약: `world.png + MockUnitySurface → 시각 검증` → 실제 Unity 도달 시 `Unity WebGL + 동일 React Layer` 로 치환 가능해야 한다.

## Product

SSAFY FESTA — 브라우저 풀스크린 게임 클라이언트. Unity World(축제 광장·부스·플레이어)가 경험의 중심이고, React 는 화면 고정 UI 와 Overlay 만 담당한다. World 는 "웹사이트의 한 페이지"가 아니라 **로그인 후 사용자가 상주하는 기본 상태**다.

## World (Unity 소유 — 이미지의 몸체)

- 환경: 야간 축제 광장 — landing/login 배경과 **같은 세계관**이되, 조감 홍보 컷이 아니라 **플레이어가 그 안에 서 있는 인게임 시점**.
- 구성 요소: 석재 광장, 스트라이프 차양 부스(간판·전구 조명), festoon 전구 스트링, 가로등, 중앙 분수(시안 발광), 원경 관람차·중앙 건물, 마스코트급 환경 캐릭터 소수, 다른 플레이어 아바타 1~2명(social).
- 조명: 웜 골드 전구가 "밝음"을 만드는 야간. 네온 튜브 아님.

## Camera / Composition (실측 근거)

| 항목 | 값 | 판정 | 근거 |
|---|---|---|---|
| 시점 | 3인칭 후방 추적 | **CONFIRMED** | PlayerCameraFollow.cs — offset (0, 5, -22.5), Owner 전용 |
| 줌 범위 | 11.25~45 unit (기본 22.5) | CONFIRMED | 동 파일 _minDistance/_maxDistance |
| 피치 | -12°~65° (기본 약간 하향 부감) | CONFIRMED | _minPitch/_maxPitch |
| 시선 높이 | 가슴~어깨(줌인 시 상승), 근접 시 자기 아바타 숨김 | CONFIRMED | _lookHeight 14.4/17.5, self-hide 7.5/10 |
| 플레이어 표시 | 화면 하단 중앙에 후면 전신, 화면 높이의 대략 1/3~1/4 | **LIKELY** | 기본 줌 22.5 unit·아바타 ~22.4 unit 높이에서 추정 — 실 스크린샷 부재 |
| 아바타 실척 | 세계 기준 인물 2.24m (스케일 통일 진행 중) | LIKELY | S15P21A604-350 '진행 중' |
| 부스 밀도·광장 배치 | 좌우 부스 열 + 중앙 통로/분수 | LIKELY | 배경 자산·AdminGameBoothBuilder 간판 배치 |
| FOV·화면비 구체값, 실제 인게임 룩 | — | **UNKNOWN** | WebGL 실행 스크린샷 없음 — 시안에서 변형 비교 |

## React UI (이미지에 그릴 수 있는 유일한 화면 고정 UI)

허용 HUD (hud-decisions.md — Post-MVP Optional 3종 + 진입점):
1. 이동·조작 안내 카드 (좌상, 닫기 가능) — WASD 이동·F 상호작용·Alt 감정표현
2. 미니게임 score/progress — **미니게임 진행 중에만** 존재. `world.png`(평상시)에는 넣지 않는다
3. Toast/Notification (우상, 일시적)
4. 메뉴 진입점 chip (우하, Esc) — 근거: 목표 구조 문서 §8의 Menu. 단정 금지 — 시안 A/B 로 유무 비교 가능

Safe area: 중앙(플레이어+상호작용 대상)은 비운다. HUD 는 모서리 4곳만. World 가림 최소화가 1원칙.

## Unity-only UI (React 고정 UI 로 그리지 말 것)

F 상호작용 프롬프트·대상 하이라이트(emission)·월드 추적 라벨/인디케이터·상호작용 애니메이션·사운드 피드백. 장면 묘사(부스가 살짝 빛나는 정도)는 허용하되 **화면 고정 React 컴포넌트처럼 보이는 F 키캡 UI 를 프레임에 붙이지 않는다** (F 힌트는 Unity 가 화면 하단 중앙에 그리는 요소 — 이미지에 넣으려면 "월드 위 힌트"로 자연스럽게, React HUD 문법으로는 금지).

## Visual Direction

- KEEP: festival · playful · welcoming · game-like · social · digital · bright(야간 웜라이트) · 3D. Landing/Login 자산과 같은 세계.
- AVOID: cyberpunk / 과도 neon / dark horror / fantasy RPG / enterprise metaverse dashboard / kids mobile game HUD / minimap·HP bar·quest tracker·crosshair·hotbar·nameplate 남발·mission panel·SaaS nav·sidebar·dashboard cards.

## Image A — `world.png`

- 상태: 평상시 인게임. World 가 주인공.
- 프레임: 16:9 (1920×1080 권장), 3인칭 후방 시점, 플레이어 하단 중앙 후면 전신, 약간 하향 부감.
- 포함: 축제 광장 원근(부스 열·분수·원경 관람차), 다른 아바타 1~2, 조작 안내 카드(좌상)·Toast 1건(우상)·메뉴 chip(우하) — HUD 는 얇고 반투명하게, 화면 점유 합계 15% 미만.
- 제외: 미니게임 score, F 키캡형 React UI, 금지 목록 전부.

## Image B — `world-project-overlay.png`

- 상태: 같은 장면에서 Project 상호작용 직후. `Unity World → controlled dim → React Project Overlay`.
- Overlay 사양(판단 대상이므로 시안 간 변주 허용): 중앙 M~L 패널(화면 50~65%), Header(부스/프로젝트명·닫기)·소개 텍스트·**videoEmbed 영역**(-195 YouTube 판정 구현 반영)·기술 스택 라인. dim 은 월드가 계속 읽히는 0.4~0.6.
- 이 이미지로 판정할 것: Overlay 위치·비율·World 노출률·dim 강도·header/close·content density·surface(라이트 vs 다크)·typography·radius — **향후 Foundation 추출의 핵심 입력.**
- HUD 는 dim 아래 유지 or 숨김 — 시안 비교 항목.

## Unknowns (시안에서 비교할 것)

① 플레이어 화면 점유율(줌 22.5 vs 30) ② 메뉴 chip 유무 ③ Overlay 라이트/다크 surface ④ dim 강도 ⑤ HUD 반투명 정도 ⑥ 부스 밀도(빽빽 vs 여유).

## 기술 계약 (이미지와 무관하게 유지)

- 목표 layer: `GameShell( UnityWorldLayer | ReactScreenLayer | OverlayRoot )` — Overlay Bus·dispatcher·sessionManager 는 module-scope 라 셸 승격 호환(2026-08-31 Audit + 금일 재검증).
- Unity lifetime 목표: 인증 진입 시 boot → overlay 개폐와 무관하게 동일 인스턴스 유지 → Quit 은 logout/fatal reset/teardown 만. 현 코드는 route-scoped(이탈 시 Quit) — GameShell 라운드에서 승격.
