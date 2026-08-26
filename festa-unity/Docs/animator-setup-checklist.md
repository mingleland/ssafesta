# 플레이어 Animator 세팅 체크리스트

> 현재 기준: 모듈러 아바타에 Humanoid Idle/Walk/Run과 8종 감정표현을 리타게팅한다.

## 1. 이동 클립

- `Assets/_Project/Animations/Idle.fbx`
- `Assets/_Project/Animations/Walking.fbx`
- `Assets/_Project/Animations/Run.fbx`

각 FBX의 Rig는 `Humanoid`로 설정하고 Avatar 매핑이 유효해야 한다. 이동 클립은 반복 재생하며 Root Transform 회전과 위치가 캐릭터 루트를 밀지 않도록 Bake Into Pose를 확인한다.

## 2. Animator 파라미터

- `IsWalking` 또는 `Speed`: 걷기 전환
- `IsRunning`: 달리기 전환
- `EmoteId`: 감정표현 선택

Idle ↔ Walk ↔ Run은 짧은 CrossFade를 사용한다. 이동 방향 회전은 `PlayerMovement`가 담당하며 Animation Root Motion으로 플레이어 위치를 이동시키지 않는다.

## 3. 감정표현

Alt + 왼쪽 클릭 드래그로 8방향 휠을 열고 선택한다.

- 인사, 경례, 왕의 자세, 강남스타일
- 패배, 기도, 트월킹, 기쁨의 점프

강남스타일과 트월킹은 이동 전까지 반복한다. 모든 감정표현은 이동 입력 시 부드럽게 종료한 뒤 Walk/Run으로 전환한다.

## 4. 이동값 기준

- Walk: `36`
- Run: `52`
- 이동 방향은 현재 카메라의 평면 Forward/Right 기준
- 방향 전환은 `SmoothDampAngle`로 보간

## 5. 검증

1. W/A/S/D 각각이 카메라 기준 방향으로 움직이는지 확인한다.
2. 반대 방향 입력에서도 캐릭터가 순간이동하듯 꺾이지 않는지 확인한다.
3. 이동 중 Idle이 재생되거나 발이 미끄러지지 않는지 확인한다.
4. 감정표현 중 이동 입력 시 자연스럽게 이동 상태로 돌아오는지 확인한다.
5. 한 Client의 감정표현이 다른 Client에도 같은 ID와 재생 상태로 보이는지 확인한다.
