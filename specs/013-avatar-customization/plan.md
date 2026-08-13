# Implementation Plan: 캐릭터 커스터마이징

**Spec**: `specs/013-avatar-customization/spec.md`
**Branch**: `game`
**Date**: 2026-08-12

---

## Summary

파츠 조립과 실시간 전파는 **동작 확인 완료**다 (16종 파츠 교체, 서버 권위 전파, T-24/T-25/T-27 해결).
남은 일은 **① Spring 영구 저장·복원 ② React 창으로 이관 ③ 성능 확인** 셋이다.

Unity 파트의 실질 작업은 ②의 **브릿지 양방향 완성**과 ③이다. 저장은 BE 완성에 종속된다.

## Technical Context

| 항목 | 값 |
|---|---|
| Engine | Unity 6000.0.78f1 / URP 17.0.4 |
| 아바타 | Synty Sidekick Runtime API (`SidekickRuntime`, `Combiner`, SQLite 파츠 DB) |
| 동기화 | NGO `NetworkVariable<FixedString4096Bytes>` (서버 쓰기 / 전원 읽기) |
| 렌더링 | `CreateCharacter(combineMesh: true)` → 캐릭터당 드로우콜 1 |
| 브릿지 | `SendMessage`(JS→Unity) / jslib 콜백(Unity→JS, **미구현**) |
| 저장 | `IUserApiClient.UpdateMyAvatarAsync` (현재 Mock) |
| Target | Unity Web (WebGL) — Sidekick SQLite는 `:memory:` + Deserialize로 동작 확인 |
| 제약 | 인코딩 상한 3800자(현행), Synty 패키지는 저장소 미포함(.gitignore) |

## Constitution Check

| 조항 | 준수 방법 |
|---|---|
| 5조 아바타는 데이터로 생성 | 문자열만 동기화, 3D는 로컬 생성, 외형에 NetworkObject 미부착 (현행 준수) |
| 12조 클라이언트 불신 | 서버 RPC에서 길이·형식 검증 (현행). 파츠 소유권 검증은 P1 |
| 17조 UI는 웹 레이어 | Unity HUD는 **임시**. React 창 완성 시 제거 또는 개발 전용 격리 |
| 18조 기준선 동결 | `NetworkPlayer` 무수정 유지 — 별도 컴포넌트로 확장 (현행) |
| 21조 계약 변경 절차 | 인코딩 상한 변경은 BE 스키마에 직결 → 단독 변경 금지 |

**계약 문서 정정 필요**: `festa-unity/Docs/avatar-customization-contract.md`의 **29~32자 제한은 무효**.
파츠 조립 시 실측 약 600자. 이 문서를 갱신하지 않으면 BE가 `VARCHAR(32)`로 만들어 T-24가 DB에서 재발한다.

## Project Structure

```text
Assets/_Project/Scripts/World/Avatar/
├── AvatarAppearance.cs             [완료] 이중 인코딩, forward compatible
├── SidekickRuntimeService.cs       [완료] DB 1회 초기화, 미러 파츠 폴백
├── PlayerAppearanceController.cs   [완료] 서버 권위 전파 + 미반영 감지
├── PlayerAvatarVisual.cs           [완료] 구독 → 로컬 생성, Animator 폴백
├── AvatarCatalog.cs                [완료] 프리셋 매핑 (FormerlySerializedAs)
├── AvatarCustomizationHud.cs       [임시] React 이관 후 개발 전용 격리
└── AvatarBridge.cs                 [수정] Unity→JS 콜백 추가 필요

Assets/Plugins/WebGL/festa-bridge.jslib   [신규] 006과 공용
festa-unity/Docs/avatar-customization-contract.md  [갱신 필수] 길이 제한 정정
```

## 접근 방식

1. **저장은 인터페이스 뒤에서 이미 끝나 있다.** `IUserApiClient.UpdateMyAvatarAsync` 구현만 Mock→Http로 바꾸면 된다.
   BE 완성 전까지 Mock 유지 — 개발이 막히지 않는다.
2. **복원 경로가 신규**: 접속 시 프로필의 외형 값을 초기값으로 사용해야 한다.
   현재는 접속 payload의 `avatarCode`를 초기값으로 쓰는데, 이 경로가 실제 저장값을 받도록 연결한다.
3. **React 이관은 브릿지 양방향이 전제**: 지금은 JS→Unity(`SendMessage`)만 된다.
   React가 파츠 목록과 현재 값을 알아야 창을 그릴 수 있으므로 **Unity→JS 콜백**이 필요하다.
4. **Unity HUD를 지우지 않는다.** React 창이 완성될 때까지 유일한 검증 수단이고,
   완성 후에도 개발 빌드에서 유용하다. `DEVELOPMENT_BUILD`로 격리만 한다.

## Complexity Tracking

| 위험 | 대응 |
|---|---|
| **인코딩 길이 상한 미확정(C-01)** — BE 스키마 결정 전 | 현행 3800 유지, 계약 문서에 "32자 무효" 명시하고 BE에 즉시 통지 |
| 파츠 목록 마스터 미결(C-02) | Unity가 소유한 상태로 진행. Spring 이관 시 `AvatarBridge`가 목록을 넘기는 형태로 확장 |
| 썸네일 없음(C-03) | 없으면 React 창이 이름 텍스트만 보여줘야 함 → **에디터에서 파츠별 캡처 자동화** 검토 |
| 30~40명 동시 렌더링 | `combineMesh:true`로 드로우콜 1 유지 중. 다만 **인원수만큼 메시 병합 비용** 발생 → 측정 필요 |
| 파츠 DB 초기화 지연 | 준비 완료 시 재생성하는 폴백 이미 존재 (`Update`에서 감시) |
