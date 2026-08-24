# FE Research: FESTA Game Studio

## 1. Web Runtime과 Unity의 관계

**결정**: 2D 게임은 웹 Runtime에서 직접 실행한다. Unity WebGL은 부스 상호작용 진입점일 뿐이다.

독립 URL, 빠른 Preview, 작은 배포, 장애 격리를 만족하며 Unity 재빌드 없이 콘텐츠를 발행할 수 있다.
Unity 내부 2D Scene이나 Unity iframe 안의 Web Runtime은 이중 Runtime과 포커스 문제를 만들므로 제외한다.

## 2. 공통 코어와 renderer 분리

변수·인벤토리·Scene·Event는 순수 TypeScript state machine이 소유한다. TOP_DOWN과 PLATFORMER는
서로 다른 renderer/physics adapter다. 이 경계 덕분에 Preview와 Published가 같은 상태 전이를 사용하고,
renderer 선택 전에도 계약 테스트가 가능하다.

## 3. Scene 모델

- `TOP_DOWN`: grid movement와 object interaction
- `DIALOGUE/OVERLAY`: 호출 Scene을 보존하고 world input을 중지
- `DIALOGUE/FULL_SCREEN`: 독립 graph Scene
- `PLATFORMER`: reference renderer/physics adapter 구현 완료
- `PUZZLE`: v1 별도 Scene이 아니라 Component/Event recipe

장르 이름마다 Scene type을 추가하지 않는다. Scene type은 실행 방식이 다를 때만 늘린다.

## 4. Asset과 배치 데이터

GameProject에는 binary나 완성 화면 이미지가 아니라 안정적인 Asset reference와 Tile/Object 배치를 저장한다.
MVP는 versioned `builtin://` catalog를 사용한다. base64, 브라우저 로컬 경로, 만료 가능한 signed URL은
Published snapshot에 저장하지 않는다. 사용자 upload는 별도 Asset spec으로 확장한다.

## 5. 제한형 Component/Event

Preset은 authoring shortcut이고 실행 능력은 allow-list Component와 Trigger/Condition/Action이 결정한다.
문 잠금, 열쇠 획득 같은 편의 UI는 되돌릴 수 있는 recipe로 변환한다. 사용자 JavaScript와 임의 표현식은
서버 검증·호환성·실행 budget을 무너뜨리므로 허용하지 않는다.

## 6. 검증과 Preview

JSON Schema 구조 검증과 semantic reference 검증을 분리한다. FE는 편집 중 즉시 feedback과 Runtime load
방어를 담당하고, BE는 저장·Publish 경계에서 재검증한다. Preview는 same-origin iframe과 명시적
`origin/source/requestId` 검사를 사용하며 인증 토큰을 메시지로 전달하지 않는다.

## 7. FESTA Frontend 내부 모듈

별도 Vite origin보다 `festa-frontend/src/game-studio/` 소유 모듈이 현재 프로젝트에 적합하다. 기존 인증과
API client를 재사용하고 lazy chunk로 일반 FESTA 초기 번들과 격리한다. 별도 origin은 인증 전달·배포·CSP
계약을 늘리므로 MVP에서 제외한다.

## 8. 제작 UX 참고 기준 — MapleStory Worlds Maker

공개 Creator Center의 [Scene](https://maplestoryworlds-creators.nexon.com/ko/docs?postId=1152),
[단축키](https://maplestoryworlds-creators.nexon.com/ko/docs?postId=813),
[UI Preset](https://maplestoryworlds-creators.nexon.com/ko/docs?postId=120) 구조를 참고한다.
Scene 중심 편집, 배치 가능한 Preset, 즉시 테스트, 반복 작업 단축키, 난이도별 학습은 채택한다.
다만 FESTA v1은 Script/API 작성 경험을 목표로 하지 않으므로 Component/ID/좌표는 고급 설정에 두고,
완성 예제·시각 재료함·빠른 행동으로 `배치 → 모습 → 동작 → 플레이`를 기본 경로로 삼는다.

## 9. 남은 FE 구현 경계

#35의 TOP_DOWN/PLATFORMER reference renderer, same-origin route Preview, builtin/local Asset resolver는 PR #63으로 완료됐다.

메이플스토리 월드의 공식 Workspace·Hierarchy·Scene·TileMap·Map Layer·Design/Pro Mode 자료에서 확인한
Maker 정보 구조와 FESTA 적용·배제 판단은 [maker-reference.md](maker-reference.md)에 기록한다. 핵심은
Library와 배치된 Object 목록을 분리하고, Scene Canvas 직접 조작과 제한형 device/recipe를 기본으로 하되
임의 Script/API는 도입하지 않는 것이다.
revision-aware Draft/Publish client, Published loader·오류 격리, Booth overlay adapter도 FE port 뒤에 구현됐다.
남은 통합 범위는 #48·#56의 서버 endpoint와 #69 stable Asset upload/resolver, 실제 Backend browser E2E다.
iframe Preview를 실제 채택하지 않는 한 postMessage CSP/origin transport는 활성 병목이 아니다.
