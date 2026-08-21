# Animator Controller 세팅 체크리스트 (Idle / Walk)

> Mixamo FBX 2개는 `Assets/_Project/Animations/`에 배치 완료 (Idle.fbx, Walking.fbx — Without Skin).
> 코드(`PlayerAvatarVisual`)는 `IsWalking`(bool) 또는 `Speed`(float) 파라미터를 자동 감지하므로
> 아래대로 Controller만 만들면 바로 연결된다. 소요 ~30분.

---

## Step 1. FBX 임포트 설정 — Idle.fbx, Walking.fbx **각각** (10분)

Mixamo 애니메이션은 자체 스켈레톤(`mixamorig:`)을 쓴다. Humanoid로 설정해야
Sidekick 캐릭터에 **리타게팅**되어 재생된다. 이게 이번 작업의 핵심이다.

Project 창에서 `Idle.fbx` 클릭 → Inspector에서:

### 1-1. Rig 탭
- **Animation Type**: `Humanoid` 로 변경
- **Avatar Definition**: `Create From This Model`
- 우측 하단 **Apply** 클릭
- Apply 후 `Configure...` 옆에 **✓ 체크**가 뜨면 성공 (본 매핑 정상)

### 1-2. Animation 탭
- 목록에서 클립(보통 `mixamo.com`) 선택
- **Loop Time**: ✅ 체크 ← 안 하면 한 번 재생하고 멈춤
- **Loop Pose**: ✅ 체크 (이음새 부드럽게)
- **Root Transform Position (Y)** → **Bake Into Pose** ✅ (캐릭터가 뜨는 것 방지)
- **Root Transform Position (XZ)** → **Bake Into Pose** ✅ ← **Walking에서 특히 중요**
  (안 하면 애니메이션이 캐릭터를 앞으로 밀어서 우리 이동 코드와 충돌)
- **Apply**

> `Walking.fbx`도 1-1 ~ 1-2 동일하게 반복.

### 1-3. 클립 이름 확인
FBX 왼쪽 화살표(▶)를 펼치면 안에 애니메이션 클립(삼각형 아이콘)이 보인다.
이걸 Step 3에서 드래그해서 쓴다.

---

## Step 2. Animator Controller 생성 (5분)

1. Project 창 `Assets/_Project` 우클릭 > Create > Folder → `Animators`
2. 그 폴더 안에서 우클릭 > **Create > Animation > Animator Controller**
   - 이름: `AvatarAnimator`
3. `AvatarAnimator` **더블클릭** → Animator 창이 열림

---

## Step 3. 상태(State)와 파라미터 설정 (10분)

Animator 창에서 작업한다.

### 3-1. 파라미터 추가
1. Animator 창 좌측 상단 **Parameters** 탭 클릭
2. **+** 버튼 → **Bool** 선택
3. 이름을 **`IsWalking`** 으로 입력 ← **정확히 이 철자** (코드가 이 이름을 찾음)

### 3-2. 상태 만들기
1. Project 창에서 **Idle.fbx 안의 클립**을 Animator 창 빈 공간으로 **드래그**
   → `mixamo.com` 이라는 주황색 박스가 생김 (첫 상태라 주황 = 기본 상태)
   → 박스 우클릭 > Rename 또는 선택 후 Inspector에서 이름을 **`Idle`** 로 변경
2. Walking 클립도 같은 방식으로 드래그 → 이름 **`Walk`**
3. `Idle`이 **주황색(기본 상태)** 인지 확인. 아니면 Idle 우클릭 > **Set as Layer Default State**

### 3-3. 전환(Transition) 연결
**Idle → Walk**
1. `Idle` 박스 우클릭 > **Make Transition** → 마우스를 `Walk`로 옮겨 클릭
2. 생긴 **화살표를 클릭** → Inspector에서:
   - **Has Exit Time**: ❌ **체크 해제** ← 안 하면 반응이 굼뜸
   - Settings 펼치기 → **Transition Duration**: `0.15`
   - **Conditions** `+` → `IsWalking` / `true`

**Walk → Idle**
1. `Walk` 우클릭 > Make Transition → `Idle` 클릭
2. 화살표 클릭 → Inspector에서:
   - **Has Exit Time**: ❌ 해제
   - **Transition Duration**: `0.15`
   - **Conditions** `+` → `IsWalking` / `false`

---

## Step 4. 아바타 프리팹에 연결 (5분)

**Avatar_A / Avatar_B / Avatar_C 3개 모두** 반복:

1. Project 창에서 `_Project/Prefabs/Avatar/Avatar_A` **더블클릭** (프리팹 편집 모드)
2. 루트 오브젝트 선택 → Inspector의 **Animator** 컴포넌트에서:
   - **Controller**: `AvatarAnimator` 드래그
   - **Apply Root Motion**: ❌ **체크 해제** ← 중요. 켜두면 애니메이션이 캐릭터를 멋대로 움직임
   - Avatar: `Avatar_AAvatar` 그대로 (자동 설정됨)
3. ← 로 프리팹 모드 나가기

> Animator 컴포넌트가 없으면 Add Component > Animator로 추가하고,
> Avatar 슬롯에 같은 이름의 `..._Avatar` 를 넣는다.

---

## Step 5. 테스트 (5분)

1. **Play** → Start Server → Player 2 / Player 3 접속
2. 확인:
   - [ ] 가만히 있을 때 **Idle 동작** (T-포즈 아님)
   - [ ] WASD 누르면 **걷는 동작**으로 전환
   - [ ] 키를 떼면 다시 Idle
   - [ ] **상대 화면에서도** 그 캐릭터가 걷는 것이 보임 ← AnimState 동기화 검증
   - [ ] 캐릭터가 제자리에서 걷고, 이동은 우리 코드가 담당 (미끄러지듯 밀려나지 않음)

### 증상별 조치

| 증상 | 원인/조치 |
|---|---|
| 여전히 T-포즈 | Controller 미할당 / 클립이 Humanoid로 설정 안 됨 (Step 1-1) |
| 한 번 재생하고 멈춤 | Loop Time 체크 안 함 (Step 1-2) |
| 걸을 때 캐릭터가 앞으로 날아감 | Apply Root Motion 체크됨(Step 4) 또는 Root Transform XZ Bake 안 함(Step 1-2) |
| 걷기로 안 바뀜 | 파라미터 이름 오타 — 정확히 `IsWalking` |
| 전환이 느림/뚝뚝 끊김 | Has Exit Time 체크 해제, Duration 0.15 |
| 캐릭터가 땅에 파묻히거나 뜸 | Root Transform Position(Y) Bake Into Pose 확인 |
| 팔다리가 뒤틀림 | Rig > Configure에서 본 매핑 확인 (드물다) |

---

## 완료 후

- 커밋: `feat(unity): add idle/walk animator for avatars`
- ⚠️ `Assets/_Project/Animations/`는 우리 에셋이므로 **커밋 대상** (Synty 폴더와 다름)
- 이후 Emote(WORLD-05)를 붙일 때 이 Controller에 상태를 추가하면 된다 — spec 013 범위
