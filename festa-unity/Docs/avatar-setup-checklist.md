# 아바타(Sidekick) 에디터 세팅 체크리스트

> 코드(Adapter 4종)는 작성 완료. 아래는 에디터에서만 가능한 클릭 작업.
> 소요 ~30분. 막히면 Console 에러를 복사해서 전달할 것.

## ⚠️ 저장소 정책: `Assets/Synty/`는 커밋하지 않는다

- Synty Sidekick 패키지(43MB)는 `.gitignore` 제외 대상 — 각자 Asset Store에서 임포트
- **완성된 아바타는 `_Project/Experimental/Sidekick/` 에 커밋**되므로, Synty 폴더가 없어도
  게임 실행·빌드·멀티플레이 모두 정상 동작한다
- **새 아바타 프리셋을 추가하려는 사람만** Asset Store에서
  "FREE Starter Pack - Sidekick Modular Characters by Synty"를 임포트하면 된다

---

## Step 1. FBX → 프리팹화 (10분)

Sidekick이 Export한 FBX는 "모델 에셋"이라 그대로 쓰면 설정이 안 붙는다. 프리팹으로 만든다.

1. Project 창에서 `Assets/_Project/Prefabs` 우클릭 > Create > Folder → 이름 `Avatar`
2. `Assets/_Project/Experimental/Sidekick/Avatar_A` FBX를 **Hierarchy로 드래그** (씬에 인스턴스 생성됨)
3. Hierarchy에서 그 오브젝트 선택 → Transform **Position (0,0,0) / Rotation (0,0,0) / Scale (1,1,1)** 확인
4. 그 오브젝트를 **`_Project/Prefabs/Avatar` 폴더로 드래그** → 프리팹 생성됨
5. Hierarchy에 남은 인스턴스는 **삭제**
6. Avatar_B, Avatar_C도 2~5 반복

> ⚠️ Sidekick FBX의 스케일이 이상하면(너무 크거나 작으면) FBX 선택 → Model 탭 → Scale Factor 조정 후 Apply.
> 사람 키가 대략 1.7~1.8 유닛이면 정상.

## Step 2. Avatar Catalog 생성 (5분)

1. Project 창 `Assets/_Project/ScriptableObjects` 우클릭 > **Create > FESTA > Avatar Catalog**
   - 이름: `AvatarCatalog` (기본값 그대로)
   - 메뉴에 FESTA가 없으면 컴파일 미완료 → Console 확인
2. 생성된 asset 선택 → Inspector에서:
   - **Entries** 우측 `+` 버튼을 3번 눌러 항목 3개 추가
   - Element 0: Avatar Code `sk_01`, Prefab ← `Avatar_A` 프리팹 드래그
   - Element 1: Avatar Code `sk_02`, Prefab ← `Avatar_B`
   - Element 2: Avatar Code `sk_03`, Prefab ← `Avatar_C`
   - **Fallback Prefab**: `Avatar_A` 드래그 (알 수 없는 코드일 때 사용)

> avatarCode 문자열은 나중에 Spring User 프로필에 저장될 값이다. 오타 주의.

## Step 3. PlayerAvatar 프리팹에 컴포넌트 추가 (10분)

1. Project 창에서 `_Project/Prefabs/Player/PlayerAvatar` **더블클릭** (프리팹 편집 모드)
2. **Add Component** → `PlayerAvatarVisual` 검색해서 추가
3. Inspector에서 설정:
   - **Catalog**: Step 2의 `AvatarCatalog` asset 드래그
   - **Visual Root**: 비워둠 (자기 자신 사용)
   - **Visual Offset**: X `0`, Y `-1`, Z `0`
     ← 캡슐은 피벗이 중심(높이 2)이라 발이 바닥에 닿으려면 1만큼 내려야 함
   - **Visual Scale**: `1`
4. **기존 캡슐 외형 끄기** (충돌·크기는 유지):
   - 같은 Inspector에서 **Mesh Renderer** 컴포넌트의 **체크박스 해제** (컴포넌트 삭제 아님)
   - **Capsule Collider는 그대로 둔다** (나중에 충돌 처리에 사용)
   - Mesh Filter도 그대로 둠
5. 프리팹 모드 나가기 (Hierarchy 상단 **←** 화살표)

## Step 4. 테스트 (5분)

DevConnectionHud가 접속할 때마다 `sk_01 → sk_02 → sk_03` 순환 배정하도록 수정돼 있다.

1. **Play** → HUD에서 **Start Server**
2. Multiplayer Play Mode의 Player 2 → **Connect as Client** (또는 Connect via Session API)
3. Player 3 → 접속
4. 확인:
   - [ ] 캡슐 대신 **Sidekick 캐릭터**가 보임
   - [ ] Player 2와 Player 3의 **외형이 서로 다름**
   - [ ] 발이 바닥에 닿아 있음 (뜨거나 묻히지 않음)
   - [ ] WASD 이동 시 캐릭터가 함께 이동, 상대 화면에도 반영
   - [ ] Console: `[AvatarVisual] 'sk_01' 적용 (owner=1)` 형태 로그
   - [ ] 이름표가 캐릭터 머리 위에 표시됨

### 증상별 조치

| 증상 | 조치 |
|---|---|
| 캐릭터가 바닥에 묻히거나 뜸 | Visual Offset Y 조정 (-1 기준으로 ±0.5씩) |
| 캐릭터가 너무 크거나 작음 | FBX의 Model 탭 > Scale Factor, 또는 Visual Scale |
| 여전히 캡슐만 보임 | Mesh Renderer 체크 해제했는지 / Catalog 할당했는지 확인 |
| `프리팹 없음 — placeholder 캡슐 사용` 경고 | Catalog의 avatarCode 문자열 오타 확인 (`sk_01`) |
| 모두 같은 외형 | Catalog 엔트리에 서로 다른 프리팹을 넣었는지 확인 |
| T-포즈로 서 있음 | **정상** — Animator Controller 미제작. 다음 작업 항목 |

## Step 5. WebGL 회귀 확인 (선택, 나중에 몰아서 가능)

1. Build Profiles → Scene List를 **Main 씬만** 체크 (SidekickTestScene 해제)
2. Web으로 Switch → Build → `Builds/web` (덮어쓰기)
3. `cd Builds\web` 후 `python -m http.server 8000`
4. 브라우저 2탭 + Docker 서버로 접속 → 서로 다른 아바타 확인
5. 빌드 용량 확인 (참고: 캐릭터 없는 버전 64.7MB)

---

## 다음 작업 (이번 세션 범위 아님)

- **Animator Controller 제작**: Mixamo에서 Humanoid Idle/Walk 다운로드 → Controller 생성 → `IsWalking`(bool) 또는 `Speed`(float) 파라미터 사용 (코드가 자동 감지)
- `Experimental/Sidekick`의 FBX를 정식 위치로 이동 (프리팹 참조 확인 필요)
- 프리셋 종수 확대 (현재 3종 → 목표 8~12종)
