// Overlay 공통 계약 정본 — Shared Contract Freeze Candidate v0.1 (2026-09-01).
// Feature Track 소유는 타입·intent·payload 경계까지. OverlayFrame 의 layout·CSS·시각 상태는 UI Track 소유.
import type { OverlayType } from '../types/overlay';

/** UI Track 이 구현할 프레임이 Feature 로부터 받는 전부 */
export interface OverlayFrameVM {
  id: OverlayType;
  title: string;
  close: () => void;
}

// OnOverlayStateChanged(game-portal-bridge.md Draft)의 FE 측 어휘는 걷어냈다 (-450, #132).
// 그 계약의 방향은 FE→Unity 인데 그 역할은 이미 `SetInputLocked` 가 하고 있고(S15P21A604-376 완료),
// 지금 없던 것은 반대 방향이라 `bridge/worldUiState` 로 새로 세웠다. 소비자가 0 인 어휘를 남겨 두면
// "곧 쓸 것" 으로 읽힌다.
