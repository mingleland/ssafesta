# Maker UX Reference: MapleStory Worlds → FESTA Game Studio

> 검토일: 2026-08-24  
> 목적: 메이플스토리 월드 메이커를 외형이 아니라 제작 흐름과 정보 구조 관점에서 참고하고,
> FESTA의 제한형 Web 2D UGC 범위에 맞는 적용·배제 결정을 기록한다.

## 1. 확인한 공식 자료

- [Workspace](https://maplestoryworlds-creators.nexon.com/ko/docs?postId=121): Scene/Hierarchy/Library/Inspector 중심 작업 공간
- [Hierarchy](https://maplestoryworlds-creators.nexon.com/ko/docs?postId=453): 현재 Scene에 배치된 Entity 탐색과 부모·자식 구조
- [Scene](https://maplestoryworlds-creators.nexon.com/ko/docs?postId=1152): 월드 구성 단위와 Scene 편집
- [TileMap](https://maplestoryworlds-creators.nexon.com/ko/docs?postId=747): 타일 기반 지형 제작
- [Map Layer](https://maplestoryworlds-creators.nexon.com/ko/docs?postId=53): 지형·장식·충돌의 층 분리
- [Transform](https://maplestoryworlds-creators.nexon.com/ko/docs?postId=82): 선택 대상의 위치·크기 등 직접 조정
- [UI Editor](https://maplestoryworlds-creators.nexon.com/ko/docs?postId=120): 게임 화면 UI 편집
- [Model Editor](https://maplestoryworlds-creators.nexon.com/ko/docs?postId=1324): 재사용 가능한 모델 편집
- [Shortcut Keys](https://maplestoryworlds-creators.nexon.com/ko/docs?postId=813): 선택·이동·편집 반복 작업 단축
- [Design Mode Course](https://maplestoryworlds-creators.nexon.com/en/resource/LetsExploreDesignMode?postId=1281): 장치와 preset 중심의 비개발자 제작 흐름
- [Pro Mode Course](https://maplestoryworlds-creators.nexon.com/en/resource/LetsExploreProMode?postId=1312): Entity/Component/Property와 Script/API 기반 확장 흐름
- 공식 Design Mode 영상: [개요](https://www.youtube.com/watch?v=x120XoO15es),
  [타일·레이어](https://www.youtube.com/watch?v=wU6F93neVTg),
  [일반 장치](https://www.youtube.com/watch?v=05vuRY1J_Vs),
  [게임 맵 장치](https://www.youtube.com/watch?v=g9aDzgor8lQ),
  [설정](https://www.youtube.com/watch?v=xHNhjDBQNp8)

## 2. 핵심 관찰

1. **Library와 Hierarchy는 역할이 다르다.** Library는 새로 놓을 재료, Hierarchy는 현재 Scene에 이미
   놓인 대상을 찾고 정리하는 곳이다. 둘을 하나의 목록으로 합치면 초보자는 “추가”와 “편집”을 구분하기 어렵다.
2. **Scene과 Canvas가 중심이다.** 속성 폼을 먼저 채우는 구조가 아니라 화면에서 선택·배치한 결과를
   Inspector가 설명한다.
3. **Design Mode는 완성 행동 단위를 제공한다.** 사용자는 내부 타입을 조립하기 전에 문, 포털, 발판,
   적 생성기 같은 의미 단위를 놓고 즉시 실행해 본다.
4. **Pro Mode는 같은 월드를 더 세밀하게 편집한다.** 간단 모드와 고급 모드가 서로 다른 저장 형식을
   쓰는 것이 아니라 동일 Entity/Component 상태를 다른 깊이로 노출한다.
5. **타일과 Object는 편집 방식이 다르다.** 타일은 brush/fill/erase의 대량 작업, Object는 선택·변환·복제·
   계층/레이어 정리가 핵심이다.
6. **템플릿은 설명서가 아니라 실행 가능한 시작점이다.** 장르 이름과 이미지가 달라도 실제 Scene과 목표가
   같다면 학습과 기획의 출발점 역할을 하지 못한다.

## 3. FESTA 적용 결정

| Reference pattern | FESTA 적용 | 이유 |
|---|---|---|
| Design/Pro Mode | 기본/고급 Inspector | 같은 GameProject를 쓰면서 초보자에게 내부 ID·Component를 숨긴다. |
| Library | 왼쪽 한국어 Object·Asset 재료함 | 기본 재료를 먼저 제공하고 선택한 요소만 이미지 교체를 연다. |
| Hierarchy | Object Layer 검색·숨김·잠금·zIndex | 배치된 대상 탐색과 새 재료 추가를 분리한다. |
| Scene Canvas | 선택·영역 선택·묶음 이동·화면 이동·격자 | 폼 입력보다 직접 조작을 우선한다. |
| Device/preset | 빠른 행동 recipe | 문·대화·pickup·Goal을 공통 Component/Event로 생성한다. |
| Tile/Map Layer | brush/fill/erase + row-major TileLayer | Object 조작과 대량 타일 작업을 분리한다. |
| Model reuse | Object+Event 복제 | 복제한 그림이 무동작 상태가 되지 않게 Trigger와 내부 참조도 복제한다. |
| Immediate play | same-origin local Preview | 저장·게시 전 현재 snapshot을 Unity 없이 실행한다. |

## 4. 의도적으로 배제한 범위

- 사용자 임의 JavaScript/Lua, Script API, Package/plugin 실행
- 임의 Component schema와 서버에서 알 수 없는 사용자 타입
- 3D Transform, 물리 재질, 네트워크 Entity, 멀티플레이 권위 상태
- 완성 화면 한 장을 배경으로 저장하는 가짜 맵 제작
- 장르마다 별도 Backend 문서와 별도 Runtime을 추가하는 구조

FESTA는 `TOP_DOWN`, `PLATFORMER`, `DIALOGUE`와 제한된 Trigger/Condition/Action을 유지한다. 자유도는
코드 실행 권한이 아니라 재료·배치·Scene 조합·Event 연결·이미지 교체·템플릿 구조의 조합 폭으로 만든다.

## 5. 현재 반영 상태와 다음 검증

- 반영: Library/Layer 분리, 기본/고급 Inspector, 한국어 recipe, Canvas 다중 선택·복제·이동,
  Tile 도구, Scene 크기 변경, 6종 구조적 템플릿, Local Preview.
- 사람 검증: 개발 경험 없는 5명 중 4명 이상이 20분 안에 key→dialogue→door 게임 완성 ([#73](https://github.com/kanghyunsoon/ssafesta/issues/73)).
- 운영 연결: Draft/Published/Portal/Asset server E2E는 #48·#55·#56·#69.
- 계약 확장: 생존 시간·처치 수·점수 임계 승리는 v1.1 FE candidate와 Mock Runtime까지 구현했다. 공동 계약 [#78](https://github.com/kanghyunsoon/ssafesta/issues/78)에서 BE validator·AI 허용 출력 승인만 남았다.
