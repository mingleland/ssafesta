# Reference Audit

> 세 레퍼런스에서 무엇을 채택/기각했는지 기록. (Phase 2 산출물)

## 버전 호환성 (핵심 발견)

BossRoom이 **현재 festa-unity와 동일한 URP 17.0.4** 기반이며, 다음 검증된 조합을 사용 중:

| Package | BossRoom 버전 | FESTA 채택 |
|---|---|---|
| com.unity.netcode.gameobjects | 2.4.3 | **2.4.3 그대로** |
| com.unity.transport | 2.5.1 | **2.5.1 그대로** |
| com.unity.multiplayer.playmode | 1.5.0 | **1.5.0** (에디터 A/B 테스트용) |
| com.unity.multiplayer.tools | 2.2.4 | **2.2.4** (Network Profiler) |

→ NGO API를 버전 차이로 재작성할 필요 없음. BossRoom 코드 패턴을 그대로 참고 가능.

## 채택/기각 요약

| 영역 | Reference | 채택 내용 | 기각/변형 |
|---|---|---|---|
| Connection Approval | BossRoom `ConnectionManager.ApprovalCheck` | payload JSON → 검증 → Approve/Deny + Reason 코드 패턴 | ConnectionState 상태 머신 클래스 6종은 **미채택** — 8주 범위에 과함. 재접속 정책 확정(doc 12 §9) 시 재검토 |
| Session Data | BossRoom `SessionPlayerData`/SessionManager | clientId→payload 보관 후 스폰 시 주입 (`SessionDataStore`로 단순화) | 재접속 데이터 보존 로직은 정책 미정으로 보류 |
| DI | BossRoom VContainer | **미채택** — 규칙 5(오버엔지니어링 금지). static composition root(`ApiServices`)로 대체 |
| Owner 권위 이동 | BossRoom(서버 권위) + NGO 표준 패턴 | `NetworkTransform.OnIsServerAuthoritative()=false` 서브클래스 (doc 12 §10 A안) | BossRoom의 서버 권위 이동/Navigation은 미채택 |
| 데이터 기반 오브젝트 매핑 | VampireSurvivors (SlimeMaster) Managers/Resource | "타입 → 프리팹" 데이터 매핑 개념 → `BoothObjectRegistry`(ScriptableObject) | Addressables는 아직 미도입 (아트 에셋 규모 확정 후). Managers 싱글턴 전역 구조는 현 단계 불필요 |
| Pool | VampireSurvivors Pool | **보류** — Booth Object는 씬당 소수라 풀링 불필요. 이펙트/미니게임 구현 시 도입 |
| UI/Data/Save | IdleGameScripts | **현 단계 미채택** — Inventory/Currency는 Spring이 Source of Truth라 Unity 로컬 Save 구조가 필요 없음. UI 구조는 P1 UI 작업 시 참고 |

## 참고한 파일 (READ ONLY 준수)

- `BossRoom/Assets/Scripts/ConnectionManagement/ConnectionManager.cs`
- `BossRoom/Assets/Scripts/ConnectionManagement/ConnectionMethod.cs`
- `BossRoom/Assets/Scripts/ConnectionManagement/SessionPlayerData.cs`
- `BossRoom/Packages/manifest.json` (버전 확인)

레퍼런스 수정 없음.
