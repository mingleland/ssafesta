# 아바타 커스터마이징 계약서

> **대상**: Frontend(React) / Backend(Spring) / Unity
> **현재 상태**: Unity 측 구현·검증 완료. Spring API와 React 창은 미구현 (아래 요청 사항 참조)
> **관련**: doc 02 WORLD-04, doc 27 spec 013(avatar-presence), Docs/architecture.md

---

## 1. 개요

캐릭터 외형은 **프리셋 방식**이다. Sidekick Character Creator(에디터 툴)로 미리 제작한
캐릭터 프리셋 중 하나를 고르고, 색상 틴트를 얹는다. 런타임 파츠 조립은 하지 않는다.

```
[커스터마이징 창]  →  avatarCode 문자열  →  ┬→ Unity 월드: 즉시 동기화 (전원에게 보임)
                                          └→ Spring: 프로필에 영구 저장
```

**핵심**: 외형 전체가 짧은 문자열 하나(`avatarCode`)로 표현된다.
파트별로 이 문자열만 주고받으면 되고, 3D 데이터는 오가지 않는다.

## 2. avatarCode 포맷 (3파트 공통 계약)

```
sk_01                 프리셋만
sk_01|c=E85D5D        프리셋 + 색상 틴트(RRGGBB)
```

| 규칙 | 내용 |
|---|---|
| 최대 길이 | **29자** (Unity NetworkVariable 제한) |
| 구분자 | `\|` (파이프), 세그먼트는 `키=값` |
| 알 수 없는 세그먼트 | **무시한다** — 구버전 클라이언트가 깨지지 않음 (forward compatible) |
| 빈 값/오류 | `sk_01`(기본값)로 폴백 |
| 확장 예정 | 파츠 단위가 필요해지면 `\|h=3\|u=7` 형태로 세그먼트 추가 |

프리셋 코드는 `sk_01`, `sk_02`, … 형식이며 **실제 목록은 Unity AvatarCatalog가 소유**한다
(아래 §5 참조).

## 3. Backend(Spring)에 요청하는 것

### 3-1. User 테이블

`avatar_code VARCHAR(32)` 컬럼. 기본값 `sk_01`. (doc 09 User 엔티티에 반영 필요)

### 3-2. API 2개

**조회** — 기존 내 정보 조회에 포함하면 됨
```http
GET /api/v1/users/me
→ { "userId": 12, "nickname": "홍길동", "avatarCode": "sk_01|c=E85D5D" }
```

**저장**
```http
PATCH /api/v1/users/me/avatar
Content-Type: application/json
{ "avatarCode": "sk_02|c=5D8CE8" }

→ 200 { "avatarCode": "sk_02|c=5D8CE8" }
→ 400 잘못된 형식 (32자 초과, 허용되지 않은 문자)
→ 401 미인증
```

검증 권장 사항:
- 길이 ≤ 32, 정규식 `^[a-zA-Z0-9_|=]+$`
- 프리셋 코드 화이트리스트 검증은 **선택** — Unity가 모르는 코드를 받으면 기본값으로 폴백하므로
  잘못된 값이 들어와도 클라이언트가 깨지지 않는다
- (P1) 상점에서 구매하지 않은 프리셋 차단이 필요해지면 이 지점에서 검증

## 4. Frontend(React)에 요청하는 것

정식 커스터마이징 창은 **React 오버레이**가 담당한다
(Booth Studio와 동일 원칙 — 복잡한 UI·입력은 React, Unity는 월드 렌더링).

### 4-1. 화면 구성 (제안)

- 프리셋 목록: 썸네일 그리드 (선택 상태 표시)
- 색상 팔레트: 원본 + 5색 스와치
- 미리보기: **Unity 월드의 내 캐릭터가 실시간으로 바뀌므로 별도 3D 프리뷰 불필요**
  (창을 반투명/사이드로 배치하면 뒤의 캐릭터가 즉시 변하는 것이 보인다)
- 저장 버튼: `PATCH /users/me/avatar` 호출

### 4-2. Unity 브릿지 호출

Unity 씬에 `AvatarBridge`라는 이름의 오브젝트가 있고, 아래 메서드를 노출한다.

```js
// 외형 적용 (즉시 월드 반영 + 다른 사용자에게 전파)
unityInstance.SendMessage('AvatarBridge', 'ApplyAppearance', 'sk_02|c=E85D5D');
```

> `SendMessage`는 반환값이 없다. 현재 값과 프리셋 목록은 아래 콜백/REST로 받는다.

**Unity → React 콜백** (jslib 연동 필요, 미구현):
```js
window.FestaUnity = window.FestaUnity || {};
window.FestaUnity.onAvatarApplied = (json) => {
  const { avatarCode } = JSON.parse(json); // 적용 완료 통지
};
```

### 4-3. 프리셋 목록을 얻는 방법 — **결정 필요**

두 가지 중 택일 (팀 합의 사항, docs/26 등록):

| 방안 | 설명 | 장단 |
|---|---|---|
| **A. Spring이 마스터** (권장) | `GET /api/v1/avatars/presets` → `[{code, name, thumbnailUrl}]`. Unity Catalog와 코드 목록을 일치시킴 | React가 썸네일까지 한 번에 받음. 프리셋 추가 시 서버만 갱신하면 UI 반영 |
| B. Unity가 제공 | `AvatarBridge.GetAvailablePresets()` (구현돼 있음) | 서버 작업 불필요하나 SendMessage 반환값 제약으로 콜백 필요, 썸네일 없음 |

**A안 채택 시 필요한 것**: 썸네일 PNG를 정적 자산으로 준비 (Unity 에디터에서 프리셋별로 캡처 → S3/CDN)

## 5. Unity 측 현재 구현 (완료)

| 파일 | 역할 |
|---|---|
| `AvatarAppearance` | avatarCode 인코딩/디코딩, forward-compatible 파서 |
| `AvatarCatalog` (SO) | presetCode → 프리팹 매핑, 표시 이름, 색상 팔레트 |
| `IAvatarVisualProvider` / `CatalogAvatarVisualProvider` | 외형 생성 경계 (Sidekick 비종속) |
| `PlayerAvatarVisual` | avatarCode 구독 → 외형 재생성 + 틴트 + Animator 연결 |
| `PlayerAppearanceController` | 변경 요청(ServerRpc) → 서버 반영 → 전원 전파 + 프로필 저장 호출 |
| `AvatarCustomizationHud` | **임시 Unity 창** (React 구현 전까지 사용, 월드 단독 데모용) |
| `AvatarBridge` | React ↔ Unity 연결 지점 |

**동기화 방식**: `avatarCode` 문자열만 NetworkVariable로 동기화된다.
3D 모델·머티리얼은 각 클라이언트가 로컬에서 생성하며 NetworkObject가 아니다
(Booth Runtime과 동일 원칙 — doc 07 §9).

**검증 완료**: 클라이언트 A가 프리셋/색상을 바꾸면 클라이언트 B 화면에서 즉시 반영됨.

## 6. 프리셋을 추가하는 방법 (Unity 담당)

1. Asset Store에서 Synty Sidekick FREE Starter Pack 임포트 (저장소에 없음 — `.gitignore` 제외)
2. Window > Sidekick Character Tool → 캐릭터 제작 → **Export Character as FBX**
   → `Assets/_Project/Experimental/Sidekick/`
3. FBX Rig 탭 → Animation Type `Humanoid` → Apply
4. 프리팹화 → `_Project/Prefabs/Avatar/`
5. `AvatarCatalog` asset에 엔트리 추가 (`presetCode`, `displayName`, `prefab`)
6. Spring 프리셋 목록(§4-3 A안 채택 시)과 썸네일도 함께 갱신

## 7. 미결 사항

- [ ] §4-3 프리셋 목록 마스터를 Spring/Unity 중 어디로 할지 (docs/26 등록)
- [ ] 썸네일 제작·호스팅 방식
- [ ] 프리셋 목표 종수 (현재 3종 → 12종 제안)
- [ ] 구매/보유 개념 도입 여부 (ECON-06/07과 연계, P1)
- [ ] jslib 콜백 구현 (React 창 착수 시)
