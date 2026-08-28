# 모듈러 플레이어 아바타 세팅 체크리스트

> 현재 기준: Rukha93 모듈형 아바타. 삭제된 `Avatar_A/B/C`와 Sidekick 프리셋은 사용하지 않는다.

## 1. 기준 에셋

- 플레이어 루트: `Assets/_Project/Prefabs/Player/PlayerAvatar.prefab`
- 외형 카탈로그: `Assets/_Project/ScriptableObjects/Avatar/AvatarCatalog.asset`
- 외형 생성: `PlayerAvatarVisual` → `CatalogAvatarVisualProvider` → `AvatarAssembler`
- 씬 간 전달: `AvatarSceneHandoff`

## 2. PlayerAvatar 필수 구성

- `NetworkObject`, `NetworkTransform`
- `PlayerMovement`, `PlayerCameraFollow`
- `PlayerAppearanceController`, `PlayerAvatarVisual`
- `PlayerEmoteController`
- 하나의 Ground Shadow

플레이어 루트의 Spawn Y는 `0`을 기준으로 한다. 런타임 자식 `AvatarVisual_Modular`의 높이와 스케일은 `PlayerAvatarVisual`이 조정하므로 루트 Transform으로 보정하지 않는다.

## 3. 외형 전달 순서

1. `CharacterLobby`가 현재 외형을 `fa` 문자열로 인코딩한다.
2. `AvatarSceneHandoff`가 문자열과 월드 접속 요청을 보존한다.
3. `main`에서 Owner 플레이어가 Spawn된다.
4. `PlayerAppearanceController`가 `FixedString4096Bytes` NetworkVariable로 전체 문자열을 서버에 요청한다.
5. 각 Client가 같은 값을 Decode해 로컬 메시를 조립한다.

접속 승인 payload의 짧은 preset 필드에 전체 `fa` 문자열을 넣지 않는다.

## 4. 화면·빌드 확인

- CharacterLobby와 main에서 같은 카탈로그를 사용하는지 확인한다.
- main 진입 후 헤어·의상·얼굴·색상이 유지되는지 확인한다.
- 두 Client에서 상대 외형 변경이 1초 이내 반영되는지 확인한다.
- WebGL에서 검은 실루엣이나 누락 파츠가 없는지 확인한다.
- Linux Dedicated Server와 WebGL Client는 같은 소스 리비전으로 배포한다.

## 5. 실패 증상별 확인 위치

| 증상 | 확인 위치 |
|---|---|
| 기본 몸체만 보임 | `PlayerAppearanceController.Encoded`, `AvatarSceneHandoff`, Catalog 참조 |
| WebGL에서 검게 보임 | `AvatarAssembler` 색상/텍스처 fallback, GraphicsSettings Always Included Shaders |
| 바닥에 파묻힘 | Player 루트가 아니라 `PlayerAvatarVisual`의 자식 정렬 값 |
| 구형 캐릭터가 보임 | Scene/Prefab에 삭제된 `Avatar_A/B/C` 참조가 남았는지 확인 |
| 원격 사용자만 외형 누락 | Server RPC 수신과 `FixedString4096Bytes` 길이 검사 확인 |
