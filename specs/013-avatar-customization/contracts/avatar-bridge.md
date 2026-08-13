# Contract: Unity ↔ React 아바타 브릿지

**Spec**: 013 | **당사자**: Unity ↔ React (FE)
**헌법 근거**: 25조 (UI는 웹 레이어)

---

## 목적

커스터마이징 창은 최종적으로 React가 담당한다. 이 계약은 **그 이관 지점**을 미리 고정해,
Unity Lobby를 들어내도 나머지가 그대로 동작하게 한다.

**지금 단계**: Unity `CharacterLobby`가 UI를 담당한다. 이 계약은 아직 사용되지 않지만 형태는 유지한다.

---

## 역할 분담

| 책임 | 담당 |
|---|---|
| 카테고리·항목 목록 제공 | **Unity** (Catalog가 소유) |
| 썸네일 제공 | **Unity** (에디터에서 캡처한 정적 자산) |
| 항목 선택 UI | **React** (이관 후) |
| 외형 표현 인코딩 | **Unity** — React는 의미 단위(카테고리·항목)로만 조작한다 |
| 서버 전파 | **Unity** |
| 저장 호출 | **Unity** |

**핵심 원칙**: React가 인코딩 형식을 알 필요가 없다.
"헤어를 3번으로 바꿔줘"라고 말하면 Unity가 나머지를 처리한다.
이렇게 해야 인코딩이 바뀌어도 React를 고치지 않는다.

---

## React → Unity

```js
// 항목 변경
unityInstance.SendMessage('AvatarBridge', 'SetItem', JSON.stringify({
  category: 'hair',
  itemId: 3
}));

// 색상 변경
unityInstance.SendMessage('AvatarBridge', 'SetColor', JSON.stringify({
  category: 'hair',
  colorId: 2
}));

// 초기화 / 랜덤 / 확정
unityInstance.SendMessage('AvatarBridge', 'ResetAppearance', '');
unityInstance.SendMessage('AvatarBridge', 'RandomizeAppearance', '');
unityInstance.SendMessage('AvatarBridge', 'ConfirmAppearance', '');
```

## Unity → React (jslib 콜백)

```js
window.FestaUnity = window.FestaUnity || {};

// 카탈로그 전달 — React가 UI를 그릴 재료
window.FestaUnity.onCatalogReady = (json) => {
  // { categories: [{ id, displayName, items: [{ itemId, displayName, thumbnailUrl, colors }] }] }
};

// 현재 외형 통지 — 변경이 실제로 적용됐을 때
window.FestaUnity.onAppearanceChanged = (json) => {
  // { gender, items: { hair: 3, top: 5 }, colors: { hair: 2 } }
};

// 거부 통지 — 조용히 실패하지 않는다 (T-24)
window.FestaUnity.onAppearanceRejected = (json) => {
  // { reason: "..." }
};
```

---

## 규칙

| # | 규칙 |
|---|---|
| 1 | `SendMessage`는 **반환값이 없다.** 결과는 반드시 콜백으로 받는다 |
| 2 | 거부·실패는 `onAppearanceRejected`로 **반드시 통지**한다. 무반응으로 두지 않는다 |
| 3 | React 창이 열려 있는 동안 **Unity 캐릭터 입력을 차단**한다 (006과 공용 플래그) |
| 4 | 카테고리 이름은 Catalog가 정의한다. React가 하드코딩하지 않는다 |

## 미확정

- jslib 콜백 구현 시점 — Unity Lobby가 완성되고 FE Overlay Platform이 확정된 뒤
- 썸네일 호스팅 방식 (정적 자산 경로 vs base64 인라인)

## 변경 절차

이 계약 변경은 **FE + Unity 합의**가 필요하다 (헌법 24조).
`006-booth-runtime`의 상호작용 payload 계약과 같은 jslib 파일을 공유하므로 함께 검토한다.
