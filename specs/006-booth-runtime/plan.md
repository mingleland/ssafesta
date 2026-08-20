# Implementation Plan: Booth Runtime

**Spec**: `specs/006-booth-runtime/spec.md`
**Branch**: `game`
**Date**: 2026-08-12

---

## Summary

Layout JSON → 3D 생성은 이미 동작한다 (오브젝트 5종 생성, 미지원 타입 격리 검증 완료).
남은 일은 셋이다: **① 공개본(Published) 연동 ② 상호작용 시스템 ③ 임시 도형을 실제 에셋으로 교체**.

이 중 ②가 실질적 신규 개발이며, **React와의 이벤트 계약**이 핵심 산출물이다.
Unity는 "무엇이 클릭됐다"만 알리고 UI는 전부 웹이 담당한다 (헌법 17조).

## Technical Context

| 항목 | 값 |
|---|---|
| Engine | Unity 6000.0.78f1 / URP 17.0.4 |
| 입력 | Input System (신규 패키지) — `Mouse.current`, Raycast |
| 데이터 | `BoothLayoutDto` (JsonUtility), HTTP 조회 |
| 브릿지 | `Application.ExternalCall` 대체 → **jslib 플러그인** (`.jslib`) 필요 |
| Target | Unity Web (WebGL) |
| 성능 목표 | 부스 7개 동시 표시, 오브젝트 상한은 005 C-01 결정 |
| 제약 | 부스 오브젝트는 NetworkObject 금지, 부스 콘텐츠 추가에 재빌드 금지 |

## Constitution Check

| 조항 | 준수 방법 |
|---|---|
| 4조 Booth는 데이터로 생성 | 타입 추가 시 Registry 매핑만 추가. 프리팹은 Addressable/Resources 조회 |
| 4조 정적 오브젝트 비동기화 | `BoothObjectFactory` 산출물에 NetworkObject를 붙이지 않는다 (현행 유지) |
| 17조 UI는 웹 레이어 | Unity는 상호작용 이벤트만 발행. 채팅창·홈페이지·설문 UI를 Unity에 만들지 않는다 |
| 21조 계약 변경 절차 | 이벤트 payload와 `LAPTOP` 타입 추가는 FE와 합의 후 확정 |
| 18조 기준선 동결 | `BoothRuntime`, `BoothObjectFactory` 구조 유지 — 델타만 |

**주의**: 3조(AI 장애 격리) — AI 오브젝트 상호작용 실패가 부스 표시나 월드 이동을 막아서는 안 된다.

## Project Structure

```text
Assets/_Project/Scripts/Booth/
├── Layout/
│   ├── BoothLayoutDto.cs           [수정] 005 계약 확정분 반영 (LAPTOP 필드 등)
│   └── BoothObjectType.cs          [수정] LAPTOP 추가
├── Factory/
│   ├── BoothObjectRegistry.cs      [수정] 타입 → 프리팹 매핑 실에셋 전환
│   └── BoothObjectFactory.cs       [수정] 상호작용 컴포넌트 부착
├── Runtime/
│   ├── BoothRuntime.cs             [수정] Published 조회 경로 + 갱신 정책
│   └── BoothRuntimeObject.cs       [수정] objectId/configId 보관
└── Interaction/                    [신규]
    ├── BoothObjectInteractable.cs  클릭 대상 표시 + 하이라이트
    ├── InteractionRaycaster.cs     화면 클릭 → 대상 판정
    └── InteractionDispatcher.cs    → jslib 경유 React 통지

Assets/Plugins/WebGL/festa-bridge.jslib   [신규] Unity → JS 콜백
```

## 접근 방식

1. **상호작용은 타입 무관하게 하나의 경로**로 만든다. `AI_AGENT`든 `LAPTOP`이든
   `{ type, boothId, objectId, configId }`를 그대로 웹에 넘긴다. Unity가 타입별 분기를 갖지 않는다
   — 새 기능이 생겨도 Unity 재빌드가 필요 없게 (헌법 4조 정신).
2. **갱신 정책(C-01)은 가장 단순한 것부터**: 부스 구역 진입 시 조회. 주기 폴링·서버 푸시는 필요해지면 추가.
3. **에셋 교체는 마지막**: 임시 도형으로 로직을 완성한 뒤 프리팹만 바꾼다. 에셋 확보가 지연돼도 개발이 막히지 않게.

## Complexity Tracking

| 위험 | 대응 |
|---|---|
| **005 Layout 계약 미확정** — 좌표 규칙이 바뀌면 배치가 전부 틀어짐 | 계약 확정 전까지 현재 mock JSON으로 개발. 확정 후 **왕복 검증 1회**(005 참조) 필수 |
| jslib 브릿지 미경험 | 아바타(`AvatarBridge`)에서 이미 쓰는 `SendMessage` 역방향. 작은 PoC 먼저 |
| 상호작용 판정이 캐릭터 이동과 충돌 | 오버레이가 열리면 Unity 입력을 막는 플래그 필요 (016 C-07과 동일 이슈) |
| 오브젝트 수 상한 미정 | 상한 없이 만들되 생성 개수를 로그로 남겨 부하 테스트 근거 확보 |
